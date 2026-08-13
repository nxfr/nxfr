//! # nxfr-daemon
//!
//! NXFR protocol daemon: TCP listener, mDNS discovery, file I/O, IPC.

use nxfr_daemon::*;

use log::{error, info, warn};
use nxfr_discovery::DiscoveryManager;
use nxfr_storage::config::NxfrConfig;
use nxfr_storage::db::PairedDeviceDb;
use nxfr_storage::resume::ResumeJournal;
use std::sync::Arc;
use tokio::sync::{Mutex, RwLock};

use std::collections::HashMap;
#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    env_logger::Builder::from_env(
        env_logger::Env::default().default_filter_or("info,mdns_sd::service_daemon=off"),
    )
    .format_timestamp_millis()
    .init();

    info!("nxfr-daemon starting...");

    // Entropy guard: verify system RNG works before any keygen.
    match nxfr_crypto::check_entropy() {
        Ok(()) => info!("Entropy check passed"),
        Err(e) => warn!("CRITICAL: {e} — key generation may produce weak keys"),
    }

    // ── Fix C: Single-instance guard ──
    if ipc::check_existing_instance().await {
        error!("nxfr-daemon is already running (IPC socket responded to status ping)");
        std::process::exit(1);
    }

    // Load config.
    let mut config = NxfrConfig::load().unwrap_or_else(|e| {
        info!("Config load failed ({e}), using defaults");
        NxfrConfig::default()
    });
    // Sanitize /tmp test path leftover.
    {
        let recv_str = config.receive_dir.to_string_lossy().to_string();
        if recv_str.contains("/tmp") || recv_str.contains("nxfr-test-recv") {
            let new_default = NxfrConfig::default().receive_dir;
            warn!(
                "Receive dir '{}' contains /tmp test path — replacing with '{}'",
                config.receive_dir.display(),
                new_default.display()
            );
            config.receive_dir = new_default;
            if let Err(e) = config.save() {
                warn!("Failed to persist updated config: {e}");
            }
        }
    }
    info!("Device name: {}", config.device_name);
    info!("Receive dir: {}", config.receive_dir.display());
    info!("Receiving enabled: {}", config.receiving_enabled);

    // Create receive dir with mode 0700.
    std::fs::create_dir_all(&config.receive_dir)?;
    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ =
            std::fs::set_permissions(&config.receive_dir, std::fs::Permissions::from_mode(0o700));
    }

    // Open paired device DB.
    let db = PairedDeviceDb::open_default()?;

    // Load or generate persistent identity.
    let data_dir = identity::data_dir();
    std::fs::create_dir_all(&data_dir)?;
    let ident = identity::PersistentIdentity::load_or_generate(&data_dir)?;
    info!("Device ID: {}", hex::encode(ident.device_id));

    // Resume journal.
    let resume_dir = ResumeJournal::default_dir();
    std::fs::create_dir_all(&resume_dir)?;
    let resume = ResumeJournal::new(resume_dir);

    // GC expired resume entries.
    match resume.gc_expired() {
        Ok(n) if n > 0 => info!("Cleaned up {n} expired resume entries"),
        _ => {}
    }

    // Set up mDNS discovery (always create the manager for browse capability).
    let discovery = match DiscoveryManager::new(
        ident.device_id,
        config.device_name.clone(),
        "linux".to_string(),
        17394,
    ) {
        Ok(mut mgr) => {
            if config.receiving_enabled {
                if let Err(e) = mgr.start_advertising() {
                    error!("mDNS advertising failed: {e}");
                } else {
                    info!("mDNS advertising started on _nxfr._tcp port 17394");
                }
            } else {
                info!("Receiving disabled, mDNS advertising not started");
            }
            Some(mgr)
        }
        Err(e) => {
            error!("mDNS init failed: {e}");
            None
        }
    };

    let state = Arc::new(DaemonState {
        config: RwLock::new(config),
        db: Mutex::new(db),
        resume,
        identity: ident,
        discovery: Mutex::new(discovery),
        active_transfers: Mutex::new(Vec::new()),
        active_connections: Mutex::new(HashMap::new()),
        shutdown: tokio::sync::Notify::new(),
        watchers: Mutex::new(Vec::new()),
        pending_offers: Mutex::new(HashMap::new()),
        browse_cache: Mutex::new(HashMap::new()),
    });

    // Spawn TCP listener.
    let state_listener = Arc::clone(&state);
    let _listener_handle = tokio::spawn(async move {
        if let Err(e) = listener::run_listener(state_listener).await {
            error!("TCP listener error: {e}");
        }
    });

    // Spawn IPC server.
    let state_ipc = Arc::clone(&state);
    let _ipc_handle = tokio::spawn(async move {
        if let Err(e) = ipc::run_ipc_server(state_ipc).await {
            error!("IPC server error: {e}");
        }
    });

    // Offer expiry: auto-reject offers older than 120s.
    let expiry_state = state.clone();
    tokio::spawn(async move {
        loop {
            tokio::time::sleep(std::time::Duration::from_secs(10)).await;
            let mut offers = expiry_state.pending_offers.lock().await;
            let now = std::time::Instant::now();
            let expired: Vec<String> = offers
                .iter()
                .filter(|(_, o)| now >= o.expires_at)
                .map(|(k, _)| k.clone())
                .collect();
            for tid in expired {
                if let Some(mut offer) = offers.remove(&tid) {
                    if let Some(tx) = offer.respond_to.take() {
                        let _ = tx.send(false);
                    }
                    info!("Offer {tid} expired (120s timeout), auto-rejected");
                }
            }
        }
    });

    // Spawn background browse cache task (60s TTL).
    let state_browse = Arc::clone(&state);
    let _browse_handle = tokio::spawn(async move {
        browse_cache_task(state_browse).await;
    });

    info!("nxfr-daemon ready");

    // Wait for shutdown signal.
    tokio::select! {
        _ = tokio::signal::ctrl_c() => {
            info!("Received SIGINT, shutting down...");
        }
        _ = state.shutdown.notified() => {
            info!("Shutdown requested via IPC");
        }
    }

    // Graceful shutdown.
    info!("Stopping mDNS...");
    if let Some(mut mgr) = state.discovery.lock().await.take() {
        let _ = mgr.stop_advertising();
        let _ = mgr.shutdown();
    }

    // Clean up IPC socket.
    let sock_path = ipc::socket_path();
    let _ = std::fs::remove_file(&sock_path);

    info!("nxfr-daemon stopped.");
    Ok(())
}

