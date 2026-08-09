#![no_main]
use libfuzzer_sys::fuzz_target;
use nxfr_core::codec;

fuzz_target!(|data: &[u8]| {
    // Decode arbitrary bytes as a CBOR control message.
    // Must never panic — only Ok/Err.
    if let Ok(msg) = codec::decode_control(data) {
        // If decode succeeds, encode must produce valid CBOR.
        if let Ok(encoded) = codec::encode_control(&msg) {
            // Re-decode must succeed and match.
            let redecoded = codec::decode_control(&encoded)
                .expect("round-trip decode must succeed");
            assert_eq!(msg, redecoded);
        }
    }
});
