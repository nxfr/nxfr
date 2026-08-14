<p align="center">
  <img src="branding/logo-full.svg" alt="NXFR — Sovereign Peer-to-Peer Transfer Protocol" width="360">
</p>

<div align="center">
  <h3><strong>No cloud. No accounts. Just math.</strong></h3>
  <p>An open, sovereign, cryptographic peer-to-peer file transfer protocol and flagship Android/Linux client.</p>
  <p>
    <a href="https://github.com/nxfr/nxfr/actions"><img src="https://img.shields.io/github/actions/workflow/status/nxfr/nxfr/ci.yml?branch=main" alt="Build Status"></a>
    <a href="https://github.com/nxfr/nxfr/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License"></a>
    <a href="https://github.com/nxfr/nxfr/stargazers"><img src="https://img.shields.io/github/stars/nxfr/nxfr?style=social" alt="GitHub stars"></a>
    <a href="https://nxfr.github.io/nxfr/"><img src="https://img.shields.io/badge/spec-v0.3.0-00E5FF" alt="Protocol Spec"></a>
  </p>
</div>

---

## 🧭 Why NXFR?

Unlike proprietary walled gardens (AirDrop, Quick Share) or bloated web wrappers, NXFR is a pure peer-to-peer standard built on uncompromising cryptographic engineering and avionics-grade aesthetics.

- **Zero-Cloud Architecture**: 100% peer-to-peer over local Wi-Fi, Ethernet, or off-grid Hotspots. No telemetry, no accounts, no tracking.
- **Instrument Deck UI**: High-contrast, mathematical visual identity featuring **The Beam** transmission motif, industrial **Breaker Switches**, and tabular monospace telemetry.
- **Zero-Permission Staging Matrix**: Send files, media, folders, apps, and contacts via system contracts without granting invasive runtime storage permissions.
- **3-Tier Storage Engine**: Automatic MediaStore indexing for instant gallery visibility, SAF directory tree writing, and sandbox fallback.
- **First-Class Rust Core**: Memory-safe, high-throughput protocol engine running natively on Linux daemons and Android JNI bindings.

---

## 🎛️ Protocol & Engine Invariants

| Invariant | Specification | Description |
| :--- | :--- | :--- |
| **Transport** | TCP + mTLS 1.3 | Authenticated, encrypted socket pipeline on port `17394`. |
| **Identity Keys** | Ed25519 / Curve25519 | Persistent sovereign node identities (`did:nxfr:<short_id>`). |
| **Trust Model** | TOFU + SAS | Trust-On-First-Use key pinning + Short Authentication String verification. |
| **Discovery** | mDNS / DNS-SD (`_nxfr._tcp`) | Local beacon broadcasting with daily-rotating ephemeral IDs. |
| **Encoding** | CBOR (RFC 8949) | Compact binary object representation for transfer handshakes. |
| **Integrity** | Streaming SHA-256 | Real-time payload checksum verification with live chunk matrix. |
| **Web Endpoint** | Token-Gated HTTPS | Port `17396` with client-side fragment-only tokens (`/#t=<token>`). |

---

## 🔒 Security Invariants

1. **Mandatory TLS 1.3 (Cannot Be Disabled)**: Every pipe between NXFR nodes is authenticated with mutual TLS 1.3 using ephemeral session keys. There is no toggle to disable encryption.
2. **Path-Jail Enforcement**: All incoming paths are verified against strict canonical sandbox roots (`canonical.starts_with(canonical_inbox)`), rendering path-traversal attacks (`../`) impossible.
3. **Fragment-Only Web Tokens**: Web share links store authorization secrets in URL fragments (`https://<ip>:17396/#t=<token>`). Fragment identifiers are processed only by local browser JavaScript and never hit HTTP request logs.
4. **Interactive Cryptographic Consent**: No file data is accepted without user authorization matching SAS codes and audited file manifests.

---

## 📱 The Instrument Deck Interface

The NXFR UI presentation layer is modeled after precision flight decks and cryptographic consoles:

- **Telemetry Ribbon**: Top status strip displaying live invariants: `● TLS 1.3 ENCRYPTED`, `TCP 17394 [LISTEN]`, and `TOFU: N PAIRED`.
- **The Beam Visualizer**: Cryptographic transmission wire between local node and broadcast target with scanning sweeps and real-time streaming packets.
- **Physical Visibility Breaker**: Industrial mechanical switch controlling native socket listeners and broadcasting beacons.
- **Packet-Stream Console**: Active transfer screen featuring dynamic packet dots scaling with throughput ($MB/s$) and a 16-block chunk matrix (`CHUNKS: [■■■■■■■■■■□□□□□□]`).
- **Cryptographic Consent Manifest**: Security stamp seals (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU: PAIRED]`), sender telemetry, large SAS auth digits (`● 123 456 ●`), and tabular payload ledgers.

---

## 🛠️ Building & Developing

### Prerequisites
- **Rust Toolchain**: 1.75+ (stable) via [rustup.rs](https://rustup.rs/)
- **Cargo NDK**: `cargo install cargo-ndk`
- **Android SDK & NDK**: Version 26.1+ configured via `ANDROID_HOME`
- **Java JDK**: 17+ (Eclipse Temurin or OpenJDK)

### 1. Build Rust Core Workspace & CLI
```bash
# Clone the repository
git clone https://github.com/nxfr/nxfr.git
cd nxfr

# Build daemon and CLI
cargo build --release

# Run Rust unit tests
cargo test --workspace
```

### 2. Build Android Native Libraries (`cargo ndk`)
Compile the native `libnxfr_ffi.so` binaries for Android architectures:
```bash
cargo ndk \
  -t arm64-v8a \
  -t x86_64 \
  -o apps/android/app/src/main/jniLibs \
  build --package nxfr-ffi --release
```

### 3. Build & Test Android App (Gradle Gate)
The Gradle build pipeline includes automated freshness verification (`verifyNativeFresh` and `verifyNativeSymbols`) ensuring native binaries are always in sync:
```bash
cd apps/android

# Run unit tests and assemble debug APK
./gradlew verifyNativeFresh test assembleDebug
```

---

## 💻 CLI Usage

Start the daemon in the background:
```bash
nxfr-daemon &
```

Inspect node identity and active socket parameters:
```bash
nxfr status
```

Watch for incoming transfer requests:
```bash
nxfr watch
```

Transmit a file or directory:
```bash
nxfr send /path/to/payload.tar.gz --to <peer-name>
```

---

## 🗺️ Roadmap & Next Milestones

- **Phase 11**: Desert Mode (Off-grid Wi-Fi Direct & Autonomous SoftAP Hotspot).
- **Phase 10 (Core)**: iroh / Anywhere Mode (QUIC NAT hole-punching for wide-area transfers).
- **Phase 12**: Native Linux (GTK4/Libadwaita), Windows (WinUI 3), and iOS (SwiftUI) shells.
- **Phase 13**: Packaging & Distribution (F-Droid, Flathub Flatpak, winget, AUR).

---

## 📄 License

Dual-licensed under either of:
- **MIT License** ([LICENSE-MIT](LICENSE) or http://opensource.org/licenses/MIT)
- **Apache License, Version 2.0** ([LICENSE-APACHE](LICENSE) or http://www.apache.org/licenses/LICENSE-2.0)
