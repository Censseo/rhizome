#![no_std]
#![no_main]

// A self-contained constant-product AMM (x*y=k) over two internal tokens A and B.
// It keeps its own accounting (no cross-contract calls yet), proving the VM runs real
// swap math deterministically; composing it with the standalone token contract is the
// cross-contract-call follow-up. Host ABI from module "env".
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

/// Aborts the call by trapping; the VM reverts all of this call's state writes atomically.
fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;

// Storage: [0]=reserve A, [1]=reserve B, [2||addr]=balance A, [3||addr]=balance B.
fn read_u64(key: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, buf.as_mut_ptr(), 8) };
    if n == 8 { u64::from_le_bytes(buf) } else { 0 }
}

fn write_u64(key: &[u8], v: u64) {
    let val = v.to_le_bytes();
    unsafe { storage_write(key.as_ptr(), key.len() as i32, val.as_ptr(), 8); }
}

fn bal_key(prefix: u8, addr: &[u8; ADDR_LEN]) -> [u8; 1 + ADDR_LEN] {
    let mut key = [0u8; 1 + ADDR_LEN];
    key[0] = prefix;
    let mut i = 0;
    while i < ADDR_LEN { key[1 + i] = addr[i]; i += 1; }
    key
}

fn read_u64_at(input: &[u8], off: usize) -> u64 {
    let mut b = [0u8; 8];
    let mut i = 0;
    while i < 8 { b[i] = input[off + i]; i += 1; }
    u64::from_le_bytes(b)
}

// Constant-product output with a 0.3% fee, in u128 to avoid overflow.
fn amount_out(amount_in: u64, reserve_in: u64, reserve_out: u64) -> u64 {
    if amount_in == 0 || reserve_in == 0 || reserve_out == 0 { return 0; }
    let in_with_fee = (amount_in as u128) * 997;
    let numerator = in_with_fee * (reserve_out as u128);
    let denominator = (reserve_in as u128) * 1000 + in_with_fee;
    (numerator / denominator) as u64
}

/// input[0] selector:
///   0 = init(ra, rb, userA, userB)  — once; sets reserves, credits caller balances
///   1 = swap_a_for_b(amount_in)     — spends caller A, pays caller B, emits "swap"
///   2 = swap_b_for_a(amount_in)     — the mirror
///   3 = reserves()                  — output reserveA(8) || reserveB(8)
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 64];
    let n = unsafe { get_input(input.as_mut_ptr(), 64) };
    if n < 1 { return; }

    let mut caller = [0u8; ADDR_LEN];
    unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }

    let ra_key = [0u8; 1];
    let rb_key = [1u8; 1];

    match input[0] {
        0 => {
            let init_flag = [9u8; 1];
            if unsafe { storage_read(init_flag.as_ptr(), 1, [0u8; 1].as_mut_ptr(), 1) } >= 0 { return; }
            write_u64(&ra_key, read_u64_at(&input, 1));
            write_u64(&rb_key, read_u64_at(&input, 9));
            write_u64(&bal_key(2, &caller), read_u64_at(&input, 17));
            write_u64(&bal_key(3, &caller), read_u64_at(&input, 25));
            let one = [1u8; 1];
            unsafe { storage_write(init_flag.as_ptr(), 1, one.as_ptr(), 1); }
        }
        1 => swap(&caller, read_u64_at(&input, 1), 2, 3, &ra_key, &rb_key),
        2 => swap(&caller, read_u64_at(&input, 1), 3, 2, &rb_key, &ra_key),
        3 => {
            let mut out = [0u8; 16];
            let a = read_u64(&ra_key).to_le_bytes();
            let b = read_u64(&rb_key).to_le_bytes();
            let mut i = 0;
            while i < 8 { out[i] = a[i]; out[8 + i] = b[i]; i += 1; }
            unsafe { set_output(out.as_ptr(), 16); }
        }
        _ => {}
    }
}

// Spend `amount_in` of the `in_prefix` token from the caller for the `out_prefix` token,
// moving the pool reserves (`res_in_key` in, `res_out_key` out) along the x*y=k curve.
fn swap(caller: &[u8; ADDR_LEN], amount_in: u64, in_prefix: u8, out_prefix: u8,
        res_in_key: &[u8], res_out_key: &[u8]) {
    let bal_in = read_u64(&bal_key(in_prefix, caller));
    if bal_in < amount_in || amount_in == 0 { return; }
    let reserve_in = read_u64(res_in_key);
    let reserve_out = read_u64(res_out_key);
    let out = amount_out(amount_in, reserve_in, reserve_out);
    if out == 0 || out >= reserve_out { return; }

    write_u64(&bal_key(in_prefix, caller), bal_in - amount_in);
    let bal_out = read_u64(&bal_key(out_prefix, caller));
    // Use checked_add so a crafted pool cannot wrap a u64 sum on overflow; a trap reverts the
    // whole call atomically (audit M13). NOTE: the bundled .wasm must be recompiled from this
    // source for the fix to take effect on-chain.
    write_u64(&bal_key(out_prefix, caller), bal_out.checked_add(out).unwrap_or_else(|| fail()));
    write_u64(res_in_key, reserve_in.checked_add(amount_in).unwrap_or_else(|| fail()));
    write_u64(res_out_key, reserve_out - out);

    // log: caller(25) || amount_in(8) || amount_out(8)
    let mut data = [0u8; ADDR_LEN + 16];
    let mut i = 0;
    while i < ADDR_LEN { data[i] = caller[i]; i += 1; }
    let ai = amount_in.to_le_bytes();
    let ao = out.to_le_bytes();
    i = 0;
    while i < 8 { data[ADDR_LEN + i] = ai[i]; data[ADDR_LEN + 8 + i] = ao[i]; i += 1; }
    let topic = *b"swap";
    unsafe { emit_log(topic.as_ptr(), 4, data.as_ptr(), data.len() as i32); }
}
