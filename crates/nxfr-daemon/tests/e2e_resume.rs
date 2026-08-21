//! End-to-end integration tests for resume functionality.

use nxfr_crypto::generate_identity;
use nxfr_daemon::handler;
use nxfr_daemon::identity::PersistentIdentity;
use nxfr_daemon::DaemonState;
use nxfr_storage::config::NxfrConfig;
use nxfr_storage::db::{PairedDevice, PairedDeviceDb};
use nxfr_storage::resume::{ResumeFileState, ResumeJournal, ResumeManifestEntry, ResumeState};
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

/// NOTE: The current implementation of `handle_incoming` in `handler.rs` does not
/// correctly transition into the file-receiving state (`handle_incoming_transfer`)
/// after sending `RESUME_STATUS(resumable=true)`. Thus, these tests may fail or
/// hang until `handler.rs` is fixed. They are written to expect the correct spec behavior.
#[tokio::test]
async fn test_resume_normal() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");

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

    // Pre-seed B to trust A with auto_accept=always
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

    let port: u16 = 17600 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a 20 MB test file
    let test_file = dir_a.join("testfile.bin");
    let file_data: Vec<u8> = (0..20 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    // First transfer A->B to get a transfer_id
    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;
    assert!(result.is_ok());
    let original_transfer_id = result.unwrap();

    // Setup partial state for resume on B's side
    let received_file = dir_b.join("recv").join("testfile.bin");
    std::fs::remove_file(&received_file).unwrap(); // Delete the completed file

    // Write a .part file with first 8MB
    let part_data = &file_data[0..8 * 1024 * 1024];
    let part_path = received_file.with_extension("bin.part");
    std::fs::write(&part_path, part_data).unwrap();

    // Create B's resume journal for partial state
    let mut files_map = HashMap::new();
    files_map.insert(
        1,
        ResumeFileState {
            received_bytes: 8 * 1024 * 1024,
            received_ranges: vec![(0, 8 * 1024 * 1024)],
            partial_sha256: None,
            dest_path: part_path.to_string_lossy().to_string(),
        },
    );

    let journal_state = ResumeState {
        transfer_id: original_transfer_id.clone(),
        peer_device_id: device_id_a.clone(),
        display_name: "testfile.bin".to_string(),
        manifest: vec![ResumeManifestEntry {
            file_id: 1,
            relative_path: "testfile.bin".to_string(),
            size: 20 * 1024 * 1024,
            sha256: hex::encode(expected_hash),
        }],
        files: files_map,
        created_at: chrono::Utc::now().timestamp(),
        expires_at: chrono::Utc::now().timestamp() + 86400,
        version: 1,
    };
    state_b.resume.save(&journal_state).unwrap();

    // Call handle_outbound_resume from A
    let resume_result = handler::handle_outbound_resume(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        &original_transfer_id,
        None,
    )
    .await;

    assert!(
        resume_result.is_ok(),
        "Resume transfer failed: {:?}",
        resume_result.err()
    );
    let new_transfer_id = resume_result.unwrap();
    // Normal resume should complete under the same transfer ID
    assert_eq!(new_transfer_id, original_transfer_id);

    // Assert file arrived complete with correct SHA-256
    assert!(received_file.exists());
    let received_data = std::fs::read(&received_file).unwrap();
    assert_eq!(received_data.len(), 20 * 1024 * 1024);
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(expected_hash, received_hash);

    // Assert journals are cleaned up on both sides
    let a_journals = state_a.resume.list_active().unwrap();
    assert!(!a_journals
        .iter()
        .any(|id| id.contains(&original_transfer_id)));
    let b_journals = state_b.resume.list_active().unwrap();
    assert!(!b_journals
        .iter()
        .any(|id| id.contains(&original_transfer_id)));

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}

