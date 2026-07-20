#![no_std]
#![no_main]

// An agent wallet — account abstraction for autonomous agents. The wallet is a
// contract account: its owner drives arbitrary calls through it (`exec`, the
// wallet is the callee's caller, so it owns tokens and positions), and can grant
// SESSION KEYS: other addresses (an AI agent's key) allowed to move at most a
// capped amount of one token out of the wallet, revocable at any time. The agent
// acts within its budget; it never holds the treasury's keys.
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;

// Storage: [0]=owner(25); [1||key(25)] = session record token(25)||remaining(8).
const KEY_OWNER: [u8; 1] = [0];

fn session_key(key: &[u8; ADDR_LEN]) -> [u8; 1 + ADDR_LEN] {
    let mut k = [0u8; 1 + ADDR_LEN];
    k[0] = 1;
    let mut i = 0;
    while i < ADDR_LEN { k[1 + i] = key[i]; i += 1; }
    k
}

fn read_addr_at(input: &[u8], off: usize) -> [u8; ADDR_LEN] {
    let mut a = [0u8; ADDR_LEN];
    let mut i = 0;
    while i < ADDR_LEN { a[i] = input[off + i]; i += 1; }
    a
}

fn read_u64_at(input: &[u8], off: usize) -> u64 {
    let mut b = [0u8; 8];
    let mut i = 0;
    while i < 8 { b[i] = input[off + i]; i += 1; }
    u64::from_le_bytes(b)
}

fn require_owner(caller: &[u8; ADDR_LEN]) {
    let mut owner = [0u8; ADDR_LEN];
    let n = unsafe { storage_read(KEY_OWNER.as_ptr(), 1, owner.as_mut_ptr(), ADDR_LEN as i32) };
    if n != ADDR_LEN as i32 { fail(); }
    let mut i = 0;
    while i < ADDR_LEN {
        if owner[i] != caller[i] { fail(); }
        i += 1;
    }
}

/// token.transfer(to, amount) with the wallet as the token's caller; traps on failure.
fn pay(token: &[u8; ADDR_LEN], to: &[u8; ADDR_LEN], amount: u64) {
    let mut call = [0u8; 1 + ADDR_LEN + 8];
    call[0] = 1;
    let mut i = 0;
    while i < ADDR_LEN { call[1 + i] = to[i]; i += 1; }
    let amt = amount.to_le_bytes();
    i = 0;
    while i < 8 { call[1 + ADDR_LEN + i] = amt[i]; i += 1; }
    let mut out = [0u8; 1];
    if unsafe { call_contract(token.as_ptr(), ADDR_LEN as i32,
                              call.as_ptr(), call.len() as i32,
                              out.as_mut_ptr(), 0) } < 0 { fail(); }
}

