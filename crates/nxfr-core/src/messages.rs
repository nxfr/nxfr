use crate::error_code::ErrorCode;
use nxfr_common::{DeviceId, Platform, ProtocolVersion, TransferId};
use std::collections::BTreeMap;

/// Manifest entry type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum ManifestEntryType {
    File,
    Dir,
}

/// A single entry in a TRANSFER_REQUEST manifest.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManifestEntry {
    pub file_id: u32,
    pub relative_path: String,
    /// Size in bytes. Required for File, absent for Dir.
    pub size: Option<u64>,
    /// SHA-256 of the complete file. Required for File, absent for Dir.
    pub sha256: Option<[u8; 32]>,
    /// Entry type. Default: File.
    pub entry_type: ManifestEntryType,
}

/// Transfer type.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferType {
    Files,
    Directory,
}

/// Resume file status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ResumeFileStatus {
    pub file_id: u32,
    pub received_bytes: u64,
    pub received_ranges: Vec<(u64, u64)>,
    pub partial_sha256: Option<[u8; 32]>,
}

/// Transfer ACK status.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum TransferAckStatus {
    Success,
    PartialFailure,
}

/// All NXFR control messages per §9.
#[derive(Debug, Clone, PartialEq)]
pub enum ControlMessage {
    Hello {
        protocol_version: ProtocolVersion,
        device_id: DeviceId,
        device_name: String,
        platform: Platform,
        capabilities: Vec<String>,
        is_paired: bool,
    },
    HelloAck {
        protocol_version: ProtocolVersion,
        device_id: DeviceId,
        device_name: String,
        platform: Platform,
        capabilities: Vec<String>,
        is_paired: bool,
        session_id: u32,
    },
    PairRequest {
        sas_method: String,
    },
    PairAccept,
    PairReject {
        reason: Option<String>,
    },
    SessionClose {
        reason: Option<String>,
    },
    Error {
        code: ErrorCode,
        message: Option<String>,
        fatal: bool,
        details: Option<BTreeMap<String, String>>,
    },
    TransferRequest {
        transfer_id: TransferId,
        transfer_type: TransferType,
        display_name: String,
        total_files: u32,
        total_size: u64,
        manifest: Vec<ManifestEntry>,
    },
    TransferAccept {
        transfer_id: TransferId,
    },
    TransferReject {
        transfer_id: TransferId,
        reason: Option<String>,
    },
    FileMetadata {
        transfer_id: TransferId,
        file_id: u32,
        stream_id: u32,
        relative_path: String,
        size: u64,
        sha256: [u8; 32],
        mime_type: Option<String>,
        modified_time: Option<u64>,
    },
    FileMetadataAck {
        transfer_id: TransferId,
        file_id: u32,
        stream_id: u32,
        accepted: bool,
    },
    ChunkAck {
        stream_id: u32,
        message_id: u64,
        offset: u64,
        length: u64,
    },
    TransferPause {
        transfer_id: TransferId,
    },
    TransferResume {
        transfer_id: TransferId,
    },
    TransferCancel {
        transfer_id: TransferId,
        reason: Option<String>,
    },
    TransferComplete {
        transfer_id: TransferId,
    },
    TransferAck {
        transfer_id: TransferId,
        status: TransferAckStatus,
        failed_files: Option<Vec<u32>>,
    },
    ResumeQuery {
        transfer_id: TransferId,
        file_ids: Option<Vec<u32>>,
    },
    ResumeStatus {
        transfer_id: TransferId,
        resumable: bool,
        files: Option<Vec<ResumeFileStatus>>,
        expiry: Option<u64>,
    },
}

impl ControlMessage {
    /// Get the wire type code for this message.
    pub fn type_code(&self) -> u8 {
        match self {
            Self::Hello { .. } => 0x01,
            Self::HelloAck { .. } => 0x02,
            Self::PairRequest { .. } => 0x03,
            Self::PairAccept => 0x04,
            Self::PairReject { .. } => 0x05,
            Self::SessionClose { .. } => 0x06,
            Self::Error { .. } => 0x09,
            Self::TransferRequest { .. } => 0x10,
            Self::TransferAccept { .. } => 0x11,
            Self::TransferReject { .. } => 0x12,
            Self::FileMetadata { .. } => 0x13,
            Self::FileMetadataAck { .. } => 0x14,
            Self::ChunkAck { .. } => 0x15,
            Self::TransferPause { .. } => 0x16,
            Self::TransferResume { .. } => 0x17,
            Self::TransferCancel { .. } => 0x18,
            Self::TransferComplete { .. } => 0x19,
            Self::TransferAck { .. } => 0x1A,
            Self::ResumeQuery { .. } => 0x20,
            Self::ResumeStatus { .. } => 0x21,
        }
    }

    /// Get the wire type name.
    pub fn type_name(&self) -> &'static str {
        match self {
            Self::Hello { .. } => "Hello",
            Self::HelloAck { .. } => "HelloAck",
            Self::PairRequest { .. } => "PairRequest",
            Self::PairAccept => "PairAccept",
            Self::PairReject { .. } => "PairReject",
            Self::SessionClose { .. } => "SessionClose",
            Self::Error { .. } => "Error",
            Self::TransferRequest { .. } => "TransferRequest",
            Self::TransferAccept { .. } => "TransferAccept",
            Self::TransferReject { .. } => "TransferReject",
            Self::FileMetadata { .. } => "FileMetadata",
            Self::FileMetadataAck { .. } => "FileMetadataAck",
            Self::ChunkAck { .. } => "ChunkAck",
            Self::TransferPause { .. } => "TransferPause",
            Self::TransferResume { .. } => "TransferResume",
            Self::TransferCancel { .. } => "TransferCancel",
            Self::TransferComplete { .. } => "TransferComplete",
            Self::TransferAck { .. } => "TransferAck",
            Self::ResumeQuery { .. } => "ResumeQuery",
            Self::ResumeStatus { .. } => "ResumeStatus",
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Since we don't know the exact structure of types like DeviceId, Platform, etc.
    // we will test the variants that only depend on standard library types.
    #[test]
    fn test_type_codes() {
        assert_eq!(
            ControlMessage::PairRequest {
                sas_method: "PIN".to_string()
            }
            .type_code(),
            0x03
        );
        assert_eq!(ControlMessage::PairAccept.type_code(), 0x04);
        assert_eq!(
            ControlMessage::PairReject { reason: None }.type_code(),
            0x05
        );
        assert_eq!(
            ControlMessage::SessionClose { reason: None }.type_code(),
            0x06
        );
        assert_eq!(
            ControlMessage::ChunkAck {
                stream_id: 1,
                message_id: 2,
                offset: 0,
                length: 1024
            }
            .type_code(),
            0x15
        );
    }

    #[test]
    fn test_type_names() {
        assert_eq!(
            ControlMessage::PairRequest {
                sas_method: "PIN".to_string()
            }
            .type_name(),
            "PairRequest"
        );
        assert_eq!(ControlMessage::PairAccept.type_name(), "PairAccept");
        assert_eq!(
            ControlMessage::PairReject { reason: None }.type_name(),
            "PairReject"
        );
        assert_eq!(
            ControlMessage::SessionClose { reason: None }.type_name(),
            "SessionClose"
        );
        assert_eq!(
            ControlMessage::ChunkAck {
                stream_id: 1,
                message_id: 2,
                offset: 0,
                length: 1024
            }
            .type_name(),
            "ChunkAck"
        );
    }
}
