//! TCP listener — accepts incoming TLS connections and spawns handlers.

use crate::handler;
use crate::DaemonState;
use log::{info, warn};
use nxfr_transport::tls;
use std::sync::Arc;
use tokio::net::TcpListener;
use tokio::sync::Semaphore;
use tokio_rustls::TlsAcceptor;

const LISTEN_PORT: u16 = 17394;

/// Maximum number of concurrent TLS connections.
const MAX_CONCURRENT_CONNECTIONS: usize = 200;

/// Backoff delay after accept() errors to prevent CPU spin.
const ACCEPT_ERROR_BACKOFF_MS: u64 = 50;

/// TLS handshake timeout — drop connections that take too long.
const TLS_HANDSHAKE_TIMEOUT_SECS: u64 = 10;

pub async fn run_listener(state: Arc<DaemonState>) -> Result<(), Box<dyn std::error::Error>> {
    run_listener_inner(state, format!("0.0.0.0:{LISTEN_PORT}"), true).await
}

/// Run the listener on a specific port (for testing).
pub async fn run_listener_on_port(
    state: Arc<DaemonState>,
    port: u16,
) -> Result<(), Box<dyn std::error::Error>> {
    run_listener_inner(state, format!("127.0.0.1:{port}"), false).await
}

/// Run the listener on an OS-assigned ephemeral port (for test concurrency).
pub async fn run_listener_dynamic(
    state: Arc<DaemonState>,
) -> Result<(u16, tokio::task::JoinHandle<()>), Box<dyn std::error::Error + Send + Sync>> {
    let server_config =
        tls::build_server_config(state.identity.private_key(), state.identity.certificate())?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));
    let semaphore = Arc::new(Semaphore::new(MAX_CONCURRENT_CONNECTIONS));

    let listener = TcpListener::bind("127.0.0.1:0").await?;
    let port = listener.local_addr()?.port();
    info!("TCP listener dynamically bound on 127.0.0.1:{port}");

    let state_for_shutdown = Arc::clone(&state);
    let handle = tokio::spawn(async move {
        loop {
            tokio::select! {
                result = listener.accept() => {
                    match result {
                        Ok((tcp_stream, addr)) => {
                            let acceptor = acceptor.clone();
                            let state = Arc::clone(&state);
                            let sem = semaphore.clone();
                            tokio::spawn(async move {
                                let _permit = match sem.acquire().await {
                                    Ok(p) => p,
                                    Err(_) => return,
                                };
                                match tokio::time::timeout(
                                    std::time::Duration::from_secs(TLS_HANDSHAKE_TIMEOUT_SECS),
                                    acceptor.accept(tcp_stream),
                                )
                                .await
                                {
                                    Ok(Ok(tls_stream)) => {
                                        if let Err(e) = handler::handle_incoming(state, tls_stream, addr).await {
                                            log::debug!("Connection handler error for {addr}: {e}");
                                        }
                                    }
                                    Ok(Err(e)) => log::warn!("TLS handshake failed from {addr}: {e}"),
                                    Err(_) => log::warn!("TLS handshake timeout from {addr}"),
                                }
                            });
                        }
                        Err(e) => {
                            log::warn!("TCP accept error: {e}");
                            tokio::time::sleep(std::time::Duration::from_millis(ACCEPT_ERROR_BACKOFF_MS)).await;
                        }
                    }
                }
                _ = state_for_shutdown.shutdown.notified() => {
                    break;
                }
            }
        }
    });

    Ok((port, handle))
}

async fn run_listener_inner(
    state: Arc<DaemonState>,
    bind_addr: String,
    try_ipv6: bool,
) -> Result<(), Box<dyn std::error::Error>> {
    let server_config =
        tls::build_server_config(state.identity.private_key(), state.identity.certificate())?;
    let acceptor = TlsAcceptor::from(Arc::new(server_config));
    let semaphore = Arc::new(Semaphore::new(MAX_CONCURRENT_CONNECTIONS));

    let listener = TcpListener::bind(&bind_addr).await?;
    info!("TCP listener bound on {bind_addr}");

    if try_ipv6 {
        // Also try IPv6 (non-fatal if it fails).
        let listener6 = TcpListener::bind(format!("[::]:{LISTEN_PORT}")).await;
        if listener6.is_ok() {
            info!("TCP listener also bound on [::]:{LISTEN_PORT}");
        }
    }

    loop {
        tokio::select! {
            result = listener.accept() => {
                match result {
                    Ok((tcp_stream, addr)) => {
                        info!("Incoming TCP connection from {addr}");
                        let acceptor = acceptor.clone();
                        let state = Arc::clone(&state);
                        let sem = semaphore.clone();
                        tokio::spawn(async move {
                            // Bound concurrent connections.
                            let _permit = match sem.acquire().await {
                                Ok(p) => p,
                                Err(_) => {
                                    warn!("Connection semaphore closed, dropping {addr}");
                                    return;
                                }
                            };
                            // Timeout TLS handshakes to prevent Slowloris.
                            match tokio::time::timeout(
                                std::time::Duration::from_secs(TLS_HANDSHAKE_TIMEOUT_SECS),
                                acceptor.accept(tcp_stream),
                            )
                            .await
                            {
                                Ok(Ok(tls_stream)) => {
                                    if let Err(e) = handler::handle_incoming(state, tls_stream, addr).await {
                                        warn!("Connection handler error for {addr}: {e}");
                                    }
                                }
                                Ok(Err(e)) => {
                                    warn!("TLS handshake failed from {addr}: {e}");
                                }
                                Err(_) => {
                                    warn!("TLS handshake timeout ({TLS_HANDSHAKE_TIMEOUT_SECS}s) from {addr}");
                                }
                            }
                        });
                    }
                    Err(e) => {
                        warn!("TCP accept error: {e} — backing off {ACCEPT_ERROR_BACKOFF_MS}ms");
                        tokio::time::sleep(std::time::Duration::from_millis(ACCEPT_ERROR_BACKOFF_MS)).await;
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
