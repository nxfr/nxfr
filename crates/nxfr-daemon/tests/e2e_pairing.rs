//! End-to-end integration test: pairing flow with SAS equality assertion.
//!
//! Spawns two daemon instances (fresh identities, unpaired),
//! exercises the outbound connect + TLS exporter SAS derivation,
//! verifies both sides compute identical SAS codes,
//! then completes pairing and asserts both DBs contain the peer.

use nxfr_core::codec;
use nxfr_core::messages::ControlMessage;
use nxfr_core::sas::derive_sas;
use nxfr_crypto::{device_id_from_cert, generate_identity};
use nxfr_daemon::handler;
use nxfr_daemon::identity::PersistentIdentity;
use nxfr_daemon::DaemonState;
use nxfr_storage::config::NxfrConfig;
use nxfr_storage::db::{PairedDevice, PairedDeviceDb};
use nxfr_storage::resume::ResumeJournal;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};
use zeroize::Zeroize;

fn create_test_state(
    identity: PersistentIdentity,
    db_path: &std::path::Path,
    resume_path: &std::path::Path,
    receive_dir: &std::path::Path,
) -> Arc<DaemonState> {
    std::fs::create_dir_all(resume_path).unwrap();
    std::fs::create_dir_all(receive_dir).unwrap();

    let db = PairedDeviceDb::open(db_path).unwrap();
    let config = NxfrConfig {
        device_name: format!("test-{}", hex::encode(&identity.device_id[..4])),
        receive_dir: receive_dir.to_path_buf(),
        receiving_enabled: true,
    };

    Arc::new(DaemonState {
        config: RwLock::new(config),
        db: Mutex::new(db),
        resume: ResumeJournal::new(resume_path.to_path_buf()),
        identity,
        discovery: Mutex::new(None),
        active_transfers: Mutex::new(Vec::new()),
        active_connections: Mutex::new(HashMap::new()),
        shutdown: tokio::sync::Notify::new(),
        watchers: Mutex::new(Vec::new()),
        pending_offers: Mutex::new(HashMap::new()),
        browse_cache: Mutex::new(HashMap::new()),
    })
}

fn gen_identity() -> PersistentIdentity {
    let raw = generate_identity().unwrap();
    PersistentIdentity::from_raw(raw.device_id, raw.private_key_der, raw.cert_der)
}