#[tokio::test]
async fn test_resume_fallback_modified_file() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");

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

    let port: u16 = 17601 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a 20 MB test file
    let test_file = dir_a.join("testfile.bin");
    let mut file_data: Vec<u8> = (0..20 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;
    assert!(result.is_ok());
    let original_transfer_id = result.unwrap();

    // Partial state on B's side
    let received_file = dir_b.join("recv").join("testfile.bin");
    std::fs::remove_file(&received_file).unwrap();

    let part_data = &file_data[0..8 * 1024 * 1024];
    let part_path = received_file.with_extension("bin.part");
    std::fs::write(&part_path, part_data).unwrap();

    let mut files_map = HashMap::new();
    files_map.insert(
        1,
        ResumeFileState {
            received_bytes: 8 * 1024 * 1024,
            received_ranges: vec![(0, 8 * 1024 * 1024)],
            partial_sha256: None,
            dest_path: part_path.to_string_lossy().to_string(),
        },
    );

    let journal_state = ResumeState {
        transfer_id: original_transfer_id.clone(),
        peer_device_id: device_id_a.clone(),
        display_name: "testfile.bin".to_string(),
        manifest: vec![ResumeManifestEntry {
            file_id: 1,
            relative_path: "testfile.bin".to_string(),
            size: 20 * 1024 * 1024,
            sha256: hex::encode(expected_hash),
        }],
        files: files_map,
        created_at: chrono::Utc::now().timestamp(),
        expires_at: chrono::Utc::now().timestamp() + 86400,
        version: 1,
    };
    state_b.resume.save(&journal_state).unwrap();

    // Modify the source file so its SHA-256 changes
    file_data[0] ^= 0xFF;
    std::fs::write(&test_file, &file_data).unwrap();
    let modified_hash: [u8; 32] = Sha256::digest(&file_data).into();

    // Call handle_outbound_resume. It should get FILE_METADATA_ACK(accepted=false) and fallback
    let resume_result = handler::handle_outbound_resume(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        &original_transfer_id,
        None,
    )
    .await;

    assert!(
        resume_result.is_ok(),
        "Fallback transfer failed: {:?}",
        resume_result.err()
    );
    let new_transfer_id = resume_result.unwrap();
    // Fallback should result in a fresh transfer with a new ID
    assert_ne!(new_transfer_id, original_transfer_id);

    // Final file should have the modified hash
    assert!(received_file.exists());
    let received_data = std::fs::read(&received_file).unwrap();
    assert_eq!(received_data.len(), 20 * 1024 * 1024);
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(modified_hash, received_hash);

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}

