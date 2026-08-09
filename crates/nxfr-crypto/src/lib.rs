//! # nxfr-crypto
//!
//! Cryptographic primitives for the NXFR protocol:
//! - ECDSA P-256 key/cert generation (self-signed)
//! - device_id derivation from SPKI DER (SHA-256)
//! - SPKI extraction from X.509 certificates

pub mod identity;

pub use identity::{
    device_id_from_cert, extract_spki, generate_identity, generate_keypair, NxfrIdentity,
};
