#![no_std]
#![no_main]

// A minimal fungible token (the memecoin base). Host ABI from module "env":
// storage + output + logs + call context (caller, input).
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_deployer(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
}

/// True if `who` is the contract's recorded deployer. init is gated on this so a mempool observer
/// cannot front-run the deployer's init and seize the supply/ownership (audit T1).
fn is_deployer(who: &[u8; ADDR_LEN]) -> bool {
    let mut d = [0u8; ADDR_LEN];
    let n = unsafe { get_deployer(d.as_mut_ptr(), ADDR_LEN as i32) };
    n == ADDR_LEN as i32 && &d == who
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

/// Revert the whole call: used by transfer_from, whose callers (pools, wallets)
/// must observe failure through call_contract's -1 rather than a silent no-op.
fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;

// Storage key layout: [0] = init flag; [1 || addr(25)] = balance (8 LE);
// [2 || owner(25) || spender(25)] = allowance (8 LE).
fn balance_key(addr: &[u8; ADDR_LEN]) -> [u8; 1 + ADDR_LEN] {
    let mut key = [0u8; 1 + ADDR_LEN];
    key[0] = 1;
    let mut i = 0;
    while i < ADDR_LEN { key[1 + i] = addr[i]; i += 1; }
    key
}

fn allowance_key(owner: &[u8; ADDR_LEN], spender: &[u8; ADDR_LEN]) -> [u8; 1 + 2 * ADDR_LEN] {
    let mut key = [0u8; 1 + 2 * ADDR_LEN];
    key[0] = 2;
    let mut i = 0;
    while i < ADDR_LEN {
        key[1 + i] = owner[i];
        key[1 + ADDR_LEN + i] = spender[i];
        i += 1;
    }
    key
}

fn read_u64(key: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, buf.as_mut_ptr(), 8) };
    if n == 8 { u64::from_le_bytes(buf) } else { 0 }
}

fn write_u64(key: &[u8], v: u64) {
    let val = v.to_le_bytes();
    unsafe { storage_write(key.as_ptr(), key.len() as i32, val.as_ptr(), 8); }
}

fn read_balance(addr: &[u8; ADDR_LEN]) -> u64 { read_u64(&balance_key(addr)) }
fn write_balance(addr: &[u8; ADDR_LEN], amount: u64) { write_u64(&balance_key(addr), amount); }

fn read_addr(input: &[u8], off: usize) -> [u8; ADDR_LEN] {
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

/// Moves `amount` from `from` to `to` and emits the "transfer" log.
/// Returns false (leaving state untouched) when the balance is short.
fn do_transfer(from: &[u8; ADDR_LEN], to: &[u8; ADDR_LEN], amount: u64) -> bool {
    let from_bal = read_balance(from);
    if from_bal < amount { return false; }
    write_balance(from, from_bal - amount);
    let to_bal = read_balance(to);
    write_balance(to, to_bal + amount);

    // log: from(25) || to(25) || amount(8 LE)
    let mut data = [0u8; ADDR_LEN * 2 + 8];
    let mut i = 0;
    while i < ADDR_LEN { data[i] = from[i]; data[ADDR_LEN + i] = to[i]; i += 1; }
    let amt = amount.to_le_bytes();
    i = 0;
    while i < 8 { data[ADDR_LEN * 2 + i] = amt[i]; i += 1; }
    let topic = *b"transfer";
    unsafe { emit_log(topic.as_ptr(), 8, data.as_ptr(), data.len() as i32); }
    true
}

/// Entry point. input[0] is the method selector:
///   0 = init(supply u64)                    — once; mints the whole supply to the caller
///   1 = transfer(to[25], amt u64)           — moves caller's balance; TRAPS if short, so composing
///       contracts observe the failure through call_contract's -1 (not a silent no-op)
///   2 = balance_of(addr[25])                — balance as 8 LE bytes via set_output
///   3 = approve(spender[25], amt u64)       — lets spender pull up to amt from the caller
///   4 = transfer_from(from[25], to[25], amt) — spends the caller's allowance; TRAPS on
///       insufficient allowance or balance, so composing contracts observe the failure
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 96];
    let n = unsafe { get_input(input.as_mut_ptr(), 96) };
    if n < 1 { return; }

    let mut caller = [0u8; ADDR_LEN];
    unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }

    match input[0] {
        0 => {
            // init: only the deployer may run it (audit T1), and only once (the flag key must be
            // absent — storage_read returns >= 0 when present).
            if !is_deployer(&caller) { return; }
            let flag = [0u8; 1];
            let mut probe = [0u8; 1];
            if unsafe { storage_read(flag.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            write_balance(&caller, read_u64_at(&input, 1));
            let one = [1u8; 1];
            unsafe { storage_write(flag.as_ptr(), 1, one.as_ptr(), 1); }
        }
        1 => {
            let to = read_addr(&input, 1);
            // Trap on an insufficient balance (audit T2). Returning a silent no-op let composing
            // contracts (pair.pay, agent_wallet.pay, launchpad) that check call_contract >= 0 believe
            // a payout succeeded when zero tokens moved — losing swapper funds or burning a session
            // budget with no transfer. transfer_from (selector 4) already traps; transfer must too.
            if !do_transfer(&caller, &to, read_u64_at(&input, 1 + ADDR_LEN)) { fail(); }
        }
        2 => {
            let addr = read_addr(&input, 1);
            let out = read_balance(&addr).to_le_bytes();
            unsafe { set_output(out.as_ptr(), 8); }
        }
        3 => {
            let spender = read_addr(&input, 1);
            let amount = read_u64_at(&input, 1 + ADDR_LEN);
            write_u64(&allowance_key(&caller, &spender), amount);

            // log: owner(25) || spender(25) || amount(8 LE)
            let mut data = [0u8; ADDR_LEN * 2 + 8];
            let mut i = 0;
            while i < ADDR_LEN { data[i] = caller[i]; data[ADDR_LEN + i] = spender[i]; i += 1; }
            let amt = amount.to_le_bytes();
            i = 0;
            while i < 8 { data[ADDR_LEN * 2 + i] = amt[i]; i += 1; }
            let topic = *b"approve";
            unsafe { emit_log(topic.as_ptr(), 7, data.as_ptr(), data.len() as i32); }
        }
        4 => {
            let from = read_addr(&input, 1);
            let to = read_addr(&input, 1 + ADDR_LEN);
            let amount = read_u64_at(&input, 1 + 2 * ADDR_LEN);

            let key = allowance_key(&from, &caller);
            let allowance = read_u64(&key);
            if allowance < amount { fail(); }
            if !do_transfer(&from, &to, amount) { fail(); }
            write_u64(&key, allowance - amount);
        }
        _ => {}
    }
}
