//! Connection handler — drives the session and transfer state machines.
//!
//! Performs identity verification against the paired device DB,
//! handles consent logic, and coordinates file I/O.

use crate::ipc::{broadcast_to_watchers, IpcEvent};
use crate::receiver;
use crate::sender;
use crate::DaemonState;
use crate::PendingOffer;
use log::{error, info, warn};
use nxfr_common::{DeviceId, Platform, ProtocolVersion, TransferId};
use nxfr_core::codec;
use nxfr_core::frame::FrameKind;
use nxfr_core::messages::{ControlMessage, ManifestEntryType, TransferAckStatus};
use nxfr_core::path::sanitize_path;
use nxfr_crypto::device_id_from_cert;
use nxfr_storage::db::IdentityCheck;
use nxfr_storage::resume::{ResumeManifestEntry, ResumeState};
use nxfr_transport::connection::NxfrConnection;
use sha2::{Digest, Sha256};
use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Instant;
use tokio::io::{AsyncRead, AsyncWrite};

/// Handle an incoming TLS connection (responder side).
pub async fn handle_incoming(
    state: Arc<DaemonState>,
    tls_stream: tokio_rustls::server::TlsStream<tokio::net::TcpStream>,
    addr: SocketAddr,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Extract peer device_id from TLS certificate SPKI.
    let (_, server_conn) = tls_stream.get_ref();
    let peer_certs = server_conn
        .peer_certificates()
        .ok_or("no peer certificates")?;
    let peer_cert = peer_certs.first().ok_or("empty peer cert chain")?;
    let peer_device_id_bytes = device_id_from_cert(peer_cert.as_ref())
        .map_err(|e| format!("device_id extraction failed: {e}"))?;
    let _peer_device_id = DeviceId::from_bytes(peer_device_id_bytes);
    let peer_id_hex = hex::encode(peer_device_id_bytes);

    info!("Peer device_id: {peer_id_hex} from {addr}");

    // Check identity against paired DB (PROTOCOL §10.4).
    let identity_check = {
        let db = state.db.lock().await;
        db.verify_identity(&peer_id_hex, peer_cert.as_ref())
    };

    let is_paired = match identity_check {
        Ok(IdentityCheck::Matched) => {
            info!("Peer {peer_id_hex} is a known paired device");
            // Update last_seen.
            let db = state.db.lock().await;
            let _ = db.update_last_seen(&peer_id_hex);
            true
        }
        Ok(IdentityCheck::Changed) => {
            error!("IDENTITY CHANGED for paired device {peer_id_hex} — sending fatal error");
            let mut conn = NxfrConnection::new(tls_stream);
            let err_msg = ControlMessage::Error {
                code: nxfr_core::error_code::ErrorCode::from_wire_str("identity_changed")
                    .unwrap_or(nxfr_core::error_code::ErrorCode::InternalError),
                message: Some("SPKI does not match pinned identity".to_string()),
                fatal: true,
                details: None,
            };
            let _ = conn.send_control(0, 0, &err_msg).await;
            return Err("identity_changed: SPKI mismatch for known peer".into());
        }
        Ok(IdentityCheck::Unknown) => {
            info!("Peer {peer_id_hex} is not paired (unknown device)");
            false
        }
        Err(e) => {
            warn!("DB identity check failed: {e}");
            false
        }
    };

    let mut conn = NxfrConnection::new(tls_stream);

    // Session: receive HELLO.
    let (hdr, payload) = conn.recv_frame().await?;
    if hdr.kind != FrameKind::Control {
        return Err("expected CONTROL frame for HELLO".into());
    }
    let msg = codec::decode_control(&payload)?;
    let (peer_name, peer_platform) = match &msg {
        ControlMessage::Hello {
            device_name,
            platform,
            protocol_version,
            ..
        } => {
            if *protocol_version != ProtocolVersion::V0_1 {
                let err = ControlMessage::Error {
                    code: nxfr_core::error_code::ErrorCode::UnsupportedVersion,
                    message: Some("only v0.1 supported".to_string()),
                    fatal: true,
                    details: None,
                };
                conn.send_control(0, 0, &err).await?;
                return Err("unsupported version".into());
            }
            (device_name.clone(), platform.clone())
        }
        _ => return Err(format!("expected HELLO, got {:?}", msg).into()),
    };

    info!("HELLO from \"{peer_name}\" ({peer_platform:?})");

    // Generate session_id.
    let session_id: u32 = rand_session_id();

    // Send HELLO_ACK.
    let config = state.config.read().await;
    let hello_ack = ControlMessage::HelloAck {
        protocol_version: ProtocolVersion::V0_1,
        device_id: DeviceId::from_bytes(state.identity.device_id),
        device_name: config.device_name.clone(),
        platform: Platform::Linux,
        capabilities: vec![],
        is_paired,
        session_id,
    };
    drop(config);
    conn.send_control(session_id, 0, &hello_ack).await?;
    info!("Sent HELLO_ACK, session_id=0x{session_id:04x}");

    // Main message loop.
    loop {
        let (hdr, payload) = match conn.recv_frame().await {
            Ok(frame) => frame,
            Err(e) if e.kind() == std::io::ErrorKind::UnexpectedEof => {
                info!("Connection closed by peer");
                break;
            }
            Err(e) => return Err(e.into()),
        };

        match hdr.kind {
            FrameKind::Control => {
                let msg = codec::decode_control(&payload)?;
                match msg {
                    ControlMessage::TransferRequest {
                        transfer_id,
                        transfer_type,
                        display_name,
                        total_files,
                        total_size,
                        manifest,
                    } => {
                        info!(
                            "TRANSFER_REQUEST: \"{display_name}\" ({total_files} files, {total_size} bytes)"
                        );

                        // Phase 5 consent logic.
                        let auto_accept = if is_paired {
                            let db = state.db.lock().await;
                            db.should_auto_accept(&peer_id_hex)
                        } else {
                            false
                        };

                        let (accepted, rejection_reason): (bool, Option<String>) = if auto_accept {
                            (true, None)
                        } else {
                            // Disk-space pre-check: statvfs on receive_dir.
                            let config = state.config.read().await;
                            let rdir = config.receive_dir.clone();
                            drop(config);
                            let free_space = get_free_space(&rdir);
                            if free_space < total_size {
                                info!(
                                    "Rejecting: disk_full (need {total_size}, free {free_space})"
                                );
                                let reject = ControlMessage::TransferReject {
                                    transfer_id,
                                    reason: Some("disk_full".to_string()),
                                };
                                conn.send_control(session_id, 0, &reject).await?;
                                continue;
                            }

                            // Check if any watchers are connected.
                            let has_watchers = {
                                let watchers = state.watchers.lock().await;
                                !watchers.is_empty()
                            };

                            if !has_watchers {
                                info!("No watchers — rejecting (consent_required)");
                                let reject = ControlMessage::TransferReject {
                                    transfer_id,
                                    reason: Some("consent_required".to_string()),
                                };
                                conn.send_control(session_id, 0, &reject).await?;
                                continue;
                            }

                            // Push TransferOffer to watchers.
                            let tid_hex = hex::encode(transfer_id.as_bytes());
                            let offer_event = IpcEvent::TransferOffer {
                                transfer_id: tid_hex.clone(),
                                peer_name: peer_name.clone(),
                                peer_device_id: peer_id_hex.clone(),
                                display_name: display_name.clone(),
                                transfer_type: format!("{transfer_type:?}").to_lowercase(),
                                total_files,
                                total_size,
                            };

                            let (consent_tx, consent_rx) = tokio::sync::oneshot::channel::<bool>();
                            {
                                let mut offers = state.pending_offers.lock().await;
                                offers.insert(
                                    tid_hex.clone(),
                                    PendingOffer {
                                        offer_event: offer_event.clone(),
                                        respond_to: Some(consent_tx),
                                        expires_at: Instant::now()
                                            + std::time::Duration::from_secs(120),
                                    },
                                );
                            }
                            broadcast_to_watchers(&state, &offer_event).await;

                            // Wait for consent (120s timeout).
                            let consent_result = tokio::time::timeout(
                                std::time::Duration::from_secs(120),
                                consent_rx,
                            )
                            .await;

                            // Clean up pending offer.
                            state.pending_offers.lock().await.remove(&tid_hex);

                            match consent_result {
                                Ok(Ok(true)) => {
                                    broadcast_to_watchers(
                                        &state,
                                        &IpcEvent::TransferResolved {
                                            transfer_id: tid_hex,
                                            accepted: true,
                                            reason: None,
                                        },
                                    )
                                    .await;
                                    (true, None)
                                }
                                Ok(Ok(false)) => {
                                    broadcast_to_watchers(
                                        &state,
                                        &IpcEvent::TransferResolved {
                                            transfer_id: tid_hex,
                                            accepted: false,
                                            reason: Some("user_declined".to_string()),
                                        },
                                    )
                                    .await;
                                    (false, Some("user_declined".to_string()))
                                }
                                _ => {
                                    broadcast_to_watchers(
                                        &state,
                                        &IpcEvent::TransferResolved {
                                            transfer_id: tid_hex,
                                            accepted: false,
                                            reason: Some("consent_timeout".to_string()),
                                        },
                                    )
                                    .await;
                                    (false, Some("consent_timeout".to_string()))
                                }
                            }
                        };

                        if !accepted {
                            info!("Rejecting transfer: {:?}", rejection_reason);
                            let reject = ControlMessage::TransferReject {
                                transfer_id,
                                reason: rejection_reason,
                            };
                            conn.send_control(session_id, 0, &reject).await?;
                            continue;
                        }

                        // Accept transfer.
                        let accept = ControlMessage::TransferAccept { transfer_id };
                        conn.send_control(session_id, 0, &accept).await?;
                        info!("Accepted transfer {}", hex::encode(transfer_id.as_bytes()));

                        // Receive files.
                        let config = state.config.read().await;
                        let receive_dir = config.receive_dir.clone();
                        drop(config);

                        handle_incoming_transfer(
                            &mut conn,
                            &state,
                            session_id,
                            transfer_id,
                            &peer_id_hex,
                            &display_name,
                            &manifest,
                            &receive_dir,
                        )
                        .await?;
                    }
                    ControlMessage::ResumeQuery {
                        transfer_id,
                        file_ids,
                    } => {
                        info!("RESUME_QUERY for {}", hex::encode(transfer_id.as_bytes()));
                        let tid_hex = hex::encode(transfer_id.as_bytes());

                        // Load resume state.
                        let resume_status = match state.resume.load(&tid_hex) {
                            Ok(Some(rs)) => {
                                // Fix B: verify peer device_id matches journal.
                                if rs.peer_device_id != peer_id_hex {
                                    warn!("RESUME_QUERY peer mismatch: journal has {}, connecting peer is {peer_id_hex}", rs.peer_device_id);
                                    ControlMessage::ResumeStatus {
                                        transfer_id,
                                        resumable: false,
                                        files: None,
                                        expiry: None,
                                    }
                                } else if rs.expires_at < chrono::Utc::now().timestamp() {
                                    info!("Resume state expired");
                                    ControlMessage::ResumeStatus {
                                        transfer_id,
                                        resumable: false,
                                        files: None,
                                        expiry: None,
                                    }
                                } else {
                                    // Filter by file_ids if specified.
                                    let files: Vec<nxfr_core::messages::ResumeFileStatus> = rs
                                        .files
                                        .iter()
                                        .filter(|(fid, _)| {
                                            file_ids.as_ref().map_or(true, |ids| ids.contains(fid))
                                        })
                                        .map(|(fid, fs)| nxfr_core::messages::ResumeFileStatus {
                                            file_id: *fid,
                                            received_bytes: fs.received_bytes,
                                            received_ranges: fs.received_ranges.clone(),
                                            partial_sha256: None,
                                        })
                                        .collect();
                                    ControlMessage::ResumeStatus {
                                        transfer_id,
                                        resumable: true,
                                        files: Some(files),
                                        expiry: Some(rs.expires_at as u64),
                                    }
                                }
                            }
                            _ => {
                                info!("No resume state found");
                                ControlMessage::ResumeStatus {
                                    transfer_id,
                                    resumable: false,
                                    files: None,
                                    expiry: None,
                                }
                            }
                        };
                        let is_resumable = matches!(
                            &resume_status,
                            ControlMessage::ResumeStatus {
                                resumable: true,
                                ..
                            }
                        );
                        conn.send_control(session_id, 0, &resume_status).await?;

                        // If resumable, enter file-receiving state (§13.2 flow).
                        if is_resumable {
                            let config = state.config.read().await;
                            let receive_dir = config.receive_dir.clone();
                            drop(config);

                            // Reconstruct manifest from resume state for handle_incoming_transfer.
                            let rs = state.resume.load(&tid_hex).ok().flatten();
                            if let Some(rs) = rs {
                                let manifest: Vec<nxfr_core::messages::ManifestEntry> = rs
                                    .manifest
                                    .iter()
                                    .map(|m| nxfr_core::messages::ManifestEntry {
                                        file_id: m.file_id,
                                        relative_path: m.relative_path.clone(),
                                        size: Some(m.size),
                                        sha256: hex::decode(&m.sha256).ok().and_then(|b| {
                                            if b.len() == 32 {
                                                let mut arr = [0u8; 32];
                                                arr.copy_from_slice(&b);
                                                Some(arr)
                                            } else {
                                                None
                                            }
                                        }),
                                        entry_type: ManifestEntryType::File,
                                    })
                                    .collect();

                                handle_incoming_transfer(
                                    &mut conn,
                                    &state,
                                    session_id,
                                    transfer_id,
                                    &peer_id_hex,
                                    &rs.display_name,
                                    &manifest,
                                    &receive_dir,
                                )
                                .await?;
                            }
                        }
                    }
                    ControlMessage::SessionClose { reason } => {
                        info!("SESSION_CLOSE from peer: {reason:?}");
                        let close = ControlMessage::SessionClose {
                            reason: Some("normal".to_string()),
                        };
                        conn.send_control(session_id, 0, &close).await?;
                        break;
                    }
                    other => {
                        warn!("Unexpected message in session loop: {}", other.type_name());
                    }
                }
            }
            FrameKind::Keepalive => {
                // Respond with pong if not already a pong.
                if !hdr.flags.is_pong() {
                    use nxfr_core::frame::FrameFlags;
                    let pong_hdr = nxfr_core::frame::FrameHeader {
                        kind: FrameKind::Keepalive,
                        flags: FrameFlags::pong(),
                        session_id,
                        stream_id: 0,
                        message_id: 0,
                        payload_len: payload.len() as u32,
                    };
                    conn.send_raw((pong_hdr, payload)).await?;
                }
            }
            FrameKind::Chunk => {
                warn!("Unexpected CHUNK frame outside transfer context");
            }
        }
    }

    Ok(())
}

