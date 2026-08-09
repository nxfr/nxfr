use nxfr_common::error::FrameError;

/// Keepalive payload per §7.2.3 / WIRE_FORMAT §6.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum KeepalivePayload {
    /// Liveness check only (0-byte payload).
    Empty,
    /// RTT measurement (8-byte timestamp in ms since epoch).
    Timestamp(u64),
}

impl KeepalivePayload {
    pub fn parse(payload: &[u8]) -> Result<Self, FrameError> {
        match payload.len() {
            0 => Ok(Self::Empty),
            8 => {
                let mut ts_bytes = [0u8; 8];
                ts_bytes.copy_from_slice(payload);
                Ok(Self::Timestamp(u64::from_be_bytes(ts_bytes)))
            }
            _ => Err(FrameError::KeepalivePayloadInvalid(payload.len() as u32)),
        }
    }

    pub fn serialize(&self) -> Vec<u8> {
        match self {
            Self::Empty => vec![],
            Self::Timestamp(ts) => ts.to_be_bytes().to_vec(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_roundtrip_empty() {
        let payload = KeepalivePayload::Empty;
        let serialized = payload.serialize();
        let parsed = KeepalivePayload::parse(&serialized).unwrap();
        assert_eq!(payload, parsed);
    }

    #[test]
    fn test_roundtrip_timestamp() {
        let payload = KeepalivePayload::Timestamp(123456789);
        let serialized = payload.serialize();
        let parsed = KeepalivePayload::parse(&serialized).unwrap();
        assert_eq!(payload, parsed);
    }

    #[test]
    fn test_invalid_sizes() {
        for size in [1, 4, 9, 16] {
            let data = vec![0; size];
            assert!(matches!(
                KeepalivePayload::parse(&data),
                Err(FrameError::KeepalivePayloadInvalid(_))
            ));
        }
    }

    #[test]
    fn test_golden_vector() {
        let timestamp = 1720000000000u64;
        let expected_bytes = [0x00, 0x00, 0x01, 0x90, 0x77, 0xfd, 0x30, 0x00];
        let payload = KeepalivePayload::Timestamp(timestamp);
        assert_eq!(payload.serialize(), expected_bytes.to_vec());
        assert_eq!(KeepalivePayload::parse(&expected_bytes).unwrap(), payload);
    }
}
