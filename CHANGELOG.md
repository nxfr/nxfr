# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
- Ed25519 identity keys with self-signed X.509 certificates.
- HKDF-SHA256 SAS derivation with sorted context binding.
- CBOR nesting depth limit of 6 to prevent recursion attacks.
- Zeroization of sensitive key material on drop.
