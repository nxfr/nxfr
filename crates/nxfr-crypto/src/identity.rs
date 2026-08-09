//! NXFR identity: P-256 keypair generation, SPKI extraction, device_id derivation.

use rcgen::{CertificateParams, KeyPair, PKCS_ECDSA_P256_SHA256};
use rustls_pki_types::{CertificateDer, PrivateKeyDer, PrivatePkcs8KeyDer};
use sha2::{Digest, Sha256};
use x509_parser::prelude::*;

/// An NXFR endpoint identity: private key + self-signed cert + derived device_id.
#[derive(Debug, Clone)]
pub struct NxfrIdentity {
    /// DER-encoded private key (PKCS#8).
    pub private_key_der: Vec<u8>,
    /// DER-encoded self-signed certificate.
    pub cert_der: Vec<u8>,
    /// 32-byte device_id = SHA-256(SPKI DER).
    pub device_id: [u8; 32],
}

impl NxfrIdentity {
    /// Get the private key in rustls format.
    pub fn private_key(&self) -> PrivateKeyDer<'static> {
        PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(self.private_key_der.clone()))
    }

    /// Get the certificate in rustls format.
    pub fn certificate(&self) -> CertificateDer<'static> {
        CertificateDer::from(self.cert_der.clone())
    }
}

/// Generate a fresh P-256 keypair and self-signed certificate.
pub fn generate_keypair() -> Result<(KeyPair, CertificateDer<'static>), String> {
    let key_pair = KeyPair::generate_for(&PKCS_ECDSA_P256_SHA256)
        .map_err(|e| format!("key generation failed: {e}"))?;

    let params = CertificateParams::new(vec!["nxfr-node".to_string()])
        .map_err(|e| format!("cert params failed: {e}"))?;

    let cert = params
        .self_signed(&key_pair)
        .map_err(|e| format!("self-sign failed: {e}"))?;

    let cert_der = CertificateDer::from(cert.der().to_vec());
    Ok((key_pair, cert_der))
}

/// Extract the SubjectPublicKeyInfo (SPKI) DER bytes from an X.509 certificate.
pub fn extract_spki(cert_der: &[u8]) -> Result<Vec<u8>, String> {
    let (_, cert) =
        X509Certificate::from_der(cert_der).map_err(|e| format!("X.509 parse failed: {e}"))?;
    Ok(cert.tbs_certificate.subject_pki.raw.to_vec())
}

/// Derive device_id = SHA-256(SPKI DER) from a certificate.
pub fn device_id_from_cert(cert_der: &[u8]) -> Result<[u8; 32], String> {
    let spki = extract_spki(cert_der)?;
    let hash = Sha256::digest(&spki);
    Ok(hash.into())
}

/// Generate a full `NxfrIdentity` (keypair + cert + device_id).
pub fn generate_identity() -> Result<NxfrIdentity, String> {
    let (key_pair, cert_der) = generate_keypair()?;
    let device_id = device_id_from_cert(cert_der.as_ref())?;
    let private_key_der = key_pair.serialize_der();

    Ok(NxfrIdentity {
        private_key_der,
        cert_der: cert_der.to_vec(),
        device_id,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_keypair() {
        let (key_pair, cert) = generate_keypair().expect("keypair generation failed");
        assert!(!cert.as_ref().is_empty());
        assert!(!key_pair.serialize_der().is_empty());
    }

    #[test]
    fn test_device_id_is_32_bytes() {
        let (_, cert) = generate_keypair().expect("keypair generation failed");
        let device_id = device_id_from_cert(cert.as_ref()).expect("device_id failed");
        assert_eq!(device_id.len(), 32);
    }

    #[test]
    fn test_device_id_deterministic_for_same_cert() {
        let (_, cert) = generate_keypair().expect("keypair generation failed");
        let id1 = device_id_from_cert(cert.as_ref()).expect("first call");
        let id2 = device_id_from_cert(cert.as_ref()).expect("second call");
        assert_eq!(id1, id2);
    }

    #[test]
    fn test_different_keys_different_ids() {
        let (_, cert1) = generate_keypair().expect("keypair 1");
        let (_, cert2) = generate_keypair().expect("keypair 2");
        let id1 = device_id_from_cert(cert1.as_ref()).expect("id1");
        let id2 = device_id_from_cert(cert2.as_ref()).expect("id2");
        assert_ne!(id1, id2);
    }

    #[test]
    fn test_extract_spki() {
        let (_, cert) = generate_keypair().expect("keypair");
        let spki = extract_spki(cert.as_ref()).expect("spki extraction");
        // P-256 SPKI DER includes algorithm OID + uncompressed point
        assert!(spki.len() > 60, "SPKI too short: {} bytes", spki.len());
    }

    #[test]
    fn test_generate_identity() {
        let id = generate_identity().expect("identity generation");
        assert_eq!(id.device_id.len(), 32);
        assert!(!id.private_key_der.is_empty());
        assert!(!id.cert_der.is_empty());
        // Verify round-trip: device_id from cert matches stored device_id
        let recalc = device_id_from_cert(&id.cert_der).unwrap();
        assert_eq!(id.device_id, recalc);
    }

    #[test]
    fn test_spki_accessible_for_peer_cert() {
        // Verify cert can be parsed to extract SPKI (critical for post-handshake device_id)
        let id = generate_identity().expect("identity");
        let (_, cert) = X509Certificate::from_der(&id.cert_der).expect("cert should parse");
        assert!(!cert.tbs_certificate.subject_pki.raw.is_empty());
    }
}
