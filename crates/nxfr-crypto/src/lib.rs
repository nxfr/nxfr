//! # nxfr-crypto
//!
//! Cryptographic primitives for the NXFR protocol:
//! - ECDSA P-256 key/cert generation (self-signed)
//! - device_id derivation from SPKI DER (SHA-256)
//! - SPKI extraction from X.509 certificates
//! - Entropy availability check

pub mod identity;

pub use identity::{
    device_id_from_cert, extract_spki, generate_identity, generate_keypair, NxfrIdentity,
};

/// Check system entropy availability by generating 32 random bytes.
/// Returns Ok(()) if entropy is available, Err(message) if it fails or blocks.
/// Uses ring::rand::SystemRandom (backed by getrandom/urandom).
pub fn check_entropy() -> Result<(), String> {
    use ring::rand::{SecureRandom, SystemRandom};
    let rng = SystemRandom::new();
    let mut buf = [0u8; 32];
    rng.fill(&mut buf)
        .map_err(|_| "System entropy unavailable: getrandom failed".to_string())?;
    // Sanity: all zeros is astronomically unlikely but indicates a broken RNG.
    if buf == [0u8; 32] {
        return Err("System entropy broken: 32 zero bytes returned".to_string());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_check_entropy_succeeds() {
        check_entropy().expect("entropy should be available on test host");
    }
}
