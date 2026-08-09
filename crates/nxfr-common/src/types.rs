//! Shared types for the NXFR protocol.

use std::fmt;

/// 32-byte device identity: SHA-256(SPKI DER).
#[derive(Clone, Copy, PartialEq, Eq, Hash, PartialOrd, Ord)]
pub struct DeviceId(pub [u8; 32]);

impl DeviceId {
    /// Create from a 32-byte array.
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// Access the raw bytes.
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl fmt::Debug for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "DeviceId(")?;
        for b in &self.0[..4] {
            write!(f, "{b:02x}")?;
        }
        write!(f, "…)")
    }
}

impl fmt::Display for DeviceId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        for b in &self.0 {
            write!(f, "{b:02x}")?;
        }
        Ok(())
    }
}

/// 16-byte random transfer identifier.
#[derive(Clone, Copy, PartialEq, Eq, Hash)]
pub struct TransferId(pub [u8; 16]);

impl TransferId {
    pub fn from_bytes(bytes: [u8; 16]) -> Self {
        Self(bytes)
    }

    pub fn as_bytes(&self) -> &[u8; 16] {
        &self.0
    }
}

impl fmt::Debug for TransferId {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "TransferId(")?;
        for b in &self.0[..4] {
            write!(f, "{b:02x}")?;
        }
        write!(f, "…)")
    }
}

/// Session identifier assigned by the responder in HELLO_ACK.
pub type SessionId = u32;

/// Stream identifier for file-level frames. 0 = session-level.
pub type StreamId = u32;

/// File identifier, unique within a transfer.
pub type FileId = u32;

/// Supported platforms.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Platform {
    Linux,
    Android,
    Windows,
    MacOs,
    Ios,
    Other(String),
}

impl Platform {
    pub fn as_str(&self) -> &str {
        match self {
            Platform::Linux => "linux",
            Platform::Android => "android",
            Platform::Windows => "windows",
            Platform::MacOs => "macos",
            Platform::Ios => "ios",
            Platform::Other(s) => s.as_str(),
        }
    }

    pub fn from_str_lossy(s: &str) -> Self {
        match s {
            "linux" => Platform::Linux,
            "android" => Platform::Android,
            "windows" => Platform::Windows,
            "macos" => Platform::MacOs,
            "ios" => Platform::Ios,
            other => Platform::Other(other.to_string()),
        }
    }
}

/// Protocol version as (major, minor).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ProtocolVersion {
    pub major: u32,
    pub minor: u32,
}

impl ProtocolVersion {
    pub const V0_1: Self = Self { major: 0, minor: 1 };
}

/// Protocol-level constants from §17.
pub mod limits {
    /// Maximum CONTROL frame payload: 64 KiB.
    pub const CONTROL_PAYLOAD_MAX: u32 = 65_536;
    /// Maximum CHUNK frame payload: 4 MiB.
    pub const CHUNK_PAYLOAD_MAX: u32 = 4_194_304;
    /// Minimum CHUNK frame payload: 41 bytes (40 header + 1 data).
    pub const CHUNK_PAYLOAD_MIN: u32 = 41;
    /// KEEPALIVE payload: 0 or 8 bytes.
    pub const KEEPALIVE_PAYLOAD_MAX: u32 = 8;
    /// Max manifest entries per TRANSFER_REQUEST.
    pub const MAX_MANIFEST_ENTRIES: usize = 500;
    /// Max device name length in bytes.
    pub const MAX_DEVICE_NAME: usize = 63;
    /// Max path component length in bytes.
    pub const MAX_PATH_COMPONENT: usize = 255;
    /// Max relative path length in bytes.
    pub const MAX_RELATIVE_PATH: usize = 4096;
    /// Max concurrent transfers per session.
    pub const MAX_CONCURRENT_TRANSFERS: usize = 4;
    /// Max concurrent sessions per device.
    pub const MAX_CONCURRENT_SESSIONS: usize = 8;
    /// In-flight chunk window.
    pub const IN_FLIGHT_CHUNK_WINDOW: usize = 8;
    /// Default chunk size: 1 MiB.
    pub const DEFAULT_CHUNK_SIZE: u32 = 1_048_576;
    /// Max CBOR nesting depth (6 needed for ResumeStatus.files[].received_ranges[][]).
    pub const MAX_CBOR_NESTING: usize = 6;
    /// Frame header size in bytes.
    pub const FRAME_HEADER_SIZE: usize = 28;
    /// NXFR frame magic bytes.
    pub const FRAME_MAGIC: [u8; 4] = *b"NXFR";
    /// Current frame format version.
    pub const FRAME_VERSION: u8 = 1;
}

/// Timeout values from §17.2, in seconds (unless noted).
pub mod timeouts {
    use std::time::Duration;

    pub const TLS_HANDSHAKE: Duration = Duration::from_secs(10);
    pub const HELLO_EXCHANGE: Duration = Duration::from_secs(10);
    pub const PAIRING_SAS: Duration = Duration::from_secs(60);
    pub const TRANSFER_CONSENT: Duration = Duration::from_secs(120);
    pub const KEEPALIVE_INTERVAL: Duration = Duration::from_secs(30);
    pub const KEEPALIVE_TIMEOUT: Duration = Duration::from_secs(90);
    pub const CHUNK_ACK: Duration = Duration::from_secs(30);
    pub const TRANSFER_COMPLETION: Duration = Duration::from_secs(60);
    pub const SESSION_CLOSE_GRACE: Duration = Duration::from_secs(5);
    pub const PAUSE_TIMEOUT: Duration = Duration::from_secs(300);
    pub const RESUME_STATE_EXPIRY: Duration = Duration::from_secs(86400);
}
