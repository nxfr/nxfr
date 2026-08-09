pub mod handler;
pub mod identity;
pub mod ipc;
pub mod listener;
pub mod receiver;
pub mod sender;

// Re-export key types
pub use identity::PersistentIdentity;

use nxfr_discovery::DiscoveryManager;
use nxfr_storage::config::NxfrConfig;
use nxfr_storage::db::PairedDeviceDb;
use nxfr_storage::resume::ResumeJournal;
use std::collections::HashMap;
use std::net::IpAddr;
use std::time::Instant;
use tokio::sync::{mpsc, Mutex, RwLock};

pub struct DaemonState {
    pub config: RwLock<NxfrConfig>,
    pub db: Mutex<PairedDeviceDb>,
    pub resume: ResumeJournal,
    pub identity: PersistentIdentity,
    pub discovery: Mutex<Option<DiscoveryManager>>,
    pub active_transfers: Mutex<Vec<ipc::TransferStatus>>,
    pub active_connections: Mutex<HashMap<String, ActiveConnection>>,
    pub shutdown: tokio::sync::Notify,
    /// IPC watcher subscribers: streaming event receivers.
    pub watchers: Mutex<Vec<mpsc::Sender<ipc::IpcEvent>>>,
    /// Pending consent offers awaiting user response.
    pub pending_offers: Mutex<HashMap<String, PendingOffer>>,
    /// Browse cache: device_id_hint → entry (60s TTL).
    pub browse_cache: Mutex<HashMap<String, BrowseEntry>>,
}

pub struct ActiveConnection {
    pub peer_device_id: String,
    pub peer_name: String,
    pub session_id: u32,
    pub cmd_tx: mpsc::Sender<ConnectionCommand>,
}

#[derive(Debug)]
pub enum ConnectionCommand {
    InitiatePairing {
        respond_to: tokio::sync::oneshot::Sender<PairingResult>,
    },
    ConfirmPairing {
        accepted: bool,
        respond_to: tokio::sync::oneshot::Sender<PairingResult>,
    },
}

#[derive(Debug)]
pub enum PairingResult {
    SasReady {
        sas_code: String,
        peer_name: String,
        peer_device_id: String,
    },
    Success {
        device_id: String,
        device_name: String,
    },
    Failed {
        reason: String,
    },
}

/// A pending transfer offer waiting for user consent.
pub struct PendingOffer {
    /// The offer event to replay to new watchers.
    pub offer_event: ipc::IpcEvent,
    /// Oneshot sender to resolve the offer. First confirm wins.
    pub respond_to: Option<tokio::sync::oneshot::Sender<bool>>,
    /// When this offer expires (120s from creation).
    pub expires_at: Instant,
}

/// A cached mDNS browse entry.
#[derive(Debug, Clone)]
pub struct BrowseEntry {
    pub name: String,
    pub device_id_hint: String,
    pub addresses: Vec<IpAddr>,
    pub port: u16,
    pub last_seen: Instant,
}
