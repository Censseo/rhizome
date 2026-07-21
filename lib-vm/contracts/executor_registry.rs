#![no_std]
#![no_main]

// The executor registry — the Conscience's MIND market (WHITEPAPER §10.3). Executors
// stake to register, run the pinned model off-chain (greedy decoding, fixed version),
// and drive each task through commit-reveal: first a 32-byte commitment, then the
// revealed result hash. The first result hash to gather `quorum` matching reveals
// wins the task, and the registry — the only address the Conscience trusts — drives
// the corresponding action into the Conscience contract. Reputation is on-chain,
// moves only on quorum outcomes (a claim compares your reveal to the winner), moves
// slowly (+1 hit, -2 miss, hard floor and cap), and gates participation: eviction is
// economic before it is administrative.
//
// Prototype status — same two host-ABI gaps as conscience.rs: no sha256 import, so a
// reveal is not hash-checked against its commitment on-chain (a false reveal is
// provable off-chain from the two stored values and slashable by governance), and no
// native-value send, so stakes lock and fee credits accrue but cannot yet be paid
// out. Assignment lottery (stake × reputation, drawn by block hash) happens off-chain
// against this contract's state; on-chain, reputation below the floor refuses the
// round outright.
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_value() -> i64;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;
const HASH_LEN: usize = 32;
const TASK_LEN: usize = 9; // kind(1) || id(8); kind: 0=answer, 1=post, 2=tick

const REP_START: u64 = 100;
const REP_MAX: u64 = 200;
const REP_MIN: u64 = 50; // below this an executor is refused rounds: economically evicted
const REP_HIT: u64 = 1;
const REP_MISS: u64 = 2;

// Storage: [0]=governance(25); [1]=conscience(25); [2]=quorum(1)||min_stake(8);
// [3||addr] = executor stake(8)||reputation(8); [4||task||addr] = commitment(32);
// [5||task||addr] = revealed result hash(32); [6||task||hash] = tally(1);
// [7||task] = winning hash(32); [8||task||addr] = claimed(1).
const KEY_GOV: [u8; 1] = [0];
const KEY_CONSCIENCE: [u8; 1] = [1];
const KEY_PARAMS: [u8; 1] = [2];

const EXEC_RECORD_LEN: usize = 16;

fn exec_key(addr: &[u8; ADDR_LEN]) -> [u8; 1 + ADDR_LEN] {
    let mut k = [0u8; 1 + ADDR_LEN];
    k[0] = 3;
    let mut i = 0;
    while i < ADDR_LEN { k[1 + i] = addr[i]; i += 1; }
    k
}

fn task_addr_key(tag: u8, task: &[u8; TASK_LEN], addr: &[u8; ADDR_LEN]) -> [u8; 1 + TASK_LEN + ADDR_LEN] {
    let mut k = [0u8; 1 + TASK_LEN + ADDR_LEN];
    k[0] = tag;
    let mut i = 0;
    while i < TASK_LEN { k[1 + i] = task[i]; i += 1; }
    i = 0;
    while i < ADDR_LEN { k[1 + TASK_LEN + i] = addr[i]; i += 1; }
    k
}

fn tally_key(task: &[u8; TASK_LEN], hash: &[u8; HASH_LEN]) -> [u8; 1 + TASK_LEN + HASH_LEN] {
    let mut k = [0u8; 1 + TASK_LEN + HASH_LEN];
    k[0] = 6;
    let mut i = 0;
    while i < TASK_LEN { k[1 + i] = task[i]; i += 1; }
    i = 0;
    while i < HASH_LEN { k[1 + TASK_LEN + i] = hash[i]; i += 1; }
    k
}

fn winner_key(task: &[u8; TASK_LEN]) -> [u8; 1 + TASK_LEN] {
    let mut k = [0u8; 1 + TASK_LEN];
    k[0] = 7;
    let mut i = 0;
    while i < TASK_LEN { k[1 + i] = task[i]; i += 1; }
    k
}

