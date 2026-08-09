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
                        fatal: false,
                        details: None,
                    };
                    conn.send_control(session_id, 0, &err).await?;
                    continue;
                }

                // Write data to .part file.
                file.write_all(data)?;
                hasher.update(data);
                received_bytes += data.len() as u64;

                // Update resume state and fsync journal (PROTOCOL §13.3).
                if let Some(fs) = resume_state.files.get_mut(&file_id) {
                    fs.received_bytes = received_bytes;
                    fs.received_ranges = vec![(0, received_bytes)];
                }
                // fsync the data file.
                file.sync_all()?;
                // Save resume journal with fsync.
                resume_journal.save(resume_state)?;

                // Send CHUNK_ACK.
                let ack = ControlMessage::ChunkAck {
                    stream_id,
                    message_id: hdr.message_id,
                    offset,
                    length: data.len() as u64,
                };
                conn.send_control(session_id, 0, &ack).await?;

                // Check if this was the last chunk.
                if hdr.flags.is_last_chunk() {
                    info!("Received LAST_CHUNK at offset {offset}, total {received_bytes} bytes");
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
