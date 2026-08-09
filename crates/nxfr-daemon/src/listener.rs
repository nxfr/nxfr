//! TCP listener — accepts incoming TLS connections and spawns handlers.

use crate::handler;
use crate::DaemonState;
use log::{error, info, warn};
use nxfr_transport::tls;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio_rustls::TlsAcceptor;

const LISTEN_PORT: u16 = 17394;

pub async fn run_listener(state: Arc<DaemonState>) -> Result<(), Box<dyn std::error::Error>> {
    let server_config =
        tls::build_server_config(state.identity.private_key(), state.identity.certificate())?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));

    let listener = TcpListener::bind(format!("0.0.0.0:{LISTEN_PORT}")).await?;
    info!("TCP listener bound on 0.0.0.0:{LISTEN_PORT}");

    // Also try IPv6 (non-fatal if it fails).
    let listener6 = TcpListener::bind(format!("[::]:{LISTEN_PORT}")).await;
    if listener6.is_ok() {
        info!("TCP listener also bound on [::]:{LISTEN_PORT}");
    }

    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((tcp_stream, addr)) => {
                        info!("Incoming TCP connection from {addr}");
                        let acceptor = acceptor.clone();
                        let state = Arc::clone(&state);
                        tokio::spawn(async move {
                            match acceptor.accept(tcp_stream).await {
                                Ok(tls_stream) => {
                                    if let Err(e) = handler::handle_incoming(state, tls_stream, addr).await {
                                        warn!("Connection handler error for {addr}: {e}");
                                    }
                                }
                                Err(e) => {
                                    warn!("TLS handshake failed from {addr}: {e}");
                                }
                            }
                        });
                    }
                    Err(e) => {
                        error!("TCP accept error: {e}");
                    }
                }
            }
            _ = state.shutdown.notified() => {
                info!("Listener shutting down");
                break;
            }
        }
    }

    Ok(())
}

/// Run the listener on a specific port (for testing).
pub async fn run_listener_on_port(
    state: Arc<DaemonState>,
    port: u16,
) -> Result<(), Box<dyn std::error::Error>> {
    let server_config =
        tls::build_server_config(state.identity.private_key(), state.identity.certificate())?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));

    let listener = TcpListener::bind(format!("127.0.0.1:{port}")).await?;
    info!("TCP listener bound on 127.0.0.1:{port}");

    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((tcp_stream, addr)) => {
                        info!("Incoming TCP connection from {addr}");
                        let acceptor = acceptor.clone();
                        let state = Arc::clone(&state);
                        tokio::spawn(async move {
                            match acceptor.accept(tcp_stream).await {
                                Ok(tls_stream) => {
                                    if let Err(e) = handler::handle_incoming(state, tls_stream, addr).await {
                                        warn!("Connection handler error for {addr}: {e}");
                                    }
                                }
                                Err(e) => {
                                    warn!("TLS handshake failed from {addr}: {e}");
                                }
                            }
                        });
                    }
                    Err(e) => {
                        error!("TCP accept error: {e}");
                    }
                }
            }
            _ = state.shutdown.notified() => {
                info!("Listener shutting down");
                break;
            }
        }
    }

    Ok(())
}
