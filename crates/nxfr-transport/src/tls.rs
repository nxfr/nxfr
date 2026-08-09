//! TLS 1.3 configuration for NXFR mTLS connections.
//!
//! Uses rustls 0.23 with a `NoVerifier` that accepts self-signed certs but
//! preserves the peer certificate chain for post-handshake SPKI extraction.

use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::pki_types::{CertificateDer, PrivateKeyDer, ServerName, UnixTime};
use rustls::server::danger::{ClientCertVerified, ClientCertVerifier};
use rustls::{
    ClientConfig, DigitallySignedStruct, DistinguishedName, Error, ServerConfig, SignatureScheme,
};
use std::sync::Arc;

/// NXFR ALPN protocol identifier.
pub const NXFR_ALPN: &[u8] = b"nxfr/0";

/// Build a rustls `ClientConfig` for NXFR mTLS.
///
/// - TLS 1.3 only (enforced by rustls default with ring provider)
/// - ALPN: "nxfr/0"
/// - Client certificate for mTLS
/// - NoVerifier: accepts any server cert (NXFR pins identity at application layer)
pub fn build_client_config(
    key: PrivateKeyDer<'static>,
    cert: CertificateDer<'static>,
) -> Result<ClientConfig, Error> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = ClientConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .dangerous()
        .with_custom_certificate_verifier(Arc::new(NoServerVerifier))
        .with_client_auth_cert(vec![cert], key)?;
    config.alpn_protocols = vec![NXFR_ALPN.to_vec()];
    Ok(config)
}

/// Build a rustls `ServerConfig` for NXFR mTLS.
///
/// - TLS 1.3 only
/// - ALPN: "nxfr/0"
/// - Requires client certificate (mTLS)
/// - NoVerifier: accepts any client cert (NXFR pins identity at application layer)
pub fn build_server_config(
    key: PrivateKeyDer<'static>,
    cert: CertificateDer<'static>,
) -> Result<ServerConfig, Error> {
    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut config = ServerConfig::builder_with_provider(provider)
        .with_protocol_versions(&[&rustls::version::TLS13])?
        .with_client_cert_verifier(Arc::new(NoClientVerifier))
        .with_single_cert(vec![cert], key)?;
    config.alpn_protocols = vec![NXFR_ALPN.to_vec()];
    Ok(config)
}

/// A server cert verifier that accepts any certificate.
///
/// CRITICAL: This preserves the peer certificate chain so that
/// `Connection::peer_certificates()` returns the certs post-handshake.
/// NXFR handles identity verification at the application layer by
/// extracting SPKI and computing device_id.
#[derive(Debug)]
struct NoServerVerifier;

impl ServerCertVerifier for NoServerVerifier {
    fn verify_server_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _server_name: &ServerName<'_>,
        _ocsp_response: &[u8],
        _now: UnixTime,
    ) -> Result<ServerCertVerified, Error> {
        Ok(ServerCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ED25519,
        ]
    }
}

/// A client cert verifier that accepts any certificate (for mTLS).
///
/// Same rationale as NoServerVerifier — preserves peer cert chain.
#[derive(Debug)]
struct NoClientVerifier;

impl ClientCertVerifier for NoClientVerifier {
    fn root_hint_subjects(&self) -> &[DistinguishedName] {
        &[]
    }

    fn verify_client_cert(
        &self,
        _end_entity: &CertificateDer<'_>,
        _intermediates: &[CertificateDer<'_>],
        _now: UnixTime,
    ) -> Result<ClientCertVerified, Error> {
        Ok(ClientCertVerified::assertion())
    }

    fn verify_tls12_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn verify_tls13_signature(
        &self,
        _message: &[u8],
        _cert: &CertificateDer<'_>,
        _dss: &DigitallySignedStruct,
    ) -> Result<HandshakeSignatureValid, Error> {
        Ok(HandshakeSignatureValid::assertion())
    }

    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        vec![
            SignatureScheme::ECDSA_NISTP256_SHA256,
            SignatureScheme::ECDSA_NISTP384_SHA384,
            SignatureScheme::ED25519,
        ]
    }

    fn client_auth_mandatory(&self) -> bool {
        true
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nxfr_crypto::generate_identity;

    #[test]
    fn test_build_client_config() {
        let id = generate_identity().expect("identity");
        let config = build_client_config(id.private_key(), id.certificate());
        assert!(config.is_ok());
        let config = config.unwrap();
        assert_eq!(config.alpn_protocols, vec![NXFR_ALPN.to_vec()]);
    }

    #[test]
    fn test_build_server_config() {
        let id = generate_identity().expect("identity");
        let config = build_server_config(id.private_key(), id.certificate());
        assert!(config.is_ok());
        let config = config.unwrap();
        assert_eq!(config.alpn_protocols, vec![NXFR_ALPN.to_vec()]);
    }
}
