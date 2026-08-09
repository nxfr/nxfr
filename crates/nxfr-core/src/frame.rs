use nxfr_common::error::FrameError;
use nxfr_common::limits;
use nxfr_common::types::{SessionId, StreamId};

/// Frame kinds. §7.1
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
#[repr(u8)]
pub enum FrameKind {
    Control = 0x01,
    Chunk = 0x02,
    Keepalive = 0x03,
}

impl FrameKind {
    pub fn from_u8(v: u8) -> Result<Self, FrameError> {
        match v {
            0x01 => Ok(FrameKind::Control),
            0x02 => Ok(FrameKind::Chunk),
            0x03 => Ok(FrameKind::Keepalive),
            _ => Err(FrameError::UnknownKind(v)),
        }
    }

    pub fn as_str(&self) -> &'static str {
        match self {
            FrameKind::Control => "CONTROL",
            FrameKind::Chunk => "CHUNK",
            FrameKind::Keepalive => "KEEPALIVE",
        }
    }
}

/// Flag bits per §7, §3.1
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct FrameFlags(pub u16);

impl FrameFlags {
    pub fn is_last_chunk(&self) -> bool {
        (self.0 & 0x0001) != 0
    }

    pub fn is_pong(&self) -> bool {
        (self.0 & 0x0001) != 0
    }

    pub fn empty() -> Self {
        Self(0x0000)
    }

    pub fn last_chunk() -> Self {
        Self(0x0001)
    }

    pub fn pong() -> Self {
        Self(0x0001)
    }
}

/// 28-byte frame header. §7
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct FrameHeader {
    pub kind: FrameKind,
    pub flags: FrameFlags,
    pub session_id: SessionId,
    pub stream_id: StreamId,
    pub message_id: u64,
    pub payload_len: u32,
}

impl FrameHeader {
    pub fn parse(buf: &[u8]) -> Result<Self, FrameError> {
        if buf.len() < limits::FRAME_HEADER_SIZE {
            return Err(FrameError::IncompleteHeader(buf.len()));
        }

        if buf[0..4] != limits::FRAME_MAGIC {
            return Err(FrameError::InvalidMagic);
        }

        if buf[4] != limits::FRAME_VERSION {
            return Err(FrameError::UnsupportedVersion(buf[4]));
        }

        let kind = FrameKind::from_u8(buf[5])?;

        let mut flags_bytes = [0u8; 2];
        flags_bytes.copy_from_slice(&buf[6..8]);
        let flags = FrameFlags(u16::from_be_bytes(flags_bytes));

        let mut session_id_bytes = [0u8; 4];
        session_id_bytes.copy_from_slice(&buf[8..12]);
        let session_id = u32::from_be_bytes(session_id_bytes);

        let mut stream_id_bytes = [0u8; 4];
        stream_id_bytes.copy_from_slice(&buf[12..16]);
        let stream_id = u32::from_be_bytes(stream_id_bytes);

        let mut message_id_bytes = [0u8; 8];
        message_id_bytes.copy_from_slice(&buf[16..24]);
        let message_id = u64::from_be_bytes(message_id_bytes);

        let mut payload_len_bytes = [0u8; 4];
        payload_len_bytes.copy_from_slice(&buf[24..28]);
        let payload_len = u32::from_be_bytes(payload_len_bytes);

        let header = FrameHeader {
            kind,
            flags,
            session_id,
            stream_id,
            message_id,
            payload_len,
        };

        header.validate_payload_len()?;

        Ok(header)
    }

    pub fn serialize(&self) -> [u8; 28] {
        let mut buf = [0u8; 28];
        buf[0..4].copy_from_slice(&limits::FRAME_MAGIC);
        buf[4] = limits::FRAME_VERSION;
        buf[5] = self.kind as u8;
        buf[6..8].copy_from_slice(&self.flags.0.to_be_bytes());
        buf[8..12].copy_from_slice(&self.session_id.to_be_bytes());
        buf[12..16].copy_from_slice(&self.stream_id.to_be_bytes());
        buf[16..24].copy_from_slice(&self.message_id.to_be_bytes());
        buf[24..28].copy_from_slice(&self.payload_len.to_be_bytes());
        buf
    }

