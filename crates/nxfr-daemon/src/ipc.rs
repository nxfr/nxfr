//! IPC server — Unix Domain Socket with line-delimited JSON commands.
//!
//! Socket: `~/.local/state/nxfr/nxfr.sock`
//!
//! Phase 5: Supports watchers, interactive consent, and resume retry.

use crate::DaemonState;
use log::{error, info, warn};
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Arc;
use std::time::Instant;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::net::unix::OwnedWriteHalf;
use tokio::net::UnixListener;
use tokio::sync::mpsc;
use zeroize::Zeroize;

/// IPC socket path.
pub fn socket_path() -> PathBuf {
    dirs::state_dir()
        .or_else(dirs::runtime_dir)
        .unwrap_or_else(|| PathBuf::from("/tmp"))
        .join("nxfr")
        .join("nxfr.sock")
}

/// Fix C: Check if another daemon instance is running by pinging the IPC socket.
pub async fn check_existing_instance() -> bool {
    let sock_path = socket_path();
    if !sock_path.exists() {
        return false;
    }
    // Try connecting and sending a status ping.
    match tokio::net::UnixStream::connect(&sock_path).await {
        Ok(stream) => {
            let (read_half, mut write_half) = stream.into_split();
            let ping = b"{\"cmd\":\"status\"}\n";
            if tokio::io::AsyncWriteExt::write_all(&mut write_half, ping)
                .await
                .is_err()
            {
                return false;
            }
            let _ = tokio::io::AsyncWriteExt::flush(&mut write_half).await;
            // Wait briefly for a response.
            let mut reader = BufReader::new(read_half);
            let mut line = String::new();
            match tokio::time::timeout(
                std::time::Duration::from_secs(2),
                reader.read_line(&mut line),
            )
            .await
            {
                Ok(Ok(n)) if n > 0 => true, // Got a response — daemon is alive.
                _ => false,
            }
        }
        Err(_) => false, // Socket exists but can't connect — stale.
    }
}

/// Active transfer status for status responses.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TransferStatus {
    pub transfer_id: String,
    pub direction: String,
    pub display_name: String,
    pub progress_bytes: u64,
    pub total_bytes: u64,
    pub peer_device_id: String,
}

/// Discovered peer info for devices response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DiscoveredDevice {
    pub name: String,
    pub device_id_hint: String,
    pub addresses: Vec<String>,
    pub port: u16,
}

/// Paired device info for devices response.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairedDeviceInfo {
    pub device_id: String,
    pub name: String,
    pub trust_level: String,
    pub auto_accept: String,
}

/// IPC event — discriminated JSON union sent from daemon to client.
#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(tag = "type")]
pub enum IpcEvent {
    /// Response to a command.
    #[serde(rename = "response")]
    Response {
        ok: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        error: Option<String>,
        #[serde(flatten)]
        data: serde_json::Value,
    },
    /// Transfer progress update (outbound send).
    #[serde(rename = "progress")]
    Progress {
        transfer_id: String,
        bytes_sent: u64,
        total_bytes: u64,
        file_name: String,
        files_done: u32,
        files_total: u32,
    },
    /// Receive progress update (inbound, pushed to watchers).
    #[serde(rename = "receive_progress")]
    ReceiveProgress {
        transfer_id: String,
        bytes_received: u64,
        total_bytes: u64,
        file_name: String,
        files_done: u32,
        files_total: u32,
    },
    /// Incoming transfer offer requiring consent.
    #[serde(rename = "transfer_offer")]
    TransferOffer {
        transfer_id: String,
        peer_name: String,
        peer_device_id: String,
        display_name: String,
        transfer_type: String,
        total_files: u32,
        total_size: u64,
    },
    /// Offer resolved (accepted/rejected).
    #[serde(rename = "transfer_resolved")]
    TransferResolved {
        transfer_id: String,
        accepted: bool,
        #[serde(skip_serializing_if = "Option::is_none")]
        reason: Option<String>,
    },
    /// SAS prompt for pairing.
    #[serde(rename = "sas_prompt")]
    SasPrompt {
        peer_name: String,
        peer_device_id: String,
        sas_code: String,
    },
    /// Pairing succeeded.
    #[serde(rename = "pair_success")]
    PairSuccess {
        device_id: String,
        device_name: String,
    },
    /// Pairing failed.
    #[serde(rename = "pair_failed")]
    PairFailed { reason: String },
    /// Transfer completed.
    #[serde(rename = "transfer_complete")]
    TransferComplete {
        transfer_id: String,
        files_received: u32,
        total_bytes: u64,
    },
    /// Error during streaming operation.
    #[serde(rename = "error")]
    Error { code: String, message: String },
}

