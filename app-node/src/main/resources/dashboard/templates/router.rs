#![no_std]
#![no_main]

// A minimal composition test contract: forwards a payload to another contract via
// call_contract. Exercises the cross-contract machinery — nested frames, caller
// identity (the callee sees the router as its caller), failure observation, and
// caller-revert-after-successful-sub-call atomicity.
extern "C" {
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
    fn get_input(out_ptr: *mut u8, out_cap: i32) -> i32;
    fn call_contract(addr_ptr: *const u8, addr_len: i32,
                     in_ptr: *const u8, in_len: i32,
                     out_ptr: *mut u8, out_cap: i32) -> i32;
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

const ADDR_LEN: usize = 25;

/// input[0] selector; input[1..26] = callee address; input[26..] = payload.
///   0 = forward: call the callee; on success output the callee's output, on
///       failure emit a "callfail" log and keep going (the router call succeeds).
///   1 = call-then-trap: call the callee, then trap — the router call reverts,
///       and the callee's already-flushed writes must be discarded with it.
#[no_mangle]
pub extern "C" fn call() {
    let mut input = [0u8; 96];
    let n = unsafe { get_input(input.as_mut_ptr(), 96) };
    if n < (1 + ADDR_LEN) as i32 { return; }
    let n = if n > 96 { 96usize } else { n as usize };

    let callee = &input[1..1 + ADDR_LEN];
    let payload = &input[1 + ADDR_LEN..n];
    let mut out = [0u8; 64];
    let ret = unsafe {
        call_contract(callee.as_ptr(), ADDR_LEN as i32,
                      payload.as_ptr(), payload.len() as i32,
                      out.as_mut_ptr(), 64)
    };

    match input[0] {
        0 => {
            if ret < 0 {
                let topic = *b"callfail";
                unsafe { emit_log(topic.as_ptr(), 8, out.as_ptr(), 0); }
            } else {
                let len = if ret > 64 { 64 } else { ret };
                unsafe { set_output(out.as_ptr(), len); }
            }
        }
        1 => {
            // Deliberate trap after the (possibly successful) sub-call: the whole
            // router frame reverts, sub-call writes included.
            core::arch::wasm32::unreachable();
        }
        _ => {}
    }
}