/// Handle an accepted incoming transfer: receive FILE_METADATA + CHUNKs.
#[allow(clippy::too_many_arguments)]
async fn handle_incoming_transfer<S: AsyncRead + AsyncWrite + Unpin>(
    conn: &mut NxfrConnection<S>,
    state: &DaemonState,
    session_id: u32,
    transfer_id: TransferId,
    peer_id_hex: &str,
    display_name: &str,
    manifest: &[nxfr_core::messages::ManifestEntry],
    receive_dir: &std::path::Path,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut received_files = 0u32;
    let total_files = manifest
        .iter()
        .filter(|e| matches!(e.entry_type, ManifestEntryType::File))
        .count();

    // Create directories for ManifestEntryType::Dir entries immediately.
    for entry in manifest {
        if matches!(entry.entry_type, ManifestEntryType::Dir) {
            let sanitized = match sanitize_path(&entry.relative_path) {
                Ok(p) => p,
                Err(e) => {
                    warn!("Dir path rejected: \"{}\" — {e}", entry.relative_path);
                    continue;
                }
            };
            let dir_path = receive_dir.join(&sanitized);
            if let Err(e) = std::fs::create_dir_all(&dir_path) {
                warn!("Failed to create directory {}: {e}", dir_path.display());
            } else {
                info!("Created directory: {}", dir_path.display());
            }
        }
    }

    // Load existing resume state if available (for resumed transfers),
    // otherwise create fresh.
    let tid_hex = hex::encode(transfer_id.as_bytes());
    let mut resume_state = match state.resume.load(&tid_hex) {
        Ok(Some(existing)) if !existing.files.is_empty() => {
            info!(
                "Loaded existing resume state for {tid_hex} with {} file entries",
                existing.files.len()
            );
            existing
        }
        _ => {
            nxfr_storage::resume::ResumeState {
                transfer_id: tid_hex.clone(),
                peer_device_id: peer_id_hex.to_string(),
                display_name: display_name.to_string(),
                manifest: manifest
                    .iter()
                    .map(|e| nxfr_storage::resume::ResumeManifestEntry {
                        file_id: e.file_id,
                        relative_path: e.relative_path.clone(),
                        size: e.size.unwrap_or(0),
                        sha256: e.sha256.map(hex::encode).unwrap_or_default(),
                    })
                    .collect(),
                files: std::collections::HashMap::new(),
                created_at: chrono::Utc::now().timestamp(),
                expires_at: chrono::Utc::now().timestamp() + 86400, // 24h
                version: 1,
            }
        }
    };

    loop {
        let (hdr, payload) = conn.recv_frame().await?;

        match hdr.kind {
            FrameKind::Control => {
                let msg = codec::decode_control(&payload)?;
                match msg {
                    ControlMessage::FileMetadata {
                        transfer_id: _,
                        file_id,
                        stream_id,
                        relative_path,
                        size,
                        sha256,
                        ..
                    } => {
                        info!("FILE_METADATA: file_id={file_id} path=\"{relative_path}\"");

                        // CRITICAL: sanitize path (PROTOCOL §18, SECURITY §6).
                        let sanitized = match sanitize_path(&relative_path) {
                            Ok(p) => p,
                            Err(e) => {
                                error!("Path rejected: \"{relative_path}\" — {e}");
                                let err = ControlMessage::Error {
                                    code: nxfr_core::error_code::ErrorCode::PathRejected,
                                    message: Some(format!("path rejected: {e}")),
                                    fatal: false,
                                    details: None,
                                };
                                conn.send_control(session_id, 0, &err).await?;
                                // Send FILE_METADATA_ACK accepted=false.
                                let nack = ControlMessage::FileMetadataAck {
                                    transfer_id,
                                    file_id,
                                    stream_id,
                                    accepted: false,
                                };
                                conn.send_control(session_id, 0, &nack).await?;
                                continue;
                            }
                        };

                        // §13.6: If resume state has partial progress, verify SHA matches.
                        let has_resume_partial = resume_state
                            .files
                            .get(&file_id)
                            .is_some_and(|fs| fs.received_bytes > 0);
                        if has_resume_partial {
                            let manifest_sha = resume_state
                                .manifest
                                .iter()
                                .find(|m| m.file_id == file_id)
                                .map(|m| m.sha256.clone())
                                .unwrap_or_default();
                            let incoming_sha = hex::encode(sha256);
                            if !manifest_sha.is_empty() && manifest_sha != incoming_sha {
                                info!("§13.6: file modified (SHA mismatch), rejecting resume for file_id={file_id}");
                                // Clean up stale .part file.
                                let dest = receive_dir.join(&sanitized);
                                let part_path = dest.with_extension(
                                    dest.extension()
                                        .map(|e| format!("{}.part", e.to_string_lossy()))
                                        .unwrap_or_else(|| "part".to_string()),
                                );
                                let _ = std::fs::remove_file(&part_path);
                                // Clear resume state for this file.
                                resume_state.files.remove(&file_id);
                                let _ = state.resume.save(&resume_state);

                                let nack = ControlMessage::FileMetadataAck {
                                    transfer_id,
                                    file_id,
                                    stream_id,
                                    accepted: false,
                                };
                                conn.send_control(session_id, 0, &nack).await?;
                                continue;
                            }
                        }

                        // Send FILE_METADATA_ACK accepted=true.
                        let ack = ControlMessage::FileMetadataAck {
                            transfer_id,
                            file_id,
                            stream_id,
                            accepted: true,
                        };
                        conn.send_control(session_id, 0, &ack).await?;

                        // Prepare destination path.
                        let dest = receive_dir.join(&sanitized);
                        if let Some(parent) = dest.parent() {
                            std::fs::create_dir_all(parent)?;
                        }
                        let part_path = dest.with_extension(
                            dest.extension()
                                .map(|e| format!("{}.part", e.to_string_lossy()))
                                .unwrap_or_else(|| "part".to_string()),
                        );

                        // Initialize resume file state (only if no existing partial progress).
                        if !resume_state
                            .files
                            .get(&file_id)
                            .is_some_and(|fs| fs.received_bytes > 0)
                        {
                            resume_state.files.insert(
                                file_id,
                                nxfr_storage::resume::ResumeFileState {
                                    received_bytes: 0,
                                    received_ranges: vec![],
                                    partial_sha256: None,
                                    dest_path: part_path.to_string_lossy().to_string(),
                                },
                            );
                        }
                        state.resume.save(&resume_state)?;

                        // Receive chunks for this file.
                        receiver::receive_file(
                            conn,
                            session_id,
                            file_id,
                            stream_id,
                            size,
                            sha256,
                            &part_path,
                            &dest,
                            &state.resume,
                            &mut resume_state,
                        )
                        .await?;

                        received_files += 1;
                        info!(
                            "File {received_files}/{total_files} complete: {}",
                            sanitized
                        );
                    }
                    ControlMessage::TransferComplete { .. } => {
                        info!("TRANSFER_COMPLETE from sender");
                        // Send TRANSFER_ACK.
                        let ack = ControlMessage::TransferAck {
                            transfer_id,
                            status: TransferAckStatus::Success,
                            failed_files: None,
                        };
                        conn.send_control(session_id, 0, &ack).await?;

                        // Clean up resume journal.
                        let _ = state.resume.delete(&hex::encode(transfer_id.as_bytes()));

                        info!("Transfer complete: {received_files}/{total_files} files");
                        return Ok(());
                    }
                    ControlMessage::TransferCancel { reason, .. } => {
                        warn!("Transfer cancelled by peer: {reason:?}");
                        return Ok(());
                    }
                    ControlMessage::SessionClose { reason } => {
                        info!("SESSION_CLOSE during transfer: {reason:?}");
                        let close = ControlMessage::SessionClose {
                            reason: Some("normal".to_string()),
                        };
                        conn.send_control(session_id, 0, &close).await?;
                        return Ok(());
                    }
                    other => {
                        warn!("Unexpected msg during transfer: {}", other.type_name());
                    }
                }
            }
            _ => {
                warn!(
                    "Unexpected frame kind {:?} during transfer negotiation",
                    hdr.kind
                );
            }
        }
    }
}