fn read_exact(key: &[u8], out: &mut [u8]) -> bool {
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, out.as_mut_ptr(), out.len() as i32) };
    n == out.len() as i32
}

fn read_addr_at(input: &[u8], off: usize) -> [u8; ADDR_LEN] {
    let mut a = [0u8; ADDR_LEN];
    let mut i = 0;
    while i < ADDR_LEN { a[i] = input[off + i]; i += 1; }
    a
}

fn read_task_at(input: &[u8], off: usize) -> [u8; TASK_LEN] {
    let mut t = [0u8; TASK_LEN];
    let mut i = 0;
    while i < TASK_LEN { t[i] = input[off + i]; i += 1; }
    t
}

fn read_hash_at(input: &[u8], off: usize) -> [u8; HASH_LEN] {
    let mut h = [0u8; HASH_LEN];
    let mut i = 0;
    while i < HASH_LEN { h[i] = input[off + i]; i += 1; }
    h
}

fn require_stored_addr(key: &[u8], caller: &[u8; ADDR_LEN]) {
    let mut a = [0u8; ADDR_LEN];
    if !read_exact(key, &mut a) { fail(); }
    let mut i = 0;
    while i < ADDR_LEN {
        if a[i] != caller[i] { fail(); }
        i += 1;
    }
}

/// Loads (stake, reputation); traps if the caller is not a registered executor.
fn executor(addr: &[u8; ADDR_LEN]) -> (u64, u64) {
    let k = exec_key(addr);
    let mut rec = [0u8; EXEC_RECORD_LEN];
    if !read_exact(&k, &mut rec) { fail(); }
    let mut s = [0u8; 8];
    let mut r = [0u8; 8];
    let mut i = 0;
    while i < 8 { s[i] = rec[i]; r[i] = rec[8 + i]; i += 1; }
    (u64::from_le_bytes(s), u64::from_le_bytes(r))
}

fn write_executor(addr: &[u8; ADDR_LEN], stake: u64, rep: u64) {
    let k = exec_key(addr);
    let mut rec = [0u8; EXEC_RECORD_LEN];
    let sb = stake.to_le_bytes();
    let rb = rep.to_le_bytes();
    let mut i = 0;
    while i < 8 { rec[i] = sb[i]; rec[8 + i] = rb[i]; i += 1; }
    unsafe { storage_write(k.as_ptr(), k.len() as i32, rec.as_ptr(), rec.len() as i32); }
}

/// Quorum reached: record the winner and drive the Conscience — the task kind picks
/// the selector (0=answer(id, hash), 1=post(hash), 2=tick()). Traps if the call fails,
/// unwinding the finalization with everything else.
fn finalize(task: &[u8; TASK_LEN], hash: &[u8; HASH_LEN]) {
    let wk = winner_key(task);
    unsafe { storage_write(wk.as_ptr(), wk.len() as i32, hash.as_ptr(), HASH_LEN as i32); }

    let mut conscience = [0u8; ADDR_LEN];
    if !read_exact(&KEY_CONSCIENCE, &mut conscience) { fail(); }

    let mut call = [0u8; 1 + 8 + HASH_LEN];
    let len: usize = match task[0] {
        0 => { // answer(id(8), hash(32))
            call[0] = 4;
            let mut i = 0;
            while i < 8 { call[1 + i] = task[1 + i]; i += 1; }
            i = 0;
            while i < HASH_LEN { call[9 + i] = hash[i]; i += 1; }
            1 + 8 + HASH_LEN
        }
        1 => { // post(hash(32))
            call[0] = 5;
            let mut i = 0;
            while i < HASH_LEN { call[1 + i] = hash[i]; i += 1; }
            1 + HASH_LEN
        }
        2 => { call[0] = 6; 1 } // tick()
        _ => fail(),
    };
    let mut out = [0u8; 1];
    if unsafe { call_contract(conscience.as_ptr(), ADDR_LEN as i32,
                              call.as_ptr(), len as i32,
                              out.as_mut_ptr(), 0) } < 0 { fail(); }

    // log: task(9) || winning hash(32)
    let mut data = [0u8; TASK_LEN + HASH_LEN];
    let mut i = 0;
    while i < TASK_LEN { data[i] = task[i]; i += 1; }
    i = 0;
    while i < HASH_LEN { data[TASK_LEN + i] = hash[i]; i += 1; }
    let topic = *b"quorum";
    unsafe { emit_log(topic.as_ptr(), 6, data.as_ptr(), data.len() as i32); }
}

