# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- **Sender false-positive completion (H3)**: `FfiEvent::Complete` was emitted even when the receiver reported partial failure. Now only fires on `TransferAckStatus::Success`; partial failures map to `FfiEvent::Error`.
- **Receiver state machine missing transitions (H7)**: Added `AllChunksReceived` and `AckSent` events to the transfer state machine. Receivers now go through `Streaming → Completing → Complete` instead of skipping straight to Complete.
- **`/dl/all.zip` buffered entire archive in memory (H4)**: Rewrote the multi-file ZIP download endpoint to stream using chunked transfer encoding (`ChunkedWriter`). No more OOM on large share sessions.
- **Web server mutex deadlock (H6)**: `nxfr_web_respond_request` held a mutex lock across an `rt.block_on()` call, causing deadlocks under concurrent requests. Fixed by cloning the handle out of the lock first.
- **Native listener/session handle leaks on Android (H5)**: `NxfrService` wasn't closing native handles in `onDestroy`, disconnect handler, or send-error paths. Added `nxfr_close()` calls in all three.
- **SHA-256 computed on main thread (H8)**: `TransferScreen.kt` was hashing files synchronously on the Compose main thread. Moved to `withContext(Dispatchers.IO)` with a streaming 64KB buffer.
- **Web share/upload lifecycle not tied to Composable disposal (H9)**: `DisposableEffect` wasn't stopping the web server on screen exit. Added explicit `stopAndCleanup()` in both `WebShareScreen` and `WebUploadScreen`.
- **History status mismatch (H1)**: Status strings were inconsistent (`"complete"` vs `"completed"`) across `WebUploadScreen.kt`, `HistorySheet.kt`, and `RecentSessionsCard.kt`. Standardized to `"completed"`.
- **Settings screen wrong identity directory (H2)**: `SettingsScreen.kt` was using `filesDir` instead of `NxfrService.getIdentityDir(context)`.
- **Send file path and multi-file history (H10)**: `doSendFile` wasn't passing absolute paths, and multi-file transfers only recorded one history entry.
- **History timestamp key mismatch (M1)**: `RecentSessionsCard.kt` was reading `"timestamp"` instead of `"ts_ms"`.
- **History DB contention under concurrent access (M3)**: Added WAL journal mode and `busy_timeout(5000)` to `HistoryDb::open()`.
- **History loading on main thread (M4)**: Wrapped `loadHistory()` in `withContext(Dispatchers.IO)`.
- **History recorded regardless of user preference (M6)**: Guarded history recording on `NxfrPreferences.saveToHistory`.
- **Cancelled/disconnected transfers not recorded in history (M5)**: Disconnect handler now records a history entry with `status="failed"`.
- **Listener port rebind race on Android (M7)**: `updateActivePortAndRebind()` now cancels and joins the old listener job and closes the native handle before rebinding.
- **Web I/O missing timeouts (M8)**: Added 15s header-read timeout and 30s chunk I/O timeout on all upload/download paths.
- **Zero-byte file Range request crash (M9)**: `Range` header on a zero-byte file caused an underflow. Now returns 416 immediately.
- **Temp file collisions and orphaned files (M10)**: Added `TmpFileGuard` RAII cleanup, random temp filenames, and `resolve_collision` renaming (`file (1).txt`).
- **Web fingerprint key inconsistency (L2)**: `nxfr_web_fingerprint` now returns both `"fingerprint"` and `"spki_sha256"` keys. `WebShareScreen.kt` falls back from one to the other.
- **File count off-by-one for single files (L3)**: Fixed file count computation in `nxfr_send_file`.
- **Unsafe CString unwrap in JNI (L5)**: Replaced `CString::new(s).unwrap()` in `jni_bindings.rs` with safe error handling for interior NUL bytes.

### Added
- **Forward-compatible error codes (L4)**: Added `ErrorCode::Unknown(String)` variant and `from_wire_str()` fallback in `nxfr-core`, so unknown error codes from newer protocol versions don't crash older clients.
- **Peer ID propagation (L1)**: `peer_id` (SPKI SHA-256 hex) now included in `FfiEvent::Complete` and `FfiEvent::Error` JSON output from `nxfr_pump`.
- **Web download history tracking (M2)**: Atomic download counter and history recording for web share downloads.
- **`SOCK_CLOEXEC` on listener sockets**: `create_reuseaddr_listener` now uses `SOCK_CLOEXEC` to prevent fd leaks across exec.