/// Type alias for client TLS stream.
pub type TlsClientStream = tokio_rustls::client::TlsStream<tokio::net::TcpStream>;

/// Connect to a peer: TCP → TLS → verify device_id → HELLO/HELLO_ACK.
///
/// Returns (connection, session_id, peer_name, tls_exporter_bytes).
/// The exporter bytes use label `NXFR-SAS-v0` and context = sorted device_ids
/// per PROTOCOL §9.2.3. Caller MUST zeroize exporter_bytes after use.
pub async fn connect_to_peer(
    state: &DaemonState,
    target_addr: &str,
    target_device_id: &str,
) -> Result<
    (NxfrConnection<TlsClientStream>, u32, String, [u8; 4]),
    Box<dyn std::error::Error + Send + Sync>,
> {
    use nxfr_transport::tls;
    use rustls::pki_types::ServerName;
    use tokio::net::TcpStream;
    use tokio_rustls::TlsConnector;

    // Build TLS client config.
    let client_config =
        tls::build_client_config(state.identity.private_key(), state.identity.certificate())?;
    let connector = TlsConnector::from(Arc::new(client_config));

    // Connect.
    let tcp_stream = TcpStream::connect(target_addr).await?;
    let server_name = ServerName::try_from("nxfr-node").unwrap();
    let tls_stream = connector.connect(server_name, tcp_stream).await?;

    // Verify peer identity.
    let (_, client_conn) = tls_stream.get_ref();
    let peer_certs = client_conn.peer_certificates().ok_or("no peer certs")?;
    let peer_cert = peer_certs.first().ok_or("empty peer cert chain")?;
    let peer_id = device_id_from_cert(peer_cert.as_ref())?;
    let peer_id_hex = hex::encode(peer_id);

    if peer_id_hex != target_device_id {
        return Err(
            format!("connected device's identity does not match the requested target (expected {target_device_id}, got {peer_id_hex}; wrong address or possible MITM)").into(),
        );
    }

    // Extract TLS exporter bytes per PROTOCOL §9.2.3:
    //   sas_bytes = TLS-Exporter("NXFR-SAS-v0", context, 4)
    //   context = sort(device_id_a, device_id_b) — 64 bytes
    let (_, sas_context) = nxfr_core::sas::derive_sas(
        &state.identity.device_id,
        &peer_id,
        &[0u8; 4], // dummy exporter, we just need the context
    );
    let mut exporter_bytes = [0u8; 4];
    client_conn
        .export_keying_material(&mut exporter_bytes, b"NXFR-SAS-v0", Some(&sas_context))
        .map_err(|e| format!("TLS exporter failed: {e}"))?;

    let mut conn = NxfrConnection::new(tls_stream);

    // Send HELLO.
    let config = state.config.read().await;
    let hello = ControlMessage::Hello {
        protocol_version: ProtocolVersion::V0_1,
        device_id: DeviceId::from_bytes(state.identity.device_id),
        device_name: config.device_name.clone(),
        platform: Platform::Linux,
        capabilities: vec![],
        is_paired: {
            let db = state.db.lock().await;
            db.is_paired(target_device_id)
        },
    };
    drop(config);
    conn.send_control(0, 0, &hello).await?;

    // Receive HELLO_ACK.
    let (_hdr, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    let (session_id, peer_name) = match &msg {
        ControlMessage::HelloAck {
            session_id,
            device_name,
            ..
        } => (*session_id, device_name.clone()),
        _ => return Err(format!("expected HELLO_ACK, got {msg:?}").into()),
    };

    info!("Session established with \"{peer_name}\", session_id=0x{session_id:04x}");

    Ok((conn, session_id, peer_name, exporter_bytes))
}

/// Handle an outbound send (initiator side).
pub async fn handle_outbound_send(
    state: Arc<DaemonState>,
    target_addr: &str,
    target_device_id: &str,
    file_path: &std::path::Path,
    progress_tx: Option<tokio::sync::mpsc::Sender<crate::ipc::IpcEvent>>,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    use nxfr_core::messages::{ManifestEntry, ManifestEntryType, TransferType};
    use zeroize::Zeroize;

    let (mut conn, session_id, _peer_name, mut exporter_bytes) =
        connect_to_peer(&state, target_addr, target_device_id).await?;

    // We don't need exporter bytes for send — zeroize immediately.
    exporter_bytes.zeroize();

    // Compute file metadata.
    let file_meta = std::fs::metadata(file_path)?;
    let file_size = file_meta.len();
    let file_name = file_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());

    // Compute file hash.
    let file_data = std::fs::read(file_path)?;
    let file_hash: [u8; 32] = Sha256::digest(&file_data).into();

    // Generate transfer_id.
    let mut tid_bytes = [0u8; 16];
    let tid_hash = Sha256::digest(format!(
        "{}{}",
        hex::encode(file_hash),
        chrono::Utc::now().timestamp_nanos_opt().unwrap_or(0)
    ));
    tid_bytes.copy_from_slice(&tid_hash[..16]);
    let transfer_id = TransferId::from_bytes(tid_bytes);

    // Send TRANSFER_REQUEST.
    let transfer_req = ControlMessage::TransferRequest {
        transfer_id,
        transfer_type: TransferType::Files,
        display_name: file_name.clone(),
        total_files: 1,
        total_size: file_size,
        manifest: vec![ManifestEntry {
            file_id: 1,
            relative_path: file_name.clone(),
            size: Some(file_size),
            sha256: Some(file_hash),
            entry_type: ManifestEntryType::File,
        }],
    };
    conn.send_control(session_id, 0, &transfer_req).await?;

    // Receive TRANSFER_ACCEPT or TRANSFER_REJECT.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::TransferAccept { .. } => {
            info!("Transfer accepted by peer");

            // Fix A: persist outbound journal on TRANSFER_ACCEPT.
            let outbound_journal = ResumeState {
                transfer_id: hex::encode(transfer_id.as_bytes()),
                peer_device_id: target_device_id.to_string(),
                display_name: file_name.clone(),
                manifest: vec![ResumeManifestEntry {
                    file_id: 1,
                    relative_path: file_name.clone(),
                    size: file_size,
                    sha256: hex::encode(file_hash),
                }],
                files: std::collections::HashMap::new(),
                created_at: chrono::Utc::now().timestamp(),
                expires_at: chrono::Utc::now().timestamp() + 86400,
                version: 1,
            };
            let outbound_tid = format!("outbound-{}", hex::encode(transfer_id.as_bytes()));
            // Use a separate journal path for outbound.
            let outbound_state_with_id = ResumeState {
                transfer_id: outbound_tid.clone(),
                ..outbound_journal
            };
            let _ = state.resume.save(&outbound_state_with_id);
        }
        ControlMessage::TransferReject { reason, .. } => {
            let reason_str = reason.as_deref().unwrap_or("unknown");
            info!("Transfer rejected: {reason_str}");
            let close = ControlMessage::SessionClose {
                reason: Some("transfer_rejected".to_string()),
            };
            conn.send_control(session_id, 0, &close).await?;
            return Err(format!("transfer rejected: {reason_str}").into());
        }
        _ => return Err(format!("unexpected response to TRANSFER_REQUEST: {msg:?}").into()),
    }

    // Send FILE_METADATA.
    let file_meta_msg = ControlMessage::FileMetadata {
        transfer_id,
        file_id: 1,
        stream_id: 1,
        relative_path: file_name.clone(),
        size: file_size,
        sha256: file_hash,
        mime_type: None,
        modified_time: None,
    };
    conn.send_control(session_id, 0, &file_meta_msg).await?;

    // Receive FILE_METADATA_ACK.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::FileMetadataAck { accepted, .. } => {
            if !*accepted {
                return Err("FILE_METADATA_ACK accepted=false".into());
            }
        }
        _ => return Err("expected FILE_METADATA_ACK".into()),
    }

    // Send file chunks with progress.
    sender::send_file(
        &mut conn,
        session_id,
        1,
        &file_data,
        file_hash,
        progress_tx.as_ref(),
        &file_name,
        file_size,
    )
    .await?;

    // Send TRANSFER_COMPLETE.
    let complete = ControlMessage::TransferComplete { transfer_id };
    conn.send_control(session_id, 0, &complete).await?;

    // Receive TRANSFER_ACK.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::TransferAck { status, .. } => {
            info!("Transfer acknowledged: {status:?}");
        }
        _ => warn!("expected TRANSFER_ACK, got {}", msg.type_name()),
    }

    // Clean up outbound journal on success.
    let outbound_tid = format!("outbound-{}", hex::encode(transfer_id.as_bytes()));
    let _ = state.resume.delete(&outbound_tid);

    // Send SESSION_CLOSE.
    let close = ControlMessage::SessionClose {
        reason: Some("normal".to_string()),
    };
    conn.send_control(session_id, 0, &close).await?;

    // Receive SESSION_CLOSE.
    let _ = conn.recv_frame().await;

    Ok(hex::encode(transfer_id.as_bytes()))
}

