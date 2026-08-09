use nxfr_common::error::FrameError;
use sha2::{Digest, Sha256};

/// Parsed chunk payload per §7.2.2 / WIRE_FORMAT §5.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChunkPayload {
    /// Byte offset within the file.
    pub offset: u64,
    /// SHA-256 of the data portion only.
    pub chunk_hash: [u8; 32],
    /// Raw file data.
    pub data: Vec<u8>,
}

impl ChunkPayload {
    pub fn parse(payload: &[u8]) -> Result<Self, FrameError> {
        if payload.len() < 41 {
            return Err(FrameError::ChunkPayloadTooSmall(payload.len() as u32));
        }

        let mut offset_bytes = [0u8; 8];
        offset_bytes.copy_from_slice(&payload[0..8]);
        let offset = u64::from_be_bytes(offset_bytes);

        let mut chunk_hash = [0u8; 32];
        chunk_hash.copy_from_slice(&payload[8..40]);

        let data = payload[40..].to_vec();

        Ok(Self {
            offset,
            chunk_hash,
            data,
        })
    }

    pub fn serialize(&self) -> Vec<u8> {
        let mut result = Vec::with_capacity(40 + self.data.len());
        result.extend_from_slice(&self.offset.to_be_bytes());
        result.extend_from_slice(&self.chunk_hash);
        result.extend_from_slice(&self.data);
        result
    }

    pub fn verify_hash(&self) -> bool {
        let mut hasher = Sha256::new();
        hasher.update(&self.data);
        let result = hasher.finalize();
        self.chunk_hash == result.as_slice()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_roundtrip() {
        let payload = ChunkPayload {
            offset: 123456789,
            chunk_hash: [0x42; 32],
            data: vec![1, 2, 3, 4, 5],
        };
        let serialized = payload.serialize();
        let parsed = ChunkPayload::parse(&serialized).unwrap();
        assert_eq!(payload, parsed);
    }

    #[test]
    fn test_hash_verify() {
        let data = b"hello world".to_vec();
        let mut hasher = Sha256::new();
        hasher.update(&data);
        let mut hash = [0u8; 32];
        hash.copy_from_slice(&hasher.finalize());

        let mut payload = ChunkPayload {
            offset: 0,
            chunk_hash: hash,
            data,
        };
        assert!(payload.verify_hash());

        payload.data.push(0);
        assert!(!payload.verify_hash());
    }

    #[test]
    fn test_too_small() {
        let data = vec![0; 40];
        assert!(matches!(
            ChunkPayload::parse(&data),
            Err(FrameError::ChunkPayloadTooSmall(_))
        ));
    }

    #[test]
    fn test_golden_vector() {
        let data: Vec<u8> = (0x00..=0x0f).collect();
        let hash_hex = "be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991";
        let mut hash = [0u8; 32];
        for i in 0..32 {
            hash[i] = u8::from_str_radix(&hash_hex[i * 2..i * 2 + 2], 16).unwrap();
        }

        let payload = ChunkPayload {
            offset: 0,
            chunk_hash: hash,
            data,
        };

        assert!(payload.verify_hash());
    }
}