impl IpcEvent {
    pub fn ok_response(data: serde_json::Value) -> Self {
        IpcEvent::Response {
            ok: true,
            error: None,
            data,
        }
    }

    pub fn err_response(msg: &str) -> Self {
        IpcEvent::Response {
            ok: false,
            error: Some(msg.to_string()),
            data: serde_json::Value::Object(serde_json::Map::new()),
        }
    }
}

/// Broadcast an event to all registered watchers, pruning dead channels.
pub async fn broadcast_to_watchers(state: &DaemonState, event: &IpcEvent) {
    let mut watchers = state.watchers.lock().await;
    watchers.retain(|tx| tx.try_send(event.clone()).is_ok());
}

/// IPC request.
#[derive(Debug, Deserialize)]
struct IpcRequest {
    cmd: String,
    // send command fields
    path: Option<String>,
    target_device_id: Option<String>,
    target_addr: Option<String>,
    // send --retry
    retry_transfer_id: Option<String>,
    // set_receiving fields
    enabled: Option<bool>,
    // pair command fields
    device_id: Option<String>,
    // transfer_confirm / pair_confirm fields
    transfer_id: Option<String>,
    accepted: Option<bool>,
}

/// Write a single IPC event as a JSON line.
async fn write_event(
    writer: &mut OwnedWriteHalf,
    event: &IpcEvent,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut json = serde_json::to_string(event)?;
    json.push('\n');
    writer.write_all(json.as_bytes()).await?;
    writer.flush().await?;
    Ok(())
}

pub async fn run_ipc_server(state: Arc<DaemonState>) -> Result<(), Box<dyn std::error::Error>> {
    let sock_path = socket_path();
    if let Some(parent) = sock_path.parent() {
        std::fs::create_dir_all(parent)?;
    }
    let _ = std::fs::remove_file(&sock_path);

    let listener = UnixListener::bind(&sock_path)?;
    info!("IPC listening on {}", sock_path.display());

    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((stream, _)) => {
                        let state = Arc::clone(&state);
                        tokio::spawn(async move {
                            if let Err(e) = handle_ipc_client(state, stream).await {
                                warn!("IPC client error: {e}");
                            }
                        });
                    }
                    Err(e) => {
                        error!("IPC accept error: {e}");
                    }
                }
            }
            _ = state.shutdown.notified() => {
                info!("IPC server shutting down");
                break;
            }
        }
    }

    Ok(())
}

async fn handle_ipc_client(
    state: Arc<DaemonState>,
    stream: tokio::net::UnixStream,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let (reader, mut writer) = stream.into_split();
    let mut lines = BufReader::new(reader).lines();

    while let Some(line) = lines.next_line().await? {
        let line = line.trim().to_string();
        if line.is_empty() {
            continue;
        }

        let req = match serde_json::from_str::<IpcRequest>(&line) {
            Ok(r) => r,
            Err(e) => {
                let event = IpcEvent::err_response(&format!("invalid JSON: {e}"));
                write_event(&mut writer, &event).await?;
                continue;
            }
        };

        match req.cmd.as_str() {
            "status" => {
                let event = cmd_status(&state).await;
                write_event(&mut writer, &event).await?;
            }
            "devices" => {
                let event = cmd_devices(&state).await;
                write_event(&mut writer, &event).await?;
            }
            "set_receiving" => {
                let event = cmd_set_receiving(&state, req).await;
                write_event(&mut writer, &event).await?;
            }
            "send" => {
                cmd_send_streaming(&state, req, &mut writer).await?;
            }
            "pair" => {
                cmd_pair_streaming(&state, req, &mut writer, &mut lines).await?;
            }
            "watch" => {
                // Streaming subscription — bidirectional: events out, commands in.
                cmd_watch(&state, &mut writer, &mut lines).await?;
                return Ok(()); // Watch takes over the connection.
            }
            "transfer_confirm" => {
                let event = cmd_transfer_confirm(&state, req).await;
                write_event(&mut writer, &event).await?;
            }
            "unpair" => {
                let event = cmd_unpair(&state, req).await;
                write_event(&mut writer, &event).await?;
            }
            other => {
                let event = IpcEvent::err_response(&format!("unknown command: {other}"));
                write_event(&mut writer, &event).await?;
            }
        }
    }

    Ok(())
}

