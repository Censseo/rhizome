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
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

const ADDR_LEN: usize = 25;

// Storage key layout: [0] = init flag; [1 || addr(25)] = balance of addr (8 LE bytes).
fn balance_key(addr: &[u8; ADDR_LEN]) -> [u8; 1 + ADDR_LEN] {
    let mut key = [0u8; 1 + ADDR_LEN];
    key[0] = 1;
    let mut i = 0;
    while i < ADDR_LEN { key[1 + i] = addr[i]; i += 1; }
    key
}

fn read_balance(addr: &[u8; ADDR_LEN]) -> u64 {
    let key = balance_key(addr);
    let mut buf = [0u8; 8];
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, buf.as_mut_ptr(), 8) };
    if n == 8 { u64::from_le_bytes(buf) } else { 0 }
}

fn write_balance(addr: &[u8; ADDR_LEN], amount: u64) {
    let key = balance_key(addr);
    let val = amount.to_le_bytes();
    unsafe { storage_write(key.as_ptr(), key.len() as i32, val.as_ptr(), 8); }
}

/// Entry point. input[0] is the method selector:
///   0 = init(supply u64)         — once; mints the whole supply to the caller
///   1 = transfer(to[25], amt u64) — moves caller's balance, emits a "transfer" log
///   2 = balance_of(addr[25])      — returns the balance as 8 LE bytes via set_output
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 64];
    let n = unsafe { get_input(input.as_mut_ptr(), 64) };
    if n < 1 { return; }

    let mut caller = [0u8; ADDR_LEN];
    unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }

    match input[0] {
        0 => {
            // init: reject if the flag key already exists (read returns >= 0 when present).
            let flag = [0u8; 1];
            let mut probe = [0u8; 1];
            if unsafe { storage_read(flag.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            let mut sb = [0u8; 8];
            let mut i = 0;
            while i < 8 { sb[i] = input[1 + i]; i += 1; }
            write_balance(&caller, u64::from_le_bytes(sb));
            let one = [1u8; 1];
            unsafe { storage_write(flag.as_ptr(), 1, one.as_ptr(), 1); }
        }
        1 => {
            let mut to = [0u8; ADDR_LEN];
            let mut i = 0;
            while i < ADDR_LEN { to[i] = input[1 + i]; i += 1; }
            let mut ab = [0u8; 8];
            i = 0;
            while i < 8 { ab[i] = input[1 + ADDR_LEN + i]; i += 1; }
            let amount = u64::from_le_bytes(ab);

            let from_bal = read_balance(&caller);
            if from_bal < amount { return; }
            write_balance(&caller, from_bal - amount);
            let to_bal = read_balance(&to);
            write_balance(&to, to_bal + amount);

            // log: from(25) || to(25) || amount(8 LE)
            let mut data = [0u8; ADDR_LEN * 2 + 8];
            i = 0;
            while i < ADDR_LEN { data[i] = caller[i]; data[ADDR_LEN + i] = to[i]; i += 1; }
            let amt = amount.to_le_bytes();
            i = 0;
            while i < 8 { data[ADDR_LEN * 2 + i] = amt[i]; i += 1; }
            let topic = *b"transfer";
            unsafe { emit_log(topic.as_ptr(), 8, data.as_ptr(), data.len() as i32); }
        }
        2 => {
            let mut addr = [0u8; ADDR_LEN];
            let mut i = 0;
            while i < ADDR_LEN { addr[i] = input[1 + i]; i += 1; }
            let out = read_balance(&addr).to_le_bytes();
            unsafe { set_output(out.as_ptr(), 8); }
        }
        _ => {}
    }
}
