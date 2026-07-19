#![no_std]
#![no_main]

// Host ABI (module "env"): storage + output + event logs. Keys/values/topics/data
// live in linear memory.
extern "C" {
    fn storage_read(key_ptr: *const u8, key_len: i32, out_ptr: *mut u8, out_cap: i32) -> i32;
    fn storage_write(key_ptr: *const u8, key_len: i32, val_ptr: *const u8, val_len: i32);
    fn set_output(ptr: *const u8, len: i32);
    fn emit_log(topic_ptr: *const u8, topic_len: i32, data_ptr: *const u8, data_len: i32);
}

#[panic_handler]
fn panic(_: &core::panic::PanicInfo) -> ! { loop {} }

/// Entry point: bump a counter, then emit a log carrying the new count.
/// topic = "count" (ASCII), data = the counter as 8 LE bytes.
#[no_mangle]
pub extern "C" fn call() {
    let key = [0u8; 1];
    let mut buf = [0u8; 8];
    let n = unsafe { storage_read(key.as_ptr(), 1, buf.as_mut_ptr(), 8) };
    let current = if n == 8 { u64::from_le_bytes(buf) } else { 0 };
    let next = current + 1;
    let out = next.to_le_bytes();
    let topic = *b"count";
    unsafe {
        storage_write(key.as_ptr(), 1, out.as_ptr(), 8);
        emit_log(topic.as_ptr(), 5, out.as_ptr(), 8);
        set_output(out.as_ptr(), 8);
    }
}