// ──────────────────────── Simple commands ────────────────────────

async fn cmd_status(state: &DaemonState) -> IpcEvent {
    let config = state.config.read().await;
    let paired_count = {
        let db = state.db.lock().await;
        db.list_all().map(|v| v.len()).unwrap_or(0)
    };
    let transfers = state.active_transfers.lock().await.clone();
    let pending = state.pending_offers.lock().await.len();

    // Fix D: discovery field.
    let discovery_status = {
        let disc = state.discovery.lock().await;
        match disc.as_ref() {
            Some(dm) => match dm.is_degraded() {
                Some(reason) => format!("degraded({reason})"),
                None => "ok".to_string(),
            },
            None => "unavailable".to_string(),
        }
    };

    IpcEvent::ok_response(serde_json::json!({
        "state": "running",
        "device_name": config.device_name,
        "receiving_enabled": config.receiving_enabled,
        "receive_dir": config.receive_dir.display().to_string(),
        "discovery": discovery_status,
        "active_transfers": transfers,
        "paired_devices": paired_count,
        "pending_offers": pending,
        "device_id": hex::encode(state.identity.device_id),
    }))
}

async fn cmd_devices(state: &DaemonState) -> IpcEvent {
    // Paired devices from DB.
    let paired: Vec<PairedDeviceInfo> = {
        let db = state.db.lock().await;
        match db.list_all() {
            Ok(devices) => devices
                .iter()
                .map(|d| PairedDeviceInfo {
                    device_id: d.device_id.clone(),
                    name: d.name.clone(),
                    trust_level: d.trust_level.clone(),
                    auto_accept: d.auto_accept.clone(),
                })
                .collect(),
            Err(_) => Vec::new(),
        }
    };

    // Discovered devices from browse cache (60s TTL).
    let discovered: Vec<DiscoveredDevice> = {
        let cache = state.browse_cache.lock().await;
        let now = Instant::now();
        cache
            .values()
            .filter(|e| now.duration_since(e.last_seen).as_secs() < 60)
            .map(|e| DiscoveredDevice {
                name: e.name.clone(),
                device_id_hint: e.device_id_hint.clone(),
                addresses: e.addresses.iter().map(|a| a.to_string()).collect(),
                port: e.port,
            })
            .collect()
    };

    IpcEvent::ok_response(serde_json::json!({
        "paired": paired,
        "discovered": discovered,
    }))
}

async fn cmd_set_receiving(state: &DaemonState, req: IpcRequest) -> IpcEvent {
    let enabled = match req.enabled {
        Some(e) => e,
        None => return IpcEvent::err_response("missing 'enabled' field"),
    };

    // Fix B: read current state WITHOUT mutating yet.
    let was_enabled = state.config.read().await.receiving_enabled;
    if enabled == was_enabled {
        return IpcEvent::ok_response(serde_json::json!({
            "receiving_enabled": enabled,
        }));
    }

    // Perform the fallible mDNS operation FIRST.
    let mut discovery = state.discovery.lock().await;
    if enabled && !was_enabled {
        // Need to start advertising.
        match discovery.as_mut() {
            Some(dm) => {
                if let Err(e) = dm.start_advertising() {
                    return IpcEvent::err_response(&format!("mDNS advertising failed: {e}"));
                }
            }
            None => {
                // No DiscoveryManager at all — mDNS init failed at boot.
                return IpcEvent::err_response(
                    "mDNS unavailable: discovery failed to initialize at startup",
                );
            }
        }
    } else if !enabled && was_enabled {
        if let Some(dm) = discovery.as_mut() {
            let _ = dm.stop_advertising();
        }
    }
    // Drop the discovery lock before writing config.
    drop(discovery);

    // Fix B: Only mutate observable state AFTER success.
    let mut config = state.config.write().await;
    config.receiving_enabled = enabled;

    if let Err(e) = config.save() {
        warn!("Failed to save config: {e}");
        // State is already toggled in memory; log but don't fail.
    }

    IpcEvent::ok_response(serde_json::json!({
        "receiving_enabled": enabled,
    }))
}

