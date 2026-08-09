//! File sender — reads file, streams chunks with in-flight window enforcement.
//!
//! PROTOCOL §9.2.13: sender MUST track unacknowledged chunks and pause at 8 in-flight.

use log::info;
use nxfr_core::codec;
use nxfr_core::frame::FrameKind;
use nxfr_core::messages::ControlMessage;
use nxfr_transport::connection::NxfrConnection;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::sync::mpsc;

use crate::ipc::IpcEvent;

/// Maximum chunks in flight before pausing.
const MAX_IN_FLIGHT: usize = 8;

/// Default chunk data size: 1 MiB.
const CHUNK_DATA_SIZE: usize = 1024 * 1024;

/// Send a file's data as CHUNK frames with in-flight window enforcement.
///
/// `file_data` is the complete file bytes.
/// `_file_hash` is the whole-file SHA-256 (used for verification by receiver).
/// `progress_tx` optionally receives progress events for IPC streaming.
/// `file_name` and `total_file_size` are for progress reporting only.
#[allow(clippy::too_many_arguments)]
pub async fn send_file<S: AsyncRead + AsyncWrite + Unpin>(
    conn: &mut NxfrConnection<S>,
    session_id: u32,
    stream_id: u32,
    file_data: &[u8],
    _file_hash: [u8; 32],
    progress_tx: Option<&mpsc::Sender<IpcEvent>>,
    file_name: &str,
    total_file_size: u64,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let total_size = file_data.len();
    let mut offset: u64 = 0;
    let mut in_flight: usize = 0;
    let mut acked_bytes: u64 = 0;

    info!(
        "Sending {} bytes on stream {stream_id}, chunk_size={CHUNK_DATA_SIZE}",
        total_size
    );

    while (offset as usize) < total_size {
        // Enforce in-flight window.
        while in_flight >= MAX_IN_FLIGHT {
            let (hdr, payload) = conn.recv_frame().await?;
            if hdr.kind == FrameKind::Control {
                let msg = codec::decode_control(&payload)?;
                if let ControlMessage::ChunkAck { length, .. } = msg {
                    acked_bytes += length;
                    in_flight -= 1;

                    // Push progress event.
                    if let Some(tx) = progress_tx {
                        let _ = tx.try_send(IpcEvent::Progress {
                            transfer_id: String::new(),
                            bytes_sent: acked_bytes,
                            total_bytes: total_file_size,
                            file_name: file_name.to_string(),
                            files_done: 0,
                            files_total: 1,
                        });
                    }
                }
            }
        }

        let end = std::cmp::min(offset as usize + CHUNK_DATA_SIZE, total_size);
        let chunk_data = &file_data[offset as usize..end];
        let is_last = end == total_size;

        // Compute per-chunk SHA-256.
        let chunk_hash: [u8; 32] = Sha256::digest(chunk_data).into();

        // Build chunk payload: 8 bytes offset + 32 bytes hash + data.
        let mut chunk_payload = Vec::with_capacity(8 + 32 + chunk_data.len());
        chunk_payload.extend_from_slice(&offset.to_be_bytes());
        chunk_payload.extend_from_slice(&chunk_hash);
        chunk_payload.extend_from_slice(chunk_data);

        let flags = if is_last { 0x0001 } else { 0x0000 };
        conn.send_chunk(session_id, stream_id, flags, chunk_payload)
            .await?;

        in_flight += 1;
        offset = end as u64;

        if (offset as usize) % (CHUNK_DATA_SIZE * 10) == 0 || is_last {
            info!(
                "Sent {offset}/{total_size} bytes ({:.1}%), in_flight={in_flight}",
                (offset as f64 / total_size as f64) * 100.0
            );
        }
    }

    // Drain remaining ACKs.
    while in_flight > 0 {
        let (hdr, payload) = conn.recv_frame().await?;
        if hdr.kind == FrameKind::Control {
            let msg = codec::decode_control(&payload)?;
            if let ControlMessage::ChunkAck { length, .. } = msg {
                acked_bytes += length;
                in_flight -= 1;

                // Push progress event.
                if let Some(tx) = progress_tx {
                    let _ = tx.try_send(IpcEvent::Progress {
                        transfer_id: String::new(),
                        bytes_sent: acked_bytes,
                        total_bytes: total_file_size,
                        file_name: file_name.to_string(),
                        files_done: if in_flight == 0 { 1 } else { 0 },
                        files_total: 1,
                    });
                }
            }
        }
    }

    info!("File send complete: {total_size} bytes, {acked_bytes} bytes acked");
    Ok(())
}

