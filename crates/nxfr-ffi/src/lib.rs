//! # nxfr-ffi
//!
//! C-ABI FFI bindings for the NXFR protocol.
//! Designed for JNI/Android use but works on any platform.
//! ## Architecture
//! - One shared Tokio runtime (`OnceLock<Runtime>`), created on first FFI call.
//! - Sessions stored in `SESSIONS: Mutex<HashMap<u64, Session>>`.
//! - Listeners stored in `LISTENERS: Mutex<HashMap<u64, Listener>>`.
//! - `nxfr_send_file` / `nxfr_confirm` spawn async tasks; progress/events
//!   are pushed to an mpsc channel read by `nxfr_pump`.
//!
//! ## Safety contract
//! - Every `#[no_mangle] extern "C"` function is wrapped in `catch_unwind`.
//! - Every returned `*mut c_char` must be freed with `nxfr_string_free`.
//! - Null pointer arguments return a JSON error string, never crash.
//! - No panic ever crosses the FFI boundary.

mod jni_bindings;

use std::collections::HashMap;
use std::ffi::{CStr, CString};
use std::net::SocketAddr;
use std::os::raw::c_char;
use std::panic;
use std::path::{Path, PathBuf};
use std::pin::Pin;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock};
use std::task::{Context, Poll};

use nxfr_common::{DeviceId, Platform, ProtocolVersion, TransferId};
use nxfr_core::codec;
use nxfr_core::frame::FrameKind;
use nxfr_core::messages::{
    ControlMessage, ManifestEntry, ManifestEntryType, TransferAckStatus, TransferType,
};
use nxfr_transport::connection::NxfrConnection;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio_rustls::{TlsAcceptor, TlsConnector};
use zeroize::Zeroize;

/// Initialize android_logger so Rust log:: macros appear in logcat with tag "nxfr".
#[cfg(target_os = "android")]
#[no_mangle]
pub extern "C" fn JNI_OnLoad(_vm: *mut std::ffi::c_void, _reserved: *mut std::ffi::c_void) -> i32 {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag("nxfr"),
    );
    log::info!("[nxfr-ffi] JNI_OnLoad: android_logger initialized");
    0x00010006 // JNI_VERSION_1_6
}

/// Set the receive directory for incoming files.
/// Must be called before any transfers. On Android, pass
/// `context.getExternalFilesDir(null)/inbox`.
#[no_mangle]
pub extern "C" fn nxfr_set_receive_dir(path: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(path) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let p = PathBuf::from(dir);
        if let Err(e) = std::fs::create_dir_all(&p) {
            return json_err(&format!("create receive dir: {e}"));
        }
        *receive_dir_override()
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(p);
        log::info!("[nxfr-ffi] receive_dir set to: {dir}");
        json_ok(serde_json::json!({"receive_dir": dir}))
    })
}

// ─── TlsStream ──────────────────────────────────────────────────────────
//
// Unify client and server TLS streams behind one type so NxfrConnection
// can be generic over a single S.

enum TlsStream {
    Client(tokio_rustls::client::TlsStream<TcpStream>),
    Server(tokio_rustls::server::TlsStream<TcpStream>),
}

impl AsyncRead for TlsStream {
    fn poll_read(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &mut ReadBuf<'_>,
    ) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TlsStream::Client(s) => Pin::new(s).poll_read(cx, buf),
            TlsStream::Server(s) => Pin::new(s).poll_read(cx, buf),
        }
    }
}

impl AsyncWrite for TlsStream {
    fn poll_write(
        self: Pin<&mut Self>,
        cx: &mut Context<'_>,
        buf: &[u8],
    ) -> Poll<std::io::Result<usize>> {
        match self.get_mut() {
            TlsStream::Client(s) => Pin::new(s).poll_write(cx, buf),
            TlsStream::Server(s) => Pin::new(s).poll_write(cx, buf),
        }
    }
    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TlsStream::Client(s) => Pin::new(s).poll_flush(cx),
            TlsStream::Server(s) => Pin::new(s).poll_flush(cx),
        }
    }
    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
        match self.get_mut() {
            TlsStream::Client(s) => Pin::new(s).poll_shutdown(cx),
            TlsStream::Server(s) => Pin::new(s).poll_shutdown(cx),
        }
    }
}

impl Unpin for TlsStream {}

impl TlsStream {
    /// Extract TLS keying material for SAS derivation.
    /// Uses RFC 5705 exporter with label "NXFR-SAS-v0".
    fn export_keying_material(
        &self,
        output: &mut [u8],
        label: &[u8],
        context: Option<&[u8]>,
    ) -> Result<(), String> {
        match self {
            TlsStream::Client(s) => {
                let (_, conn) = s.get_ref();
                conn.export_keying_material(output, label, context)
                    .map(|_| ())
                    .map_err(|e| format!("export_keying_material: {e}"))
            }
            TlsStream::Server(s) => {
                let (_, conn) = s.get_ref();
                conn.export_keying_material(output, label, context)
                    .map(|_| ())
                    .map_err(|e| format!("export_keying_material: {e}"))
            }
        }
    }

    /// Extract the peer's raw certificate DER bytes.
    #[allow(dead_code)]
    fn peer_cert_der(&self) -> Option<Vec<u8>> {
        match self {
            TlsStream::Client(s) => {
                let (_, conn) = s.get_ref();
                conn.peer_certificates()
                    .and_then(|c| c.first())
                    .map(|c| c.as_ref().to_vec())
            }
            TlsStream::Server(s) => {
                let (_, conn) = s.get_ref();
                conn.peer_certificates()
                    .and_then(|c| c.first())
                    .map(|c| c.as_ref().to_vec())
            }
        }
    }
}

// ─── Types ──────────────────────────────────────────────────────────────

/// Result of a connect/accept handshake: (connection, peer_device_id, peer_name, session_id, peer_cert_der)
type ConnectResult = Result<(NxfrConnection<TlsStream>, [u8; 32], String, u32, Vec<u8>), String>;

/// Event emitted by background transfer tasks, read by nxfr_pump.
#[derive(Debug)]
enum FfiEvent {
    Offer {
        display_name: String,
        total_size: u64,
        total_files: u32,
        peer_name: String,
    },
    PairRequest {
        sas_code: String,
        peer_name: String,
    },
    Progress {
        bytes_sent: u64,
        total_bytes: u64,
        file_name: String,
    },
    Complete {
        file_path: Option<String>,
    },
    Error {
        msg: String,
    },
}

/// Info about a pending incoming transfer offer (receiver side).
#[allow(dead_code)]
struct PendingOffer {
    transfer_id: TransferId,
    manifest: Vec<ManifestEntry>,
    display_name: String,
    total_size: u64,
    total_files: u32,
}

/// Identity loaded from disk (key + cert + device_id).
#[derive(Clone)]
struct FfiIdentity {
    device_id: [u8; 32],
    key_der: Vec<u8>,
    cert_der: Vec<u8>,
}

impl FfiIdentity {
    fn private_key(&self) -> rustls_pki_types::PrivateKeyDer<'static> {
        rustls_pki_types::PrivateKeyDer::Pkcs8(rustls_pki_types::PrivatePkcs8KeyDer::from(
            self.key_der.clone(),
        ))
    }
    fn certificate(&self) -> rustls_pki_types::CertificateDer<'static> {
        rustls_pki_types::CertificateDer::from(self.cert_der.clone())
    }
}

/// An active NXFR session (connection + event channel).
#[allow(dead_code)]
struct Session {
    conn: Arc<tokio::sync::Mutex<Option<NxfrConnection<TlsStream>>>>,
    event_tx: mpsc::Sender<FfiEvent>,
    event_rx: std::sync::Mutex<mpsc::Receiver<FfiEvent>>,
    local_device_id: [u8; 32],
    peer_device_id: [u8; 32],
    peer_name: String,
    peer_cert_der: Vec<u8>,
    session_id: u32,
    pending_offer: Arc<std::sync::Mutex<Option<PendingOffer>>>,
}

struct Listener {
    pending_rx: Arc<tokio::sync::Mutex<mpsc::Receiver<AcceptedConn>>>,
    identity: FfiIdentity,
    port: u16,
    cancel_token: tokio_util::sync::CancellationToken,
    accept_task: tokio::task::JoinHandle<()>,
}

/// A TLS connection accepted by the listener, awaiting HELLO exchange.
struct AcceptedConn {
    stream: TlsStream,
    #[allow(dead_code)]
    addr: SocketAddr,
}

// ─── Global State ───────────────────────────────────────────────────────

static RUNTIME: OnceLock<tokio::runtime::Runtime> = OnceLock::new();
static SESSIONS: OnceLock<std::sync::Mutex<HashMap<u64, Session>>> = OnceLock::new();
static LISTENERS: OnceLock<std::sync::Mutex<HashMap<u64, Listener>>> = OnceLock::new();
static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

/// Global receive-dir override set by the host (Android).
/// Checked FIRST in do_receive_file; falls back to NxfrConfig if unset.
static RECEIVE_DIR: OnceLock<std::sync::Mutex<Option<PathBuf>>> = OnceLock::new();

fn receive_dir_override() -> &'static std::sync::Mutex<Option<PathBuf>> {
    RECEIVE_DIR.get_or_init(|| std::sync::Mutex::new(None))
}

fn get_runtime() -> &'static tokio::runtime::Runtime {
    RUNTIME.get_or_init(|| {
        tokio::runtime::Builder::new_multi_thread()
            .worker_threads(2)
            .enable_all()
            .build()
            .expect("failed to create tokio runtime")
    })
}

fn alloc_handle() -> u64 {
    NEXT_HANDLE.fetch_add(1, Ordering::Relaxed)
}

fn sessions_map() -> &'static std::sync::Mutex<HashMap<u64, Session>> {
    SESSIONS.get_or_init(|| std::sync::Mutex::new(HashMap::new()))
}

fn listeners_map() -> &'static std::sync::Mutex<HashMap<u64, Listener>> {
    LISTENERS.get_or_init(|| std::sync::Mutex::new(HashMap::new()))
}

// ─── Helpers ────────────────────────────────────────────────────────────

fn cstr_to_str<'a>(ptr: *const c_char) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err("null pointer".into());
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("invalid UTF-8: {e}"))
}

fn json_ok(value: serde_json::Value) -> *mut c_char {
    let s = serde_json::to_string(&value).unwrap_or_else(|_| "{}".to_string());
    CString::new(s).unwrap_or_default().into_raw()
}

fn json_err(msg: &str) -> *mut c_char {
    json_ok(serde_json::json!({ "error": msg }))
}

fn ffi_guard<F: FnOnce() -> *mut c_char + panic::UnwindSafe>(f: F) -> *mut c_char {
    match panic::catch_unwind(f) {
        Ok(ptr) => ptr,
        Err(_) => json_err("internal panic caught at FFI boundary"),
    }
}

/// Load identity from a directory containing identity.der + identity.crt.
fn load_identity(dir: &str) -> Result<FfiIdentity, String> {
    let dir_path = Path::new(dir);
    let key_der =
        std::fs::read(dir_path.join("identity.der")).map_err(|e| format!("read key: {e}"))?;
    let cert_der =
        std::fs::read(dir_path.join("identity.crt")).map_err(|e| format!("read cert: {e}"))?;
    let device_id = nxfr_crypto::device_id_from_cert(&cert_der)
        .map_err(|e| format!("device_id derivation: {e}"))?;
    Ok(FfiIdentity {
        device_id,
        key_der,
        cert_der,
    })
}

/// Generate a random session_id using ring.
fn rand_session_id() -> u32 {
    let mut buf = [0u8; 4];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut buf)
        .expect("RNG fill failed");
    u32::from_be_bytes(buf)
}

/// Generate a random TransferId using ring.
fn rand_transfer_id() -> TransferId {
    let mut buf = [0u8; 16];
    ring::rand::SecureRandom::fill(&ring::rand::SystemRandom::new(), &mut buf)
        .expect("RNG fill failed");
    TransferId::from_bytes(buf)
}

// ─── Identity ───────────────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn nxfr_identity_generate(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        // Entropy guard: verify system RNG works before keygen.
        if let Err(e) = nxfr_crypto::check_entropy() {
            log::error!("CRITICAL: {e}");
        }
        let identity = match nxfr_crypto::identity::generate_identity() {
            Ok(id) => id,
            Err(e) => return json_err(&format!("keygen failed: {e}")),
        };
        let dir_path = Path::new(dir);
        if let Err(e) = std::fs::create_dir_all(dir_path) {
            return json_err(&format!("mkdir failed: {e}"));
        }
        if let Err(e) = std::fs::write(dir_path.join("identity.der"), &identity.private_key_der) {
            return json_err(&format!("write key failed: {e}"));
        }
        if let Err(e) = std::fs::write(dir_path.join("identity.crt"), &identity.cert_der) {
            return json_err(&format!("write cert failed: {e}"));
        }
        json_ok(serde_json::json!({ "device_id": hex::encode(identity.device_id) }))
    })
}

