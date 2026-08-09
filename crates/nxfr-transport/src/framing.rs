//! NXFR frame codec for tokio-util `Framed`.
//!
//! Decodes/encodes the 28-byte frame header + variable payload per WIRE_FORMAT §3.

use bytes::{BufMut, BytesMut};
use nxfr_core::frame::FrameHeader;
use tokio_util::codec::{Decoder, Encoder};

/// Codec for NXFR frames over a byte stream.
///
/// Decodes: reads 28-byte header, then `payload_len` bytes → `(FrameHeader, Vec<u8>)`
/// Encodes: serializes `(FrameHeader, Vec<u8>)` → header + payload bytes
pub struct NxfrCodec;

/// A decoded NXFR frame: header + payload bytes.
pub type NxfrFrame = (FrameHeader, Vec<u8>);

impl Decoder for NxfrCodec {
    type Item = NxfrFrame;
    type Error = std::io::Error;

    fn decode(&mut self, src: &mut BytesMut) -> Result<Option<Self::Item>, Self::Error> {
        // Need at least 28 bytes for the header.
        if src.len() < 28 {
            return Ok(None);
        }

        // Peek at the header to get payload_len without consuming.
        let header = FrameHeader::parse(&src[..28]).map_err(|e| {
            std::io::Error::new(
                std::io::ErrorKind::InvalidData,
                format!("frame header: {e}"),
            )
        })?;

        let total_len = 28 + header.payload_len as usize;
        if src.len() < total_len {
            // Reserve space for the rest.
            src.reserve(total_len - src.len());
            return Ok(None);
        }

        // Consume the frame.
        let _ = src.split_to(28); // header bytes (already parsed)
        let payload = src.split_to(header.payload_len as usize).to_vec();

        Ok(Some((header, payload)))
    }
}

impl Encoder<NxfrFrame> for NxfrCodec {
    type Error = std::io::Error;

    fn encode(&mut self, item: NxfrFrame, dst: &mut BytesMut) -> Result<(), Self::Error> {
        let (header, payload) = item;
        let header_bytes = header.serialize();
        dst.reserve(28 + payload.len());
        dst.put_slice(&header_bytes);
        dst.put_slice(&payload);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use nxfr_core::frame::FrameKind;

    #[test]
    fn test_codec_roundtrip() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: nxfr_core::frame::FrameFlags(0),
            session_id: 0x1234,
            stream_id: 0,
            message_id: 1,
            payload_len: 5,
        };
        let payload = vec![0x01, 0x02, 0x03, 0x04, 0x05];

        // Encode
        let mut buf = BytesMut::new();
        let mut codec = NxfrCodec;
        codec
            .encode((header.clone(), payload.clone()), &mut buf)
            .unwrap();

        // Decode
        let decoded = codec.decode(&mut buf).unwrap().unwrap();
        assert_eq!(decoded.0, header);
        assert_eq!(decoded.1, payload);
    }

    #[test]
    fn test_codec_partial_read() {
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: nxfr_core::frame::FrameFlags(0),
            session_id: 0,
            stream_id: 0,
            message_id: 1,
            payload_len: 10,
        };
        let payload = vec![0u8; 10];

        let mut buf = BytesMut::new();
        let mut codec = NxfrCodec;
        codec
            .encode((header.clone(), payload.clone()), &mut buf)
            .unwrap();

        // Only give partial data — should return None.
        let mut partial = buf.split_to(20);
        assert!(codec.decode(&mut partial).unwrap().is_none());
    }

    #[test]
    fn test_codec_bad_magic() {
        let mut buf = BytesMut::from(&[0xDE, 0xAD, 0xBE, 0xEF][..]);
        buf.extend_from_slice(&[0u8; 24]); // fill rest of header
        let mut codec = NxfrCodec;
        let result = codec.decode(&mut buf);
        assert!(result.is_err());
    }
}
