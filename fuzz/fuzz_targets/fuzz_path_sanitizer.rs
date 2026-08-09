#![no_main]
use libfuzzer_sys::fuzz_target;
use nxfr_core::path::sanitize_path;

fuzz_target!(|data: &[u8]| {
    // Feed arbitrary bytes as a UTF-8 path candidate.
    // Must never panic — only Ok/Err.
    if let Ok(s) = std::str::from_utf8(data) {
        let _ = sanitize_path(s);
    }
});
