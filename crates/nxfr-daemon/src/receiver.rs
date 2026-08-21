//! File receiver — writes to .part files, verifies hashes, atomic rename.
//!
//! Path sanitization (PROTOCOL §18, SECURITY §6) is enforced by the handler
//! before this module is called. This module handles the I/O.

use log::{error, info, warn};
use nxfr_core::codec;
use nxfr_core::frame::FrameKind;
use nxfr_core::messages::ControlMessage;
use nxfr_storage::resume::{ResumeJournal, ResumeState};
use nxfr_transport::connection::NxfrConnection;
use sha2::{Digest, Sha256};
use std::io::Write;
use std::path::{Path, PathBuf};
use tokio::io::{AsyncRead, AsyncWrite};

/// Receive chunks for a single file, write to .part, verify, rename.
#[allow(clippy::too_many_arguments)]
pub async fn receive_file<S: AsyncRead + AsyncWrite + Unpin>(
    conn: &mut NxfrConnection<S>,
    session_id: u32,
    file_id: u32,
    stream_id: u32,
    expected_size: u64,
    expected_hash: [u8; 32],
    part_path: &Path,
    final_path: &Path,
    resume_journal: &ResumeJournal,
    resume_state: &mut ResumeState,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Resume-aware: only append to .part if resume state has partial progress for this file.
    let has_partial = resume_state
        .files
        .get(&file_id)
        .is_some_and(|fs| fs.received_bytes > 0);

    let (mut file, mut received_bytes, mut hasher) = if has_partial && part_path.exists() {
        let existing_data = std::fs::read(part_path)?;
        let existing_len = existing_data.len() as u64;
        let mut h = Sha256::new();
        h.update(&existing_data);

        // If file is already completely received from prior transfer, verify and finish immediately.
        if existing_len == expected_size {
            let final_hash: [u8; 32] = h.clone().finalize().into();
            if final_hash == expected_hash {
                info!(
                    "File file_id={file_id} already fully received ({existing_len} bytes), completing directly",
                );
                let final_dest = resolve_collision(final_path);
                std::fs::rename(part_path, &final_dest)?;
                info!("File written: {}", final_dest.display());
                return Ok(());
            }
        }

        let f = std::fs::OpenOptions::new().append(true).open(part_path)?;
        info!(
            "Resuming file_id={file_id} from {} bytes, appending to {}",
            existing_len,
            part_path.display()
        );
        (f, existing_len, h)
    } else {
        let f = std::fs::File::create(part_path)?;
        info!("Receiving file_id={file_id} to {}", part_path.display());
        (f, 0u64, Sha256::new())
    };

    loop {
        let (hdr, payload) = conn.recv_frame().await?;

        match hdr.kind {
            FrameKind::Chunk => {
                if hdr.stream_id != stream_id {
                    warn!(
                        "Chunk for stream {} but expected {stream_id}",
                        hdr.stream_id
                    );
                    continue;
                }

                if payload.len() < 41 {
                    error!("Chunk payload too small: {} bytes", payload.len());
                    continue;
                }

                // Parse chunk: 8 bytes offset + 32 bytes hash + data.
                let offset = u64::from_be_bytes(payload[0..8].try_into().unwrap());
                let chunk_hash = &payload[8..40];
                let data = &payload[40..];

                // Verify per-chunk SHA-256.
                let computed_hash = Sha256::digest(data);
                if chunk_hash != computed_hash.as_slice() {
                    error!(
                        "Chunk hash mismatch at offset {offset}: expected {}, got {}",
                        hex::encode(chunk_hash),
                        hex::encode(computed_hash)
                    );
                    let err = ControlMessage::Error {
                        code: nxfr_core::error_code::ErrorCode::ChecksumMismatch,
                        message: Some(format!("chunk hash mismatch at offset {offset}")),
                        fatal: true,
                        details: None,
                    };
                    conn.send_control(session_id, 0, &err).await?;
                    let _ = std::fs::remove_file(part_path);
                    return Err("chunk hash mismatch".into());
                }

                // Enforce chunk offset invariants
                if offset == received_bytes {
                    let new_total = received_bytes + data.len() as u64;
                    if new_total > expected_size {
                        error!(
                            "Chunk at offset {offset} (len {}) exceeds expected size {expected_size}",
                            data.len(),
                        );
                        let err = ControlMessage::Error {
                            code: nxfr_core::error_code::ErrorCode::InvalidFrame,
                            message: Some("chunk exceeds expected file size".to_string()),
                            fatal: true,
                            details: None,
                        };
                        conn.send_control(session_id, 0, &err).await?;
                        let _ = std::fs::remove_file(part_path);
                        return Err("chunk exceeds expected file size".into());
                    }

                    // Write data to .part file.
                    file.write_all(data)?;
                    hasher.update(data);
                    received_bytes = new_total;

                    // Update resume state and fsync journal (PROTOCOL §13.3).
                    if let Some(fs) = resume_state.files.get_mut(&file_id) {
                        fs.received_bytes = received_bytes;
                        fs.received_ranges = vec![(0, received_bytes)];
                    }
                    // fsync the data file.
                    file.sync_all()?;
                    // Save resume journal with fsync.
                    resume_journal.save(resume_state)?;
                } else if offset < received_bytes {
                    // Check for duplicate / already-received chunk replay
                    if offset + data.len() as u64 <= received_bytes {
                        warn!(
                            "Duplicate chunk at offset {offset} (len {}) already received ({received_bytes}), skipping write",
                            data.len(),
                        );
                    } else {
                        error!(
                            "Overlapping chunk at offset {offset} (len {}) conflicts with received {received_bytes}",
                            data.len()
                        );
                        let err = ControlMessage::Error {
                            code: nxfr_core::error_code::ErrorCode::InvalidFrame,
                            message: Some("overlapping chunk offset".to_string()),
                            fatal: true,
                            details: None,
                        };
                        conn.send_control(session_id, 0, &err).await?;
                        return Err("overlapping chunk offset".into());
                    }
                } else {
                    // Gap in stream: offset > received_bytes
                    error!("Out-of-order chunk: expected offset {received_bytes}, got {offset}");
                    let err = ControlMessage::Error {
                        code: nxfr_core::error_code::ErrorCode::InvalidFrame,
                        message: Some(format!(
                            "gap in chunk stream: expected {received_bytes}, got {offset}"
                        )),
                        fatal: true,
                        details: None,
                    };
                    conn.send_control(session_id, 0, &err).await?;
                    return Err("out-of-order chunk received".into());
                }

                // Send CHUNK_ACK.
                let ack = ControlMessage::ChunkAck {
                    stream_id,
                    message_id: hdr.message_id,
                    offset,
                    length: data.len() as u64,
                };
                conn.send_control(session_id, 0, &ack).await?;

                // Check if this was the last chunk or all bytes received.
                if hdr.flags.is_last_chunk() || received_bytes == expected_size {
                    info!("Received LAST_CHUNK at offset {offset}, total {received_bytes}/{expected_size} bytes");
                    break;
                }
            }
            FrameKind::Control => {
                let msg = codec::decode_control(&payload)?;
                match msg {
                    ControlMessage::TransferCancel { reason, .. } => {
                        warn!("Transfer cancelled during file receive: {reason:?}");
                        // Clean up .part file.
                        let _ = std::fs::remove_file(part_path);
                        return Err("transfer cancelled".into());
                    }
                    ControlMessage::SessionClose { .. } => {
                        warn!("Session closed during file receive");
                        return Err("session closed during receive".into());
                    }
                    other => {
                        warn!(
                            "Unexpected control msg during chunk receive: {}",
                            other.type_name()
                        );
                    }
                }
            }
            FrameKind::Keepalive => {
                // Ignore during file receive.
            }
        }
    }

    // Verify whole-file SHA-256.
    let final_hash: [u8; 32] = hasher.finalize().into();
    if final_hash != expected_hash {
        error!(
            "Whole-file hash mismatch: expected {}, got {}",
            hex::encode(expected_hash),
            hex::encode(final_hash)
        );
        let _ = std::fs::remove_file(part_path);
        return Err("whole-file hash mismatch".into());
    }

    // Verify size.
    if received_bytes != expected_size {
        error!("Size mismatch: expected {expected_size}, got {received_bytes}");
        let _ = std::fs::remove_file(part_path);
        return Err("file size mismatch".into());
    }

    // Atomic rename: .part -> final.
    let final_dest = resolve_collision(final_path);
    std::fs::rename(part_path, &final_dest)?;
    info!("File written: {}", final_dest.display());

    Ok(())
}