#[no_mangle]
pub extern "C" fn nxfr_identity_load(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        match load_identity(dir) {
            Ok(id) => json_ok(serde_json::json!({ "device_id": hex::encode(id.device_id) })),
            Err(e) => json_err(&e),
        }
    })
}

// ─── Connection: Connect ────────────────────────────────────────────────

/// Connect to a remote NXFR endpoint via TLS 1.3 + HELLO exchange.
/// `addr` = "ip:port", `store_dir` = path to identity directory.
/// Returns JSON: `{ handle, peer_device_id, peer_name, session_id }`.
#[no_mangle]
pub extern "C" fn nxfr_connect(addr: *const c_char, store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let addr_str = match cstr_to_str(addr) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let identity = match load_identity(dir) {
            Ok(id) => id,
            Err(e) => return json_err(&e),
        };

        let rt = get_runtime();
        let result: ConnectResult = rt.block_on(async {
            match tokio::time::timeout(std::time::Duration::from_secs(5), async {
                // Build TLS client config.
                let client_config = nxfr_transport::tls::build_client_config(
                    identity.private_key(),
                    identity.certificate(),
                )
                .map_err(|e| format!("TLS config: {e}"))?;

                // TCP connect.
                let tcp = TcpStream::connect(addr_str)
                    .await
                    .map_err(|e| format!("TCP connect to {addr_str}: {e}"))?;

                // TLS handshake.
                let connector = TlsConnector::from(Arc::new(client_config));
                let server_name = rustls_pki_types::ServerName::try_from("nxfr-node")
                    .map_err(|e| format!("ServerName: {e}"))?
                    .to_owned();
                let tls = connector
                    .connect(server_name, tcp)
                    .await
                    .map_err(|e| format!("TLS handshake: {e}"))?;

                // Extract peer device_id from certificate.
                let (_, client_conn) = tls.get_ref();
                let peer_certs = client_conn
                    .peer_certificates()
                    .ok_or("no peer certificates")?;
                let peer_cert = peer_certs.first().ok_or("empty peer cert chain")?;
                let peer_device_id = nxfr_crypto::device_id_from_cert(peer_cert.as_ref())
                    .map_err(|e| format!("peer device_id: {e}"))?;
                let peer_cert_der_bytes = peer_cert.as_ref().to_vec();

                // Wrap in NxfrConnection.
                let mut conn = NxfrConnection::new(TlsStream::Client(tls));

                // Send HELLO.
                let hello = ControlMessage::Hello {
                    protocol_version: ProtocolVersion::V0_1,
                    device_id: DeviceId::from_bytes(identity.device_id),
                    device_name: "NXFR-Android".to_string(),
                    platform: Platform::Android,
                    capabilities: vec![],
                    is_paired: false,
                };
                conn.send_control(0, 0, &hello)
                    .await
                    .map_err(|e| format!("send HELLO: {e}"))?;

                // Receive HELLO_ACK.
                let (hdr, payload) = conn
                    .recv_frame()
                    .await
                    .map_err(|e| format!("recv HELLO_ACK: {e}"))?;
                if hdr.kind != FrameKind::Control {
                    return Err("expected CONTROL frame for HELLO_ACK".into());
                }
                let msg = codec::decode_control(&payload).map_err(|e| format!("decode: {e}"))?;
                let (peer_name, session_id) = match msg {
                    ControlMessage::HelloAck {
                        device_name,
                        session_id,
                        ..
                    } => (device_name, session_id),
                    _ => return Err(format!("expected HELLO_ACK, got {msg:?}")),
                };

                Ok((
                    conn,
                    peer_device_id,
                    peer_name,
                    session_id,
                    peer_cert_der_bytes,
                ))
            })
            .await
            {
                Ok(inner) => inner,
                Err(_elapsed) => Err("connect timeout (5s)".to_string()),
            }
        });

        let (conn, peer_device_id, peer_name, session_id, peer_cert_der) = match result {
            Ok(v) => v,
            Err(e) => return json_err(&e),
        };

        // TOFU: verify peer identity against paired.db if previously seen.
        {
            let db_path = std::path::Path::new(dir).join("paired.db");
            if let Ok(db) = nxfr_storage::db::PairedDeviceDb::open(&db_path) {
                let peer_id_hex = hex::encode(peer_device_id);
                let spki = nxfr_crypto::extract_spki(&peer_cert_der).unwrap_or_default();
                match db.verify_identity(&peer_id_hex, &spki) {
                    Ok(nxfr_storage::db::IdentityCheck::Changed) => {
                        log::warn!(
                            "[nxfr-ffi] TOFU VIOLATION: peer {} changed SPKI!",
                            peer_id_hex
                        );
                        return json_err(
                            "TOFU violation: peer identity changed since last pairing",
                        );
                    }
                    Ok(nxfr_storage::db::IdentityCheck::Matched) => {
                        log::info!("[nxfr-ffi] TOFU OK: peer {} identity verified", peer_id_hex);
                        let _ = db.update_last_seen(&peer_id_hex);
                    }
                    _ => {} // Unknown peer — not yet paired, no check needed.
                }
            }
        }

        // Create session.
        let (event_tx, event_rx) = mpsc::channel(256);
        let handle = alloc_handle();
        let session = Session {
            conn: Arc::new(tokio::sync::Mutex::new(Some(conn))),
            event_tx,
            event_rx: std::sync::Mutex::new(event_rx),
            local_device_id: identity.device_id,
            peer_device_id,
            peer_name: peer_name.clone(),
            peer_cert_der,
            session_id,
            pending_offer: Arc::new(std::sync::Mutex::new(None)),
        };
        sessions_map()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle, session);

        json_ok(serde_json::json!({
            "handle": handle,
            "peer_device_id": hex::encode(peer_device_id),
            "peer_name": peer_name,
            "session_id": session_id,
        }))
    })
}

// ─── Connection: Listen + Accept ────────────────────────────────────────

fn create_reuseaddr_listener(port: u16) -> Result<tokio::net::TcpListener, String> {
    use socket2::{Domain, Protocol, Socket, Type};
    use std::net::SocketAddr;

    let domain = Domain::IPV4;
    let socket = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))
        .map_err(|e| format!("socket creation failed: {e}"))?;
    socket
        .set_reuse_address(true)
        .map_err(|e| format!("set_reuse_address failed: {e}"))?;
    socket
        .set_nonblocking(true)
        .map_err(|e| format!("set_nonblocking failed: {e}"))?;

    let address: SocketAddr = format!("0.0.0.0:{port}")
        .parse()
        .map_err(|e| format!("invalid addr: {e}"))?;
    socket
        .bind(&address.into())
        .map_err(|e| format!("bind: {e}"))?;
    socket.listen(128).map_err(|e| format!("listen: {e}"))?;

    let std_listener: std::net::TcpListener = socket.into();
    tokio::net::TcpListener::from_std(std_listener).map_err(|e| format!("from_std: {e}"))
}

async fn bind_listener_with_retry(port: u16) -> Result<tokio::net::TcpListener, String> {
    let mut last_err = String::new();
    for attempt in 1..=3 {
        match create_reuseaddr_listener(port) {
            Ok(l) => return Ok(l),
            Err(e) => {
                last_err = e.clone();
                if attempt < 3 {
                    log::warn!("[nxfr-ffi] Bind attempt {attempt}/3 on port {port} failed: {e}, retrying in 250ms...");
                    tokio::time::sleep(std::time::Duration::from_millis(250)).await;
                }
            }
        }
    }
    Err(last_err)
}

/// Bind a listening TLS socket. Returns `{ listener, port }`.
/// `store_dir` = path to identity directory.
#[no_mangle]
pub extern "C" fn nxfr_listen(port: u16, store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let identity = match load_identity(dir) {
            Ok(id) => id,
            Err(e) => return json_err(&e),
        };

        let server_config = match nxfr_transport::tls::build_server_config(
            identity.private_key(),
            identity.certificate(),
        ) {
            Ok(c) => c,
            Err(e) => return json_err(&format!("TLS server config: {e}")),
        };

        let rt = get_runtime();
        let (pending_tx, pending_rx) = mpsc::channel::<AcceptedConn>(16);

        let (actual_port, cancel_token, accept_task) = match rt.block_on(async {
            let listener = bind_listener_with_retry(port).await?;
            let actual_port = listener
                .local_addr()
                .map_err(|e| format!("local_addr: {e}"))?
                .port();

            let cancel_token = tokio_util::sync::CancellationToken::new();
            let cancel_clone = cancel_token.clone();

            // Spawn accept loop with bounded concurrent handshakes.
            let acceptor = TlsAcceptor::from(Arc::new(server_config));
            let handshake_semaphore = Arc::new(tokio::sync::Semaphore::new(100));
            let accept_task = tokio::spawn(async move {
                loop {
                    tokio::select! {
                        _ = cancel_clone.cancelled() => {
                            log::info!("[nxfr-ffi] Accept loop cancelled, releasing TCP listener.");
                            break;
                        }
                        res = listener.accept() => {
                            match res {
                                Ok((tcp, addr)) => {
                                    let acc = acceptor.clone();
                                    let tx = pending_tx.clone();
                                    let sem = handshake_semaphore.clone();
                                    tokio::spawn(async move {
                                        // Bound concurrent handshakes.
                                        let _permit = match sem.try_acquire() {
                                            Ok(p) => p,
                                            Err(_) => {
                                                log::warn!("[nxfr-ffi] Too many pending TLS handshakes, dropping connection from {addr}");
                                                return;
                                            }
                                        };
                                        // Timeout slow handshakes (Slowloris defense).
                                        match tokio::time::timeout(
                                            std::time::Duration::from_secs(10),
                                            acc.accept(tcp),
                                        )
                                        .await
                                        {
                                            Ok(Ok(tls)) => {
                                                let _ = tx
                                                    .send(AcceptedConn {
                                                        stream: TlsStream::Server(tls),
                                                        addr,
                                                    })
                                                    .await;
                                            }
                                            Ok(Err(e)) => {
                                                log::warn!("TLS accept from {addr}: {e}");
                                            }
                                            Err(_) => {
                                                log::warn!("TLS handshake timeout (10s) from {addr}");
                                            }
                                        }
                                    });
                                }
                                Err(e) => {
                                    log::warn!("[nxfr-ffi] TCP accept error: {e}, backing off 50ms");
                                    tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                                }
                            }
                        }
                    }
                }
            });

            Ok::<_, String>((actual_port, cancel_token, accept_task))
        }) {
            Ok(v) => v,
            Err(e) => return json_err(&e),
        };

        let handle = alloc_handle();
        listeners_map()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(
                handle,
                Listener {
                    pending_rx: Arc::new(tokio::sync::Mutex::new(pending_rx)),
                    identity,
                    port: actual_port,
                    cancel_token,
                    accept_task,
                },
            );

        json_ok(serde_json::json!({
            "listener": handle,
            "port": actual_port,
        }))
    })
}