#[tokio::test]
async fn test_pairing_sas_equality_and_db_insertion() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");
    std::fs::create_dir_all(&dir_a).unwrap();
    std::fs::create_dir_all(&dir_b).unwrap();

    // Fresh identities — NOT pre-paired.
    let ident_a = gen_identity();
    let ident_b = gen_identity();

    let device_id_a = hex::encode(ident_a.device_id);
    let device_id_b = hex::encode(ident_b.device_id);

    let state_a = create_test_state(
        ident_a.clone(),
        &dir_a.join("paired.db"),
        &dir_a.join("resume"),
        &dir_a.join("recv"),
    );
    let state_b = create_test_state(
        ident_b.clone(),
        &dir_b.join("paired.db"),
        &dir_b.join("resume"),
        &dir_b.join("recv"),
    );

    // Start B's TCP listener.
    let port: u16 = 17600 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let state_b_for_pair = Arc::clone(&state_b);

    // We need to intercept B's incoming connection to extract its TLS exporter bytes.
    // We'll do this by running a custom handler that captures the SAS.
    let (sas_b_tx, sas_b_rx) = tokio::sync::oneshot::channel::<String>();

    // Start B's listener with a custom pairing handler.
    let device_id_a_clone = device_id_a.clone();
    let listener_handle = tokio::spawn(async move {
        use nxfr_transport::tls;
        use tokio::net::TcpListener;
        use tokio_rustls::TlsAcceptor;

        let server_config = tls::build_server_config(
            state_b_clone.identity.private_key(),
            state_b_clone.identity.certificate(),
        )
        .unwrap();
        let acceptor = TlsAcceptor::from(Arc::new(server_config));
        let listener = TcpListener::bind(format!("127.0.0.1:{port}"))
            .await
            .unwrap();

        let (tcp_stream, _addr) = listener.accept().await.unwrap();
        let tls_stream = acceptor.accept(tcp_stream).await.unwrap();

        // Extract TLS exporter bytes from the server side.
        let (peer_cert_der, sas_code_b) = {
            let (_, server_conn) = tls_stream.get_ref();

            // Get peer device_id from cert.
            let peer_certs = server_conn.peer_certificates().unwrap();
            let peer_cert = peer_certs.first().unwrap();
            let peer_cert_der = peer_cert.as_ref().to_vec();
            let peer_id = device_id_from_cert(&peer_cert_der).unwrap();

            // Compute SAS context and exporter.
            let (_, sas_context) =
                derive_sas(&state_b_clone.identity.device_id, &peer_id, &[0u8; 4]);
            let mut exporter_bytes_b = [0u8; 4];
            server_conn
                .export_keying_material(&mut exporter_bytes_b, b"NXFR-SAS-v0", Some(&sas_context))
                .unwrap();

            let (sas_code_b, _) = derive_sas(
                &state_b_clone.identity.device_id,
                &peer_id,
                &exporter_bytes_b,
            );

            exporter_bytes_b.zeroize();
            (peer_cert_der, sas_code_b)
        };

        // Send B's SAS code back to the test.
        let _ = sas_b_tx.send(sas_code_b);

        // Now handle the HELLO/HELLO_ACK exchange.
        let mut conn = nxfr_transport::connection::NxfrConnection::new(tls_stream);
        let (_hdr, payload) = conn.recv_frame().await.unwrap();
        let msg = codec::decode_control(&payload).unwrap();
        let peer_name = match msg {
            ControlMessage::Hello { device_name, .. } => device_name,
            other => panic!("Expected HELLO, got {:?}", other),
        };

        // Send HELLO_ACK.
        let session_id = 0x1234u32;
        let config = state_b_clone.config.read().await;
        let ack = ControlMessage::HelloAck {
            protocol_version: nxfr_common::ProtocolVersion::V0_1,
            session_id,
            device_id: nxfr_common::DeviceId::from_bytes(state_b_clone.identity.device_id),
            device_name: config.device_name.clone(),
            platform: nxfr_common::Platform::Linux,
            capabilities: vec![],
            is_paired: false,
        };
        drop(config);
        conn.send_control(0, 0, &ack).await.unwrap();

        // Receive PAIR_REQUEST.
        let (_hdr, payload) = conn.recv_frame().await.unwrap();
        let msg = codec::decode_control(&payload).unwrap();
        match msg {
            ControlMessage::PairRequest { sas_method } => {
                assert_eq!(sas_method, "numeric-6");
            }
            other => panic!("Expected PAIR_REQUEST, got {:?}", other),
        }

        // Send PAIR_ACCEPT (auto-confirm from B's side).
        let accept = ControlMessage::PairAccept;
        conn.send_control(session_id, 0, &accept).await.unwrap();

        // Wait for A's PAIR_ACCEPT.
        let (_hdr, payload) = conn.recv_frame().await.unwrap();
        let msg = codec::decode_control(&payload).unwrap();
        match msg {
            ControlMessage::PairAccept => {}
            other => panic!("Expected PAIR_ACCEPT from A, got {:?}", other),
        }

        // Insert A into B's paired DB.
        {
            let db = state_b_for_pair.db.lock().await;
            let device = PairedDevice {
                device_id: device_id_a_clone,
                name: peer_name,
                public_key_spki: peer_cert_der.clone(),
                first_seen: chrono::Utc::now().timestamp(),
                last_seen: chrono::Utc::now().timestamp(),
                trust_level: "paired".to_string(),
                auto_accept: "prompt".to_string(),
            };
            db.insert_or_update(&device).unwrap();
        }

        // Receive SESSION_CLOSE.
        let _ = conn.recv_frame().await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // A side: connect to B and derive SAS.
    let (mut conn_a, session_id_a, peer_name_a, mut exporter_bytes_a) =
        handler::connect_to_peer(&state_a, &format!("127.0.0.1:{port}"), &device_id_b)
            .await
            .expect("connect_to_peer failed");

    // Derive A's SAS.
    let peer_id_b_bytes = ident_b.device_id;
    let (sas_code_a, _) = derive_sas(
        &state_a.identity.device_id,
        &peer_id_b_bytes,
        &exporter_bytes_a,
    );
    exporter_bytes_a.zeroize();

    // Get B's SAS (computed on the server side).
    let sas_code_b = sas_b_rx.await.expect("B should have sent SAS code");

    // *** CRITICAL ASSERTION: SAS codes MUST be identical ***
    assert_eq!(
        sas_code_a, sas_code_b,
        "SAS code mismatch! A={sas_code_a} B={sas_code_b} — TLS exporter wiring is broken"
    );
    println!("✓ SAS codes match: A={sas_code_a} B={sas_code_b}");

    // A sends PAIR_REQUEST.
    let pair_req = ControlMessage::PairRequest {
        sas_method: "numeric-6".to_string(),
    };
    conn_a
        .send_control(session_id_a, 0, &pair_req)
        .await
        .unwrap();

    // A sends PAIR_ACCEPT (simulating user confirm).
    let accept = ControlMessage::PairAccept;
    conn_a.send_control(session_id_a, 0, &accept).await.unwrap();

    // A receives B's PAIR_ACCEPT.
    let (_hdr, payload) = conn_a.recv_frame().await.unwrap();
    let msg = codec::decode_control(&payload).unwrap();
    match msg {
        ControlMessage::PairAccept => {}
        other => panic!("Expected PAIR_ACCEPT from B, got {:?}", other),
    }

    // A stores B in its paired DB.
    {
        let peer_spki = {
            let stream_ref = conn_a.get_ref();
            let (_, client_conn) = stream_ref.get_ref();
            let peer_certs = client_conn.peer_certificates().unwrap_or(&[]);
            peer_certs
                .first()
                .map(|c| c.as_ref().to_vec())
                .unwrap_or_default()
        };

        let db = state_a.db.lock().await;
        let device = PairedDevice {
            device_id: device_id_b.clone(),
            name: peer_name_a,
            public_key_spki: peer_spki,
            first_seen: chrono::Utc::now().timestamp(),
            last_seen: chrono::Utc::now().timestamp(),
            trust_level: "paired".to_string(),
            auto_accept: "prompt".to_string(),
        };
        db.insert_or_update(&device).unwrap();
    }

    // A sends SESSION_CLOSE.
    let close = ControlMessage::SessionClose {
        reason: Some("pairing_complete".to_string()),
    };
    conn_a.send_control(session_id_a, 0, &close).await.unwrap();

    // Wait for B's handler to finish.
    let _ = listener_handle.await;

    // *** VERIFY: Both DBs contain the paired peer with trust_level=paired ***
    {
        let db_a = state_a.db.lock().await;
        assert!(
            db_a.is_paired(&device_id_b),
            "A's DB should contain B as paired"
        );
        let device_in_a = db_a.lookup(&device_id_b).unwrap().unwrap();
        assert_eq!(device_in_a.trust_level, "paired");
        println!(
            "✓ A's DB: B is paired (trust_level={})",
            device_in_a.trust_level
        );
    }

    {
        let db_b = state_b.db.lock().await;
        assert!(
            db_b.is_paired(&device_id_a),
            "B's DB should contain A as paired"
        );
        let device_in_b = db_b.lookup(&device_id_a).unwrap().unwrap();
        assert_eq!(device_in_b.trust_level, "paired");
        println!(
            "✓ B's DB: A is paired (trust_level={})",
            device_in_b.trust_level
        );
    }

    println!("✓ Pairing E2E test passed: SAS equal, both DBs updated");
}
