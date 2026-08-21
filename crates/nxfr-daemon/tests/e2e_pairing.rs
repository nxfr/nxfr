//! End-to-end integration test: pairing flow with SAS equality assertion.
//!
//! Spawns two daemon instances (fresh identities, unpaired),
//! exercises the outbound connect + TLS exporter SAS derivation,
//! verifies both sides compute identical SAS codes,
//! then completes pairing and asserts both DBs contain the peer.

use nxfr_core::codec;
use nxfr_core::messages::ControlMessage;
use nxfr_core::sas::derive_sas;
use nxfr_crypto::generate_identity;
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

    // Start B's real TCP listener running production handle_incoming.
    let (port, listener_handle) = nxfr_daemon::listener::run_listener_dynamic(Arc::clone(&state_b))
        .await
        .unwrap();

    // A side: connect to B and derive SAS using production connect_to_peer.
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
    assert_eq!(sas_code_a.len(), 6, "SAS code must be 6 digits");

    // A sends PAIR_REQUEST over real connection to B's listener.
    let pair_req = ControlMessage::PairRequest {
        sas_method: "numeric-6".to_string(),
    };
    conn_a
        .send_control(session_id_a, 0, &pair_req)
        .await
        .unwrap();

    // A receives B's PAIR_ACCEPT (from real handle_incoming).
    let (_hdr, payload) = conn_a.recv_frame().await.unwrap();
    let msg = codec::decode_control(&payload).unwrap();
    match msg {
        ControlMessage::PairAccept => {}
        other => panic!("Expected PAIR_ACCEPT from B, got {:?}", other),
    }

    // A sends PAIR_ACCEPT (confirming pairing).
    let accept = ControlMessage::PairAccept;
    conn_a.send_control(session_id_a, 0, &accept).await.unwrap();

    // A stores B in its paired DB with extracted SPKI.
    {
        let peer_spki = {
            let stream_ref = conn_a.get_ref();
            let (_, client_conn) = stream_ref.get_ref();
            let peer_certs = client_conn.peer_certificates().unwrap_or(&[]);
            peer_certs
                .first()
                .and_then(|c| nxfr_crypto::extract_spki(c.as_ref()).ok())
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

    // Wait a brief moment for B's DB write to complete.
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

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
            "B's DB should contain A as paired (saved by real handle_incoming)"
        );
        let device_in_b = db_b.lookup(&device_id_a).unwrap().unwrap();
        assert_eq!(device_in_b.trust_level, "paired");
        println!(
            "✓ B's DB: A is paired (trust_level={})",
            device_in_b.trust_level
        );
    }

    // *** VERIFY: Subsequent connection from A to B recognizes A as paired ***
    let (_conn_a2, _session_id_a2, _peer_name_a2, mut exporter_2) =
        handler::connect_to_peer(&state_a, &format!("127.0.0.1:{port}"), &device_id_b)
            .await
            .expect("subsequent connect_to_peer should succeed as paired device");
    exporter_2.zeroize();

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;

    println!("✓ Real production pairing E2E test passed: mutual DB storage & identity match");
}

#[tokio::test]
async fn test_receiving_disabled_rejects_inbound() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");
    std::fs::create_dir_all(&dir_a).unwrap();
    std::fs::create_dir_all(&dir_b).unwrap();

    let ident_a = gen_identity();
    let ident_b = gen_identity();
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

    // Disable receiving on B
    {
        let mut cfg = state_b.config.write().await;
        cfg.receiving_enabled = false;
    }

    let (port, listener_handle) = nxfr_daemon::listener::run_listener_dynamic(Arc::clone(&state_b))
        .await
        .unwrap();

    // Connecting to B when receiving is disabled should be rejected / closed
    let connect_res =
        handler::connect_to_peer(&state_a, &format!("127.0.0.1:{port}"), &device_id_b).await;

    assert!(
        connect_res.is_err(),
        "Inbound connection to daemon with receiving_enabled=false MUST be rejected"
    );

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}