/// Resolve filename collisions by appending (1), (2), etc.
fn resolve_collision(path: &Path) -> PathBuf {
    if !path.exists() {
        return path.to_path_buf();
    }

    let stem = path
        .file_stem()
        .map(|s| s.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());
    let ext = path
        .extension()
        .map(|e| format!(".{}", e.to_string_lossy()))
        .unwrap_or_default();
    let parent = path.parent().unwrap_or(Path::new("."));

    for i in 1..=999 {
        let candidate = parent.join(format!("{stem} ({i}){ext}"));
        if !candidate.exists() {
            return candidate;
        }
    }

    // Fallback: timestamp.
    parent.join(format!(
        "{stem}_{}{ext}",
        std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs()
    ))
}

#[cfg(test)]
mod tests {
    use super::*;
    use nxfr_core::frame::FrameFlags;

    #[tokio::test]
    async fn test_receive_file_duplicate_chunk_does_not_corrupt() {
        let tmp = tempfile::tempdir().unwrap();
        let part_path = tmp.path().join("test.bin.part");
        let final_path = tmp.path().join("test.bin");
        let resume_journal = ResumeJournal::new(tmp.path().join("resume"));
        let mut resume_state = ResumeState {
            transfer_id: "test1".to_string(),
            peer_device_id: "peer1".to_string(),
            display_name: "test.bin".to_string(),
            manifest: vec![],
            files: std::collections::HashMap::new(),
            created_at: 0,
            expires_at: 1000,
            version: 1,
        };

        let file_data =
            b"Hello world! This is a test file for duplicate chunk verification.".to_vec();
        let total_size = file_data.len() as u64;
        let expected_hash: [u8; 32] = Sha256::digest(&file_data).into();

        let (client_io, server_io) = tokio::io::duplex(65536);
        let mut client_conn = NxfrConnection::new(client_io);
        let mut server_conn = NxfrConnection::new(server_io);

        // Split file into two halves
        let mid = file_data.len() / 2;
        let chunk1_data = &file_data[..mid];
        let chunk2_data = &file_data[mid..];

        let chunk1_hash: [u8; 32] = Sha256::digest(chunk1_data).into();
        let chunk2_hash: [u8; 32] = Sha256::digest(chunk2_data).into();

        let mut payload1 = Vec::new();
        payload1.extend_from_slice(&0u64.to_be_bytes());
        payload1.extend_from_slice(&chunk1_hash);
        payload1.extend_from_slice(chunk1_data);

        let mut payload2 = Vec::new();
        payload2.extend_from_slice(&(mid as u64).to_be_bytes());
        payload2.extend_from_slice(&chunk2_hash);
        payload2.extend_from_slice(chunk2_data);

        let sender_task = tokio::spawn(async move {
            // Send chunk 1
            client_conn
                .send_chunk(1, 1, 0, payload1.clone())
                .await
                .unwrap();
            let _ = client_conn.recv_frame().await.unwrap(); // ChunkAck

            // Send duplicate of chunk 1 (replay)
            client_conn.send_chunk(1, 1, 0, payload1).await.unwrap();
            let _ = client_conn.recv_frame().await.unwrap(); // ChunkAck

            // Send chunk 2 with LAST_CHUNK
            client_conn
                .send_chunk(1, 1, FrameFlags::last_chunk().0, payload2)
                .await
                .unwrap();
            let _ = client_conn.recv_frame().await.unwrap(); // ChunkAck
        });

        let recv_result = receive_file(
            &mut server_conn,
            1,
            1,
            1,
            total_size,
            expected_hash,
            &part_path,
            &final_path,
            &resume_journal,
            &mut resume_state,
        )
        .await;

        assert!(
            recv_result.is_ok(),
            "receive_file failed: {:?}",
            recv_result.err()
        );
        sender_task.await.unwrap();

        // Verify final file exists, exact size (NOT inflated by duplicate chunk), and exact hash
        assert!(final_path.exists());
        let disk_data = std::fs::read(&final_path).unwrap();
        assert_eq!(
            disk_data.len(),
            file_data.len(),
            "Duplicate chunk must NOT inflate file size"
        );
        assert_eq!(disk_data, file_data);
    }

