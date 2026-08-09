#![no_main]
use libfuzzer_sys::fuzz_target;
use nxfr_core::frame::FrameHeader;

fuzz_target!(|data: &[u8]| {
    // Parse arbitrary bytes as a frame header.
    // Must never panic — only Ok/Err.
    if let Ok(header) = FrameHeader::parse(data) {
        // If parse succeeds, serialize must round-trip.
        let serialized = header.serialize();
        let reparsed = FrameHeader::parse(&serialized).expect("round-trip must succeed");
        assert_eq!(header, reparsed);
        // Validate payload_len — must not panic.
        let _ = header.validate_payload_len();
    }
});