/// Background task that keeps the browse cache updated from mDNS discovery.
async fn browse_cache_task(state: Arc<DaemonState>) {
    use std::time::Instant;

    // Wait a moment for daemon to fully initialize.
    tokio::time::sleep(std::time::Duration::from_secs(2)).await;

    info!("Browse cache task started (60s TTL)");

    loop {
        // Try to browse. If discovery isn't available, sleep and retry.
        let (discovered, is_degraded) = {
            let mut disc = state.discovery.lock().await;
            match disc.as_mut() {
                Some(dm) => {
                    if let Some(reason) = dm.is_degraded() {
                        warn!("Browse cache stopping: discovery degraded ({reason})");
                        return; // Stop polling forever.
                    }
                    (dm.browse_snapshot(), false)
                }
                None => (Vec::new(), true),
            }
        };

        if is_degraded {
            tokio::time::sleep(std::time::Duration::from_secs(10)).await;
            continue;
        }

        // Update cache.
        {
            let mut cache = state.browse_cache.lock().await;

            // Prune expired entries (> 60s).
            let now = Instant::now();
            cache.retain(|_, entry| now.duration_since(entry.last_seen).as_secs() < 60);

            // Insert/update discovered entries.
            for entry in discovered {
                cache.insert(
                    entry.device_id_hint.clone(),
                    BrowseEntry {
                        name: entry.name,
                        device_id_hint: entry.device_id_hint,
                        addresses: entry.addresses,
                        port: entry.port,
                        last_seen: Instant::now(),
                    },
                );
            }
        }

        tokio::time::sleep(std::time::Duration::from_secs(15)).await;
    }
}
