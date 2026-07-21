#![no_std]
#![no_main]

// A fair-launch launchpad: a fixed-price, first-come sale of a token the launchpad
// holds. Buyers attach native coin to a call; the launchpad checks its own token
// balance (call output round-trip), pays out via the token contract, and traps if
// it cannot deliver — so the buyer's coin only moves when tokens do. No presale,
// no allocations: the memecoin fair launch, built on call_contract.
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_caller(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_deployer(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn get_value() -> i64;
    fn get_self(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

/// Revert the whole call (a trap): used whenever the sale cannot deliver, so the
/// executor rolls the buyer's attached value back with everything else.
fn fail() -> ! { core::arch::wasm32::unreachable() }

const ADDR_LEN: usize = 25;

// Storage keys: [0] = token address (25), [1] = tokens per native unit (8 LE), [2] = init flag.
const KEY_TOKEN: [u8; 1] = [0];
const KEY_RATE: [u8; 1] = [1];
const KEY_INIT: [u8; 1] = [2];

fn read_exact(key: &[u8], out: &mut [u8]) -> bool {
    let n = unsafe { storage_read(key.as_ptr(), key.len() as i32, out.as_mut_ptr(), out.len() as i32) };
    n == out.len() as i32
}

/// True if `who` deployed this launchpad; init and withdraw are gated on this (audit T1 / T4).
fn is_deployer(who: &[u8; ADDR_LEN]) -> bool {
    let mut d = [0u8; ADDR_LEN];
    let n = unsafe { get_deployer(d.as_mut_ptr(), ADDR_LEN as i32) };
    n == ADDR_LEN as i32 && &d == who
}

/// input[0] selector:
///   0 = init(token(25), rate(8 LE)) — once; the creator then funds the launchpad
///       by a plain token.transfer to its address.
///   1 = buy() — attach value; receive value*rate tokens or revert.
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 64];
    let n = unsafe { get_input(input.as_mut_ptr(), 64) };
    if n < 1 { return; }

    match input[0] {
        0 => {
            let mut caller = [0u8; ADDR_LEN];
            unsafe { get_caller(caller.as_mut_ptr(), ADDR_LEN as i32); }
            if !is_deployer(&caller) { return; }
            let mut probe = [0u8; 1];
            if unsafe { storage_read(KEY_INIT.as_ptr(), 1, probe.as_mut_ptr(), 1) } >= 0 { return; }
            if n < (1 + ADDR_LEN + 8) as i32 { return; }
            unsafe {
                storage_write(KEY_TOKEN.as_ptr(), 1, input[1..1 + ADDR_LEN].as_ptr(), ADDR_LEN as i32);
                storage_write(KEY_RATE.as_ptr(), 1, input[1 + ADDR_LEN..1 + ADDR_LEN + 8].as_ptr(), 8);
                let one = [1u8; 1];
                storage_write(KEY_INIT.as_ptr(), 1, one.as_ptr(), 1);
            }
        }
        1 => buy(),
        _ => {}
    }
}

fn buy() {
    let value = unsafe { get_value() } as u64;

    let mut token = [0u8; ADDR_LEN];
    let mut rate_b = [0u8; 8];
    if !read_exact(&KEY_TOKEN, &mut token) || !read_exact(&KEY_RATE, &mut rate_b) { fail(); }
    let rate = u64::from_le_bytes(rate_b);

    let tokens = match value.checked_mul(rate) {
        Some(t) if t > 0 => t,
        _ => fail(), // zero or overflowing ask: refuse the sale, refund by revert
    };

    let mut buyer = [0u8; ADDR_LEN];
    let mut own = [0u8; ADDR_LEN];
    unsafe {
        get_caller(buyer.as_mut_ptr(), ADDR_LEN as i32);
        get_self(own.as_mut_ptr(), ADDR_LEN as i32);
    }

    // balance_of(self) through the token: the call output drives the decision.
    let mut query = [0u8; 1 + ADDR_LEN];
    query[0] = 2;
    let mut i = 0;
    while i < ADDR_LEN { query[1 + i] = own[i]; i += 1; }
    let mut bal_out = [0u8; 8];
    let ret = unsafe {
        call_contract(token.as_ptr(), ADDR_LEN as i32,
                      query.as_ptr(), query.len() as i32,
                      bal_out.as_mut_ptr(), 8)
    };
    if ret != 8 || u64::from_le_bytes(bal_out) < tokens { fail(); } // cannot deliver: revert

    // transfer(buyer, tokens) — the launchpad is the token's caller, spending its own balance.
    let mut xfer = [0u8; 1 + ADDR_LEN + 8];
    xfer[0] = 1;
    i = 0;
    while i < ADDR_LEN { xfer[1 + i] = buyer[i]; i += 1; }
    let amt = tokens.to_le_bytes();
    i = 0;
    while i < 8 { xfer[1 + ADDR_LEN + i] = amt[i]; i += 1; }
    if unsafe { call_contract(token.as_ptr(), ADDR_LEN as i32,
                              xfer.as_ptr(), xfer.len() as i32,
                              bal_out.as_mut_ptr(), 0) } < 0 { fail(); }

    // log: buyer(25) || value(8) || tokens(8)
    let mut data = [0u8; ADDR_LEN + 16];
    i = 0;
    while i < ADDR_LEN { data[i] = buyer[i]; i += 1; }
    let vb = value.to_le_bytes();
    i = 0;
    while i < 8 { data[ADDR_LEN + i] = vb[i]; data[ADDR_LEN + 8 + i] = amt[i]; i += 1; }
    let topic = *b"buy";
    unsafe { emit_log(topic.as_ptr(), 3, data.as_ptr(), data.len() as i32); }
}
