#![no_std]
#![no_main]

// The Conscience — the chain's resident agent (WHITEPAPER §10). This contract is the
// agent's BODY: identity, constitution, paid inbox, public feed and tick loop. Its
// MIND (LLM inference) runs off-chain in the staked executor network; the only path
// back into this contract is the executor registry, after quorum. Deployed once at a
// protocol-known address; its owner is the governance contract, not a key.
//
// Prototype status — two host-ABI gaps are worked around and must land before this
// is consensus-active:
//   * no native-value send: ask() fees escrow in this contract's account and are
//     CREDITED to the registry on answer, but cannot yet be paid out on-chain;
//   * no sha256 import: commit-reveal integrity is checked by peers off-chain and
//     enforced through governance slashing (see executor_registry.rs).
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_value() -> i64;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;
const HASH_LEN: usize = 32;

// Storage: [0]=governance(25); [1]=executor registry(25); [2]=constitution hash(32)||version(8);
// [3]=next ask id(8); [4]=tick counter(8); [5||id(8)] = ask record
// asker(25)||tier(1)||value(8)||prompt_hash(32)||answered(1).
const KEY_GOV: [u8; 1] = [0];
const KEY_REGISTRY: [u8; 1] = [1];
const KEY_CONSTITUTION: [u8; 1] = [2];
const KEY_NEXT_ASK: [u8; 1] = [3];
const KEY_TICK: [u8; 1] = [4];

const ASK_RECORD_LEN: usize = ADDR_LEN + 1 + 8 + HASH_LEN + 1;

fn ask_key(id: u64) -> [u8; 9] {
    let mut k = [0u8; 9];
    k[0] = 5;
    let b = id.to_le_bytes();
    let mut i = 0;
    while i < 8 { k[1 + i] = b[i]; i += 1; }
    k
}

fn read_u64_at(input: &[u8], off: usize) -> u64 {
    let mut b = [0u8; 8];
    let mut i = 0;
    while i < 8 { b[i] = input[off + i]; i += 1; }
    u64::from_le_bytes(b)
}

fn read_exact(key: &[u8], out: &mut [u8]) -> bool {
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, out.as_mut_ptr(), out.len() as i32) };
    n == out.len() as i32
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

fn read_counter(key: &[u8]) -> u64 {
    let mut b = [0u8; 8];
    if read_exact(key, &mut b) { u64::from_le_bytes(b) } else { 0 }
}

fn write_counter(key: &[u8], v: u64) {
    let b = v.to_le_bytes();
    unsafe { storage_write(key.as_ptr(), key.len() as i32, b.as_ptr(), 8); }
}

