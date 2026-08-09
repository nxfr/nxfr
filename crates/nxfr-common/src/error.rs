//! Error types for the NXFR protocol.

use thiserror::Error;

/// Top-level error type for the NXFR protocol library.
#[derive(Debug, Error)]
pub enum NxfrError {
    /// Frame-level errors (bad magic, version, kind, size).
    #[error("frame error: {0}")]
    Frame(#[from] FrameError),

    /// CBOR encoding/decoding errors.
    #[error("codec error: {0}")]
    Codec(#[from] CodecError),

    /// Path validation errors.
    #[error("path error: {0}")]
    Path(#[from] PathError),

    /// State machine transition errors.
    #[error("state error: {0}")]
    State(#[from] StateError),
}

/// Errors during frame header parsing/validation.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum FrameError {
    #[error("invalid magic: expected NXFR (0x4E584652)")]
    InvalidMagic,

    #[error("unsupported version: {0} (expected 1)")]
    UnsupportedVersion(u8),

    #[error("unknown frame kind: 0x{0:02x}")]
    UnknownKind(u8),

    #[error("payload too large for {kind}: {size} bytes (max {max})")]
    PayloadTooLarge {
        kind: &'static str,
        size: u32,
        max: u32,
    },

    #[error("incomplete header: need 28 bytes, got {0}")]
    IncompleteHeader(usize),

    #[error("chunk payload too small: {0} bytes (min 41)")]
    ChunkPayloadTooSmall(u32),

    #[error("keepalive payload invalid size: {0} bytes (must be 0 or 8)")]
    KeepalivePayloadInvalid(u32),
}

/// Errors during CBOR encoding/decoding.
#[derive(Debug, Error)]
pub enum CodecError {
    #[error("CBOR decode error: {0}")]
    CborDecode(String),

    #[error("not a CBOR map at top level")]
    NotAMap,

    #[error("non-string map key found")]
    NonStringKey,

    #[error("missing required field: {0}")]
    MissingField(String),

    #[error("wrong type for field '{field}': expected {expected}")]
    WrongType {
        field: String,
        expected: &'static str,
    },

    #[error("unknown message type: {0}")]
    UnknownMessageType(u64),

    #[error("CBOR nesting depth exceeds maximum ({0})")]
    NestingTooDeep(usize),

    #[error("CBOR tag encountered (tags not allowed in v0.1)")]
    TagNotAllowed,

    #[error("indefinite-length CBOR item encountered")]
    IndefiniteLength,

    #[error("CBOR encode error: {0}")]
    CborEncode(String),

    #[error("manifest too large: {0} entries (max 500)")]
    ManifestTooLarge(usize),

    #[error("encoded message exceeds 64 KiB control payload limit")]
    EncodedTooLarge,
}

/// Errors during path sanitization.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum PathError {
    #[error("empty path")]
    EmptyPath,

    #[error("absolute path: {0:?}")]
    AbsolutePath(String),

    #[error("parent traversal (..) in path: {0:?}")]
    ParentTraversal(String),

    #[error("null byte in path")]
    NullByte,

    #[error("control character (0x{0:02x}) in path")]
    ControlCharacter(u8),

    #[error("Windows reserved name: {0:?}")]
    WindowsReservedName(String),

    #[error("path component too long: {len} bytes (max 255)")]
    ComponentTooLong { len: usize },

    #[error("total path too long: {len} bytes (max 4096)")]
    PathTooLong { len: usize },

    #[error("path contains backslash")]
    Backslash,
}

/// State machine transition errors.
#[derive(Debug, Error, PartialEq, Eq)]
pub enum StateError {
    #[error("invalid transition from {from} on event {event}")]
    InvalidTransition { from: String, event: String },
}
