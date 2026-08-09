//! # nxfr-transport
//!
//! TCP + TLS 1.3 transport layer for the NXFR protocol.
//!
//! Provides:
//! - TLS configuration (mTLS, ALPN "nxfr/0", P-256, self-signed)
//! - Frame codec (tokio-util Framed) for NXFR wire protocol
//! - Connection abstraction over TLS streams

pub mod connection;
pub mod framing;
pub mod tls;