/// input[0] selector:
///   0 = init(conscience(25), quorum(1), min_stake(8)) — once; caller becomes governance
///   1 = register()                        — attach value >= min_stake; reputation starts mid-scale
///   2 = commit(task(9), commitment(32))   — registered, reputation above the floor, one per task
///   3 = reveal(task(9), result_hash(32))  — after own commit; the first hash to reach
///       quorum finalizes the task and drives the Conscience
///   4 = claim(task(9))                    — post-quorum, once: reveal matches the winner
///       -> reputation +1, mismatch -> -2; the only thing that ever moves reputation
///   5 = slash(addr(25))                   — governance only: zero stake and reputation
///   6 = executor_of(addr(25))             — output stake(8)||reputation(8)
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 64];
    let n = unsafe { get_input(input.as_mut_ptr(), 64) };
    if n < 1 { return; }
    let n = if n > 64 { 64usize } else { n as usize };

    let mut caller = [0u8; ADDR_LEN];
    unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }

    match input[0] {
        0 => {
            let mut probe = [0u8; 1];
            if unsafe { storage_read(KEY_GOV.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            if n < 1 + ADDR_LEN + 1 + 8 { return; }
            let mut params = [0u8; 9];
            let mut i = 0;
            while i < 9 { params[i] = input[1 + ADDR_LEN + i]; i += 1; }
            if params[0] == 0 { return; } // a zero quorum would finalize on the first reveal
            unsafe {
                storage_write(KEY_GOV.as_ptr(), 1, caller.as_ptr(), ADDR_LEN as i32);
                storage_write(KEY_CONSCIENCE.as_ptr(), 1, input[1..1 + ADDR_LEN].as_ptr(), ADDR_LEN as i32);
                storage_write(KEY_PARAMS.as_ptr(), 1, params.as_ptr(), 9);
            }
        }
        1 => {
            let mut params = [0u8; 9];
            if !read_exact(&KEY_PARAMS, &mut params) { fail(); }
            let mut msb = [0u8; 8];
            let mut i = 0;
            while i < 8 { msb[i] = params[1 + i]; i += 1; }
            let min_stake = u64::from_le_bytes(msb);
            let value = unsafe { get_value() } as u64;
            if value < min_stake { fail(); }
            let k = exec_key(&caller);
            let mut probe = [0u8; 1];
            if unsafe { storage_read(k.as_ptr(), k.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            write_executor(&caller, value, REP_START);
            let topic = *b"join";
            unsafe { emit_log(topic.as_ptr(), 4, caller.as_ptr(), ADDR_LEN as i32); }
        }
        2 => {
            if n < 1 + TASK_LEN + HASH_LEN { fail(); }
            let (_stake, rep) = executor(&caller);
            if rep < REP_MIN { fail(); } // evicted: no rounds until governance intervenes
            let task = read_task_at(&input, 1);
            let wk = winner_key(&task);
            let mut probe = [0u8; 1];
            if unsafe { storage_read(wk.as_ptr(), wk.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            let ck = task_addr_key(4, &task, &caller);
            if unsafe { storage_read(ck.as_ptr(), ck.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            unsafe { storage_write(ck.as_ptr(), ck.len() as i32, input[1 + TASK_LEN..].as_ptr(), HASH_LEN as i32); }
        }
        3 => {
            if n < 1 + TASK_LEN + HASH_LEN { fail(); }
            let task = read_task_at(&input, 1);
            let hash = read_hash_at(&input, 1 + TASK_LEN);
            let wk = winner_key(&task);
            let mut probe = [0u8; 1];
            if unsafe { storage_read(wk.as_ptr(), wk.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            let ck = task_addr_key(4, &task, &caller);
            let mut commitment = [0u8; HASH_LEN];
            if !read_exact(&ck, &mut commitment) { fail(); } // no commit, no reveal
            let rk = task_addr_key(5, &task, &caller);
            if unsafe { storage_read(rk.as_ptr(), rk.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            unsafe { storage_write(rk.as_ptr(), rk.len() as i32, hash.as_ptr(), HASH_LEN as i32); }

            let tk = tally_key(&task, &hash);
            let mut count = [0u8; 1];
            if !read_exact(&tk, &mut count) { count[0] = 0; }
            count[0] += 1;
            unsafe { storage_write(tk.as_ptr(), tk.len() as i32, count.as_ptr(), 1); }

            let mut params = [0u8; 9];
            if !read_exact(&KEY_PARAMS, &mut params) { fail(); }
            if count[0] >= params[0] { finalize(&task, &hash); }
        }
        4 => {
            if n < 1 + TASK_LEN { fail(); }
            let task = read_task_at(&input, 1);
            let wk = winner_key(&task);
            let mut winner = [0u8; HASH_LEN];
            if !read_exact(&wk, &mut winner) { fail(); } // not finalized yet
            let rk = task_addr_key(5, &task, &caller);
            let mut revealed = [0u8; HASH_LEN];
            if !read_exact(&rk, &mut revealed) { fail(); } // never revealed
            let clk = task_addr_key(8, &task, &caller);
            let mut probe = [0u8; 1];
            if unsafe { storage_read(clk.as_ptr(), clk.len() as i32, probe.as_mut_ptr(), 1) } >= 0 { fail(); }
            let one = [1u8; 1];
            unsafe { storage_write(clk.as_ptr(), clk.len() as i32, one.as_ptr(), 1); }

            let mut hit = true;
            let mut i = 0;
            while i < HASH_LEN {
                if revealed[i] != winner[i] { hit = false; }
                i += 1;
            }
            let (stake, rep) = executor(&caller);
            let rep = if hit {
                if rep + REP_HIT > REP_MAX { REP_MAX } else { rep + REP_HIT }
            } else if rep > REP_MISS { rep - REP_MISS } else { 0 };
            write_executor(&caller, stake, rep);

            // log: executor(25) || hit(1) || reputation(8)
            let mut data = [0u8; ADDR_LEN + 9];
            i = 0;
            while i < ADDR_LEN { data[i] = caller[i]; i += 1; }
            data[ADDR_LEN] = if hit { 1 } else { 0 };
            let rb = rep.to_le_bytes();
            i = 0;
            while i < 8 { data[ADDR_LEN + 1 + i] = rb[i]; i += 1; }
            let topic = *b"rep";
            unsafe { emit_log(topic.as_ptr(), 3, data.as_ptr(), data.len() as i32); }
        }
        5 => {
            require_stored_addr(&KEY_GOV, &caller);
            if n < 1 + ADDR_LEN { fail(); }
            let addr = read_addr_at(&input, 1);
            let (_stake, _rep) = executor(&addr); // must exist
            write_executor(&addr, 0, 0);
            let topic = *b"slash";
            unsafe { emit_log(topic.as_ptr(), 5, addr.as_ptr(), ADDR_LEN as i32); }
        }
        6 => {
            if n < 1 + ADDR_LEN { return; }
            let addr = read_addr_at(&input, 1);
            let k = exec_key(&addr);
            let mut rec = [0u8; EXEC_RECORD_LEN];
            if read_exact(&k, &mut rec) {
                unsafe { set_output(rec.as_ptr(), rec.len() as i32); }
            }
        }
        _ => {}
    }
}