/// input[0] selector:
///   0 = init()                                   — once; the first caller becomes owner
///       (deploy and init in the same block to keep the claim safe)
///   1 = exec(target(25), payload...)             — owner only: arbitrary call through the
///       wallet; the callee's output is forwarded, a failed call traps
///   2 = grant_session(key(25), token(25), cap(8)) — owner only
///   3 = revoke_session(key(25))                  — owner only
///   4 = session_transfer(to(25), amount(8))      — session key only: moves the session's
///       token out of the wallet, within the remaining cap; traps on any violation
///   5 = session_of(key(25))                      — output token(25) || remaining(8)
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 128];
    let n = unsafe { get_input(input.as_mut_ptr(), 128) };
    if n < 1 { return; }
    let n = if n > 128 { 128usize } else { n as usize };

    let mut caller = [0u8; ADDR_LEN];
    unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }

    match input[0] {
        0 => {
            let mut probe = [0u8; 1];
            if unsafe { storage_read(KEY_OWNER.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            unsafe { storage_write(KEY_OWNER.as_ptr(), 1, caller.as_ptr(), ADDR_LEN as i32); }
        }
        1 => {
            require_owner(&caller);
            if n < 1 + ADDR_LEN { fail(); }
            let target = read_addr_at(&input, 1);
            let payload = &input[1 + ADDR_LEN..n];
            let mut out = [0u8; 64];
            let ret = unsafe {
                call_contract(target.as_ptr(), ADDR_LEN as i32,
                              payload.as_ptr(), payload.len() as i32,
                              out.as_mut_ptr(), 64)
            };
            if ret < 0 { fail(); }
            let len = if ret > 64 { 64 } else { ret };
            unsafe { set_output(out.as_ptr(), len); }
        }
        2 => {
            require_owner(&caller);
            let key = read_addr_at(&input, 1);
            let token = read_addr_at(&input, 1 + ADDR_LEN);
            let cap = read_u64_at(&input, 1 + 2 * ADDR_LEN);
            let mut record = [0u8; ADDR_LEN + 8];
            let mut i = 0;
            while i < ADDR_LEN { record[i] = token[i]; i += 1; }
            let cap_b = cap.to_le_bytes();
            i = 0;
            while i < 8 { record[ADDR_LEN + i] = cap_b[i]; i += 1; }
            let sk = session_key(&key);
            unsafe { storage_write(sk.as_ptr(), sk.len() as i32, record.as_ptr(), record.len() as i32); }

            // log: key(25) || token(25) || cap(8)
            let mut data = [0u8; 2 * ADDR_LEN + 8];
            i = 0;
            while i < ADDR_LEN { data[i] = key[i]; data[ADDR_LEN + i] = token[i]; i += 1; }
            i = 0;
            while i < 8 { data[2 * ADDR_LEN + i] = cap_b[i]; i += 1; }
            let topic = *b"grant";
            unsafe { emit_log(topic.as_ptr(), 5, data.as_ptr(), data.len() as i32); }
        }
        3 => {
            require_owner(&caller);
            let key = read_addr_at(&input, 1);
            let sk = session_key(&key);
            // Zeroed record: no token, no remaining budget.
            let record = [0u8; ADDR_LEN + 8];
            unsafe { storage_write(sk.as_ptr(), sk.len() as i32, record.as_ptr(), record.len() as i32); }
        }
        4 => {
            let to = read_addr_at(&input, 1);
            let amount = read_u64_at(&input, 1 + ADDR_LEN);
            if amount == 0 { fail(); }

            let sk = session_key(&caller);
            let mut record = [0u8; ADDR_LEN + 8];
            let r = unsafe { storage_read(sk.as_ptr(), sk.len() as i32,
                                          record.as_mut_ptr(), record.len() as i32) };
            if r != record.len() as i32 { fail(); } // not a session key
            let token = read_addr_at(&record, 0);
            let remaining = read_u64_at(&record, ADDR_LEN);
            if remaining < amount { fail(); } // over budget (also the revoked case)

            let left = (remaining - amount).to_le_bytes();
            let mut i = 0;
            while i < 8 { record[ADDR_LEN + i] = left[i]; i += 1; }
            unsafe { storage_write(sk.as_ptr(), sk.len() as i32, record.as_ptr(), record.len() as i32); }
            pay(&token, &to, amount);

            // log: key(25) || token(25) || to(25) || amount(8)
            let mut data = [0u8; 3 * ADDR_LEN + 8];
            i = 0;
            while i < ADDR_LEN {
                data[i] = caller[i];
                data[ADDR_LEN + i] = token[i];
                data[2 * ADDR_LEN + i] = to[i];
                i += 1;
            }
            let amt = amount.to_le_bytes();
            i = 0;
            while i < 8 { data[3 * ADDR_LEN + i] = amt[i]; i += 1; }
            let topic = *b"spend";
            unsafe { emit_log(topic.as_ptr(), 5, data.as_ptr(), data.len() as i32); }
        }
        5 => {
            let key = read_addr_at(&input, 1);
            let sk = session_key(&key);
            let mut record = [0u8; ADDR_LEN + 8];
            let r = unsafe { storage_read(sk.as_ptr(), sk.len() as i32,
                                          record.as_mut_ptr(), record.len() as i32) };
            if r == record.len() as i32 {
                unsafe { set_output(record.as_ptr(), record.len() as i32); }
            }
        }
        _ => {}
    }
}