#[tokio::test]
async fn test_resume_wrong_peer() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");
    let dir_c = tmp.path().join("daemon_c");

    let ident_a = gen_identity();
    let ident_b = gen_identity();
    let ident_c = gen_identity();
    let device_id_a = hex::encode(ident_a.device_id);
    let device_id_b = hex::encode(ident_b.device_id);
    let device_id_c = hex::encode(ident_c.device_id);

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
    let state_c = create_test_state(
        ident_c.clone(),
        &dir_c.join("paired.db"),
        &dir_c.join("resume"),
        &dir_c.join("recv"),
    );

    // Pre-seed B to trust A AND C
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
        db.insert_or_update(&PairedDevice {
            device_id: device_id_c.clone(),
            name: "DaemonC".to_string(),
            public_key_spki: ident_c.cert_der_bytes().to_vec(),
            first_seen: chrono::Utc::now().timestamp(),
            last_seen: chrono::Utc::now().timestamp(),
            trust_level: "paired".to_string(),
            auto_accept: "always".to_string(),
        })
        .unwrap();
    }

    let port: u16 = 17602 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    let test_file = dir_a.join("testfile.bin");
    let file_data: Vec<u8> = (0..20 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;
    assert!(result.is_ok());
    let original_transfer_id = result.unwrap();

    let received_file = dir_b.join("recv").join("testfile.bin");
    std::fs::remove_file(&received_file).unwrap();

    let part_data = &file_data[0..8 * 1024 * 1024];
    let part_path = received_file.with_extension("bin.part");
    std::fs::write(&part_path, part_data).unwrap();

    let mut files_map = HashMap::new();
    files_map.insert(
        1,
        ResumeFileState {
            received_bytes: 8 * 1024 * 1024,
            received_ranges: vec![(0, 8 * 1024 * 1024)],
            partial_sha256: None,
            dest_path: part_path.to_string_lossy().to_string(),
        },
    );

    // Save journal with A's device_id
    let journal_state = ResumeState {
        transfer_id: original_transfer_id.clone(),
        peer_device_id: device_id_a.clone(),
        display_name: "testfile.bin".to_string(),
        manifest: vec![ResumeManifestEntry {
            file_id: 1,
            relative_path: "testfile.bin".to_string(),
            size: 20 * 1024 * 1024,
            sha256: hex::encode(expected_hash),
        }],
        files: files_map,
        created_at: chrono::Utc::now().timestamp(),
        expires_at: chrono::Utc::now().timestamp() + 86400,
        version: 1,
    };
    state_b.resume.save(&journal_state).unwrap();

    // Now Daemon C attempts to resume using A's transfer_id
    let test_file_c = dir_c.join("testfile.bin");
    std::fs::write(&test_file_c, &file_data).unwrap();

    let resume_result = handler::handle_outbound_resume(
        Arc::clone(&state_c), // Using state_c!
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file_c,
        &original_transfer_id,
        None,
    )
    .await;

    assert!(
        resume_result.is_ok(),
        "Fallback transfer failed: {:?}",
        resume_result.err()
    );
    let new_transfer_id = resume_result.unwrap();

    // Because C's device_id doesn't match the journal's peer_device_id (which is A's),
    // B should respond with resumable=false, forcing a fresh transfer with a new ID.
    assert_ne!(new_transfer_id, original_transfer_id);

    // Final file should be complete
    assert!(received_file.exists());
    let received_data = std::fs::read(&received_file).unwrap();
    assert_eq!(received_data.len(), 20 * 1024 * 1024);

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}

#[tokio::test]
async fn test_resume_tail_covered_full_file() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");

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

    let port: u16 = 17603 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a 20 MB test file
    let test_file = dir_a.join("testfile.bin");
    let file_data: Vec<u8> = (0..20 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;
    assert!(result.is_ok());
    let original_transfer_id = result.unwrap();

    let received_file = dir_b.join("recv").join("testfile.bin");
    std::fs::remove_file(&received_file).unwrap();

    // Partial state has the COMPLETE 20 MB file on disk in .part
    let part_path = received_file.with_extension("bin.part");
    std::fs::write(&part_path, &file_data).unwrap();

    let mut files_map = HashMap::new();
    files_map.insert(
        1,
        ResumeFileState {
            received_bytes: 20 * 1024 * 1024,
            received_ranges: vec![(0, 20 * 1024 * 1024)],
            partial_sha256: None,
            dest_path: part_path.to_string_lossy().to_string(),
        },
    );

    let journal_state = ResumeState {
        transfer_id: original_transfer_id.clone(),
        peer_device_id: device_id_a.clone(),
        display_name: "testfile.bin".to_string(),
        manifest: vec![ResumeManifestEntry {
            file_id: 1,
            relative_path: "testfile.bin".to_string(),
            size: 20 * 1024 * 1024,
            sha256: hex::encode(expected_hash),
        }],
        files: files_map,
        created_at: chrono::Utc::now().timestamp(),
        expires_at: chrono::Utc::now().timestamp() + 86400,
        version: 1,
    };
    state_b.resume.save(&journal_state).unwrap();

    // Resume transfer when all bytes are already in skip_ranges
    let resume_result = handler::handle_outbound_resume(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        &original_transfer_id,
        None,
    )
    .await;

    assert!(
        resume_result.is_ok(),
        "Resume transfer failed: {:?}",
        resume_result.err()
    );
    let resumed_transfer_id = resume_result.unwrap();
    assert_eq!(resumed_transfer_id, original_transfer_id);

    // Assert file arrived complete with EXACT size (no tail duplication) and SHA-256
    assert!(received_file.exists(), "Final file should exist");
    let received_data = std::fs::read(&received_file).unwrap();
    assert_eq!(
        received_data.len(),
        20 * 1024 * 1024,
        "File size MUST be exactly 20 MB (no duplicate tail bytes)"
    );
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(expected_hash, received_hash, "SHA-256 must match source");

    // .part file must no longer exist
    assert!(!part_path.exists(), ".part file must be cleaned up");

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}

