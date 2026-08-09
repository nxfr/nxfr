//! End-to-end integration test: dual-daemon 10 MB file transfer.

use nxfr_crypto::generate_identity;
use nxfr_daemon::handler;
use nxfr_daemon::identity::PersistentIdentity;
use nxfr_daemon::DaemonState;
use nxfr_storage::config::NxfrConfig;
use nxfr_storage::db::{PairedDevice, PairedDeviceDb};
use nxfr_storage::resume::ResumeJournal;
use sha2::{Digest, Sha256};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};

/// Create a test daemon state.
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

/// Generate a PersistentIdentity from raw crypto.
fn gen_identity() -> PersistentIdentity {
    let raw = generate_identity().unwrap();
    PersistentIdentity::from_raw(raw.device_id, raw.private_key_der, raw.cert_der)
}

#[tokio::test]
async fn test_dual_daemon_file_transfer() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");
    std::fs::create_dir_all(&dir_a).unwrap();
    std::fs::create_dir_all(&dir_b).unwrap();

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

    // Pre-seed: B trusts A with auto_accept=always.
    {
        let db = state_b.db.lock().await;
        db.insert_or_update(&PairedDevice {
            device_id: device_id_a.clone(),
            name: "DaemonA".to_string(),
            public_key_spki: ident_a.cert_der_bytes().to_vec(),
            first_seen: chrono::Utc::now().timestamp(),
            last_seen: chrono::Utc::now().timestamp(),
            trust_level: "paired".to_string(),
            auto_accept: "always".to_string(),
        })
        .unwrap();
    }

    // Start B's TCP listener on a random high port.
    let port: u16 = 17500 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a 10 MB test file.
    let test_file = dir_a.join("testfile.bin");
    let file_data: Vec<u8> = (0..10 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    // Send file from A to B.
    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;

    assert!(result.is_ok(), "Transfer failed: {:?}", result.err());
    let transfer_id = result.unwrap();
    assert!(!transfer_id.is_empty());

    // Verify file arrived.
    let received_file = dir_b.join("recv").join("testfile.bin");
    assert!(
        received_file.exists(),
        "Received file not found at {:?}",
        received_file
    );

    // Verify SHA-256.
    let received_data = std::fs::read(&received_file).unwrap();
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(expected_hash, received_hash, "SHA-256 mismatch");

    // Verify no .part remains.
    let part_file = received_file.with_extension("bin.part");
    assert!(!part_file.exists(), ".part file still exists");

    // Verify size.
    assert_eq!(received_data.len(), 10 * 1024 * 1024);

    // Shutdown.
    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}