async fn cmd_transfer_confirm(state: &DaemonState, req: IpcRequest) -> IpcEvent {
    let transfer_id = match req.transfer_id {
        Some(id) => id,
        None => return IpcEvent::err_response("missing 'transfer_id' field"),
    };
    let accepted = match req.accepted {
        Some(a) => a,
        None => return IpcEvent::err_response("missing 'accepted' field"),
    };

    // First-confirm-wins: remove the pending offer.
    let respond_to = {
        let mut offers = state.pending_offers.lock().await;
        match offers.get_mut(&transfer_id) {
            Some(offer) => offer.respond_to.take(),
            None => None,
        }
    };

    match respond_to {
        Some(tx) => {
            let _ = tx.send(accepted);
            // Clean up the offer entry.
            state.pending_offers.lock().await.remove(&transfer_id);

            // NOTE: handler.rs broadcasts TransferResolved when consent_rx
            // resolves — do NOT duplicate the broadcast here.

            IpcEvent::ok_response(serde_json::json!({
                "transfer_id": transfer_id,
                "accepted": accepted,
            }))
        }
        None => IpcEvent::err_response("offer already resolved or not found"),
    }
}

async fn cmd_unpair(state: &DaemonState, req: IpcRequest) -> IpcEvent {
    let device_id = match req.device_id.or(req.target_device_id) {
        Some(id) => id,
        None => return IpcEvent::err_response("missing 'device_id' field"),
    };

    let db = state.db.lock().await;
    match db.remove(&device_id) {
        Ok(()) => {
            info!("Unpaired device: {device_id}");
            IpcEvent::ok_response(serde_json::json!({
                "unpaired": device_id,
            }))
        }
        Err(e) => IpcEvent::err_response(&format!("failed to unpair: {e}")),
    }
}

// ──────────────────────── Watch command ────────────────────────

async fn cmd_watch(
    state: &Arc<DaemonState>,
    writer: &mut OwnedWriteHalf,
    lines: &mut tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Register this client as a watcher.
    let (tx, mut rx) = mpsc::channel::<IpcEvent>(64);

    {
        let mut watchers = state.watchers.lock().await;
        watchers.push(tx);
    }

    // Replay pending offers.
    {
        let offers = state.pending_offers.lock().await;
        for offer in offers.values() {
            if offer.respond_to.is_some() {
                // Only replay unresolved offers.
                if write_event(writer, &offer.offer_event).await.is_err() {
                    return Ok(());
                }
            }
        }
    }

    // Send initial ack.
    write_event(
        writer,
        &IpcEvent::ok_response(serde_json::json!({
            "status": "watching",
            "message": "Subscribed to transfer events",
        })),
    )
    .await?;

    // BUG 1 FIX: Poll BOTH the mpsc event channel AND inbound IPC commands
    // concurrently. The watch CLI sends transfer_confirm on this same socket,
    // so we must read and dispatch it here.
    loop {
        tokio::select! {
            // Branch 1: outbound events from daemon → watcher.
            event = rx.recv() => {
                match event {
                    Some(ev) => {
                        if write_event(writer, &ev).await.is_err() {
                            break; // Client disconnected.
                        }
                    }
                    None => break, // All senders dropped.
                }
            }
            // Branch 2: inbound commands from watch CLI (e.g., transfer_confirm).
            line_result = lines.next_line() => {
                match line_result {
                    Ok(Some(line)) => {
                        let line = line.trim().to_string();
                        if line.is_empty() {
                            continue;
                        }
                        let req = match serde_json::from_str::<IpcRequest>(&line) {
                            Ok(r) => r,
                            Err(e) => {
                                let ev = IpcEvent::err_response(
                                    &format!("invalid JSON: {e}"),
                                );
                                let _ = write_event(writer, &ev).await;
                                continue;
                            }
                        };
                        // Dispatch commands that make sense on a watch connection.
                        match req.cmd.as_str() {
                            "transfer_confirm" => {
                                info!("Watch connection: transfer_confirm received for {:?}", req.transfer_id);
                                let event = cmd_transfer_confirm(state, req).await;
                                let _ = write_event(writer, &event).await;
                            }
                            other => {
                                let ev = IpcEvent::err_response(
                                    &format!("command '{other}' not supported on watch connection; use a separate nxfr command"),
                                );
                                let _ = write_event(writer, &ev).await;
                            }
                        }
                    }
                    Ok(None) => break, // Client disconnected.
                    Err(_) => break,   // Read error.
                }
            }
        }
    }

    Ok(())
}