### Changed
- `nxfr-core` test count: 100 → 107 (new transfer state machine and error code tests)
- `nxfr-ffi` test count: 38 → 44 (new CString safety, collision policy, history, and web endpoint tests)
- `nxfr-web` test count: 7 → 13 (new chunked streaming, timeout, temp file, and ZIP tests)

## [1.0.0] - 2026-08-16

### Added
- Desert Mode three-tier auto-discovery orchestrator (Wi-Fi Direct → SoftAP → QR fallback)
- `DesertModeOrchestrator` state machine with automatic tier escalation
- QR code generation and scanning for SoftAP network credentials
- `WifiNetworkSpecifier` integration for Android 10+ network joining (replaces deprecated `WifiManager.addNetwork`)

### Fixed
- Desert Mode QR scan crash (`SecurityException` on Android 10+) caused by missing `CHANGE_NETWORK_STATE` permission and legacy Wi-Fi join API
- Added `removeCapability(NET_CAPABILITY_INTERNET)` to `NetworkRequest` for local-only SoftAP networks
- Rust clippy warnings: unused imports in `nxfr-daemon`, type complexity in `nxfr-ffi`
- Rust formatting violations in `nxfr-ffi` test code
- CI: skip native library verification for Kotlin-only CI runs

### Changed
- Bumped Android `versionName` to `1.0.0` (versionCode 23)
- Bumped Rust workspace version to `1.0.0`
- Protocol version promoted to v1.0.0

## [0.4.5-alpha] - 2026-08-15

### Security & Cryptography
- **Strict TLS 1.3 Handshake Signature Verification**:
  - Replaced no-op assertions in `NoServerVerifier` and `NoClientVerifier` with real cryptographic handshake signature verification (`rustls::crypto::verify_tls13_signature` and `verify_tls12_signature`) backed by `ring`'s verification algorithms.
  - Ensures mutual key possession is cryptographically proven during the mTLS handshake while preserving application-layer TOFU certificate binding. Added full integration tests against key/cert mismatch attacks.
- **Web Portal Cross-Site Scripting (XSS) Mitigation**:
  - Refactored web portal manifest rendering from raw template string `innerHTML` interpolation to safe programmatic DOM element construction with `textContent` auto-escaping.
  - Malicious filenames (e.g. `<img src=x onerror=alert(1)>.txt`) now render safely as literal text.
- **Logcat Credential Redaction**: Redacted raw authentication tokens from application logcat output (`token=****`) across web sharing components.

### Memory Safety & Concurrency
- **JNI SAS Derivation Bounds Check**:
  - Fixed out-of-bounds memory read in JNI SAS derivation (`nxfr_derive_sas`) by enforcing minimum byte length checks before accessing raw memory pointers.
  - Added explicit `exporter_len: usize` to C-ABI export and added unit tests for 0-, 1-, and 3-byte inputs.
- **FFI Session Close Deadlock Prevention**:
  - Restructured reader tasks in `nxfr-ffi` to take ownership of the connection before awaiting `recv_frame()`, releasing the Tokio `Mutex` during framed network I/O.
  - Added 3-second lock acquisition timeouts and `Arc::strong_count` session-liveness guards to prevent deadlocks and connection leaks when `nxfr_close` is invoked concurrently.

### Network Resiliency & DoS Protection
- **Slowloris & Connection Starvation Defense**:
  - Implemented a 10-second timeout on TLS handshake completion across `nxfr-ffi` and `nxfr-daemon`.
  - Added concurrency-bounding semaphores (100 permits in FFI listener, 200 in daemon) to protect against connection table exhaustion.
- **File Descriptor Starvation Backoff**:
  - Added 50ms exponential retry backoff on TCP `accept()` errors (e.g. `EMFILE`/`ENFILE`), eliminating CPU spin loops under file descriptor exhaustion.

### Android Lifecycle & Battery
- **Adaptive UDP Beacon Ladder**:
  - Replaced static 1-second discovery beaconing with a state-aware frequency ladder: `ACTIVE` (1s, foreground & device picker), `BACKGROUND` (5s, active background transfer), and `LOW_POWER` (30s, deep idle background).
  - Integrated dynamic mode transitions into `evaluateLifecycleContract`, `onStart`/`onStop` hooks, and screen composables.
- **Web Server Lifecycle Teardown**:
  - Guaranteed immediate web server shutdown and port `17396` release in `onTaskRemoved` (swipe-away) and `onDestroy`, eliminating `EADDRINUSE` conflicts on restart.
- **Android 14+ Foreground Service Timeout**:
  - Implemented `onTimeout(startId)` in `NxfrService` to gracefully flush state and release network resources on OS-enforced service timeouts.
