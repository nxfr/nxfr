# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.4.2-alpha] - 2026-08-14

### Added
- **Share via Link PIN Protection**: Optional 4–8 digit security PIN for browser downloads with interactive web PIN gate dialog (`HTML_DOWNLOAD_PAGE` & `HTML_PAGE`), random PIN generator (🎲), and custom PIN editor (✏️).
- **Web Verification Endpoint (`GET /auth`)**: Real-time token and PIN validation before starting streaming file transfers.
- **Instrument Deck UI (Phase 10-ID)**: Complete avionics/flight-deck aesthetic overhaul featuring:
  - **The Beam** transmission visualizer (linear directional wire with scanning pulses, replacing circular radar motifs).
  - **Breaker Switch**: Industrial tactile toggles for listener socket control.
  - **Packet-Stream Console**: Dynamic packet dot animation scaling with transfer speed and live 16-block chunk matrix (`[■■■■■■■■□□□□□□□□]`).
  - **Telemetry Ribbon**: Top status bar displaying live TLS, listener, and pairing states.
  - **Cryptographic Consent Stamps**: Security seals (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU: PAIRED]`), SAS verification digits (`● 123 456 ●`), and tabular payload manifests.
- **Receive via Link (Web Upload)**: Token/PIN-gated browser file upload portal on port `17396` with automatic multi-directory inbox polling (`NxfrService.getWebInboxDirs`) and transfer history tracking.
- **Automated Native Freshness & Symbol Verification**: Gradle task verifying JNI symbol fresh exports before APK assembly.

### Fixed
- **MediaStore Orphan Rows (Phase 10.7 Audit T1)**: `FilePublisher.kt` tracks `insertedUri` and deletes orphaned `IS_PENDING=1` entries on I/O failure, preventing corrupt ghost files in Downloads.
- **Web Upload Path Traversal (Phase 10.7 Audit T2)**: `nxfr-web/src/lib.rs` sanitizes filenames and safely replaces empty/dot traversal sequences (`"."`, `".."` , `"..."`) with `uploaded_file_<rand_hex>.bin`.
- **FFI Error Key Mismatch (Phase 10.7 Audit T3)**: `NxfrService.kt` parses `"error"` key with fallback to `"message"` so failure reasons are properly displayed.
- **Bottom Navigation Bar Modal Leaking (Phase 10.7 Audit T4)**: Bottom navigation bar hides automatically on modal routes (`transfer`, `web_upload`, `web_share`) and tab state is preserved with `saveState`/`restoreState`.
- **Mutex Lock Poisoning Recovery (Phase 10.7 Audit T5)**: Replaced unwrap calls in `nxfr-ffi` with `.lock().unwrap_or_else(|e| e.into_inner())`.
- **Startup Crash in `NxfrScreen`**: Fixed NPE in `bottomNavItems` by using lazy getter property evaluation.
- **Foreground Service Crash**: Guarded `startForegroundWithType` against `ForegroundServiceStartNotAllowedException` on Android 12+.

### Security
- **Strict Fragment-Only Token Isolation**: Access tokens are kept strictly in URL hash fragments (`/#t=<token>`), preventing token leakage in HTTP server logs.
- **Exponential Rate Limiting & IP Lockout**: 5 failed token/PIN attempts trigger an automatic 5-minute block per client IP.

## [0.3.0] - 2026-08-13

### Added
- **Desert Mode (Phase 9)**: Off-grid connectivity engine supporting:
  - **Wi-Fi Direct (P2P)**: Service-owned `NxfrP2pManager` with DNS-SD service discovery, TXT record pairing, and autonomous Group Owner negotiation.
  - **Autonomous SoftAP Hotspot**: Local AP creation with dynamic QR code generation containing SSID, WPA2 passphrase, and listening IP.
  - **Desert Mode Sheet**: Interactive modal sheet for joining or starting off-grid networks with live interface telemetry.

## [0.2.0-alpha] - 2026-08-11

### Added
- **Android app** (Phase 7): full Material3 UI with Receive, Send, Settings tabs
- **UDP beacon discovery** (Phase 7.7): LocalSend-style instant device finding on port 17395, works on hotspots where mDNS fails
- **4-tier discovery ladder**: UDP beacon (Tier 0) → NSD/mDNS (Tier 1) → TCP probe (Tier 2) → Manual (Tier 3)
- **Pairing storage** (Phase 8): FFI functions for paired_list, unpair, set_auto_accept, set_name backed by SQLite
- **Paired Devices UI**: SettingsScreen section with auto-accept toggles, unpair with confirmation, device rename persistence
- **FFI connect timeout**: 5-second tokio timeout prevents infinite hangs
- **Listener EADDRINUSE retry**: automatic retry on bind failure
- **Android CI**: GitHub Actions job for compileDebugKotlin + testDebugUnitTest
- 186 Rust tests (34 FFI, including 7 pairing storage tests)

### Fixed
- **Infinite spinner bug**: doManualConnect now catches all Throwable, always emits Error state
- **Dead UI controls**: About links, notification PendingIntent, transfer cancel, hardcoded strings
- **CLI double-print**: removed redundant TransferResolved broadcast in ipc.rs
- **Duplicate string resources**: cleaned up subagent-introduced duplicates
- **Send tab crash** (Phase 7.8): UdpBeacon.start() ran DatagramSocket.bind() on main thread → NetworkOnMainThreadException. Moved all socket I/O to Dispatchers.IO with try/catch(Throwable).
- **Dead theme picker** (Phase 7.8): theme preference now persisted in SharedPreferences, applied instantly via ThemePreference singleton.
- **Stale Phase-8 stubs** (Phase 7.8): removed placeholder strings, enabled Paired auto-accept, wired global auto-accept policy to NxfrService pump loop.

### Security
- **Privacy hotfix** (Phase 7.9): UDP beacon now broadcasts a daily-rotating `advertised_id` (HKDF-SHA256 of device_id + date) instead of the permanent `device_id`, preventing passive Wi-Fi tracking. Added `test_advertised_id_rotates_daily` host test.
- New threat T10 documented in SECURITY.md: "Passive Tracking via UDP Beacon Sniffing" with mitigation details.
- Protocol §5.6 added: UDP Beacon Discovery specification with privacy-preserving payload format.

## [0.1.0] - 2026-08-09

### Added
- Complete NXFR protocol specification (PROTOCOL.md, WIRE_FORMAT.md, SECURITY.md)
- Pure Rust implementation: nxfr-core, nxfr-crypto, nxfr-transport, nxfr-storage
- Linux daemon (nxfr-daemon) with systemd integration
- CLI (nxfr-cli) with send, watch, accept, status, devices, pair commands
- mDNS zero-configuration discovery via mdns-sd
- TLS 1.3 mutual authentication with self-signed certificates
- SAS (Short Authentication String) pairing with 4-digit codes
- Identity pinning with TOFU (Trust On First Use)
- Interactive user consent with 120-second timeout
- Chunk-level resumable transfers with journal persistence
- Adversarial path rejection (traversal, reserved names, allow-list)
- Single-instance guard with status ping
- 151 unit, integration, and E2E tests

### Security
- Ed25519 identity keys with X.509 self-signed certificates
- HKDF-SHA256 SAS derivation with sorted context binding
- CBOR max nesting depth: 6 (prevents stack exhaustion)
- Zeroization of sensitive key material on drop