    #[tokio::test]
    async fn test_receive_file_out_of_order_chunk_aborts() {
        let tmp = tempfile::tempdir().unwrap();
        let part_path = tmp.path().join("test.bin.part");
        let final_path = tmp.path().join("test.bin");
        let resume_journal = ResumeJournal::new(tmp.path().join("resume"));
        let mut resume_state = ResumeState {
            transfer_id: "test2".to_string(),
            peer_device_id: "peer1".to_string(),
            display_name: "test.bin".to_string(),
            manifest: vec![],
            files: std::collections::HashMap::new(),
            created_at: 0,
            expires_at: 1000,
            version: 1,
        };

        let (client_io, server_io) = tokio::io::duplex(65536);
        let mut client_conn = NxfrConnection::new(client_io);
        let mut server_conn = NxfrConnection::new(server_io);

        let data = b"chunk starting at offset 100".to_vec();
        let chunk_hash: [u8; 32] = Sha256::digest(&data).into();

        let mut payload = Vec::new();
        payload.extend_from_slice(&100u64.to_be_bytes()); // Gap: expected 0, got 100
        payload.extend_from_slice(&chunk_hash);
        payload.extend_from_slice(&data);

        let sender_task = tokio::spawn(async move {
            client_conn.send_chunk(1, 1, 0, payload).await.unwrap();
            // Receiver should respond with Error
            let (hdr, p) = client_conn.recv_frame().await.unwrap();
            assert_eq!(hdr.kind, FrameKind::Control);
            let msg = codec::decode_control(&p).unwrap();
            assert!(matches!(msg, ControlMessage::Error { .. }));
        });

        let recv_result = receive_file(
            &mut server_conn,
            1,
            1,
            1,
            200,
            [0u8; 32],
            &part_path,
            &final_path,
            &resume_journal,
            &mut resume_state,
        )
        .await;

        assert!(
            recv_result.is_err(),
            "Out of order chunk must cause an error"
        );
        sender_task.await.unwrap();
    }