    pub fn validate_payload_len(&self) -> Result<(), FrameError> {
        match self.kind {
            FrameKind::Control => {
                if self.payload_len > limits::CONTROL_PAYLOAD_MAX {
                    return Err(FrameError::PayloadTooLarge {
                        kind: self.kind.as_str(),
                        size: self.payload_len,
                        max: limits::CONTROL_PAYLOAD_MAX,
                    });
                }
            }
            FrameKind::Chunk => {
                if self.payload_len < limits::CHUNK_PAYLOAD_MIN {
                    return Err(FrameError::ChunkPayloadTooSmall(self.payload_len));
                }
                if self.payload_len > limits::CHUNK_PAYLOAD_MAX {
                    return Err(FrameError::PayloadTooLarge {
                        kind: self.kind.as_str(),
                        size: self.payload_len,
                        max: limits::CHUNK_PAYLOAD_MAX,
                    });
                }
            }
            FrameKind::Keepalive => {
                if self.payload_len != 0 && self.payload_len != limits::KEEPALIVE_PAYLOAD_MAX {
                    return Err(FrameError::KeepalivePayloadInvalid(self.payload_len));
                }
            }
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_roundtrip() {
        let header = FrameHeader {
            kind: FrameKind::Chunk,
            flags: FrameFlags::last_chunk(),
            session_id: 12345,
            stream_id: 67890,
            message_id: 42,
            payload_len: 1024,
        };

        let bytes = header.serialize();
        let parsed = FrameHeader::parse(&bytes).unwrap();
        assert_eq!(header, parsed);
        assert!(parsed.flags.is_last_chunk());
    }

    #[test]
    fn test_roundtrip_reserved_flags() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags(0xFFFF),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 0,
        };

        let bytes = header.serialize();
        let parsed = FrameHeader::parse(&bytes).unwrap();
        assert_eq!(header.flags.0, parsed.flags.0);
    }

    #[test]
    fn test_bad_magic() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 0,
        };
        let mut bytes = header.serialize();
        bytes[0] = b'X';
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::InvalidMagic)
        ));
    }

    #[test]
    fn test_bad_version() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 0,
        };
        let mut bytes = header.serialize();
        bytes[4] = 2;
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::UnsupportedVersion(2))
        ));
    }

    #[test]
    fn test_bad_kind() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 0,
        };

        let mut bytes = header.serialize();
        for bad_kind in [0x00, 0x04, 0xFF] {
            bytes[5] = bad_kind;
            assert!(
                matches!(FrameHeader::parse(&bytes), Err(FrameError::UnknownKind(k)) if k == bad_kind)
            );
        }
    }

    #[test]
    fn test_payload_too_large_control() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: limits::CONTROL_PAYLOAD_MAX + 1,
        };
        let bytes = header.serialize();
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::PayloadTooLarge { .. })
        ));
    }

    #[test]
    fn test_payload_too_large_chunk() {
        let header = FrameHeader {
            kind: FrameKind::Chunk,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: limits::CHUNK_PAYLOAD_MAX + 1,
        };
        let bytes = header.serialize();
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::PayloadTooLarge { .. })
        ));
    }

    #[test]
    fn test_chunk_too_small() {
        let header = FrameHeader {
            kind: FrameKind::Chunk,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 40,
        };
        let bytes = header.serialize();
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::ChunkPayloadTooSmall(40))
        ));
    }

    #[test]
    fn test_keepalive_bad_size() {
        let header = FrameHeader {
            kind: FrameKind::Keepalive,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 4,
        };
        let bytes = header.serialize();
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::KeepalivePayloadInvalid(4))
        ));
    }

    #[test]
    fn test_keepalive_valid_size() {
        let mut header = FrameHeader {
            kind: FrameKind::Keepalive,
            flags: FrameFlags::empty(),
            session_id: 0,
            stream_id: 0,
            message_id: 0,
            payload_len: 0,
        };
        assert!(FrameHeader::parse(&header.serialize()).is_ok());

        header.payload_len = 8;
        assert!(FrameHeader::parse(&header.serialize()).is_ok());
    }

    #[test]
    fn test_short_buffer() {
        let bytes = [0u8; 27];
        assert!(matches!(
            FrameHeader::parse(&bytes),
            Err(FrameError::IncompleteHeader(27))
        ));
    }
}