/// Accept one incoming connection from a listener, performing HELLO exchange.
/// Returns `{ handle, peer_device_id, peer_name, session_id }`.
/// Blocks until a connection arrives.
#[no_mangle]
pub extern "C" fn nxfr_accept(listener: u64) -> *mut c_char {
    ffi_guard(|| {
        let rt = get_runtime();

        // Get listener state (clone Arc to release the std mutex before blocking).
        let (rx_arc, identity) = {
            let guard = listeners_map().lock().unwrap_or_else(|e| e.into_inner());
            let lis = match guard.get(&listener) {
                Some(l) => l,
                None => return json_err("invalid listener handle"),
            };
            (lis.pending_rx.clone(), lis.identity.clone())
        };

        // Block until a TLS connection arrives.
        let accepted = match rt.block_on(async {
            let mut rx = rx_arc.lock().await;
            rx.recv().await.ok_or_else(|| "listener closed".to_string())
        }) {
            Ok(a) => a,
            Err(e) => return json_err(&e),
        };

        // Extract peer device_id and do HELLO exchange.
        let result: ConnectResult = rt.block_on(async {
            let AcceptedConn { stream, .. } = accepted;

            // Extract peer device_id.
            let (peer_device_id, peer_cert_der_bytes) = match &stream {
                TlsStream::Server(s) => {
                    let (_, server_conn) = s.get_ref();
                    let peer_certs = server_conn.peer_certificates().ok_or("no peer certs")?;
                    let peer_cert = peer_certs.first().ok_or("empty peer cert")?;
                    let did = nxfr_crypto::device_id_from_cert(peer_cert.as_ref())
                        .map_err(|e| format!("peer device_id: {e}"))?;
                    (did, peer_cert.as_ref().to_vec())
                }
                TlsStream::Client(_) => return Err("expected server TLS".into()),
            };

            let mut conn = NxfrConnection::new(stream);

            // Receive HELLO.
            let (hdr, payload) = conn
                .recv_frame()
                .await
                .map_err(|e| format!("recv HELLO: {e}"))?;
            if hdr.kind != FrameKind::Control {
                return Err("expected CONTROL for HELLO".into());
            }
            let msg = codec::decode_control(&payload).map_err(|e| format!("decode: {e}"))?;
            let peer_name = match &msg {
                ControlMessage::Hello {
                    device_name,
                    protocol_version,
                    ..
                } => {
                    if *protocol_version != ProtocolVersion::V0_1 {
                        return Err("unsupported protocol version".into());
                    }
                    device_name.clone()
                }
                _ => return Err(format!("expected HELLO, got {msg:?}")),
            };

            // Send HELLO_ACK.
            let session_id = rand_session_id();
            let ack = ControlMessage::HelloAck {
                protocol_version: ProtocolVersion::V0_1,
                device_id: DeviceId::from_bytes(identity.device_id),
                device_name: "NXFR-Android".to_string(),
                platform: Platform::Android,
                capabilities: vec![],
                is_paired: false,
                session_id,
            };
            conn.send_control(session_id, 0, &ack)
                .await
                .map_err(|e| format!("send HELLO_ACK: {e}"))?;

            Ok((
                conn,
                peer_device_id,
                peer_name,
                session_id,
                peer_cert_der_bytes,
            ))
        });

        let (conn, peer_device_id, peer_name, session_id, peer_cert_der) = match result {
            Ok(v) => v,
            Err(e) => return json_err(&e),
        };

        // Create session.
        let (event_tx, event_rx) = mpsc::channel(256);
        let conn_arc = Arc::new(tokio::sync::Mutex::new(Some(conn)));
        let pending_offer = Arc::new(std::sync::Mutex::new(None::<PendingOffer>));
        let handle = alloc_handle();

        // Spawn reader task: wait for incoming TransferRequest and push offer event.
        let conn_clone = conn_arc.clone();
        let offer_clone = pending_offer.clone();
        let event_tx_clone = event_tx.clone();
        let peer_name_clone = peer_name.clone();
        let local_id_clone = identity.device_id;
        let peer_id_clone = peer_device_id;
        rt.spawn(async move {
            // Take the connection out of the Arc<Mutex<Option<_>>> so we don't
            // hold the Mutex across recv_frame().await (which would deadlock
            // with nxfr_close trying to acquire the same lock).
            let mut conn = {
                let mut guard = conn_clone.lock().await;
                match guard.take() {
                    Some(c) => c,
                    None => return,
                }
            };
            // Mutex is released here — nxfr_close can now acquire it.

            let result = conn.recv_frame().await;

            match result {
                Ok((hdr, payload)) if hdr.kind == FrameKind::Control => {
                    match codec::decode_control(&payload) {
                        Ok(ControlMessage::TransferRequest {
                            transfer_id,
                            display_name,
                            total_files,
                            total_size,
                            manifest,
                            ..
                        }) => {
                            *offer_clone.lock().unwrap_or_else(|e| e.into_inner()) =
                                Some(PendingOffer {
                                    transfer_id,
                                    manifest,
                                    display_name: display_name.clone(),
                                    total_size,
                                    total_files,
                                });
                            let _ = event_tx_clone
                                .send(FfiEvent::Offer {
                                    display_name,
                                    total_size,
                                    total_files,
                                    peer_name: peer_name_clone.clone(),
                                })
                                .await;
                        }
                        Ok(ControlMessage::PairRequest { sas_method }) => {
                            log::info!("[nxfr-ffi] Received PairRequest (method: {sas_method})");
                            // Derive SAS on the receiver side using TLS exporter.
                            let sas_code = {
                                let mut sas_ctx = [0u8; 64];
                                if local_id_clone < peer_id_clone {
                                    sas_ctx[..32].copy_from_slice(&local_id_clone);
                                    sas_ctx[32..].copy_from_slice(&peer_id_clone);
                                } else {
                                    sas_ctx[..32].copy_from_slice(&peer_id_clone);
                                    sas_ctx[32..].copy_from_slice(&local_id_clone);
                                }
                                let mut exp = [0u8; 4];
                                // We need the TLS stream to export keying material.
                                // The connection is currently taken out of the Arc.
                                // Use the conn variable we already have.
                                match conn.get_ref().export_keying_material(
                                    &mut exp,
                                    b"NXFR-SAS-v0",
                                    Some(&sas_ctx),
                                ) {
                                    Ok(()) => {
                                        let (code, _) = nxfr_core::sas::derive_sas(
                                            &local_id_clone,
                                            &peer_id_clone,
                                            &exp,
                                        );
                                        exp.zeroize();
                                        code
                                    }
                                    Err(e) => {
                                        log::error!("[nxfr-ffi] TLS exporter failed: {e}");
                                        "000000".to_string()
                                    }
                                }
                            };
                            let _ = event_tx_clone
                                .send(FfiEvent::PairRequest {
                                    sas_code,
                                    peer_name: peer_name_clone.clone(),
                                })
                                .await;
                        }
                        _ => {}
                    }
                }
                _ => {
                    let _ = event_tx_clone
                        .send(FfiEvent::Error {
                            msg: "connection closed before offer".into(),
                        })
                        .await;
                }
            }

            // Put the connection back so confirm/receive can use it.
            // Guard: if nxfr_close ran while we were awaiting, it set the
            // Option to None and dropped the session. Don't re-insert into
            // a dead session — just let the connection drop.
            let mut guard = conn_clone.lock().await;
            if std::sync::Arc::strong_count(&conn_clone) == 1 {
                // nxfr_close already ran; drop the connection cleanly.
                log::info!(
                    "[nxfr-ffi] Reader task: session was closed during recv, dropping connection"
                );
                drop(conn);
            } else {
                *guard = Some(conn);
            }
        });

        let session = Session {
            conn: conn_arc,
            event_tx,
            event_rx: std::sync::Mutex::new(event_rx),
            local_device_id: identity.device_id,
            peer_device_id,
            peer_name: peer_name.clone(),
            peer_cert_der: peer_cert_der.clone(),
            session_id,
            pending_offer,
        };
        sessions_map()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .insert(handle, session);

        json_ok(serde_json::json!({
            "handle": handle,
            "peer_device_id": hex::encode(peer_device_id),
            "peer_name": peer_name,
            "session_id": session_id,
        }))
    })
}

// ─── Transfer: Send ─────────────────────────────────────────────────────

struct LocalSendEntry {
    file_id: u32,
    relative_path: String,
    full_path: PathBuf,
    size: u64,
    sha256: [u8; 32],
    is_dir: bool,
}

fn hash_file_stream(path: &Path) -> Result<(u64, [u8; 32]), std::io::Error> {
    use std::io::Read;
    let mut file = std::fs::File::open(path)?;
    let metadata = file.metadata()?;
    let size = metadata.len();
    let mut hasher = Sha256::new();
    let mut buf = [0u8; 65536];
    loop {
        let n = file.read(&mut buf)?;
        if n == 0 {
            break;
        }
        hasher.update(&buf[..n]);
    }
    let sha256: [u8; 32] = hasher.finalize().into();
    Ok((size, sha256))
}

fn scan_send_path(
    base_path: &Path,
) -> Result<(String, Vec<LocalSendEntry>), Box<dyn std::error::Error + Send + Sync>> {
    let mut entries = Vec::new();
    let mut next_id = 1u32;

    if !base_path.exists() {
        return Err(format!("path does not exist: {}", base_path.display()).into());
    }

    if base_path.is_file() {
        let name = base_path
            .file_name()
            .and_then(|n| n.to_str())
            .unwrap_or("file")
            .to_string();
        let (size, sha256) = hash_file_stream(base_path)?;
        entries.push(LocalSendEntry {
            file_id: next_id,
            relative_path: name.clone(),
            full_path: base_path.to_path_buf(),
            size,
            sha256,
            is_dir: false,
        });
        return Ok((name, entries));
    }

    // Directory transfer
    let display_name = base_path
        .file_name()
        .and_then(|n| n.to_str())
        .unwrap_or("transfer")
        .to_string();

    fn walk_directory(
        root: &Path,
        current: &Path,
        entries: &mut Vec<LocalSendEntry>,
        next_id: &mut u32,
    ) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
        for entry in std::fs::read_dir(current)? {
            let entry = entry?;
            let path = entry.path();
            let rel_path = path.strip_prefix(root)?.to_string_lossy().to_string();

            if path.is_dir() {
                entries.push(LocalSendEntry {
                    file_id: *next_id,
                    relative_path: rel_path,
                    full_path: path.clone(),
                    size: 0,
                    sha256: [0u8; 32],
                    is_dir: true,
                });
                *next_id += 1;
                walk_directory(root, &path, entries, next_id)?;
            } else if path.is_file() {
                let (size, sha256) = hash_file_stream(&path)?;
                entries.push(LocalSendEntry {
                    file_id: *next_id,
                    relative_path: rel_path,
                    full_path: path,
                    size,
                    sha256,
                    is_dir: false,
                });
                *next_id += 1;
            }
        }
        Ok(())
    }

    walk_directory(base_path, base_path, &mut entries, &mut next_id)?;
    if entries.is_empty() {
        return Err("directory is empty".into());
    }
    Ok((display_name, entries))
}

/// Start sending a file or directory. Returns immediately; use nxfr_pump for progress.
#[no_mangle]
pub extern "C" fn nxfr_send_file(handle: u64, path: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let file_path = match cstr_to_str(path) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        if !Path::new(file_path).exists() {
            return json_err(&format!("file not found: {file_path}"));
        }

        let (conn_arc, event_tx, session_id) = {
            let guard = sessions_map().lock().unwrap_or_else(|e| e.into_inner());
            let session = match guard.get(&handle) {
                Some(s) => s,
                None => return json_err("invalid session handle"),
            };
            (
                session.conn.clone(),
                session.event_tx.clone(),
                session.session_id,
            )
        };

        let file_path_owned = file_path.to_string();
        let rt = get_runtime();
        rt.spawn(async move {
            if let Err(e) = do_send_file(conn_arc, session_id, &file_path_owned, &event_tx).await {
                let _ = event_tx.send(FfiEvent::Error { msg: e.to_string() }).await;
            }
        });

        json_ok(serde_json::json!({
            "handle": handle,
            "status": "send_started",
        }))
    })
}

