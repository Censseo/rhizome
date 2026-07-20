#![no_std]
#![no_main]

// A constant-product AMM over two REAL token contracts (x*y=k, 0.3% fee): the
// token-backed variant of amm.rs, built on call_contract. Liquidity and swap
// inputs are pulled from the caller via the tokens' transfer_from (after an
// approve), payouts leave via transfer — the pair is the tokens' caller, spending
// its own balances. Every failed pull or payout traps, so a swap is atomic: both
// legs move or neither does.
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_self(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;

// Storage keys: [0]=token A addr, [1]=token B addr, [2]=reserve A, [3]=reserve B, [4]=init flag.
const KEY_TOKEN_A: [u8; 1] = [0];
const KEY_TOKEN_B: [u8; 1] = [1];
const KEY_RESERVE_A: [u8; 1] = [2];
const KEY_RESERVE_B: [u8; 1] = [3];
const KEY_INIT: [u8; 1] = [4];

fn read_u64(key: &[u8]) -> u64 {
    let mut buf = [0u8; 8];
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, buf.as_mut_ptr(), 8) };
    if n == 8 { u64::from_le_bytes(buf) } else { 0 }
}

fn write_u64(key: &[u8], v: u64) {
    let val = v.to_le_bytes();
    unsafe { storage_write(key.as_ptr(), key.len() as i32, val.as_ptr(), 8); }
}

fn read_token(key: &[u8]) -> [u8; ADDR_LEN] {
    let mut addr = [0u8; ADDR_LEN];
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, addr.as_mut_ptr(), ADDR_LEN as i32) };
    if n != ADDR_LEN as i32 { fail(); }
    addr
}

fn read_u64_at(input: &[u8], off: usize) -> u64 {
    let mut b = [0u8; 8];
    let mut i = 0;
    while i < 8 { b[i] = input[off + i]; i += 1; }
    u64::from_le_bytes(b)
}

/// token.transfer_from(from, to, amount) — traps if the pull fails (the token
/// itself traps on insufficient allowance/balance, surfacing here as -1).
fn pull(token: &[u8; ADDR_LEN], from: &[u8; ADDR_LEN], to: &[u8; ADDR_LEN], amount: u64) {
    let mut call = [0u8; 1 + 2 * ADDR_LEN + 8];
    call[0] = 4;
    let mut i = 0;
    while i < ADDR_LEN {
        call[1 + i] = from[i];
        call[1 + ADDR_LEN + i] = to[i];
        i += 1;
    }
    let amt = amount.to_le_bytes();
    i = 0;
    while i < 8 { call[1 + 2 * ADDR_LEN + i] = amt[i]; i += 1; }
    let mut out = [0u8; 1];
    if unsafe { call_contract(token.as_ptr(), ADDR_LEN as i32,
                              call.as_ptr(), call.len() as i32,
                              out.as_mut_ptr(), 0) } < 0 { fail(); }
}

/// token.transfer(to, amount) — the pair spends its own balance; traps on failure.
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

// Constant-product output with a 0.3% fee, in u128 to avoid overflow.
fn amount_out(amount_in: u64, reserve_in: u64, reserve_out: u64) -> u64 {
    if amount_in == 0 || reserve_in == 0 || reserve_out == 0 { return 0; }
    let in_with_fee = (amount_in as u128) * 997;
    let numerator = in_with_fee * (reserve_out as u128);
    let denominator = (reserve_in as u128) * 1000 + in_with_fee;
    (numerator / denominator) as u64
}

/// input[0] selector:
///   0 = init(tokenA(25), tokenB(25))       — once
///   1 = add_liquidity(amtA(8), amtB(8))    — pulls both legs from the caller (approve first)
///   2 = swap_a_for_b(amount_in(8))         — pulls A, pays B, emits "swap"
///   3 = swap_b_for_a(amount_in(8))         — the mirror
///   4 = reserves()                         — output reserveA(8) || reserveB(8)
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 64];
    let n = unsafe { get_input(input.as_mut_ptr(), 64) };
    if n < 1 { return; }

    let mut caller = [0u8; ADDR_LEN];
    let mut own = [0u8; ADDR_LEN];
    unsafe {
        get_caller(caller.as_mut_ptr(), ADDR_LEN as i32);
        get_self(own.as_mut_ptr(), ADDR_LEN as i32);
    }

    match input[0] {
        0 => {
            let mut probe = [0u8; 1];
            if unsafe { storage_read(KEY_INIT.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            if n < (1 + 2 * ADDR_LEN) as i32 { return; }
            unsafe {
                storage_write(KEY_TOKEN_A.as_ptr(), 1, input[1..1 + ADDR_LEN].as_ptr(), ADDR_LEN as i32);
                storage_write(KEY_TOKEN_B.as_ptr(), 1,
                    input[1 + ADDR_LEN..1 + 2 * ADDR_LEN].as_ptr(), ADDR_LEN as i32);
                let one = [1u8; 1];
                storage_write(KEY_INIT.as_ptr(), 1, one.as_ptr(), 1);
            }
        }
        1 => {
            let amt_a = read_u64_at(&input, 1);
            let amt_b = read_u64_at(&input, 9);
            let token_a = read_token(&KEY_TOKEN_A);
            let token_b = read_token(&KEY_TOKEN_B);
            pull(&token_a, &caller, &own, amt_a);
            pull(&token_b, &caller, &own, amt_b);
            write_u64(&KEY_RESERVE_A, read_u64(&KEY_RESERVE_A).checked_add(amt_a).unwrap_or_else(|| fail()));
            write_u64(&KEY_RESERVE_B, read_u64(&KEY_RESERVE_B).checked_add(amt_b).unwrap_or_else(|| fail()));
        }
        2 => swap(&caller, &own, read_u64_at(&input, 1), &KEY_TOKEN_A, &KEY_TOKEN_B, &KEY_RESERVE_A, &KEY_RESERVE_B),
        3 => swap(&caller, &own, read_u64_at(&input, 1), &KEY_TOKEN_B, &KEY_TOKEN_A, &KEY_RESERVE_B, &KEY_RESERVE_A),
        4 => {
            let mut out = [0u8; 16];
            let a = read_u64(&KEY_RESERVE_A).to_le_bytes();
            let b = read_u64(&KEY_RESERVE_B).to_le_bytes();
            let mut i = 0;
            while i < 8 { out[i] = a[i]; out[8 + i] = b[i]; i += 1; }
            unsafe { set_output(out.as_ptr(), 16); }
        }
        _ => {}
    }
}

// Pull `amount_in` of the in-token from the caller, pay the x*y=k output of the
// out-token back, and move the stored reserves. Trapping anywhere unwinds it all.
fn swap(caller: &[u8; ADDR_LEN], own: &[u8; ADDR_LEN], amount_in: u64,
        in_token_key: &[u8], out_token_key: &[u8], in_res_key: &[u8], out_res_key: &[u8]) {
    let reserve_in = read_u64(in_res_key);
    let reserve_out = read_u64(out_res_key);
    let out = amount_out(amount_in, reserve_in, reserve_out);
    if out == 0 || out >= reserve_out { fail(); }

    let token_in = read_token(in_token_key);
    let token_out = read_token(out_token_key);
    pull(&token_in, caller, own, amount_in);
    pay(&token_out, caller, out);
    write_u64(in_res_key, reserve_in + amount_in);
    write_u64(out_res_key, reserve_out - out);

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
