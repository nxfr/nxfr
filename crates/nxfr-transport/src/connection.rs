//! NXFR connection abstraction over TLS streams.

use futures_util::{SinkExt, StreamExt};
use nxfr_core::codec;
use nxfr_core::frame::{FrameFlags, FrameHeader, FrameKind};
use nxfr_core::messages::ControlMessage;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio_util::codec::Framed;

use crate::framing::{NxfrCodec, NxfrFrame};

/// An NXFR connection wrapping a TLS stream with frame-level send/recv.
pub struct NxfrConnection<S> {
    framed: Framed<S, NxfrCodec>,
    next_message_id: u64,
}

impl<S: AsyncRead + AsyncWrite + Unpin> NxfrConnection<S> {
    /// Create a new connection over the given stream.
    pub fn new(stream: S) -> Self {
        Self {
            framed: Framed::new(stream, NxfrCodec),
            next_message_id: 1,
        }
    }

    /// Send a CONTROL frame with the given message.
    pub async fn send_control(
        &mut self,
        session_id: u32,
        stream_id: u32,
        msg: &ControlMessage,
    ) -> Result<u64, std::io::Error> {
        let payload = codec::encode_control(msg).map_err(|e| {
            std::io::Error::new(std::io::ErrorKind::InvalidData, format!("encode: {e}"))
        })?;

        let msg_id = self.next_message_id;
        let header = FrameHeader {
            kind: FrameKind::Control,
            flags: FrameFlags(0),
            session_id,
            stream_id,
            message_id: msg_id,
            payload_len: payload.len() as u32,
        };

        self.framed.send((header, payload)).await?;
        self.next_message_id += 1;
        Ok(msg_id)
    }

    /// Send a CHUNK frame.
    pub async fn send_chunk(
        &mut self,
        session_id: u32,
        stream_id: u32,
        flags: u16,
        payload: Vec<u8>,
    ) -> Result<u64, std::io::Error> {
        let msg_id = self.next_message_id;
        let header = FrameHeader {
            kind: FrameKind::Chunk,
            flags: FrameFlags(flags),
            session_id,
            stream_id,
            message_id: msg_id,
            payload_len: payload.len() as u32,
        };

        self.framed.send((header, payload)).await?;
        self.next_message_id += 1;
        Ok(msg_id)
    }

    /// Send a raw frame (for testing).
    pub async fn send_raw(&mut self, frame: NxfrFrame) -> Result<(), std::io::Error> {
        self.framed.send(frame).await
    }

    /// Receive the next frame.
    pub async fn recv_frame(&mut self) -> Result<NxfrFrame, std::io::Error> {
        match self.framed.next().await {
            Some(Ok(frame)) => Ok(frame),
            Some(Err(e)) => Err(e),
            None => Err(std::io::Error::new(
                std::io::ErrorKind::UnexpectedEof,
                "connection closed",
            )),
        }
    }

    /// Get the current message_id counter value (next to be used).
    pub fn next_message_id(&self) -> u64 {
        self.next_message_id
    }

    /// Get a reference to the inner stream (for peer_certificates etc).
    pub fn get_ref(&self) -> &S {
        self.framed.get_ref()
    }
}
