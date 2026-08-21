<p align="center">
  <img src="branding/logo-full.svg" alt="NXFR — Peer-to-Peer Transfer Protocol" width="360">
</p>

<div align="center">
  <h3><strong>No cloud. No accounts. Just math.</strong></h3>
  <p>An open, cryptographic peer-to-peer file transfer protocol and Android/Linux client.</p>
  <p>
    <a href="https://github.com/nxfr/nxfr/actions"><img src="https://img.shields.io/github/actions/workflow/status/nxfr/nxfr/ci.yml?branch=main" alt="Build Status"></a>
    <a href="https://github.com/nxfr/nxfr/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License"></a>
    <a href="https://github.com/nxfr/nxfr/stargazers"><img src="https://img.shields.io/github/stars/nxfr/nxfr?style=social" alt="GitHub stars"></a>
    <a href="https://nxfr.github.io/nxfr/"><img src="https://img.shields.io/badge/spec-v1.0.0-00E5FF" alt="Protocol Spec"></a>
  </p>
</div>

---

## Overview

NXFR is a peer-to-peer file transfer protocol and client implementation built with Rust and Kotlin/Jetpack Compose.

- **Local Network Focus**: Peer-to-peer transfers over Wi-Fi, Ethernet, or local hotspots. No cloud servers, user accounts, or tracking.
- **Strict Scope**: NXFR is LAN + off-grid by design; internet relay mode is out of scope for v1.x.
- **Instrument Deck UI**: High-contrast, technical visual identity featuring linear transmission visualizers, mechanical breaker toggles, and monospace telemetry.
- **Zero-Permission Staging**: Send files, media, folders, installed apps, and contact vCards via Android system pickers without broad storage permissions.
- **Storage Engine**: MediaStore indexing for gallery access, Storage Access Framework for directory trees, and app sandbox fallbacks.
- **Browser Transfers**: Token-gated HTTP server with optional PIN protection for transferring files to and from web browsers.
- **Desert Mode**: Off-grid transfers using Wi-Fi Direct (P2P) and autonomous SoftAP hotspots.
- **Rust Core Engine**: Core protocol logic, cryptography, and network framing implemented in Rust with JNI bindings for Android.

---

## Protocol Invariants

| Invariant | Specification | Description |
| :--- | :--- | :--- |
| **Transport** | TCP + mTLS 1.3 | Authenticated, encrypted socket pipeline on port `17394`. |
| **Identity Keys** | ECDSA P-256 (secp256r1) | Persistent node identities (`did:nxfr:<short_id>`). |
| **Trust Model** | TOFU + SAS | Trust-On-First-Use key pinning and Short Authentication String verification. |
| **Discovery** | mDNS / DNS-SD (`_nxfr._tcp`) + UDP Beacon | Local discovery on `17394`/`17395` with daily-rotating ephemeral IDs. |
| **Encoding** | CBOR (RFC 8949) | Compact binary object representation for transfer handshakes. |
| **Integrity** | Streaming SHA-256 | Real-time payload checksum verification with chunk matrix tracking. |
| **Web Portal** | Token & PIN-Gated HTTPS | Port `17396` with fragment-only tokens (`/#t=<token>`) and 4–8 digit PIN gates. |
| **Off-Grid** | Wi-Fi Direct & SoftAP | Direct device-to-device transfers without external networking hardware. |

---

## Security Invariants

1. **Mandatory TLS 1.3**: Every connection between nodes is authenticated with mutual TLS 1.3 using ephemeral session keys. Encryption cannot be disabled.
2. **Path Jail Enforcement**: Incoming files are validated against canonical sandbox roots (`canonical.starts_with(canonical_inbox)`), preventing path traversal attacks.
3. **Fragment-Only Web Tokens & PIN Protection**: Browser share links keep authentication secrets in URL fragments (`https://<ip>:17396/#t=<token>`) or require numeric PINs with exponential rate limiting.
4. **Interactive Consent**: Transfers require explicit recipient approval matching SAS codes and audited file manifests.
5. **Privacy-Preserving Ephemeral IDs**: UDP beacons and Wi-Fi Direct records use daily-rotating HKDF hashes to prevent passive tracking.

---

## User Interface

The user interface uses the Instrument Deck design system:

- **Telemetry Ribbon**: Displays active protocol state (`TLS 1.3 ENCRYPTED`, `TCP 17394 [LISTEN]`, paired node count).
- **Beam Visualizer**: Linear transmission channel indicating connection state, scan activity, and transfer throughput.
- **Breaker Switch**: Toggle switch for controlling socket listeners and beacon broadcasts.
- **Packet Stream Console**: Real-time throughput indicators and chunk transfer matrix.
- **Consent Dialog**: Transfer verification with security stamps, SAS codes, and file manifests.

---

## Building and Testing

### Prerequisites
- **Rust Toolchain**: 1.75+ (stable) via [rustup.rs](https://rustup.rs/)
- **Cargo NDK**: `cargo install cargo-ndk`
- **Android SDK & NDK**: Version 26.1+ configured via `ANDROID_HOME`
- **Java JDK**: 21+ (Android Studio JBR or Eclipse Temurin recommended)

### 1. Build Rust Core Workspace & CLI
```bash
# Clone the repository
git clone https://github.com/nxfr/nxfr.git
cd nxfr

# Build daemon and CLI
cargo build --release

# Run Rust test suite
cargo test --workspace
```

### 2. Build Android App & Native Binaries
```bash
cd apps/android

# Build native JNI libraries and assemble debug APK
./gradlew rebuildNative assembleDebug

# Run Android unit tests
./gradlew test
```

---

## CLI Usage

Start the daemon:
```bash
nxfr-daemon &
```

Inspect node identity and status:
```bash
nxfr status
```

Watch for incoming transfer requests:
```bash
nxfr watch
```

Send a file or directory:
```bash
nxfr send /path/to/payload.tar.gz --to <peer-name>
```

---

## Roadmap

- ~~Off-Grid Connectivity (Desert Mode)~~: Shipped in v1.0.0.
- **Packaging and Distribution**: F-Droid, Google Play, RPM/DEB/APT repositories, and Flathub Flatpak.
- **Desktop and iOS Shells**: Native Linux (GTK4/Libadwaita), Windows (WinUI 3), and iOS (SwiftUI) applications.

---

## License

Dual-licensed under either of:
- **MIT License** ([LICENSE-MIT](LICENSE-MIT) or http://opensource.org/licenses/MIT)
- **Apache License, Version 2.0** ([LICENSE-APACHE](LICENSE-APACHE) or http://www.apache.org/licenses/LICENSE-2.0)