    #[tokio::test]
    async fn test_receive_file_checksum_mismatch_aborts() {
        let tmp = tempfile::tempdir().unwrap();
        let part_path = tmp.path().join("test.bin.part");
        let final_path = tmp.path().join("test.bin");
        let resume_journal = ResumeJournal::new(tmp.path().join("resume"));
        let mut resume_state = ResumeState {
            transfer_id: "test3".to_string(),
            peer_device_id: "peer1".to_string(),
            display_name: "test.bin".to_string(),
            manifest: vec![],
            files: std::collections::HashMap::new(),
            created_at: 0,
            expires_at: 1000,
            version: 1,
        };

        let (client_io, server_io) = tokio::io::duplex(65536);
        let mut client_conn = NxfrConnection::new(client_io);
        let mut server_conn = NxfrConnection::new(server_io);

        let data = b"some data".to_vec();
        let wrong_hash = [0xFFu8; 32]; // Corrupt chunk hash

        let mut payload = Vec::new();
        payload.extend_from_slice(&0u64.to_be_bytes());
        payload.extend_from_slice(&wrong_hash);
        payload.extend_from_slice(&data);

        let sender_task = tokio::spawn(async move {
            client_conn.send_chunk(1, 1, 0, payload).await.unwrap();
            let (hdr, p) = client_conn.recv_frame().await.unwrap();
            assert_eq!(hdr.kind, FrameKind::Control);
            let msg = codec::decode_control(&p).unwrap();
            assert!(matches!(msg, ControlMessage::Error { .. }));
        });

        let recv_result = receive_file(
            &mut server_conn,
            1,
            1,
            1,
            100,
            [0u8; 32],
            &part_path,
            &final_path,
            &resume_journal,
            &mut resume_state,
        )
        .await;

        assert!(recv_result.is_err(), "Checksum mismatch must abort receive");
        sender_task.await.unwrap();
        assert!(!part_path.exists(), "Corrupt .part file must be removed");
    }
}