/// Handle an outbound resume (§13.2 flow).
pub async fn handle_outbound_resume(
    state: Arc<DaemonState>,
    target_addr: &str,
    target_device_id: &str,
    file_path: &std::path::Path,
    retry_transfer_id: &str,
    progress_tx: Option<tokio::sync::mpsc::Sender<IpcEvent>>,
) -> Result<String, Box<dyn std::error::Error + Send + Sync>> {
    use zeroize::Zeroize;

    let (mut conn, session_id, _peer_name, mut exporter_bytes) =
        connect_to_peer(&state, target_addr, target_device_id).await?;
    exporter_bytes.zeroize();

    // Parse previous transfer_id.
    let tid_bytes: [u8; 16] = {
        let decoded = hex::decode(retry_transfer_id).map_err(|_| "invalid transfer_id hex")?;
        if decoded.len() != 16 {
            return Err("transfer_id must be 16 bytes".into());
        }
        let mut b = [0u8; 16];
        b.copy_from_slice(&decoded);
        b
    };
    let transfer_id = TransferId::from_bytes(tid_bytes);

    // Send RESUME_QUERY.
    let rq = ControlMessage::ResumeQuery {
        transfer_id,
        file_ids: None,
    };
    conn.send_control(session_id, 0, &rq).await?;

    // Receive RESUME_STATUS.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;

    let skip_ranges: Vec<(u64, u64)> = match &msg {
        ControlMessage::ResumeStatus {
            resumable: true,
            files: Some(files),
            ..
        } => {
            info!(
                "Resume available: {} file(s) with partial state",
                files.len()
            );
            files
                .first()
                .map(|f| f.received_ranges.clone())
                .unwrap_or_default()
        }
        ControlMessage::ResumeStatus {
            resumable: false, ..
        } => {
            info!("Not resumable — falling back to fresh transfer");
            // Fall back: fresh transfer with new transfer_id.
            return handle_outbound_send(
                state,
                target_addr,
                target_device_id,
                file_path,
                progress_tx,
            )
            .await;
        }
        _ => return Err(format!("expected RESUME_STATUS, got {msg:?}").into()),
    };

    // Compute file metadata.
    let file_data = std::fs::read(file_path)?;
    let file_size = file_data.len() as u64;
    let file_hash: [u8; 32] = Sha256::digest(&file_data).into();
    let file_name = file_path
        .file_name()
        .map(|n| n.to_string_lossy().to_string())
        .unwrap_or_else(|| "file".to_string());

    // Send FILE_METADATA.
    let file_meta_msg = ControlMessage::FileMetadata {
        transfer_id,
        file_id: 1,
        stream_id: 1,
        relative_path: file_name.clone(),
        size: file_size,
        sha256: file_hash,
        mime_type: None,
        modified_time: None,
    };
    conn.send_control(session_id, 0, &file_meta_msg).await?;

    // Receive FILE_METADATA_ACK.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::FileMetadataAck {
            accepted: false, ..
        } => {
            info!("FILE_METADATA_ACK rejected (§13.6: file modified) — fresh transfer");
            // Fall back to fresh transfer.
            let close = ControlMessage::SessionClose {
                reason: Some("resume_metadata_rejected".to_string()),
            };
            conn.send_control(session_id, 0, &close).await?;
            return handle_outbound_send(
                state,
                target_addr,
                target_device_id,
                file_path,
                progress_tx,
            )
            .await;
        }
        ControlMessage::FileMetadataAck { accepted: true, .. } => {
            info!(
                "FILE_METADATA_ACK accepted — resuming with {} skip ranges",
                skip_ranges.len()
            );
        }
        _ => return Err("expected FILE_METADATA_ACK".into()),
    }

    // Send file chunks, skipping received ranges.
    sender::send_file_resume(
        &mut conn,
        session_id,
        1,
        &file_data,
        file_hash,
        &skip_ranges,
        progress_tx.as_ref(),
        &file_name,
        file_size,
    )
    .await?;

    // Send TRANSFER_COMPLETE.
    let complete = ControlMessage::TransferComplete { transfer_id };
    conn.send_control(session_id, 0, &complete).await?;

    // Receive TRANSFER_ACK.
    let (_, payload) = conn.recv_frame().await?;
    let msg = codec::decode_control(&payload)?;
    match &msg {
        ControlMessage::TransferAck { status, .. } => {
            info!("Resume transfer acknowledged: {status:?}");
        }
        _ => warn!("expected TRANSFER_ACK, got {}", msg.type_name()),
    }

    // Clean up outbound journal.
    let outbound_tid = format!("outbound-{}", hex::encode(transfer_id.as_bytes()));
    let _ = state.resume.delete(&outbound_tid);

    // Close session.
    let close = ControlMessage::SessionClose {
        reason: Some("normal".to_string()),
    };
    conn.send_control(session_id, 0, &close).await?;
    let _ = conn.recv_frame().await;

    Ok(hex::encode(transfer_id.as_bytes()))
}

/// Get free disk space on the filesystem containing `path`.
fn get_free_space(path: &std::path::Path) -> u64 {
    use nix::sys::statvfs::statvfs;
    match statvfs(path) {
        Ok(stat) => stat.blocks_available() * stat.fragment_size(),
        Err(e) => {
            warn!(
                "statvfs failed for {}: {e} — assuming unlimited",
                path.display()
            );
            u64::MAX
        }
    }
}

/// Generate a random session ID.
fn rand_session_id() -> u32 {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default();
    let hash = Sha256::digest(now.as_nanos().to_le_bytes());
    u32::from_be_bytes([hash[0], hash[1], hash[2], hash[3]])
}
