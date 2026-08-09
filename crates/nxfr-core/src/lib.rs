//! # nxfr-core
//!
//! Pure protocol logic for the NXFR protocol.
//!
//! This crate contains:
//! - Frame header parsing and serialization (§7)
//! - CBOR encoding/decoding for all 22 control messages (§8-9)
//! - Chunk and keepalive payload structures (§7.2)
//! - Error code enumeration with metadata (§15.1)
//! - Path sanitizer (§18.2)
//! - Session state machine (§10)
//! - Transfer state machine (§11)
//! - SAS derivation (§9.2.3)
//!
//! **No I/O. No unsafe. No tokio/rustls dependencies.**

#![forbid(unsafe_code)]

pub mod chunk;
pub mod codec;
pub mod error_code;
pub mod frame;
pub mod keepalive;
pub mod messages;
pub mod path;
pub mod sas;
pub mod session;
pub mod transfer;

// Re-export key types at crate root.
pub use chunk::ChunkPayload;
pub use error_code::ErrorCode;
pub use frame::{FrameHeader, FrameKind};
pub use keepalive::KeepalivePayload;
pub use messages::ControlMessage;
pub use path::sanitize_path;
pub use session::{SessionAction, SessionEvent, SessionState};
pub use transfer::{TransferAction, TransferEvent, TransferState};

/// In-tree property tests exercising the same surfaces as cargo-fuzz targets.
/// Run with: cargo test -p nxfr-core -- fuzz_property
#[cfg(test)]
mod fuzz_property_tests {
    use super::*;

    /// Simple PRNG (xorshift64) for generating deterministic random bytes.
    struct Rng(u64);
    impl Rng {
        fn new(seed: u64) -> Self {
            Self(if seed == 0 { 1 } else { seed })
        }
        fn next(&mut self) -> u64 {
            self.0 ^= self.0 << 13;
            self.0 ^= self.0 >> 7;
            self.0 ^= self.0 << 17;
            self.0
        }
        fn bytes(&mut self, len: usize) -> Vec<u8> {
            let mut out = Vec::with_capacity(len);
            while out.len() < len {
                let v = self.next();
                for b in v.to_le_bytes() {
                    if out.len() < len {
                        out.push(b);
                    }
                }
            }
            out
        }
    }

    #[test]
    fn fuzz_property_frame_parser_100k() {
        let mut rng = Rng::new(0xDEAD_BEEF);
        let mut parse_ok = 0u64;
        for _ in 0..100_000 {
            let len = (rng.next() % 64) as usize;
            let data = rng.bytes(len);

            // Must never panic.
            match FrameHeader::parse(&data) {
                Ok(header) => {
                    parse_ok += 1;
                    // Round-trip invariant.
                    let serialized = header.serialize();
                    let reparsed =
                        FrameHeader::parse(&serialized).expect("round-trip must succeed");
                    assert_eq!(header, reparsed);
                }
                Err(_) => {}
            }
        }
        eprintln!("[fuzz_property_frame_parser] 100k inputs, {parse_ok} parsed OK");
    }

    #[test]
    fn fuzz_property_cbor_decoder_100k() {
        let mut rng = Rng::new(0xCAFE_BABE);
        let mut decode_ok = 0u64;
        for _ in 0..100_000 {
            let len = (rng.next() % 256) as usize;
            let data = rng.bytes(len);

            // Must never panic.
            match codec::decode_control(&data) {
                Ok(msg) => {
                    decode_ok += 1;
                    if let Ok(encoded) = codec::encode_control(&msg) {
                        let redecoded = codec::decode_control(&encoded)
                            .expect("round-trip decode must succeed");
                        assert_eq!(msg, redecoded);
                    }
                }
                Err(_) => {}
            }
        }
        eprintln!("[fuzz_property_cbor_decoder] 100k inputs, {decode_ok} decoded OK");
    }

    #[test]
    fn fuzz_property_path_sanitizer_100k() {
        let mut rng = Rng::new(0xBAAD_F00D);
        for _ in 0..100_000 {
            let len = (rng.next() % 512) as usize;
            let data = rng.bytes(len);

            // Try as UTF-8. Must never panic.
            if let Ok(s) = std::str::from_utf8(&data) {
                let _ = sanitize_path(s);
            }
        }
        eprintln!("[fuzz_property_path_sanitizer] 100k inputs OK");
    }
}
