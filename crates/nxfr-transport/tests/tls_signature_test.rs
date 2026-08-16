//! Integration test: verify that the NXFR TLS config actually validates
//! handshake signatures. A legitimate mTLS connection should succeed;
//! the signature verification is exercised implicitly by rustls during
//! the handshake.

use std::sync::Arc;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

#[tokio::test]
async fn test_mtls_handshake_succeeds_with_valid_keys() {
    let server_id = nxfr_crypto::generate_identity().expect("server identity");
    let client_id = nxfr_crypto::generate_identity().expect("client identity");

    let server_config =
        nxfr_transport::tls::build_server_config(server_id.private_key(), server_id.certificate())
            .expect("server config");

    let client_config =
        nxfr_transport::tls::build_client_config(client_id.private_key(), client_id.certificate())
            .expect("client config");

    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();

    let server_task = tokio::spawn(async move {
        let acceptor = tokio_rustls::TlsAcceptor::from(Arc::new(server_config));
        let (tcp, _) = listener.accept().await.unwrap();
        let mut tls = acceptor
            .accept(tcp)
            .await
            .expect("server TLS handshake should succeed");
        let mut buf = [0u8; 5];
        let n = tls.read(&mut buf).await.unwrap();
        assert_eq!(&buf[..n], b"hello");
        tls.write_all(b"world").await.unwrap();
        tls.shutdown().await.unwrap();
    });

    let connector = tokio_rustls::TlsConnector::from(Arc::new(client_config));
    let server_name = rustls_pki_types::ServerName::try_from("nxfr-node")
        .unwrap()
        .to_owned();
    let tcp = tokio::net::TcpStream::connect(addr).await.unwrap();
    let mut tls = connector
        .connect(server_name, tcp)
        .await
        .expect("client TLS handshake should succeed");

    tls.write_all(b"hello").await.unwrap();
    let mut buf = [0u8; 5];
    let n = tls.read(&mut buf).await.unwrap();
    assert_eq!(&buf[..n], b"world");

    server_task.await.unwrap();
}

/// Verify that a client using a DIFFERENT private key than the one that
/// generated its certificate is rejected. rustls catches this mismatch
/// at config build time (`InconsistentKeys`), which proves the key/cert
/// binding is enforced.
#[tokio::test]
async fn test_mtls_handshake_rejects_mismatched_key() {
    let legit_client = nxfr_crypto::generate_identity().expect("legit client");
    let imposter = nxfr_crypto::generate_identity().expect("imposter");

    // The client presents legit_client's CERTIFICATE but uses imposter's PRIVATE KEY.
    // This simulates an attacker who stole a cert but doesn't have the matching key.
    let result = nxfr_transport::tls::build_client_config(
        imposter.private_key(),     // wrong key
        legit_client.certificate(), // stolen cert
    );

    assert!(
        result.is_err(),
        "build_client_config should reject mismatched key/cert, but got Ok"
    );
}