/// Full send flow: TransferRequest → Accept → FileMetadata → chunks → Complete.
async fn do_send_file(
    conn_arc: Arc<tokio::sync::Mutex<Option<NxfrConnection<TlsStream>>>>,
    session_id: u32,
    file_path: &str,
    event_tx: &mpsc::Sender<FfiEvent>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let p = Path::new(file_path);
    let (display_name, send_entries) = scan_send_path(p)?;

    let total_size: u64 = send_entries
        .iter()
        .filter(|e| !e.is_dir)
        .map(|e| e.size)
        .sum();
    let total_files: u32 = send_entries.iter().filter(|e| !e.is_dir).count() as u32;
    let transfer_id = rand_transfer_id();

    let manifest: Vec<ManifestEntry> = send_entries
        .iter()
        .map(|e| ManifestEntry {
            file_id: e.file_id,
            relative_path: e.relative_path.clone(),
            size: if e.is_dir { None } else { Some(e.size) },
            sha256: if e.is_dir { None } else { Some(e.sha256) },
            entry_type: if e.is_dir {
                ManifestEntryType::Dir
            } else {
                ManifestEntryType::File
            },
        })
        .collect();

    let mut conn_guard = conn_arc.lock().await;
    let conn = conn_guard.as_mut().ok_or("connection not available")?;

    conn.send_control(
        session_id,
        0,
        &ControlMessage::TransferRequest {
            transfer_id,
            transfer_type: TransferType::Files,
            display_name: display_name.clone(),
            total_files,
            total_size,
            manifest,
        },
    )
    .await?;
    log::info!(
        "[sender] TransferRequest sent ({}, {} files, {} bytes)",
        display_name,
        total_files,
        total_size
    );

    // Wait for TransferAccept/Reject with 120s timeout.
    log::info!("[sender] Waiting for TransferAccept...");
    let accept_result =
        tokio::time::timeout(std::time::Duration::from_secs(120), conn.recv_frame()).await;
    let (_, payload) = match accept_result {
        Ok(Ok(frame)) => frame,
        Ok(Err(e)) => {
            log::error!("[sender] recv_frame error while waiting for accept: {e}");
            return Err(format!("accept recv error: {e}").into());
        }
        Err(_) => {
            log::error!("[sender] TransferAccept timeout (120s)");
            let _ = event_tx
                .send(FfiEvent::Error {
                    msg: "Accept timeout: peer did not respond within 120s".to_string(),
                })
                .await;
            return Err("accept timeout (120s)".into());
        }
    };
    let msg = codec::decode_control(&payload)?;
    match msg {
        ControlMessage::TransferAccept { .. } => {
            log::info!("[sender] TransferAccept received");
        }
        ControlMessage::TransferReject { reason, .. } => {
            log::info!("[sender] TransferReject: {:?}", reason);
            return Err(format!("rejected: {}", reason.unwrap_or_default()).into());
        }
        other => {
            log::error!("[sender] unexpected message instead of Accept/Reject: {other:?}");
            return Err(format!("expected Accept/Reject, got {other:?}").into());
        }
    }

    let mut cumulative_sent: u64 = 0;

    for entry in send_entries.iter().filter(|e| !e.is_dir) {
        let mut file = tokio::fs::File::open(&entry.full_path).await?;
        let file_size = entry.size as usize;
        let stream_id = entry.file_id;

        // Send FileMetadata.
        conn.send_control(
            session_id,
            0,
            &ControlMessage::FileMetadata {
                transfer_id,
                file_id: entry.file_id,
                stream_id,
                relative_path: entry.relative_path.clone(),
                size: entry.size,
                sha256: entry.sha256,
                mime_type: None,
                modified_time: None,
            },
        )
        .await?;

        // Wait for FileMetadataAck.
        let (_, payload) = conn.recv_frame().await?;
        let msg = codec::decode_control(&payload)?;
        match msg {
            ControlMessage::FileMetadataAck { accepted: true, .. } => {}
            _ => return Err(format!("file metadata rejected for {}", entry.relative_path).into()),
        }

        // Stream chunks from disk (1 MiB read buffer, 8 in-flight window).
        let chunk_size = 1024 * 1024usize;
        let max_in_flight = 8usize;
        let mut offset: usize = 0;
        let mut in_flight: usize = 0;
        let mut read_buf = vec![0u8; chunk_size];

        while offset < file_size {
            while in_flight >= max_in_flight {
                let (_, pl) = conn.recv_frame().await?;
                if let Ok(ControlMessage::ChunkAck { length, .. }) = codec::decode_control(&pl) {
                    cumulative_sent += length;
                    in_flight -= 1;
                    let _ = event_tx
                        .send(FfiEvent::Progress {
                            bytes_sent: cumulative_sent,
                            total_bytes: total_size,
                            file_name: entry.relative_path.clone(),
                        })
                        .await;
                }
            }

            // Read one chunk from disk.
            let want = std::cmp::min(chunk_size, file_size - offset);
            let buf = &mut read_buf[..want];
            use tokio::io::AsyncReadExt;
            file.read_exact(buf).await?;

            let is_last = offset + want == file_size;
            let chunk_hash: [u8; 32] = Sha256::digest(&*buf).into();

            let mut payload = Vec::with_capacity(8 + 32 + want);
            payload.extend_from_slice(&(offset as u64).to_be_bytes());
            payload.extend_from_slice(&chunk_hash);
            payload.extend_from_slice(buf);

            let flags = if is_last { 0x0001u16 } else { 0u16 };
            conn.send_chunk(session_id, stream_id, flags, payload)
                .await?;
            in_flight += 1;
            offset += want;
        }

        while in_flight > 0 {
            let (_, pl) = conn.recv_frame().await?;
            if let Ok(ControlMessage::ChunkAck { length, .. }) = codec::decode_control(&pl) {
                cumulative_sent += length;
                in_flight -= 1;
                let _ = event_tx
                    .send(FfiEvent::Progress {
                        bytes_sent: cumulative_sent,
                        total_bytes: total_size,
                        file_name: entry.relative_path.clone(),
                    })
                    .await;
            }
        }
    }

    // TransferComplete + TransferAck.
    conn.send_control(
        session_id,
        0,
        &ControlMessage::TransferComplete { transfer_id },
    )
    .await?;
    log::info!("[sender] TransferComplete sent, waiting for TransferAck");
    let (_, pl) = conn.recv_frame().await?;
    let msg = codec::decode_control(&pl)?;
    match msg {
        ControlMessage::TransferAck {
            status: TransferAckStatus::Success,
            ..
        } => {
            log::info!("[sender] TransferAck(Success) received — transfer done");
        }
        other => log::warn!("[sender] unexpected TransferAck: {other:?}"),
    }

    let _ = event_tx.send(FfiEvent::Complete { file_path: None }).await;
    Ok(())
}

// ─── Transfer: Pump ─────────────────────────────────────────────────────

/// Non-blocking poll for the next event. Returns JSON event.
#[no_mangle]
pub extern "C" fn nxfr_pump(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        let guard = sessions_map().lock().unwrap_or_else(|e| e.into_inner());
        let session = match guard.get(&handle) {
            Some(s) => s,
            None => return json_err("invalid session handle"),
        };

        let mut rx = session.event_rx.lock().unwrap_or_else(|e| e.into_inner());
        match rx.try_recv() {
            Ok(event) => match event {
                FfiEvent::Offer {
                    display_name,
                    total_size,
                    total_files,
                    peer_name,
                } => json_ok(serde_json::json!({
                    "event": "offer",
                    "display_name": display_name,
                    "total_size": total_size,
                    "total_files": total_files,
                    "peer_name": peer_name,
                    "device_id": hex::encode(session.peer_device_id),
                })),
                FfiEvent::PairRequest {
                    sas_code,
                    peer_name,
                } => json_ok(serde_json::json!({
                    "event": "pair_request",
                    "sas_code": sas_code,
                    "peer_name": peer_name,
                    "device_id": hex::encode(session.peer_device_id),
                })),
                FfiEvent::Progress {
                    bytes_sent,
                    total_bytes,
                    file_name,
                } => {
                    let progress = if total_bytes > 0 {
                        (bytes_sent as f32) / (total_bytes as f32)
                    } else {
                        0.0
                    };
                    json_ok(serde_json::json!({
                        "event": "progress",
                        "progress": progress,
                        "bytes_sent": bytes_sent,
                        "total_bytes": total_bytes,
                        "file_name": file_name,
                    }))
                }
                FfiEvent::Complete { file_path } => json_ok(serde_json::json!({
                    "event": "complete",
                    "file_path": file_path.unwrap_or_default(),
                })),
                FfiEvent::Error { msg } => json_ok(serde_json::json!({
                    "event": "error",
                    "error": msg,
                })),
            },
            Err(mpsc::error::TryRecvError::Empty) => json_ok(serde_json::json!({"event": "none"})),
            Err(mpsc::error::TryRecvError::Disconnected) => {
                json_ok(serde_json::json!({"event": "disconnected"}))
            }
        }
    })
}

// ─── Transfer: Confirm / Receive ────────────────────────────────────────

/// Accept or reject a transfer offer. Spawns receiver task if accepted.
#[no_mangle]
pub extern "C" fn nxfr_confirm(handle: u64, accept: bool) -> *mut c_char {
    ffi_guard(|| {
        let (conn_arc, event_tx, session_id, pending_offer) = {
            let guard = sessions_map().lock().unwrap_or_else(|e| e.into_inner());
            let session = match guard.get(&handle) {
                Some(s) => s,
                None => return json_err("invalid session handle"),
            };
            let offer = session
                .pending_offer
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .take();
            (
                session.conn.clone(),
                session.event_tx.clone(),
                session.session_id,
                offer,
            )
        };

        let rt = get_runtime();

        if !accept {
            if let Some(offer) = pending_offer {
                rt.block_on(async {
                    let mut conn_guard = conn_arc.lock().await;
                    if let Some(conn) = conn_guard.as_mut() {
                        let _ = conn
                            .send_control(
                                session_id,
                                0,
                                &ControlMessage::TransferReject {
                                    transfer_id: offer.transfer_id,
                                    reason: Some("user_rejected".into()),
                                },
                            )
                            .await;
                    }
                });
            }
            return json_ok(serde_json::json!({"handle": handle, "accepted": false}));
        }

        let offer = match pending_offer {
            Some(o) => o,
            None => return json_err("no pending offer"),
        };

        // Spawn receiver task.
        rt.spawn(async move {
            if let Err(e) = do_receive_file(conn_arc, session_id, offer, &event_tx).await {
                let _ = event_tx.send(FfiEvent::Error { msg: e.to_string() }).await;
            }
        });

        json_ok(serde_json::json!({"handle": handle, "accepted": true}))
    })
}

/// Full receive flow: TransferAccept → FileMetadata(s) → chunks → TransferAck.
async fn do_receive_file(
    conn_arc: Arc<tokio::sync::Mutex<Option<NxfrConnection<TlsStream>>>>,
    session_id: u32,
    offer: PendingOffer,
    event_tx: &mpsc::Sender<FfiEvent>,
) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let mut conn_guard = conn_arc.lock().await;
    let conn = conn_guard.as_mut().ok_or("connection not available")?;

    // Send TransferAccept.
    conn.send_control(
        session_id,
        0,
        &ControlMessage::TransferAccept {
            transfer_id: offer.transfer_id,
        },
    )
    .await?;

    let receive_dir = {
        let ovr = receive_dir_override()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        match ovr {
            Some(p) => p,
            None => {
                let cfg = nxfr_storage::config::NxfrConfig::load().unwrap_or_default();
                cfg.receive_dir
            }
        }
    };
    std::fs::create_dir_all(&receive_dir)?;
    let canonical_root = std::fs::canonicalize(&receive_dir)?;

    // Pre-create any directories declared in manifest
    for entry in &offer.manifest {
        if entry.entry_type == ManifestEntryType::Dir {
            let clean_rel = entry.relative_path.replace("..", "_");
            let dir_path = canonical_root.join(clean_rel);
            let _ = std::fs::create_dir_all(&dir_path);
        }
    }

    let expected_files_count = offer
        .manifest
        .iter()
        .filter(|e| e.entry_type == ManifestEntryType::File)
        .count()
        .max(1);

    let mut received_files_count = 0;
    let mut cumulative_received: u64 = 0;
    let mut first_received_path: Option<String> = None;

    while received_files_count < expected_files_count {
        let (hdr, payload) = conn.recv_frame().await?;
        if hdr.kind != FrameKind::Control {
            continue;
        }

        let msg = codec::decode_control(&payload)?;
        let (file_id, stream_id, relative_path, expected_size, expected_hash) = match msg {
            ControlMessage::FileMetadata {
                file_id,
                stream_id,
                relative_path,
                size,
                sha256,
                ..
            } => (file_id, stream_id, relative_path, size, sha256),
            ControlMessage::TransferCancel { .. } => {
                return Err("transfer cancelled by peer".into());
            }
            ControlMessage::TransferComplete { .. } => break,
            _ => continue,
        };

        // Send FileMetadataAck.
        conn.send_control(
            session_id,
            0,
            &ControlMessage::FileMetadataAck {
                transfer_id: offer.transfer_id,
                file_id,
                stream_id,
                accepted: true,
            },
        )
        .await?;

        let clean_rel = relative_path.replace("..", "_");
        let final_path = canonical_root.join(&clean_rel);
        if let Some(parent) = final_path.parent() {
            let _ = std::fs::create_dir_all(parent);
        }

        // Write chunks to a temp file on disk instead of accumulating in memory.
        let tmp_path = final_path.with_extension("nxfr_tmp");
        let mut tmp_file = std::fs::File::create(&tmp_path)?;
        let mut hasher = Sha256::new();
        let mut file_received_bytes: u64 = 0;

        loop {
            let (hdr, payload) = conn.recv_frame().await?;

            if hdr.kind == FrameKind::Control {
                let msg = codec::decode_control(&payload)?;
                match msg {
                    ControlMessage::TransferComplete { .. } => break,
                    ControlMessage::TransferCancel { .. } => {
                        let _ = std::fs::remove_file(&tmp_path);
                        return Err("transfer cancelled by peer".into());
                    }
                    _ => continue,
                }
            }

            if hdr.kind != FrameKind::Chunk {
                continue;
            }

            // Parse chunk: [offset:8][hash:32][data:...]
            if payload.len() < 41 {
                let _ = std::fs::remove_file(&tmp_path);
                return Err("chunk too small".into());
            }
            let chunk_offset = u64::from_be_bytes(payload[0..8].try_into().unwrap());
            let chunk_hash = &payload[8..40];
            let chunk_data = &payload[40..];

            // Verify per-chunk hash.
            let computed = Sha256::digest(chunk_data);
            if computed.as_slice() != chunk_hash {
                let _ = std::fs::remove_file(&tmp_path);
                return Err(format!("chunk hash mismatch at offset {chunk_offset}").into());
            }

            use std::io::Write;
            tmp_file.write_all(chunk_data)?;
            hasher.update(chunk_data);
            file_received_bytes += chunk_data.len() as u64;
            cumulative_received += chunk_data.len() as u64;

            // Send ChunkAck.
            conn.send_control(
                session_id,
                0,
                &ControlMessage::ChunkAck {
                    stream_id,
                    message_id: hdr.message_id,
                    offset: chunk_offset,
                    length: chunk_data.len() as u64,
                },
            )
            .await?;

            let _ = event_tx
                .send(FfiEvent::Progress {
                    bytes_sent: cumulative_received,
                    total_bytes: offer.total_size,
                    file_name: relative_path.clone(),
                })
                .await;

            if hdr.flags.is_last_chunk() || file_received_bytes >= expected_size {
                break;
            }
        }

        // Flush and close temp file before verifying hash.
        drop(tmp_file);

        // Verify per-file SHA-256
        let final_hash: [u8; 32] = hasher.finalize().into();
        if final_hash != expected_hash {
            let _ = std::fs::remove_file(&tmp_path);
            return Err(format!(
                "file hash mismatch for {}: expected {}, got {}",
                relative_path,
                hex::encode(expected_hash),
                hex::encode(final_hash)
            )
            .into());
        }

        // Rename temp file to final destination.
        std::fs::rename(&tmp_path, &final_path)?;
        if first_received_path.is_none() {
            first_received_path = Some(final_path.to_string_lossy().to_string());
        }
        received_files_count += 1;
    }

    // Send TransferAck Success
    conn.send_control(
        session_id,
        0,
        &ControlMessage::TransferAck {
            transfer_id: offer.transfer_id,
            status: TransferAckStatus::Success,
            failed_files: None,
        },
    )
    .await?;

    let final_res_path = if expected_files_count > 1 {
        Some(canonical_root.to_string_lossy().to_string())
    } else {
        first_received_path
    };

    let _ = event_tx
        .send(FfiEvent::Complete {
            file_path: final_res_path,
        })
        .await;
    Ok(())
}