/// Send a file's data as CHUNK frames, skipping already-received ranges.
///
/// `skip_ranges` is a list of `(offset, length)` pairs already received by the peer.
/// Only chunks NOT covered by any skip range are sent.
#[allow(clippy::too_many_arguments)]
pub async fn send_file_resume<S: AsyncRead + AsyncWrite + Unpin>(
    conn: &mut NxfrConnection<S>,
    session_id: u32,
    stream_id: u32,
    file_data: &[u8],
    _file_hash: [u8; 32],
    skip_ranges: &[(u64, u64)],
    progress_tx: Option<&mpsc::Sender<IpcEvent>>,
    file_name: &str,
    total_file_size: u64,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let total_size = file_data.len();
    let mut offset: u64 = 0;
    let mut in_flight: usize = 0;
    let mut chunks_sent: u64 = 0;
    let mut chunks_skipped: u64 = 0;

    // Count pre-received bytes for progress tracking.
    let pre_received: u64 = skip_ranges.iter().map(|(_, len)| len).sum();
    let mut acked_bytes: u64 = pre_received;

    info!(
        "Resume send: {} bytes total, {} bytes pre-received, {} skip ranges",
        total_size,
        pre_received,
        skip_ranges.len()
    );

    while (offset as usize) < total_size {
        let end = std::cmp::min(offset as usize + CHUNK_DATA_SIZE, total_size);
        let chunk_len = (end - offset as usize) as u64;
        let is_last = end == total_size;

        // Check if this chunk overlaps any skip range.
        let should_skip = skip_ranges.iter().any(|&(skip_off, skip_len)| {
            offset >= skip_off && offset + chunk_len <= skip_off + skip_len
        });

        if should_skip {
            chunks_skipped += 1;
            offset = end as u64;

            // If this is the last chunk position, we still need to send the LAST_CHUNK flag.
            if is_last {
                // Send a minimal "end marker" chunk for the last position.
                // Actually, the data at this offset was already received. But we need to
                // signal end-of-file. Send the actual last chunk anyway.
                // The receiver will verify via whole-file hash, so duplicates are safe.
                let chunk_data = &file_data[(end - std::cmp::min(1, end))..end];
                if chunk_data.is_empty() {
                    break;
                }
                // Reset offset to send the last chunk.
                offset -= chunk_len;
            } else {
                continue;
            }
        }

        // Enforce in-flight window.
        while in_flight >= MAX_IN_FLIGHT {
            let (hdr, payload) = conn.recv_frame().await?;
            if hdr.kind == FrameKind::Control {
                let msg = codec::decode_control(&payload)?;
                if let ControlMessage::ChunkAck { length, .. } = msg {
                    acked_bytes += length;
                    in_flight -= 1;

                    if let Some(tx) = progress_tx {
                        let _ = tx.try_send(IpcEvent::Progress {
                            transfer_id: String::new(),
                            bytes_sent: acked_bytes,
                            total_bytes: total_file_size,
                            file_name: file_name.to_string(),
                            files_done: 0,
                            files_total: 1,
                        });
                    }
                }
            }
        }

        let chunk_data = &file_data[offset as usize..end];
        let chunk_hash: [u8; 32] = Sha256::digest(chunk_data).into();

        let mut chunk_payload = Vec::with_capacity(8 + 32 + chunk_data.len());
        chunk_payload.extend_from_slice(&offset.to_be_bytes());
        chunk_payload.extend_from_slice(&chunk_hash);
        chunk_payload.extend_from_slice(chunk_data);

        let flags = if is_last { 0x0001 } else { 0x0000 };
        conn.send_chunk(session_id, stream_id, flags, chunk_payload)
            .await?;

        in_flight += 1;
        chunks_sent += 1;
        offset = end as u64;
    }

    // Drain remaining ACKs.
    while in_flight > 0 {
        let (hdr, payload) = conn.recv_frame().await?;
        if hdr.kind == FrameKind::Control {
            let msg = codec::decode_control(&payload)?;
            if let ControlMessage::ChunkAck { length, .. } = msg {
                acked_bytes += length;
                in_flight -= 1;

                if let Some(tx) = progress_tx {
                    let _ = tx.try_send(IpcEvent::Progress {
                        transfer_id: String::new(),
                        bytes_sent: acked_bytes,
                        total_bytes: total_file_size,
                        file_name: file_name.to_string(),
                        files_done: if in_flight == 0 { 1 } else { 0 },
                        files_total: 1,
                    });
                }
            }
        }
    }

    info!(
        "Resume send complete: {chunks_sent} chunks sent, {chunks_skipped} skipped, {acked_bytes} bytes acked"
    );
    Ok(())
}
