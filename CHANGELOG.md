# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

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