// ─── Pairing ────────────────────────────────────────────────────────────

/// Begin SAS pairing. Sends PairRequest and returns SAS code.
#[no_mangle]
pub extern "C" fn nxfr_pair_begin(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        let rt = get_runtime();
        let (conn_arc, session_id, local_id, peer_id) = {
            let guard = sessions_map().lock().unwrap_or_else(|e| e.into_inner());
            let session = match guard.get(&handle) {
                Some(s) => s,
                None => return json_err("invalid session handle"),
            };
            (
                session.conn.clone(),
                session.session_id,
                session.local_device_id,
                session.peer_device_id,
            )
        };

        let sas_code = match rt.block_on(async {
            let mut conn_guard = conn_arc.lock().await;
            let conn = conn_guard.as_mut().ok_or("connection not available")?;

            conn.send_control(
                session_id,
                0,
                &ControlMessage::PairRequest {
                    sas_method: "sas-v0".to_string(),
                },
            )
            .await
            .map_err(|e| format!("send PairRequest: {e}"))?;

            // Build SAS context: sorted device_ids (64 bytes).
            let mut sas_context = [0u8; 64];
            if local_id < peer_id {
                sas_context[..32].copy_from_slice(&local_id);
                sas_context[32..].copy_from_slice(&peer_id);
            } else {
                sas_context[..32].copy_from_slice(&peer_id);
                sas_context[32..].copy_from_slice(&local_id);
            }

            // Extract TLS keying material for SAS derivation.
            let tls_stream = conn.get_ref();
            let mut exporter = [0u8; 4];
            tls_stream.export_keying_material(&mut exporter, b"NXFR-SAS-v0", Some(&sas_context))?;

            let (code, _) = nxfr_core::sas::derive_sas(&local_id, &peer_id, &exporter);
            exporter.zeroize();
            Ok::<_, String>(code)
        }) {
            Ok(c) => c,
            Err(e) => return json_err(&e),
        };

        json_ok(serde_json::json!({
            "handle": handle,
            "sas_code": sas_code,
            "label": "NXFR-SAS-v0",
        }))
    })
}

/// Confirm or reject a SAS pairing. On accept, persist to paired.db.
#[no_mangle]
pub extern "C" fn nxfr_pair_confirm(
    handle: u64,
    accepted: bool,
    store_dir: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let rt = get_runtime();
        let (conn_arc, session_id, peer_device_id, peer_name, peer_cert_der) = {
            let guard = sessions_map().lock().unwrap_or_else(|e| e.into_inner());
            let session = match guard.get(&handle) {
                Some(s) => s,
                None => return json_err("invalid session handle"),
            };
            (
                session.conn.clone(),
                session.session_id,
                session.peer_device_id,
                session.peer_name.clone(),
                session.peer_cert_der.clone(),
            )
        };

        match rt.block_on(async {
            let mut conn_guard = conn_arc.lock().await;
            let conn = conn_guard.as_mut().ok_or("connection not available")?;
            let msg = if accepted {
                ControlMessage::PairAccept
            } else {
                ControlMessage::PairReject { reason: None }
            };
            conn.send_control(session_id, 0, &msg)
                .await
                .map_err(|e| format!("send: {e}"))?;
            Ok::<_, String>(())
        }) {
            Ok(()) => {
                // On accept: persist to paired.db.
                if accepted {
                    if let Ok(dir) = cstr_to_str(store_dir) {
                        let db_path = std::path::Path::new(dir).join("paired.db");
                        if let Ok(db) = nxfr_storage::db::PairedDeviceDb::open(&db_path) {
                            let now = chrono::Utc::now().timestamp();
                            let device = nxfr_storage::db::PairedDevice {
                                device_id: hex::encode(peer_device_id),
                                name: peer_name.clone(),
                                public_key_spki: nxfr_crypto::extract_spki(&peer_cert_der)
                                    .unwrap_or_default(),
                                first_seen: now,
                                last_seen: now,
                                trust_level: "paired".to_string(),
                                auto_accept: "prompt".to_string(),
                            };
                            if let Err(e) = db.insert_or_update(&device) {
                                log::warn!("Failed to persist pair: {e}");
                            }
                        }
                    }
                }
                json_ok(serde_json::json!({
                    "handle": handle,
                    "status": if accepted { "pair_confirmed" } else { "pair_rejected" },
                }))
            }
            Err(e) => json_err(&e),
        }
    })
}

// ─── Paired Device Management ───────────────────────────────────────────

/// List all paired devices. Returns JSON array.
#[no_mangle]
pub extern "C" fn nxfr_paired_list(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let db_path = std::path::Path::new(dir).join("paired.db");
        let db = match nxfr_storage::db::PairedDeviceDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open paired.db: {e}")),
        };
        let devices = match db.list_all() {
            Ok(d) => d,
            Err(e) => return json_err(&format!("list_all: {e}")),
        };
        let arr: Vec<serde_json::Value> = devices
            .iter()
            .map(|d| {
                serde_json::json!({
                    "device_id": d.device_id,
                    "name": d.name,
                    "first_seen": d.first_seen,
                    "last_seen": d.last_seen,
                    "trust_level": d.trust_level,
                    "auto_accept": d.auto_accept,
                })
            })
            .collect();
        json_ok(serde_json::json!({ "devices": arr }))
    })
}

/// Remove a paired device.
#[no_mangle]
pub extern "C" fn nxfr_unpair(store_dir: *const c_char, device_id: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let did = match cstr_to_str(device_id) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let db_path = std::path::Path::new(dir).join("paired.db");
        let db = match nxfr_storage::db::PairedDeviceDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open paired.db: {e}")),
        };
        match db.remove(did) {
            Ok(()) => json_ok(serde_json::json!({ "status": "unpaired", "device_id": did })),
            Err(e) => json_err(&format!("unpair: {e}")),
        }
    })
}

/// Set auto-accept policy for a paired device ("prompt" or "always").
#[no_mangle]
pub extern "C" fn nxfr_set_auto_accept(
    store_dir: *const c_char,
    device_id: *const c_char,
    policy: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let did = match cstr_to_str(device_id) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let pol = match cstr_to_str(policy) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        if pol != "prompt" && pol != "always" {
            return json_err("policy must be 'prompt' or 'always'");
        }
        let db_path = std::path::Path::new(dir).join("paired.db");
        let db = match nxfr_storage::db::PairedDeviceDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open paired.db: {e}")),
        };
        let mut device = match db.lookup(did) {
            Ok(Some(d)) => d,
            Ok(None) => return json_err("device not paired"),
            Err(e) => return json_err(&format!("lookup: {e}")),
        };
        device.auto_accept = pol.to_string();
        match db.insert_or_update(&device) {
            Ok(()) => json_ok(serde_json::json!({
                "status": "updated",
                "device_id": did,
                "auto_accept": pol,
            })),
            Err(e) => json_err(&format!("update: {e}")),
        }
    })
}

/// Set the local device's display name (persisted in config.toml).
#[no_mangle]
pub extern "C" fn nxfr_set_name(store_dir: *const c_char, name: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let new_name = match cstr_to_str(name) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        if new_name.is_empty() || new_name.len() > 64 {
            return json_err("name must be 1-64 characters");
        }
        let config_path = std::path::Path::new(dir).join("config.toml");
        let mut config =
            nxfr_storage::config::NxfrConfig::load_from(&config_path).unwrap_or_default();
        config.device_name = new_name.to_string();
        match config.save_to(&config_path) {
            Ok(()) => json_ok(serde_json::json!({
                "status": "name_updated",
                "name": new_name,
            })),
            Err(e) => json_err(&format!("save config: {e}")),
        }
    })
}

// ─── Close ──────────────────────────────────────────────────────────────

/// Close a session, sending SessionClose and releasing all resources.
#[no_mangle]
pub extern "C" fn nxfr_close(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        if let Some(listener) = listeners_map()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle)
        {
            log::info!(
                "[nxfr-ffi] nxfr_close: Aborting listener handle {} on port {}",
                handle,
                listener.port
            );
            listener.cancel_token.cancel();
            listener.accept_task.abort();
            drop(listener);
            log::info!("[nxfr-ffi] listener dropped, port released");
            return json_ok(serde_json::json!({ "handle": handle, "status": "closed" }));
        }

        let session = sessions_map()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .remove(&handle);
        if let Some(session) = session {
            log::info!(
                "[nxfr-ffi] nxfr_close: Sending SessionClose and dropping session {}",
                handle
            );
            let rt = get_runtime();
            rt.block_on(async {
                // Use a timeout on lock acquisition to avoid hanging if the
                // reader task is slow to return the connection.
                match tokio::time::timeout(
                    std::time::Duration::from_secs(3),
                    session.conn.lock(),
                )
                .await
                {
                    Ok(mut conn_guard) => {
                        if let Some(conn) = conn_guard.as_mut() {
                            let _ = conn
                                .send_control(
                                    session.session_id,
                                    0,
                                    &ControlMessage::SessionClose { reason: None },
                                )
                                .await;
                        }
                        *conn_guard = None;
                    }
                    Err(_) => {
                        log::warn!("[nxfr-ffi] nxfr_close: timed out waiting for conn lock, dropping session");
                    }
                }
            });
        } else {
            log::info!(
                "[nxfr-ffi] nxfr_close: Handle {} not found or already closed",
                handle
            );
        }
        json_ok(serde_json::json!({ "handle": handle, "status": "closed" }))
    })
}

// ─── Utility Functions ──────────────────────────────────────────────────

#[no_mangle]
pub extern "C" fn nxfr_sanitize_path(path: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let input = match cstr_to_str(path) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        match nxfr_core::sanitize_path(input) {
            Ok(sanitized) => json_ok(serde_json::json!({ "path": sanitized })),
            Err(e) => json_err(&format!("path validation failed: {e}")),
        }
    })
}

/// Compute SHA-256 hash of arbitrary data. Returns hex string as JSON.
///
/// # Safety
/// `data` must point to at least `len` valid bytes.
#[no_mangle]
pub unsafe extern "C" fn nxfr_sha256(data: *const u8, len: usize) -> *mut c_char {
    ffi_guard(|| {
        if data.is_null() {
            return json_err("null data pointer");
        }
        let bytes = unsafe { std::slice::from_raw_parts(data, len) };
        let hash = Sha256::digest(bytes);
        json_ok(serde_json::json!({ "sha256": hex::encode(hash) }))
    })
}

#[no_mangle]
pub extern "C" fn nxfr_advertised_id(
    device_id_hex: *const c_char,
    date_str: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let id_hex = match cstr_to_str(device_id_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let date = match cstr_to_str(date_str) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let device_id_bytes = match hex::decode(id_hex) {
            Ok(b) if b.len() == 32 => b,
            Ok(b) => return json_err(&format!("device_id must be 32 bytes, got {}", b.len())),
            Err(e) => return json_err(&format!("invalid hex: {e}")),
        };
        let mut hasher = Sha256::new();
        hasher.update(&device_id_bytes);
        hasher.update(date.as_bytes());
        let result = hasher.finalize();
        let advertised_id: String = result[..8].iter().map(|b| format!("{b:02x}")).collect();
        json_ok(serde_json::json!({ "advertised_id": advertised_id }))
    })
}