// ──────────────────────── Send (streaming) ────────────────────────

async fn cmd_send_streaming(
    state: &Arc<DaemonState>,
    req: IpcRequest,
    writer: &mut OwnedWriteHalf,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let path = match req.path {
        Some(p) => p,
        None => {
            write_event(writer, &IpcEvent::err_response("missing 'path' field")).await?;
            return Ok(());
        }
    };
    let target_device_id = match req.target_device_id {
        Some(id) => id,
        None => {
            write_event(
                writer,
                &IpcEvent::err_response("missing 'target_device_id' field"),
            )
            .await?;
            return Ok(());
        }
    };
    // BUG 2 defense-in-depth: reject non-hex device_id early.
    if target_device_id.len() != 64 || !target_device_id.chars().all(|c| c.is_ascii_hexdigit()) {
        write_event(
            writer,
            &IpcEvent::err_response(
                "target_device_id must be a 64-character hex string; use `nxfr devices` to find device IDs",
            ),
        )
        .await?;
        return Ok(());
    }
    let target_addr = match req.target_addr {
        Some(a) => a,
        None => {
            // Try browse cache for address.
            let cache = state.browse_cache.lock().await;
            let found = cache.values().find(|e| {
                e.device_id_hint
                    .starts_with(&target_device_id[..8.min(target_device_id.len())])
            });
            match found {
                Some(entry) => {
                    if let Some(addr) = entry.addresses.first() {
                        format!("{}:{}", addr, entry.port)
                    } else {
                        write_event(
                            writer,
                            &IpcEvent::err_response("no address for target device"),
                        )
                        .await?;
                        return Ok(());
                    }
                }
                None => {
                    write_event(
                        writer,
                        &IpcEvent::err_response(
                            "missing 'target_addr' and device not in browse cache",
                        ),
                    )
                    .await?;
                    return Ok(());
                }
            }
        }
    };

    let file_path = std::path::PathBuf::from(&path);
    if !file_path.exists() {
        write_event(
            writer,
            &IpcEvent::err_response(&format!("file not found: {path}")),
        )
        .await?;
        return Ok(());
    }

    // Check for retry.
    let is_retry = req.retry_transfer_id.is_some();
    let retry_id = req.retry_transfer_id;

    write_event(
        writer,
        &IpcEvent::ok_response(serde_json::json!({
            "status": "connecting",
            "message": format!("{} {} to {target_device_id}",
                if is_retry { "Resuming" } else { "Sending" }, path),
            "retry": is_retry,
        })),
    )
    .await?;

    // Create progress channel.
    let (progress_tx, mut progress_rx) = mpsc::channel::<IpcEvent>(64);

    let state_clone = Arc::clone(state);
    let file_path_clone = file_path.clone();
    let target_addr_clone = target_addr.clone();
    let target_id_clone = target_device_id.clone();

    let send_handle = tokio::spawn(async move {
        if let Some(retry_tid) = retry_id {
            crate::handler::handle_outbound_resume(
                state_clone,
                &target_addr_clone,
                &target_id_clone,
                &file_path_clone,
                &retry_tid,
                Some(progress_tx),
            )
            .await
        } else {
            crate::handler::handle_outbound_send(
                state_clone,
                &target_addr_clone,
                &target_id_clone,
                &file_path_clone,
                Some(progress_tx),
            )
            .await
        }
    });

    // Forward progress events.
    loop {
        tokio::select! {
            event = progress_rx.recv() => {
                match event {
                    Some(evt) => {
                        if write_event(writer, &evt).await.is_err() {
                            break;
                        }
                    }
                    None => break,
                }
            }
        }
    }

    match send_handle.await {
        Ok(Ok(transfer_id)) => {
            let file_size = std::fs::metadata(&file_path).map(|m| m.len()).unwrap_or(0);
            write_event(
                writer,
                &IpcEvent::TransferComplete {
                    transfer_id,
                    files_received: 1,
                    total_bytes: file_size,
                },
            )
            .await?;
        }
        Ok(Err(e)) => {
            write_event(
                writer,
                &IpcEvent::Error {
                    code: "send_failed".to_string(),
                    message: e.to_string(),
                },
            )
            .await?;
        }
        Err(e) => {
            write_event(
                writer,
                &IpcEvent::Error {
                    code: "send_panic".to_string(),
                    message: e.to_string(),
                },
            )
            .await?;
        }
    }

    Ok(())
}

