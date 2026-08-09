<p align="center">
  <img src="branding/logo-full.svg" alt="NXFR — Nearby Xfer Protocol" width="300">
</p>

<div align="center">
  <p><strong>Open, secure, cross-platform file transfer over LAN. No cloud, no accounts, no cables.</strong></p>
  <p>
    <a href="https://github.com/nxfr/nxfr/actions"><img src="https://img.shields.io/github/actions/workflow/status/nxfr/nxfr/ci.yml?branch=main" alt="Build Status"></a>
    <a href="https://github.com/nxfr/nxfr/blob/main/LICENSE"><img src="https://img.shields.io/badge/license-MIT%20OR%20Apache--2.0-blue.svg" alt="License"></a>
    <a href="https://github.com/nxfr/nxfr/stargazers"><img src="https://img.shields.io/github/stars/nxfr/nxfr?style=social" alt="GitHub stars"></a>
    <a href="https://nxfr.github.io/nxfr/"><img src="https://img.shields.io/badge/docs-NXFR-blue" alt="Documentation"></a>
  </p>
</div>

---

## Why NXFR?

Unlike AirDrop or Quick Share, NXFR is an open specification designed with transparency, security, and developer freedom in mind.

- **Open Specification:** We believe file transfer should be an open standard, not a proprietary walled garden.
- **No Cloud Dependency:** 100% peer-to-peer over your local network. No accounts, no sign-ins, no data collection.
- **First-Class Linux Support:** Built primarily with Linux in mind, featuring a daemon and CLI architecture.
- **Built-in Resumable Transfers:** Transfers that get interrupted can be resumed right where they left off.
- **No Vendor Lock-in:** Anyone can implement the NXFR protocol on any device or platform.

## Protocol at a Glance

| Property | Value |
|----------|-------|
| Transport | TCP + TLS 1.3 |
| Discovery | mDNS/DNS-SD (`_nxfr._tcp`) |
| Encoding | CBOR (RFC 8949) |
| Auth | ECDSA P-256 + TOFU + SAS |
| Port | 17394 |
| Max Chunk | 1 MiB |
| Resume | Chunk-level with journal |

## Features

- **TLS 1.3 Encryption:** All transfers are fully encrypted with modern standards.
- **Cross-Platform:** The specification supports Linux, Android, Windows, and more.
- **Resumable Chunk-Level Transfers:** Reliable file delivery even on flaky networks.
- **mDNS Zero-Config Discovery:** Instantly find other NXFR-enabled devices on your LAN.
- **Interactive User Consent Model:** Receive explicit prompts before any file is saved.
- **SAS-Based Pairing:** Secure device pairing using Short Authentication Strings (4-digit codes) to prevent MITM attacks.

## Quick Start

### Prerequisites
You will need Rust installed on your system via [rustup](https://rustup.rs/).

### Installation

Clone the repository and install the daemon and CLI components:

```bash
cargo install --path crates/nxfr-daemon
cargo install --path crates/nxfr-cli
```

### Usage

Start the daemon in the background (or manage it via systemd):

```bash
nxfr-daemon &
```

Check the status of your daemon and your device identity:

```bash
nxfr status
```

Watch for incoming transfer requests:

```bash
nxfr watch
```

Send a file to a discovered device:

```bash
nxfr send /path/to/file.pdf --to <device-name>
```

## Architecture

```
┌────────────────────────────────┐
│        Platform UI Layer       │  (GTK4 / Android / WinUI / CLI)
├────────────────────────────────┤
│    Platform Integration Layer  │  (D-Bus / Keystore / SChannel / Intents)
├────────────────────────────────┤
│      Protocol Core (pure)      │  (State machines, CBOR, Framing, Auth)
├────────────────────────────────┤
│     Transport Layer (async)    │  (TLS 1.3, TCP Sockets)
├────────────────────────────────┤
│       Discovery Layer          │  (mDNS/DNS-SD, Avahi, NsdManager)
└────────────────────────────────┘
```

The project is structured as a Cargo workspace containing the following crates:

- `nxfr-core`: Core protocol types, messages, and state machines.
- `nxfr-crypto`: TLS 1.3 integration, SAS derivation, and device identity.
- `nxfr-transport`: Protocol framing, chunking, and network I/O.
- `nxfr-storage`: Database, configuration, and transfer resume journals.
- `nxfr-discovery`: mDNS-based device discovery and advertising.
- `nxfr-daemon`: The background service running on Linux.
- `nxfr-cli`: The command-line interface for user interaction.
- `nxfr-loopback`: Utilities for testing the protocol in a loopback environment.

## Documentation

Full documentation for the project and protocol specification can be found at:
- **[https://nxfr.github.io/nxfr/](https://nxfr.github.io/nxfr/)**
- **[`docs/PROTOCOL.md`](docs/PROTOCOL.md)** for protocol internals.

## Contributing

We welcome contributions from the community! Please read our [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to get started, our code style guidelines, and the pull request process.

## License

NXFR is dual-licensed under either the **MIT License** or the **Apache License 2.0**, at your option.

By contributing to NXFR, you agree that your contributions will be licensed under both licenses.