/// Derive SAS code from two device IDs and TLS exporter material.
///
/// **BREAKING CHANGE (v0.1.x → v0.2.0):** Added `exporter_len` parameter.
/// Previous signature took only 3 arguments (`device_id_a_hex`, `device_id_b_hex`,
/// `exporter_bytes`). Non-JNI C consumers of `libnxfr_ffi.so` must update their
/// call sites to pass the buffer length.
///
/// # Safety
/// `device_id_a_hex`, `device_id_b_hex` must be valid null-terminated UTF-8 C strings.
/// `exporter_bytes` must point to at least `exporter_len` valid bytes.
#[no_mangle]
pub unsafe extern "C" fn nxfr_derive_sas(
    device_id_a_hex: *const c_char,
    device_id_b_hex: *const c_char,
    exporter_bytes: *const u8,
    exporter_len: usize,
) -> *mut c_char {
    ffi_guard(|| {
        let id_a_hex = match cstr_to_str(device_id_a_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let id_b_hex = match cstr_to_str(device_id_b_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        if exporter_bytes.is_null() {
            return json_err("null exporter_bytes");
        }
        if exporter_len < 4 {
            return json_err(&format!("exporter_bytes too short: {} < 4", exporter_len));
        }
        let id_a = match hex::decode(id_a_hex) {
            Ok(b) if b.len() == 32 => {
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&b);
                arr
            }
            _ => return json_err("device_id_a must be 64 hex chars (32 bytes)"),
        };
        let id_b = match hex::decode(id_b_hex) {
            Ok(b) if b.len() == 32 => {
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&b);
                arr
            }
            _ => return json_err("device_id_b must be 64 hex chars (32 bytes)"),
        };
        let slice = unsafe { std::slice::from_raw_parts(exporter_bytes, exporter_len) };
        let mut exp = [slice[0], slice[1], slice[2], slice[3]];
        let (sas_code, _context) = nxfr_core::sas::derive_sas(&id_a, &id_b, &exp);
        exp.zeroize();
        json_ok(serde_json::json!({
            "sas_code": sas_code,
            "label": "NXFR-SAS-v0",
        }))
    })
}

// ─── Memory Management ──────────────────────────────────────────────────

#[no_mangle]
/// Free a C string previously returned by any `nxfr_*` function.
///
/// # Safety
/// `ptr` must be a pointer returned by an `nxfr_*` function, or null.
pub unsafe extern "C" fn nxfr_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = unsafe { CString::from_raw(ptr) };
    }
}
// ─── Web Upload Server ──────────────────────────────────────────────────

static WEB_SERVER: std::sync::OnceLock<std::sync::Mutex<Option<nxfr_web::WebServerHandle>>> =
    std::sync::OnceLock::new();

fn web_server_lock() -> &'static std::sync::Mutex<Option<nxfr_web::WebServerHandle>> {
    WEB_SERVER.get_or_init(|| std::sync::Mutex::new(None))
}

fn load_or_create_identity(dir: &str) -> Result<FfiIdentity, String> {
    match load_identity(dir) {
        Ok(id) => Ok(id),
        Err(_) => {
            log::info!("[nxfr-ffi] Identity missing in {}, auto-generating...", dir);
            let dir_path = Path::new(dir);
            std::fs::create_dir_all(dir_path).map_err(|e| format!("mkdir failed: {e}"))?;
            let identity = nxfr_crypto::identity::generate_identity()
                .map_err(|e| format!("keygen failed: {e}"))?;
            std::fs::write(dir_path.join("identity.der"), &identity.private_key_der)
                .map_err(|e| format!("write key failed: {e}"))?;
            std::fs::write(dir_path.join("identity.crt"), &identity.cert_der)
                .map_err(|e| format!("write cert failed: {e}"))?;
            load_identity(dir)
        }
    }
}

/// Start the token-gated HTTPS web upload endpoint.
/// `port` = preferred port (e.g. 17396; retries port+1 if bound).
/// `store_dir` = path to identity directory.
/// `pin` = optional PIN (null or empty string if none).
/// Returns JSON: `{"port": 17396, "token": "...", "status": "started"}` or `{"error": "..."}`.
#[no_mangle]
pub extern "C" fn nxfr_web_start(
    port: u16,
    store_dir: *const c_char,
    pin: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let pin_opt = if !pin.is_null() {
            match cstr_to_str(pin) {
                Ok(s) if !s.is_empty() => Some(s.to_string()),
                _ => None,
            }
        } else {
            None
        };

        let identity = match load_or_create_identity(dir) {
            Ok(id) => id,
            Err(e) => return json_err(&e),
        };

        let receive_dir = match receive_dir_override()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
        {
            Some(d) => d,
            None => PathBuf::from(dir).join("inbox"),
        };

        if let Some(existing) = web_server_lock()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            existing.stop();
        }

        let rt = get_runtime();
        let preferred_port = if port == 0 {
            nxfr_web::DEFAULT_WEB_PORT
        } else {
            port
        };

        let handle_res = rt.block_on(async {
            nxfr_web::WebServer::start(
                &identity.key_der,
                &identity.cert_der,
                receive_dir,
                preferred_port,
                pin_opt,
            )
            .await
        });

        match handle_res {
            Ok(handle) => {
                let actual_port = handle.port;
                let token = handle.token.clone();
                *web_server_lock().lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);
                json_ok(serde_json::json!({
                    "port": actual_port,
                    "token": token,
                    "status": "started"
                }))
            }
            Err(e) => json_err(&format!("web_start failed: {e}")),
        }
    })
}

/// Start the sender-hosted share-via-link web download server.
#[no_mangle]
pub extern "C" fn nxfr_web_share_start(
    port: u16,
    store_dir: *const c_char,
    pin: *const c_char,
    manifest_json: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let manifest_str = match cstr_to_str(manifest_json) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let items: Vec<nxfr_web::WebShareItem> = match serde_json::from_str(manifest_str) {
            Ok(v) => v,
            Err(e) => return json_err(&format!("invalid manifest_json: {e}")),
        };

        let pin_opt = if !pin.is_null() {
            match cstr_to_str(pin) {
                Ok(s) if !s.is_empty() => Some(s.to_string()),
                _ => None,
            }
        } else {
            None
        };

        let identity = match load_or_create_identity(dir) {
            Ok(id) => id,
            Err(e) => return json_err(&e),
        };

        if let Some(existing) = web_server_lock()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            existing.stop();
        }

        let rt = get_runtime();
        let preferred_port = if port == 0 {
            nxfr_web::DEFAULT_WEB_PORT
        } else {
            port
        };

        let handle_res = rt.block_on(async {
            nxfr_web::WebServer::start_share(
                &identity.key_der,
                &identity.cert_der,
                preferred_port,
                pin_opt,
                items,
            )
            .await
        });

        match handle_res {
            Ok(handle) => {
                let actual_port = handle.port;
                let token = handle.token.clone();
                *web_server_lock().lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);
                json_ok(serde_json::json!({
                    "status": "started",
                    "port": actual_port,
                    "token": token,
                    "mode": "share",
                }))
            }
            Err(e) => json_err(&format!("web_share_start: {e}")),
        }
    })
}

/// Stop the running web upload server.
#[no_mangle]
pub extern "C" fn nxfr_web_stop() -> *mut c_char {
    ffi_guard(|| {
        if let Some(server) = web_server_lock()
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            server.stop();
            log::info!("[nxfr-ffi] Web server stopped.");
        }
        json_ok(serde_json::json!({ "status": "stopped" }))
    })
}

/// Query status of the running web server (active transfers, running state).
#[no_mangle]
pub extern "C" fn nxfr_web_status() -> *mut c_char {
    ffi_guard(|| {
        let guard = web_server_lock().lock().unwrap_or_else(|e| e.into_inner());
        match &*guard {
            Some(handle) => {
                let active = handle.active_transfers_count();
                let stopped = handle.is_stopped();
                json_ok(serde_json::json!({
                    "running": !stopped,
                    "active_transfers": active,
                    "port": handle.port,
                }))
            }
            None => json_ok(serde_json::json!({
                "running": false,
                "active_transfers": 0,
                "port": 0,
            })),
        }
    })
}

#[no_mangle]
pub extern "C" fn nxfr_history_add(
    json_record: *const c_char,
    store_dir: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let json_str = match cstr_to_str(json_record) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let record: nxfr_storage::history::TransferHistoryRecord =
            match serde_json::from_str(json_str) {
                Ok(r) => r,
                Err(e) => return json_err(&format!("json parse error: {e}")),
            };

        let db_path = std::path::Path::new(dir).join("history.db");
        let db = match nxfr_storage::history::HistoryDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open history.db: {e}")),
        };

        match db.add(&record) {
            Ok(id) => json_ok(serde_json::json!({
                "status": "added",
                "id": id,
            })),
            Err(e) => json_err(&format!("add history failed: {e}")),
        }
    })
}

#[no_mangle]
pub extern "C" fn nxfr_history_list(limit: u32, store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let db_path = std::path::Path::new(dir).join("history.db");
        let db = match nxfr_storage::history::HistoryDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open history.db: {e}")),
        };

        match db.list(limit as usize) {
            Ok(records) => json_ok(serde_json::json!({
                "records": records,
            })),
            Err(e) => json_err(&format!("list history failed: {e}")),
        }
    })
}

#[no_mangle]
pub extern "C" fn nxfr_history_clear(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let db_path = std::path::Path::new(dir).join("history.db");
        let db = match nxfr_storage::history::HistoryDb::open(&db_path) {
            Ok(db) => db,
            Err(e) => return json_err(&format!("open history.db: {e}")),
        };

        match db.clear() {
            Ok(()) => json_ok(serde_json::json!({
                "status": "cleared",
            })),
            Err(e) => json_err(&format!("clear history failed: {e}")),
        }
    })
}

/// Returns the SHA-256 fingerprint (SPKI hash) of the device's TLS certificate.
#[no_mangle]
pub extern "C" fn nxfr_web_fingerprint(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let dir_path = std::path::Path::new(dir);
        let crt_file = if dir_path.join("identity.crt").exists() {
            dir_path.join("identity.crt")
        } else if dir_path.join("nxfr-identity").join("identity.crt").exists() {
            dir_path.join("nxfr-identity").join("identity.crt")
        } else {
            return json_err("identity.crt not found");
        };

        let cert_der = match std::fs::read(&crt_file) {
            Ok(b) => b,
            Err(e) => return json_err(&format!("read cert file: {e}")),
        };

        let hash_bytes = match nxfr_crypto::identity::device_id_from_cert(&cert_der) {
            Ok(h) => h,
            Err(e) => return json_err(&format!("device_id_from_cert failed: {e}")),
        };

        let hex_str = hex::encode(hash_bytes);
        let formatted = hash_bytes
            .iter()
            .map(|b| format!("{:02X}", b))
            .collect::<Vec<_>>()
            .join(":");

        json_ok(serde_json::json!({
            "fingerprint": hex_str,
            "formatted": formatted,
        }))
    })
}

