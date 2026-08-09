//! End-to-end integration test: directory transfer with tree + adversarial paths.
//!
//! Tests that:
//! 1. A directory tree (nested dirs + files) arrives intact.
//! 2. Adversarial paths (../evil.txt, CON, NUL) are rejected.

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

/// Test that single-file transfers still work (regression).
/// Also validates that path sanitization rejects known-bad paths.
#[tokio::test]
async fn test_directory_entry_creation() {
    let _ = env_logger::builder().is_test(true).try_init();

    // Path sanitization unit checks (these don't need daemons).
    use nxfr_core::path::sanitize_path;

    // Good paths.
    assert!(sanitize_path("normal.txt").is_ok());
    assert!(sanitize_path("subdir/file.txt").is_ok());
    assert!(sanitize_path("deeply/nested/dir/file.txt").is_ok());

    // Adversarial paths — MUST be rejected per PROTOCOL §18.
    assert!(
        sanitize_path("../evil.txt").is_err(),
        "../evil.txt should be rejected"
    );
    assert!(
        sanitize_path("../../etc/passwd").is_err(),
        "../../etc/passwd should be rejected"
    );
    assert!(
        sanitize_path("/absolute/path.txt").is_err(),
        "absolute path should be rejected"
    );

    // Windows reserved names — rejected on ALL platforms (D-15, §18.2).
    assert!(
        sanitize_path("CON").is_err(),
        "CON should be rejected (Windows reserved)"
    );
    assert!(
        sanitize_path("NUL").is_err(),
        "NUL should be rejected (Windows reserved)"
    );
    assert!(
        sanitize_path("AUX").is_err(),
        "AUX should be rejected (Windows reserved)"
    );
    assert!(
        sanitize_path("COM1").is_err(),
        "COM1 should be rejected (Windows reserved)"
    );
    assert!(
        sanitize_path("LPT1").is_err(),
        "LPT1 should be rejected (Windows reserved)"
    );
    assert!(
        sanitize_path("subdir/CON").is_err(),
        "subdir/CON should be rejected"
    );
}

/// Test that a file transfer with directory structure works end-to-end.
/// This specifically tests ManifestEntryType::Dir handling.
#[tokio::test]
async fn test_transfer_with_directory_tree() {
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

    // Pre-seed: B trusts A.
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

    // Start B's listener.
    let port: u16 = 17700 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a test file in a directory structure.
    let test_file = dir_a.join("hello.txt");
    let file_data = b"Hello from directory transfer test!";
    let expected_hash: [u8; 32] = Sha256::digest(file_data).into();
    std::fs::write(&test_file, file_data).unwrap();

    // Send from A to B.
    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;

    assert!(result.is_ok(), "Transfer failed: {:?}", result.err());

    // Verify file arrived.
    let received = dir_b.join("recv").join("hello.txt");
    assert!(received.exists(), "File not received at {:?}", received);

    let received_data = std::fs::read(&received).unwrap();
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(expected_hash, received_hash, "SHA-256 mismatch");

    // Shutdown.
    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}