- **Android 12+ Notification Action Fix**:
  - Switched notification cancel actions to `PendingIntent.getForegroundService()` to eliminate `IllegalStateException` crashes on API 31+.
- **Storage Access Framework (SAF) Resiliency**:
  - Wrapped content resolver stream operations in `SecurityException` guards in `StagingRepository`, ensuring revoked permissions on a single file skip gracefully with user toast notifications rather than aborting multi-file batches.
- **Touch Target & Accessibility Compliance**:
  - Expanded interactive UI elements (attach chip rail, web upload buttons) to meet the standard 48dp minimum accessible touch target.
- **Consent Dialog Dismissal Lock**:
  - Added `confirmValueChange = { false }` to the incoming transfer consent sheet to prevent accidental swipe or back-gesture dismissal.

### Added
- **Share-via-Link Inactivity Timeout & Active Transfer Deferral**:
  - The 10-minute web share timer is now an **idle/silence timer**, not a hard stopwatch.
  - Active transfers hold an `ActiveTransferGuard` tracking live byte streams. `last_activity` is bumped on every accepted request and every chunk transferred.
  - Expiry loop defers shutdown while active transfers are in flight and drains gracefully before closing listeners.
  - Configurable expiry via `start_share_with_expiry` and `NXFR_WEB_EXPIRY_SECS` environment variable.
  - Exposed `nxfr_web_status()` FFI function returning live server state and active transfer counts.
- **UI Telemetry for Active Web Transfers**: `WebShareScreen.kt` displays `"TRANSFER ACTIVE — auto-stop deferred"` in signal cyan during active downloads and resumes silence countdown upon completion.
- **Automated Lifecycle Cache Cleaner**: Introduced `CacheCleaner.kt` with automatic recursive cache purging on app startup (`NxfrApp.onCreate`) and transfer completion/cancellation to prevent orphaned staging files (`staging_*`, `web-share-staging`, `send_*`, `apps/`, `debug_bundle_*`).

### Fixed
- **Large File Send OOM Crash**: Replaced in-memory `std::fs::read` in `scan_send_path` with `hash_file_stream`, streaming files in fixed 64 KB chunks to eliminate memory spikes and native OOM crashes on 1GB+ files.
- **Empty Directory Transfer Race Condition**: Fixed race condition in `SendScreen.kt` where tapping a peer without staged items created an empty `staging_<timestamp>` directory, causing `"directory is empty"` errors.
- **Desert Mode Send Robustness**: Prevented direct transfers from initiating without selected items and added defensive validation in `StagingRepository.prepareStagingDirectory`.

## [0.4.3-alpha] - 2026-08-15

### Fixed
- **Wi-Fi Direct Process Network Binding**: Bound the Android application process to the Wi-Fi Direct network interface (`ConnectivityManager.bindProcessToNetwork`), ensuring native Rust TCP sockets route directly to the Group Owner IP (`192.168.49.1`).
- **Desert Connection Budget**: Added retry logic and extended connection budget for off-grid socket initialization.

## [0.4.2-alpha] - 2026-08-14

### Added
- **Share via Link PIN Protection**: Optional 4–8 digit security PIN for browser downloads with interactive web PIN gate dialog, random PIN generator, and custom PIN editor.
- **Web Verification Endpoint (`GET /auth`)**: Real-time token and PIN validation before starting streaming file transfers.
- **Instrument Deck UI**: High-contrast interface layout featuring:
  - **The Beam** transmission visualizer (linear directional channel with live data flows).
  - **Breaker Switch**: Mechanical toggle switches for listener socket control.
  - **Packet-Stream Console**: Dynamic packet dot animation scaling with transfer speed and live 16-block chunk matrix (`[■■■■■■■■□□□□□□□□]`).
  - **Telemetry Ribbon**: Top status bar displaying live TLS, listener, and pairing states.
  - **Cryptographic Consent Stamps**: Security seals (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU: PAIRED]`), SAS verification digits, and tabular payload manifests.
- **Receive via Link (Web Upload)**: Token/PIN-gated browser file upload portal on port `17396` with multi-directory inbox polling and transfer history tracking.
- **Automated Native Freshness & Symbol Verification**: Gradle task verifying JNI symbol exports before APK assembly.

### Fixed
- **MediaStore Orphan Rows**: `FilePublisher.kt` tracks inserted URIs and deletes orphaned `IS_PENDING=1` entries on I/O failure.
- **Web Upload Path Traversal**: Sanitized filenames and safely replaced empty or dot traversal sequences (`"."`, `".."` , `"..."`) with random identifiers.
- **FFI Error Key Parsing**: `NxfrService.kt` parses `"error"` key with fallback to `"message"` so failure reasons are properly displayed.
- **Navigation Modal Visibility**: Bottom navigation bar hides automatically on modal routes (`transfer`, `web_upload`, `web_share`) and tab state is preserved with `saveState`/`restoreState`.
- **Mutex Lock Recovery**: Handled mutex poisoning safely in `nxfr-ffi` using `.unwrap_or_else(|e| e.into_inner())`.
- **Navigation Route Evaluation**: Fixed NPE in `bottomNavItems` by using lazy getter property evaluation.
- **Foreground Service Lifecycle**: Wrapped `startForegroundWithType` safely against Android 12+ foreground service exceptions.

### Security
- **Fragment-Only Token Isolation**: Access tokens are kept in URL hash fragments (`/#t=<token>`), preventing token leakage in HTTP server logs.
- **Rate Limiting & IP Lockout**: 5 failed token/PIN attempts trigger an automatic 5-minute block per client IP.