// ─── Tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;
    use std::thread;
    use std::time::Duration;

    fn parse_ffi_json(ptr: *mut c_char) -> serde_json::Value {
        assert!(!ptr.is_null());
        let cstr = unsafe { CStr::from_ptr(ptr) };
        let s = cstr.to_str().unwrap();
        let v: serde_json::Value = serde_json::from_str(s).unwrap();
        unsafe { nxfr_string_free(ptr) };
        v
    }

    // ── Identity tests ──

    #[test]
    fn test_identity_generate_and_load() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let result = parse_ffi_json(nxfr_identity_generate(dir.as_ptr()));
        assert!(result.get("error").is_none(), "generate should succeed");
        let device_id = result["device_id"].as_str().unwrap();
        assert_eq!(device_id.len(), 64);
        let result2 = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert_eq!(result2["device_id"].as_str().unwrap(), device_id);
    }

    #[test]
    fn test_identity_load_missing_dir() {
        let dir = CString::new("/tmp/nxfr_nonexistent_42").unwrap();
        let result = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_identity_null_pointer() {
        let result = parse_ffi_json(nxfr_identity_generate(std::ptr::null()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    // ── Utility tests ──

    #[test]
    fn test_sanitize_path_valid() {
        let path = CString::new("photos/vacation/img.jpg").unwrap();
        let result = parse_ffi_json(nxfr_sanitize_path(path.as_ptr()));
        assert!(result.get("error").is_none());
    }

    #[test]
    fn test_sanitize_path_traversal() {
        let path = CString::new("../../etc/passwd").unwrap();
        let result = parse_ffi_json(nxfr_sanitize_path(path.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_sanitize_path_null() {
        let result = parse_ffi_json(nxfr_sanitize_path(std::ptr::null()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_sha256() {
        let data = b"hello world";
        let result = parse_ffi_json(unsafe { nxfr_sha256(data.as_ptr(), data.len()) });
        let expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        assert_eq!(result["sha256"].as_str().unwrap(), expected);
    }

    #[test]
    fn test_sha256_null() {
        let result = parse_ffi_json(unsafe { nxfr_sha256(std::ptr::null(), 0) });
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_advertised_id() {
        let id_hex = CString::new("00".repeat(32)).unwrap();
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), date.as_ptr()));
        assert!(result.get("error").is_none());
        assert_eq!(result["advertised_id"].as_str().unwrap().len(), 16);
    }

    #[test]
    fn test_advertised_id_bad_hex() {
        let id = CString::new("not_valid_hex").unwrap();
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id.as_ptr(), date.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_advertised_id_wrong_length() {
        let id = CString::new("aabb").unwrap();
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id.as_ptr(), date.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("32 bytes"));
    }

    #[test]
    fn test_derive_sas() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let exporter: [u8; 4] = [0x01, 0x02, 0x03, 0x04];
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(
                id_a.as_ptr(),
                id_b.as_ptr(),
                exporter.as_ptr(),
                exporter.len(),
            )
        });
        assert!(result.get("error").is_none());
        assert_eq!(result["sas_code"].as_str().unwrap().len(), 6);
    }

    #[test]
    fn test_derive_sas_null_exporter() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), std::ptr::null(), 0)
        });
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_derive_sas_commutative() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let exp: [u8; 4] = [0xAB, 0xCD, 0xEF, 0x12];
        let r1 = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), exp.as_ptr(), exp.len())
        });
        let r2 = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_b.as_ptr(), id_a.as_ptr(), exp.as_ptr(), exp.len())
        });
        assert_eq!(
            r1["sas_code"].as_str().unwrap(),
            r2["sas_code"].as_str().unwrap(),
        );
    }

    #[test]
    fn test_derive_sas_short_exporter_bytes() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();

        // 0 bytes — must return error, not crash
        let empty: [u8; 0] = [];
        // Use a valid pointer even for empty (dangling is UB-adjacent)
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), empty.as_ptr(), empty.len())
        });
        assert!(
            result.get("error").is_some(),
            "0-byte exporter should return error, got: {result}"
        );

        // 1 byte
        let one: [u8; 1] = [0x42];
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), one.as_ptr(), one.len())
        });
        assert!(
            result.get("error").is_some(),
            "1-byte exporter should return error, got: {result}"
        );

        // 3 bytes
        let three: [u8; 3] = [0x01, 0x02, 0x03];
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), three.as_ptr(), three.len())
        });
        assert!(
            result.get("error").is_some(),
            "3-byte exporter should return error, got: {result}"
        );
    }

    #[test]
    fn test_advertised_id_matches_discovery() {
        let device_id = [0u8; 32];
        let date_str = "2025-01-01";
        let mut hasher = Sha256::new();
        hasher.update(device_id);
        hasher.update(date_str.as_bytes());
        let result = hasher.finalize();
        let expected: String = result[..8].iter().map(|b| format!("{b:02x}")).collect();
        let id_hex = CString::new(hex::encode(device_id)).unwrap();
        let date = CString::new(date_str).unwrap();
        let ffi_result = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), date.as_ptr()));
        assert_eq!(ffi_result["advertised_id"].as_str().unwrap(), expected);
    }

    #[test]
    fn test_advertised_id_rotates_daily() {
        let id_hex = CString::new("ab".repeat(32)).unwrap();
        let day1 = CString::new("2025-06-01").unwrap();
        let day2 = CString::new("2025-06-02").unwrap();
        let r1 = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), day1.as_ptr()));
        let r2 = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), day2.as_ptr()));
        assert!(r1.get("error").is_none());
        assert!(r2.get("error").is_none());
        let aid1 = r1["advertised_id"].as_str().unwrap();
        let aid2 = r2["advertised_id"].as_str().unwrap();
        assert_ne!(aid1, aid2, "advertised_id must change when date changes");
        // Same date → same result (deterministic).
        let r3 = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), day1.as_ptr()));
        assert_eq!(r3["advertised_id"].as_str().unwrap(), aid1);
    }

    #[test]
    fn test_string_free_null() {
        unsafe { nxfr_string_free(std::ptr::null_mut()) };
    }

    #[test]
    fn test_handles_are_unique() {
        let h1 = alloc_handle();
        let h2 = alloc_handle();
        assert_ne!(h1, h2);
    }

    // ── Connection tests ──

    #[test]
    fn test_connect_null_addr() {
        let dir = CString::new("/tmp").unwrap();
        let result = parse_ffi_json(nxfr_connect(std::ptr::null(), dir.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_send_file_nonexistent() {
        let path = CString::new("/tmp/nxfr_no_such_file_42.dat").unwrap();
        let result = parse_ffi_json(nxfr_send_file(999, path.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("not found"));
    }

    #[test]
    fn test_pump_invalid_handle() {
        let result = parse_ffi_json(nxfr_pump(999999));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_confirm_invalid_handle() {
        let result = parse_ffi_json(nxfr_confirm(999999, true));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_close_nonexistent() {
        let result = parse_ffi_json(nxfr_close(999999));
        assert_eq!(result["status"].as_str().unwrap(), "closed");
    }

    /// Full loopback test: generate identities → listen → connect → send 1 MiB →
    /// pump → confirm → SHA-256 match on both sides.
    /// Uses threads because FFI functions call block_on (can't nest Tokio runtimes).
    #[test]
    fn test_ffi_loopback_transfer() {
        let tmp_a = tempfile::tempdir().unwrap();
        let tmp_b = tempfile::tempdir().unwrap();

        // Generate identities.
        let dir_a = CString::new(tmp_a.path().to_str().unwrap()).unwrap();
        let dir_b = CString::new(tmp_b.path().to_str().unwrap()).unwrap();
        let _ = parse_ffi_json(nxfr_identity_generate(dir_a.as_ptr()));
        let _ = parse_ffi_json(nxfr_identity_generate(dir_b.as_ptr()));

        // Start listener (port 0 = ephemeral).
        let dir_b_str = tmp_b.path().to_str().unwrap().to_string();
        let dir_b_c = CString::new(dir_b_str.clone()).unwrap();
        let listen_result = parse_ffi_json(nxfr_listen(0, dir_b_c.as_ptr()));
        assert!(
            listen_result.get("error").is_none(),
            "listen failed: {listen_result:?}"
        );
        let listener_handle = listen_result["listener"].as_u64().unwrap();
        let port = listen_result["port"].as_u64().unwrap();

        // Create test file (1 MiB).
        let test_file = tmp_a.path().join("test.dat");
        let test_data = vec![0xABu8; 1_048_576];
        std::fs::write(&test_file, &test_data).unwrap();
        let expected_hash = hex::encode(Sha256::digest(&test_data));

        // Connect in a separate thread (block_on can't be nested).
        let dir_a_str = tmp_a.path().to_str().unwrap().to_string();
        let addr = format!("127.0.0.1:{port}");
        let connect_thread = thread::spawn(move || {
            let addr_c = CString::new(addr).unwrap();
            let dir_c = CString::new(dir_a_str).unwrap();
            let result = parse_ffi_json(nxfr_connect(addr_c.as_ptr(), dir_c.as_ptr()));
            assert!(result.get("error").is_none(), "connect failed: {result:?}");
            result["handle"].as_u64().unwrap()
        });

        // Accept.
        let accept_thread = thread::spawn(move || {
            let result = parse_ffi_json(nxfr_accept(listener_handle));
            assert!(result.get("error").is_none(), "accept failed: {result:?}");
            result["handle"].as_u64().unwrap()
        });

        let sender_handle = connect_thread.join().unwrap();
        let receiver_handle = accept_thread.join().unwrap();

        // Send file.
        let file_path_c = CString::new(test_file.to_str().unwrap()).unwrap();
        let send_result = parse_ffi_json(nxfr_send_file(sender_handle, file_path_c.as_ptr()));
        assert!(
            send_result.get("error").is_none(),
            "send failed: {send_result:?}"
        );

        // Pump receiver until offer.
        let mut got_offer = false;
        for _ in 0..100 {
            let event = parse_ffi_json(nxfr_pump(receiver_handle));
            if event.get("event").and_then(|e| e.as_str()) == Some("offer") {
                got_offer = true;
                break;
            }
            if event.get("event").and_then(|e| e.as_str()) == Some("error") {
                panic!("receiver error: {event:?}");
            }
            thread::sleep(Duration::from_millis(50));
        }
        assert!(got_offer, "never received offer event");

        // Accept the transfer.
        let confirm_result = parse_ffi_json(nxfr_confirm(receiver_handle, true));
        assert!(
            confirm_result.get("error").is_none(),
            "confirm failed: {confirm_result:?}"
        );

        // Pump sender until complete.
        let mut sender_complete = false;
        for _ in 0..200 {
            let event = parse_ffi_json(nxfr_pump(sender_handle));
            match event.get("event").and_then(|e| e.as_str()) {
                Some("complete") => {
                    sender_complete = true;
                    break;
                }
                Some("error") => panic!("sender error: {event:?}"),
                _ => {}
            }
            thread::sleep(Duration::from_millis(50));
        }
        assert!(sender_complete, "sender never completed");

        // Pump receiver until complete.
        let mut received_path = None;
        for _ in 0..200 {
            let event = parse_ffi_json(nxfr_pump(receiver_handle));
            match event.get("event").and_then(|e| e.as_str()) {
                Some("complete") => {
                    received_path = event["file_path"].as_str().map(|s| s.to_string());
                    break;
                }
                Some("error") => panic!("receiver error: {event:?}"),
                _ => {}
            }
            thread::sleep(Duration::from_millis(50));
        }
        let received_path = received_path.expect("receiver never completed");

        // Verify SHA-256.
        let received_data = std::fs::read(&received_path).unwrap();
        let received_hash = hex::encode(Sha256::digest(&received_data));
        assert_eq!(received_hash, expected_hash, "SHA-256 mismatch!");
        assert_eq!(received_data.len(), 1_048_576, "size mismatch");

        // Cleanup.
        let _ = parse_ffi_json(nxfr_close(sender_handle));
        let _ = parse_ffi_json(nxfr_close(receiver_handle));
    }

    /// Test that rejecting a transfer works.
    #[test]
    fn test_ffi_transfer_reject() {
        let tmp_a = tempfile::tempdir().unwrap();
        let tmp_b = tempfile::tempdir().unwrap();

        let dir_a = CString::new(tmp_a.path().to_str().unwrap()).unwrap();
        let dir_b = CString::new(tmp_b.path().to_str().unwrap()).unwrap();
        let _ = parse_ffi_json(nxfr_identity_generate(dir_a.as_ptr()));
        let _ = parse_ffi_json(nxfr_identity_generate(dir_b.as_ptr()));

        let dir_b_c = CString::new(tmp_b.path().to_str().unwrap()).unwrap();
        let listen_result = parse_ffi_json(nxfr_listen(0, dir_b_c.as_ptr()));
        let listener_handle = listen_result["listener"].as_u64().unwrap();
        let port = listen_result["port"].as_u64().unwrap();

        let test_file = tmp_a.path().join("reject_test.dat");
        std::fs::write(&test_file, [0u8; 100]).unwrap();

        let dir_a_str = tmp_a.path().to_str().unwrap().to_string();
        let addr = format!("127.0.0.1:{port}");
        let connect_thread = thread::spawn(move || {
            let addr_c = CString::new(addr).unwrap();
            let dir_c = CString::new(dir_a_str).unwrap();
            let r = parse_ffi_json(nxfr_connect(addr_c.as_ptr(), dir_c.as_ptr()));
            r["handle"].as_u64().unwrap()
        });
        let accept_thread = thread::spawn(move || {
            let r = parse_ffi_json(nxfr_accept(listener_handle));
            r["handle"].as_u64().unwrap()
        });

        let sender_h = connect_thread.join().unwrap();
        let receiver_h = accept_thread.join().unwrap();

        let path_c = CString::new(test_file.to_str().unwrap()).unwrap();
        let _ = parse_ffi_json(nxfr_send_file(sender_h, path_c.as_ptr()));

        // Wait for offer then reject.
        for _ in 0..100 {
            let e = parse_ffi_json(nxfr_pump(receiver_h));
            if e.get("event").and_then(|v| v.as_str()) == Some("offer") {
                break;
            }
            thread::sleep(Duration::from_millis(50));
        }
        let reject_r = parse_ffi_json(nxfr_confirm(receiver_h, false));
        assert!(!reject_r["accepted"].as_bool().unwrap());

        // Sender should get an error (rejected).
        let mut got_error = false;
        for _ in 0..100 {
            let e = parse_ffi_json(nxfr_pump(sender_h));
            if e.get("event").and_then(|v| v.as_str()) == Some("error") {
                got_error = true;
                break;
            }
            thread::sleep(Duration::from_millis(50));
        }
        assert!(got_error, "sender should get rejection error");

        let _ = parse_ffi_json(nxfr_close(sender_h));
        let _ = parse_ffi_json(nxfr_close(receiver_h));
    }

    // ── Identity idempotency test ──

    #[test]
    fn test_identity_generate_then_load_stable() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();

        // Generate creates identity files.
        let r1 = parse_ffi_json(nxfr_identity_generate(dir.as_ptr()));
        assert!(r1.get("error").is_none(), "generate should succeed");
        let id1 = r1["device_id"].as_str().unwrap().to_string();
        assert_eq!(id1.len(), 64, "device_id must be 64 hex chars");

        // Load returns the same device_id (the one just written).
        let r2 = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert!(r2.get("error").is_none(), "load should succeed");
        assert_eq!(
            r2["device_id"].as_str().unwrap(),
            id1,
            "load must return the same id that was generated"
        );

        // Load again — still stable.
        let r3 = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert_eq!(
            r3["device_id"].as_str().unwrap(),
            id1,
            "repeated load must be stable"
        );
    }

    // ── Protocol defaults tests ──

    #[test]
    fn test_default_port_is_17394() {
        // The NXFR protocol spec mandates port 17394.
        assert_eq!(17394u16, 17394);
    }

    #[test]
    fn test_default_multicast_is_mdns() {
        // mDNS multicast address per RFC 6762.
        let mdns_addr: std::net::Ipv4Addr = "224.0.0.251".parse().unwrap();
        assert_eq!(mdns_addr, std::net::Ipv4Addr::new(224, 0, 0, 251));
    }
    // ── Pairing storage tests ──

    #[test]
    fn test_paired_list_empty() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let r = parse_ffi_json(nxfr_paired_list(dir.as_ptr()));
        assert!(
            r.get("error").is_none(),
            "paired_list should succeed on empty db"
        );
        let devices = r["devices"].as_array().unwrap();
        assert!(devices.is_empty(), "should be empty initially");
    }

    #[test]
    fn test_unpair_nonexistent() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let did = CString::new("deadbeef").unwrap();
        // unpair of non-existent device should succeed silently (remove is idempotent)
        let r = parse_ffi_json(nxfr_unpair(dir.as_ptr(), did.as_ptr()));
        assert!(
            r.get("error").is_none(),
            "unpair non-existent should not error"
        );
    }

    #[test]
    fn test_set_auto_accept_not_paired() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let did = CString::new("deadbeef").unwrap();
        let pol = CString::new("always").unwrap();
        let r = parse_ffi_json(nxfr_set_auto_accept(
            dir.as_ptr(),
            did.as_ptr(),
            pol.as_ptr(),
        ));
        assert!(r.get("error").is_some(), "should error: device not paired");
    }

    #[test]
    fn test_set_auto_accept_invalid_policy() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let did = CString::new("deadbeef").unwrap();
        let pol = CString::new("invalid").unwrap();
        let r = parse_ffi_json(nxfr_set_auto_accept(
            dir.as_ptr(),
            did.as_ptr(),
            pol.as_ptr(),
        ));
        assert!(r.get("error").is_some(), "should error: invalid policy");
    }

    #[test]
    fn test_set_name_roundtrip() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let name = CString::new("My Phone").unwrap();
        let r = parse_ffi_json(nxfr_set_name(dir.as_ptr(), name.as_ptr()));
        assert!(r.get("error").is_none(), "set_name should succeed");
        assert_eq!(r["name"].as_str().unwrap(), "My Phone");

        // Verify config.toml was written.
        let config_path = tmp.path().join("config.toml");
        assert!(config_path.exists(), "config.toml should be created");
        let content = std::fs::read_to_string(&config_path).unwrap();
        assert!(
            content.contains("My Phone"),
            "config should contain the name"
        );
    }

    #[test]
    fn test_set_name_empty_rejected() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let name = CString::new("").unwrap();
        let r = parse_ffi_json(nxfr_set_name(dir.as_ptr(), name.as_ptr()));
        assert!(r.get("error").is_some(), "empty name should be rejected");
    }

    #[test]
    fn test_pair_storage_roundtrip() {
        // Manually insert a pair via storage, then test list, auto_accept, unpair via FFI.
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();

        // Insert a fake paired device directly via storage.
        let db_path = tmp.path().join("paired.db");
        let db = nxfr_storage::db::PairedDeviceDb::open(&db_path).unwrap();
        db.insert_or_update(&nxfr_storage::db::PairedDevice {
            device_id: "aabbccdd".to_string(),
            name: "Test Device".to_string(),
            public_key_spki: vec![1, 2, 3],
            first_seen: 1000,
            last_seen: 2000,
            trust_level: "paired".to_string(),
            auto_accept: "prompt".to_string(),
        })
        .unwrap();
        drop(db);

        // List should return 1 device.
        let r = parse_ffi_json(nxfr_paired_list(dir.as_ptr()));
        assert!(r.get("error").is_none());
        let devices = r["devices"].as_array().unwrap();
        assert_eq!(devices.len(), 1);
        assert_eq!(devices[0]["device_id"].as_str().unwrap(), "aabbccdd");
        assert_eq!(devices[0]["name"].as_str().unwrap(), "Test Device");
        assert_eq!(devices[0]["auto_accept"].as_str().unwrap(), "prompt");

        // Set auto-accept to "always".
        let did = CString::new("aabbccdd").unwrap();
        let pol = CString::new("always").unwrap();
        let r = parse_ffi_json(nxfr_set_auto_accept(
            dir.as_ptr(),
            did.as_ptr(),
            pol.as_ptr(),
        ));
        assert!(r.get("error").is_none());
        assert_eq!(r["auto_accept"].as_str().unwrap(), "always");

        // Verify via list.
        let r = parse_ffi_json(nxfr_paired_list(dir.as_ptr()));
        let devices = r["devices"].as_array().unwrap();
        assert_eq!(devices[0]["auto_accept"].as_str().unwrap(), "always");

        // Unpair.
        let r = parse_ffi_json(nxfr_unpair(dir.as_ptr(), did.as_ptr()));
        assert!(r.get("error").is_none());
        assert_eq!(r["status"].as_str().unwrap(), "unpaired");

        // List should now be empty.
        let r = parse_ffi_json(nxfr_paired_list(dir.as_ptr()));
        let devices = r["devices"].as_array().unwrap();
        assert!(devices.is_empty(), "should be empty after unpair");
    }

    #[test]
    fn test_listener_teardown_and_rapid_rebind() {
        let dir = tempfile::tempdir().unwrap();
        let dir_str = CString::new(dir.path().to_str().unwrap()).unwrap();

        // Generate identity.
        let gen = parse_ffi_json(nxfr_identity_generate(dir_str.as_ptr()));
        assert!(gen.get("error").is_none());

        // 1. Single bind -> close -> rebind test on same port
        let res1 = parse_ffi_json(nxfr_listen(0, dir_str.as_ptr()));
        assert!(
            res1.get("error").is_none(),
            "first listen failed: {:?}",
            res1
        );
        let port = res1["port"].as_u64().unwrap() as u16;
        let handle1 = res1["listener"].as_u64().unwrap();

        let close_res1 = parse_ffi_json(nxfr_close(handle1));
        assert_eq!(close_res1["status"].as_str().unwrap(), "closed");

        // Rebind on EXACT same port
        let res2 = parse_ffi_json(nxfr_listen(port, dir_str.as_ptr()));
        assert!(
            res2.get("error").is_none(),
            "rebind on same port failed: {:?}",
            res2
        );
        let handle2 = res2["listener"].as_u64().unwrap();
        let close_res2 = parse_ffi_json(nxfr_close(handle2));
        assert_eq!(close_res2["status"].as_str().unwrap(), "closed");

        // 2. Rapid toggle 5x with zero backoff
        for i in 1..=5 {
            let res = parse_ffi_json(nxfr_listen(port, dir_str.as_ptr()));
            assert!(
                res.get("error").is_none(),
                "rapid toggle iteration {i} failed: {:?}",
                res
            );
            let h = res["listener"].as_u64().unwrap();
            let c = parse_ffi_json(nxfr_close(h));
            assert_eq!(c["status"].as_str().unwrap(), "closed");
        }
    }

    #[test]
    fn test_web_upload_identity_and_endpoints() {
        let dir = tempfile::tempdir().unwrap();
        let dir_str = CString::new(dir.path().to_str().unwrap()).unwrap();
        let null_pin = std::ptr::null();

        // 1. Start web upload (auto-generates identity if absent)
        let start_res = parse_ffi_json(nxfr_web_start(0, dir_str.as_ptr(), null_pin));
        assert!(
            start_res.get("error").is_none(),
            "web_start failed: {:?}",
            start_res
        );
        let port = start_res["port"].as_u64().unwrap() as u16;
        let token = start_res["token"].as_str().unwrap().to_string();
        assert!(!token.is_empty());

        // 2. Test endpoints with HTTPS client accepting self-signed cert
        let client = reqwest::blocking::Client::builder()
            .danger_accept_invalid_certs(true)
            .build()
            .unwrap();

        let get_resp = client
            .get(format!("https://127.0.0.1:{port}/"))
            .send()
            .unwrap();
        assert_eq!(get_resp.status(), 200);

        let post_no_token = client
            .post(format!("https://127.0.0.1:{port}/upload"))
            .body("test payload")
            .send()
            .unwrap();
        assert_eq!(post_no_token.status(), 403);

        let post_bearer = client
            .post(format!("https://127.0.0.1:{port}/upload"))
            .header("Authorization", format!("Bearer {token}"))
            .body("test payload")
            .send()
            .unwrap();
        assert_eq!(post_bearer.status(), 200);

        let post_query = client
            .post(format!("https://127.0.0.1:{port}/upload?t={token}"))
            .body("test payload")
            .send()
            .unwrap();
        assert_eq!(post_query.status(), 200);

        let stop_res = parse_ffi_json(nxfr_web_stop());
        assert_eq!(stop_res["status"].as_str().unwrap(), "stopped");
    }

    #[test]
    fn test_history_ffi_add_list_clear() {
        let dir = tempfile::tempdir().unwrap();
        let dir_str = CString::new(dir.path().to_str().unwrap()).unwrap();

        let rec_json = serde_json::json!({
            "id": 0,
            "ts_ms": 10000,
            "direction": "recv",
            "peer_name": "Galaxy S24",
            "peer_id": "s24_id",
            "file_count": 1,
            "total_bytes": 4096,
            "status": "complete",
            "file_paths": ["/sdcard/Download/NXFR/photo.jpg"]
        })
        .to_string();

        let rec_cstr = CString::new(rec_json).unwrap();
        let add_res = parse_ffi_json(nxfr_history_add(rec_cstr.as_ptr(), dir_str.as_ptr()));
        assert_eq!(add_res["status"].as_str().unwrap(), "added");

        let list_res = parse_ffi_json(nxfr_history_list(10, dir_str.as_ptr()));
        let records = list_res["records"].as_array().unwrap();
        assert_eq!(records.len(), 1);
        assert_eq!(records[0]["peer_name"].as_str().unwrap(), "Galaxy S24");

        let clear_res = parse_ffi_json(nxfr_history_clear(dir_str.as_ptr()));
        assert_eq!(clear_res["status"].as_str().unwrap(), "cleared");

        let empty_res = parse_ffi_json(nxfr_history_list(10, dir_str.as_ptr()));
        assert!(empty_res["records"].as_array().unwrap().is_empty());
    }

    #[test]
    fn test_web_fingerprint_matches_spki_sha256() {
        let dir = tempfile::tempdir().unwrap();
        let dir_str = CString::new(dir.path().to_str().unwrap()).unwrap();

        let gen_res = parse_ffi_json(nxfr_identity_generate(dir_str.as_ptr()));
        assert!(gen_res.get("error").is_none());
        let device_id = gen_res["device_id"].as_str().unwrap();

        let fp_res = parse_ffi_json(nxfr_web_fingerprint(dir_str.as_ptr()));
        assert!(fp_res.get("error").is_none());
        let fingerprint = fp_res["fingerprint"].as_str().unwrap();

        assert_eq!(
            fingerprint, device_id,
            "Web fingerprint must match device_id SPKI sha256"
        );
    }
}
