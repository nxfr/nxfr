use std::fmt;

/// Protocol error codes per §15.1.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum ErrorCode {
    UnsupportedVersion,
    InvalidFrame,
    PayloadTooLarge,
    InvalidCbor,
    UnknownMessageType,
    SessionTimeout,
    ChecksumMismatch,
    DiskFull,
    StorageError,
    PathRejected,
    TransferNotFound,
    StreamNotFound,
    IdentityChanged,
    PairRequired,
    RateLimited,
    InternalError,
    ManifestTooLarge,
    /// Forward-compatible unrecognized error code from a newer peer.
    Unknown(String),
}

impl ErrorCode {
    pub fn is_fatal(&self) -> bool {
        matches!(
            self,
            Self::UnsupportedVersion
                | Self::InvalidFrame
                | Self::PayloadTooLarge
                | Self::InvalidCbor
                | Self::SessionTimeout
                | Self::IdentityChanged
                | Self::InternalError
        )
    }

    pub fn is_retryable(&self) -> bool {
        matches!(self, Self::ChecksumMismatch | Self::RateLimited)
    }

    pub fn as_str(&self) -> &str {
        match self {
            Self::UnsupportedVersion => "unsupported_version",
            Self::InvalidFrame => "invalid_frame",
            Self::PayloadTooLarge => "payload_too_large",
            Self::InvalidCbor => "invalid_cbor",
            Self::UnknownMessageType => "unknown_message_type",
            Self::SessionTimeout => "session_timeout",
            Self::ChecksumMismatch => "checksum_mismatch",
            Self::DiskFull => "disk_full",
            Self::StorageError => "storage_error",
            Self::PathRejected => "path_rejected",
            Self::TransferNotFound => "transfer_not_found",
            Self::StreamNotFound => "stream_not_found",
            Self::IdentityChanged => "identity_changed",
            Self::PairRequired => "pair_required",
            Self::RateLimited => "rate_limited",
            Self::InternalError => "internal_error",
            Self::ManifestTooLarge => "manifest_too_large",
            Self::Unknown(s) => s.as_str(),
        }
    }

    pub fn from_wire_str(s: &str) -> Self {
        match s {
            "unsupported_version" => Self::UnsupportedVersion,
            "invalid_frame" => Self::InvalidFrame,
            "payload_too_large" => Self::PayloadTooLarge,
            "invalid_cbor" => Self::InvalidCbor,
            "unknown_message_type" => Self::UnknownMessageType,
            "session_timeout" => Self::SessionTimeout,
            "checksum_mismatch" => Self::ChecksumMismatch,
            "disk_full" => Self::DiskFull,
            "storage_error" => Self::StorageError,
            "path_rejected" => Self::PathRejected,
            "transfer_not_found" => Self::TransferNotFound,
            "stream_not_found" => Self::StreamNotFound,
            "identity_changed" => Self::IdentityChanged,
            "pair_required" => Self::PairRequired,
            "rate_limited" => Self::RateLimited,
            "internal_error" => Self::InternalError,
            "manifest_too_large" => Self::ManifestTooLarge,
            other => Self::Unknown(other.to_string()),
        }
    }
}

impl fmt::Display for ErrorCode {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", self.as_str())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_is_fatal() {
        assert!(ErrorCode::UnsupportedVersion.is_fatal());
        assert!(ErrorCode::InvalidFrame.is_fatal());
        assert!(ErrorCode::PayloadTooLarge.is_fatal());
        assert!(ErrorCode::InvalidCbor.is_fatal());
        assert!(ErrorCode::SessionTimeout.is_fatal());
        assert!(ErrorCode::IdentityChanged.is_fatal());
        assert!(ErrorCode::InternalError.is_fatal());

        assert!(!ErrorCode::ChecksumMismatch.is_fatal());
        assert!(!ErrorCode::UnknownMessageType.is_fatal());
        assert!(!ErrorCode::Unknown("custom_nonfatal".into()).is_fatal());
    }

    #[test]
    fn test_is_retryable() {
        assert!(ErrorCode::ChecksumMismatch.is_retryable());
        assert!(ErrorCode::RateLimited.is_retryable());

        assert!(!ErrorCode::InternalError.is_retryable());
        assert!(!ErrorCode::Unknown("custom_error".into()).is_retryable());
    }

    #[test]
    fn test_as_str_and_from_str() {
        let codes = vec![
            ErrorCode::UnsupportedVersion,
            ErrorCode::InvalidFrame,
            ErrorCode::PayloadTooLarge,
            ErrorCode::InvalidCbor,
            ErrorCode::UnknownMessageType,
            ErrorCode::SessionTimeout,
            ErrorCode::ChecksumMismatch,
            ErrorCode::DiskFull,
            ErrorCode::StorageError,
            ErrorCode::PathRejected,
            ErrorCode::TransferNotFound,
            ErrorCode::StreamNotFound,
            ErrorCode::IdentityChanged,
            ErrorCode::PairRequired,
            ErrorCode::RateLimited,
            ErrorCode::InternalError,
            ErrorCode::ManifestTooLarge,
        ];

        for code in codes {
            let s = code.as_str();
            assert_eq!(ErrorCode::from_wire_str(s), code);
            assert_eq!(code.to_string(), s);
        }

        // Unknown forward-compatible codes
        let unknown = ErrorCode::from_wire_str("future_quota_error");
        assert_eq!(
            unknown,
            ErrorCode::Unknown("future_quota_error".to_string())
        );
        assert_eq!(unknown.as_str(), "future_quota_error");
        assert_eq!(unknown.to_string(), "future_quota_error");
    }
}