## [0.3.0] - 2026-08-13

### Added
- **Desert Mode (Off-Grid Connectivity)**:
  - **Wi-Fi Direct (P2P)**: Service-owned `NxfrP2pManager` with DNS-SD service discovery, TXT record pairing, and autonomous Group Owner negotiation.
  - **Autonomous SoftAP Hotspot**: Local AP creation with dynamic QR code generation containing SSID, WPA2 passphrase, and listening IP.
  - **Desert Mode Sheet**: Interactive sheet for joining or starting off-grid networks with live interface telemetry.

## [0.2.0-alpha] - 2026-08-11

### Added
- **Android Application**: Full Compose UI with Receive, Send, and Settings tabs.
- **UDP Beacon Discovery**: Instant device discovery on port 17395 for local networks where mDNS multicast is filtered.
- **Multi-Tier Discovery Ladder**: UDP beacon (Tier 0) → NSD/mDNS (Tier 1) → TCP probe (Tier 2) → Manual (Tier 3).
- **Pairing Storage**: FFI functions for `paired_list`, `unpair`, `set_auto_accept`, `set_name` backed by SQLite.
- **Paired Devices UI**: Settings section with auto-accept toggles, unpair confirmation, and device renaming.
- **FFI Connect Timeout**: 5-second timeout preventing hangs on unresponsive addresses.
- **Listener Port Retry**: Automatic retry on listener bind conflicts.
- **Android CI**: GitHub Actions workflow for Kotlin compilation and unit testing.

### Fixed
- **Manual Connect Exception Handling**: `doManualConnect` catches all exceptions and emits error states reliably.
- **CLI IPC Duplicate Broadcasts**: Removed redundant `TransferResolved` broadcast in `ipc.rs`.
- **UDP Beacon Threading**: Moved UDP beacon socket initialization to background IO dispatcher.
- **Theme Preference Persistence**: Theme preferences persisted in SharedPreferences and applied immediately.

### Security
- **Ephemeral Advertised IDs**: UDP beacon broadcasts daily-rotating `advertised_id` (HKDF-SHA256 of device ID + date) instead of permanent device IDs.
- **Threat Model Documentation**: Documented passive tracking threats and mitigations in `SECURITY.md`.

## [0.1.0] - 2026-08-09

### Added
- Complete NXFR protocol specification (`PROTOCOL.md`, `WIRE_FORMAT.md`, `SECURITY.md`).
- Rust implementation: `nxfr-core`, `nxfr-crypto`, `nxfr-transport`, `nxfr-storage`.
- Linux daemon (`nxfr-daemon`) with systemd unit configuration.
- Command-line client (`nxfr-cli`) with `send`, `watch`, `accept`, `status`, `devices`, and `pair` commands.
- mDNS discovery via `mdns-sd`.
- TLS 1.3 mutual authentication with self-signed X.509 certificates.
- SAS (Short Authentication String) pairing with 6-digit codes.
- TOFU (Trust On First Use) identity pinning.
- Interactive transfer consent with timeout.
- Chunk-level resumable transfers with journal persistence.
- Canonical path boundary checks for traversal defense.
- Single-instance daemon guard via status socket.

### Security
- ECDSA P-256 identity keys with self-signed X.509 certificates.
- HKDF-SHA256 SAS derivation with sorted context binding.
- CBOR nesting depth limit of 6 to prevent recursion attacks.
- Zeroization of sensitive key material on drop.
