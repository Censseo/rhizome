#![no_std]
#![no_main]

// Log-ordering test contract (not a dashboard template): emits a "before" log,
// performs an optional nested call_contract, then emits "after". Selector 1
// traps instead of emitting "after", so tests can check that a failed frame's
// logs are dropped — never spliced into the parent's — and that a call tree's
// logs aggregate in exact emission (causal) order.

#[link(wasm_import_module = "env")]
extern "C" {
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

const ADDR_LEN: usize = 25;

fn log(topic: &[u8]) {
    // A valid (here: the topic's own) pointer with length 0 — null data pointers are rejected.
    unsafe { emit_log(topic.as_ptr(), topic.len() as i32, topic.as_ptr(), 0) };
}

/// input: [selector] || callee(25)? || payload?
///   selector 0: emit "before", run the nested call (if a callee is present), emit "after".
///   selector 1: emit "before", run the nested call, then trap — the whole frame reverts.
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 128];
    let n = unsafe { get_input(input.as_mut_ptr(), 128) };
    if n < 1 { return; }
    let n = if n > 128 { 128usize } else { n as usize };

    log(b"before");

    if n >= 1 + ADDR_LEN {
        let callee = &input[1..1 + ADDR_LEN];
        let payload = &input[1 + ADDR_LEN..n];
        let mut out = [0u8; 64];
        unsafe {
            call_contract(callee.as_ptr(), ADDR_LEN as i32,
                          payload.as_ptr(), payload.len() as i32,
                          out.as_mut_ptr(), 64);
        }
    }

    match input[0] {
        1 => core::arch::wasm32::unreachable(),
        _ => log(b"after"),
    }
}