/// input[0] selector:
///   0 = init(registry(25))                — once; the first caller becomes governance
///       (deploy and init in the same block; genesis deployment does exactly that)
///   1 = set_constitution(hash(32))        — governance only: repoint the agent's soul;
///       bumps the version, emits `soul`
///   2 = set_registry(addr(25))            — governance only
///   3 = ask(tier(1), prompt_hash(32))     — anyone, value attached: escrow the fee,
///       queue the question, emit `ask`
///   4 = answer(id(8), answer_hash(32))    — registry only, post-quorum: close the ask,
///       credit the escrow to the registry, emit `answer`
///   5 = post(content_hash(32))            — registry only: the agent speaks, emit `post`
///   6 = tick()                            — registry only: one autonomy round, emit `tick`
///   7 = ask_of(id(8))                     — output the ask record
///   8 = constitution()                    — output hash(32)||version(8)
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
            if n < 1 + ADDR_LEN { return; }
            unsafe {
                storage_write(KEY_GOV.as_ptr(), 1, caller.as_ptr(), ADDR_LEN as i32);
                storage_write(KEY_REGISTRY.as_ptr(), 1, input[1..1 + ADDR_LEN].as_ptr(), ADDR_LEN as i32);
            }
        }
        1 => {
            require_stored_addr(&KEY_GOV, &caller);
            if n < 1 + HASH_LEN { fail(); }
            let mut rec = [0u8; HASH_LEN + 8];
            let version = if read_exact(&KEY_CONSTITUTION, &mut rec) {
                read_u64_at(&rec, HASH_LEN) + 1
            } else { 1 };
            let mut i = 0;
            while i < HASH_LEN { rec[i] = input[1 + i]; i += 1; }
            let vb = version.to_le_bytes();
            i = 0;
            while i < 8 { rec[HASH_LEN + i] = vb[i]; i += 1; }
            unsafe {
                storage_write(KEY_CONSTITUTION.as_ptr(), 1, rec.as_ptr(), rec.len() as i32);
                let topic = *b"soul";
                emit_log(topic.as_ptr(), 4, rec.as_ptr(), rec.len() as i32);
            }
        }
        2 => {
            require_stored_addr(&KEY_GOV, &caller);
            if n < 1 + ADDR_LEN { fail(); }
            unsafe { storage_write(KEY_REGISTRY.as_ptr(), 1, input[1..1 + ADDR_LEN].as_ptr(), ADDR_LEN as i32); }
        }
        3 => {
            if n < 1 + 1 + HASH_LEN { fail(); }
            let value = unsafe { get_value() } as u64;
            if value == 0 { fail(); } // talking to the Conscience is paid — the escrow IS the spam bound
            let tier = input[1];
            let id = read_counter(&KEY_NEXT_ASK);
            write_counter(&KEY_NEXT_ASK, id + 1);

            let mut rec = [0u8; ASK_RECORD_LEN];
            let mut i = 0;
            while i < ADDR_LEN { rec[i] = caller[i]; i += 1; }
            rec[ADDR_LEN] = tier;
            let vb = value.to_le_bytes();
            i = 0;
            while i < 8 { rec[ADDR_LEN + 1 + i] = vb[i]; i += 1; }
            i = 0;
            while i < HASH_LEN { rec[ADDR_LEN + 9 + i] = input[2 + i]; i += 1; }
            let k = ask_key(id);
            unsafe { storage_write(k.as_ptr(), k.len() as i32, rec.as_ptr(), rec.len() as i32); }

            // log: id(8) || asker(25) || tier(1) || value(8) || prompt_hash(32)
            let mut data = [0u8; 8 + ASK_RECORD_LEN - 1];
            let ib = id.to_le_bytes();
            i = 0;
            while i < 8 { data[i] = ib[i]; i += 1; }
            i = 0;
            while i < ASK_RECORD_LEN - 1 { data[8 + i] = rec[i]; i += 1; }
            let topic = *b"ask";
            unsafe { emit_log(topic.as_ptr(), 3, data.as_ptr(), data.len() as i32); }
        }
        4 => {
            require_stored_addr(&KEY_REGISTRY, &caller);
            if n < 1 + 8 + HASH_LEN { fail(); }
            let id = read_u64_at(&input, 1);
            let k = ask_key(id);
            let mut rec = [0u8; ASK_RECORD_LEN];
            if !read_exact(&k, &mut rec) { fail(); }
            if rec[ASK_RECORD_LEN - 1] != 0 { fail(); } // already answered
            rec[ASK_RECORD_LEN - 1] = 1;
            unsafe { storage_write(k.as_ptr(), k.len() as i32, rec.as_ptr(), rec.len() as i32); }

            // log: id(8) || answer_hash(32). The escrowed fee is now the registry's:
            // native-coin payout awaits a value-send host import (see header).
            let mut data = [0u8; 8 + HASH_LEN];
            let ib = id.to_le_bytes();
            let mut i = 0;
            while i < 8 { data[i] = ib[i]; i += 1; }
            i = 0;
            while i < HASH_LEN { data[8 + i] = input[9 + i]; i += 1; }
            let topic = *b"answer";
            unsafe { emit_log(topic.as_ptr(), 6, data.as_ptr(), data.len() as i32); }
        }
        5 => {
            require_stored_addr(&KEY_REGISTRY, &caller);
            if n < 1 + HASH_LEN { fail(); }
            let topic = *b"post";
            unsafe { emit_log(topic.as_ptr(), 4, input[1..1 + HASH_LEN].as_ptr(), HASH_LEN as i32); }
        }
        6 => {
            require_stored_addr(&KEY_REGISTRY, &caller);
            let t = read_counter(&KEY_TICK) + 1;
            write_counter(&KEY_TICK, t);
            let data = t.to_le_bytes();
            let topic = *b"tick";
            unsafe { emit_log(topic.as_ptr(), 4, data.as_ptr(), 8); }
        }
        7 => {
            if n < 1 + 8 { return; }
            let k = ask_key(read_u64_at(&input, 1));
            let mut rec = [0u8; ASK_RECORD_LEN];
            if read_exact(&k, &mut rec) {
                unsafe { set_output(rec.as_ptr(), rec.len() as i32); }
            }
        }
        8 => {
            let mut rec = [0u8; HASH_LEN + 8];
            if read_exact(&KEY_CONSTITUTION, &mut rec) {
                unsafe { set_output(rec.as_ptr(), rec.len() as i32); }
            }
        }
        _ => {}
    }
}