// ──────────────────────── Pair (streaming) ────────────────────────

/// Streaming pair command: connects, derives SAS, waits for user confirm.
async fn cmd_pair_streaming(
    state: &Arc<DaemonState>,
    req: IpcRequest,
    writer: &mut OwnedWriteHalf,
    lines: &mut tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let device_id = match req.device_id {
        Some(id) => id,
        None => {
            write_event(writer, &IpcEvent::err_response("missing 'device_id' field")).await?;
            return Ok(());
        }
    };
    let target_addr = match req.target_addr {
        Some(a) => a,
        None => {
            // Try browse cache.
            let cache = state.browse_cache.lock().await;
            let found = cache.values().find(|e| {
                e.device_id_hint
                    .starts_with(&device_id[..8.min(device_id.len())])
            });
            match found {
                Some(entry) => {
                    if let Some(addr) = entry.addresses.first() {
                        format!("{}:{}", addr, entry.port)
                    } else {
                        write_event(
                            writer,
                            &IpcEvent::err_response("no address for target device"),
                        )
                        .await?;
                        return Ok(());
                    }
                }
                None => {
                    write_event(
                        writer,
                        &IpcEvent::err_response(
                            "missing 'target_addr' — address required when peer is not in cache",
                        ),
                    )
                    .await?;
                    return Ok(());
                }
            }
        }
    };

    info!("Pair request for device_id={device_id} at {target_addr}");

    // Check if already paired.
    {
        let db = state.db.lock().await;
        if db.is_paired(&device_id) {
            write_event(
                writer,
                &IpcEvent::err_response(&format!("device {device_id} is already paired")),
            )
            .await?;
            return Ok(());
        }
    }

    // Connect to peer (outbound).
    write_event(
        writer,
        &IpcEvent::ok_response(serde_json::json!({
            "status": "connecting",
            "message": format!("Connecting to {target_addr}...")
        })),
    )
    .await?;

    let connect_result = crate::handler::connect_to_peer(state, &target_addr, &device_id).await;

    let (mut conn, session_id, peer_name, mut exporter_bytes) = match connect_result {
        Ok(r) => r,
        Err(e) => {
            write_event(
                writer,
                &IpcEvent::PairFailed {
                    reason: format!("connection failed: {e}"),
                },
            )
            .await?;
            return Ok(());
        }
    };

    // Derive SAS using the TLS exporter bytes.
    let peer_id_bytes: [u8; 32] = {
        let mut b = [0u8; 32];
        if let Ok(decoded) = hex::decode(&device_id) {
            if decoded.len() == 32 {
                b.copy_from_slice(&decoded);
            }
        }
        b
    };

    let (sas_code, _context) =
        nxfr_core::sas::derive_sas(&state.identity.device_id, &peer_id_bytes, &exporter_bytes);

    // Zeroize exporter bytes (SECURITY §10).
    exporter_bytes.zeroize();

    // Send PAIR_REQUEST over protocol.
    let pair_req = nxfr_core::messages::ControlMessage::PairRequest {
        sas_method: "numeric-6".to_string(),
    };
    conn.send_control(session_id, 0, &pair_req).await?;
    info!("Sent PAIR_REQUEST to {peer_name}");

    // Push SAS prompt to IPC client.
    write_event(
        writer,
        &IpcEvent::SasPrompt {
            peer_name: peer_name.clone(),
            peer_device_id: device_id.clone(),
            sas_code: sas_code.clone(),
        },
    )
    .await?;

    // Wait for pair_confirm from IPC client (timeout 60s).
    let confirm_result = tokio::time::timeout(
        std::time::Duration::from_secs(60),
        wait_for_pair_confirm(lines),
    )
    .await;

    let accepted = match confirm_result {
        Ok(Ok(a)) => a,
        Ok(Err(e)) => {
            warn!("pair_confirm read error: {e}");
            false
        }
        Err(_) => {
            warn!("pair_confirm timed out after 60s");
            false
        }
    };

    if accepted {
        // Send PAIR_ACCEPT over protocol.
        let accept = nxfr_core::messages::ControlMessage::PairAccept;
        conn.send_control(session_id, 0, &accept).await?;

        // Wait for peer's PAIR_ACCEPT.
        let (_hdr, payload) = conn.recv_frame().await?;
        let msg = nxfr_core::codec::decode_control(&payload)?;
        match msg {
            nxfr_core::messages::ControlMessage::PairAccept => {
                info!("Peer accepted pairing");
            }
            nxfr_core::messages::ControlMessage::PairReject { reason } => {
                let reason_str = reason.unwrap_or_else(|| "peer rejected".to_string());
                write_event(writer, &IpcEvent::PairFailed { reason: reason_str }).await?;
                return Ok(());
            }
            _ => {
                write_event(
                    writer,
                    &IpcEvent::PairFailed {
                        reason: "unexpected response from peer".to_string(),
                    },
                )
                .await?;
                return Ok(());
            }
        }

        // Extract peer cert SPKI for storage.
        let peer_spki = {
            let stream_ref = conn.get_ref();
            let (_, client_conn) = stream_ref.get_ref();
            let peer_certs = client_conn.peer_certificates().unwrap_or(&[]);
            peer_certs
                .first()
                .map(|c| c.as_ref().to_vec())
                .unwrap_or_default()
        };

        // Insert into paired DB.
        {
            let db = state.db.lock().await;
            let device = nxfr_storage::db::PairedDevice {
                device_id: device_id.clone(),
                name: peer_name.clone(),
                public_key_spki: peer_spki,
                first_seen: chrono::Utc::now().timestamp(),
                last_seen: chrono::Utc::now().timestamp(),
                trust_level: "paired".to_string(),
                auto_accept: "prompt".to_string(),
            };
            if let Err(e) = db.insert_or_update(&device) {
                error!("Failed to store paired device: {e}");
            }
        }

        info!("Pairing complete: {device_id} ({peer_name})");
        write_event(
            writer,
            &IpcEvent::PairSuccess {
                device_id,
                device_name: peer_name,
            },
        )
        .await?;
    } else {
        // Send PAIR_REJECT over protocol.
        let reject = nxfr_core::messages::ControlMessage::PairReject {
            reason: Some("user_rejected".to_string()),
        };
        conn.send_control(session_id, 0, &reject).await?;

        write_event(
            writer,
            &IpcEvent::PairFailed {
                reason: "user rejected pairing".to_string(),
            },
        )
        .await?;
    }

    // Close session.
    let close = nxfr_core::messages::ControlMessage::SessionClose {
        reason: Some("pairing_complete".to_string()),
    };
    conn.send_control(session_id, 0, &close).await?;

    Ok(())
}

/// Wait for a pair_confirm JSON line from the IPC client.
async fn wait_for_pair_confirm(
    lines: &mut tokio::io::Lines<BufReader<tokio::net::unix::OwnedReadHalf>>,
) -> Result<bool, Box<dyn std::error::Error + Send + Sync>> {
    while let Some(line) = lines.next_line().await? {
        let line = line.trim().to_string();
        if line.is_empty() {
            continue;
        }
        if let Ok(req) = serde_json::from_str::<IpcRequest>(&line) {
            if req.cmd == "pair_confirm" {
                return Ok(req.accepted.unwrap_or(false));
            }
        }
    }
    Err("IPC client disconnected before pair_confirm".into())
}