#[tokio::test]
async fn test_resume_tail_covered_partial_file() {
    let _ = env_logger::builder().is_test(true).try_init();

    let tmp = tempfile::tempdir().unwrap();
    let dir_a = tmp.path().join("daemon_a");
    let dir_b = tmp.path().join("daemon_b");

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

    let port: u16 = 17604 + (std::process::id() % 100) as u16;
    let state_b_clone = Arc::clone(&state_b);
    let listener_handle = tokio::spawn(async move {
        let _ = nxfr_daemon::listener::run_listener_on_port(state_b_clone, port).await;
    });

    tokio::time::sleep(std::time::Duration::from_millis(200)).await;

    // Create a 20 MB test file (20 chunks of 1 MB)
    let test_file = dir_a.join("testfile.bin");
    let file_data: Vec<u8> = (0..20 * 1024 * 1024).map(|i| (i % 256) as u8).collect();
    let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();
    std::fs::write(&test_file, &file_data).unwrap();

    let result = handler::handle_outbound_send(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        None,
    )
    .await;
    assert!(result.is_ok());
    let original_transfer_id = result.unwrap();

    let received_file = dir_b.join("recv").join("testfile.bin");
    std::fs::remove_file(&received_file).unwrap();

    // Partial state: 19 MB received out of 20 MB (19 chunks done, only chunk 20 remaining)
    let pre_bytes = 19 * 1024 * 1024;
    let part_data = &file_data[0..pre_bytes];
    let part_path = received_file.with_extension("bin.part");
    std::fs::write(&part_path, part_data).unwrap();

    let mut files_map = HashMap::new();
    files_map.insert(
        1,
        ResumeFileState {
            received_bytes: pre_bytes as u64,
            received_ranges: vec![(0, pre_bytes as u64)],
            partial_sha256: None,
            dest_path: part_path.to_string_lossy().to_string(),
        },
    );

    let journal_state = ResumeState {
        transfer_id: original_transfer_id.clone(),
        peer_device_id: device_id_a.clone(),
        display_name: "testfile.bin".to_string(),
        manifest: vec![ResumeManifestEntry {
            file_id: 1,
            relative_path: "testfile.bin".to_string(),
            size: 20 * 1024 * 1024,
            sha256: hex::encode(expected_hash),
        }],
        files: files_map,
        created_at: chrono::Utc::now().timestamp(),
        expires_at: chrono::Utc::now().timestamp() + 86400,
        version: 1,
    };
    state_b.resume.save(&journal_state).unwrap();

    // Resume transfer
    let resume_result = handler::handle_outbound_resume(
        Arc::clone(&state_a),
        &format!("127.0.0.1:{port}"),
        &device_id_b,
        &test_file,
        &original_transfer_id,
        None,
    )
    .await;

    assert!(
        resume_result.is_ok(),
        "Resume transfer failed: {:?}",
        resume_result.err()
    );
    let resumed_transfer_id = resume_result.unwrap();
    assert_eq!(resumed_transfer_id, original_transfer_id);

    // Verify exact size and SHA-256
    assert!(received_file.exists(), "Final file should exist");
    let received_data = std::fs::read(&received_file).unwrap();
    assert_eq!(
        received_data.len(),
        20 * 1024 * 1024,
        "File size MUST be exactly 20 MB"
    );
    let received_hash: [u8; 32] = Sha256::digest(&received_data).into();
    assert_eq!(expected_hash, received_hash, "SHA-256 must match source");

    state_b.shutdown.notify_waiters();
    let _ = listener_handle.await;
}
