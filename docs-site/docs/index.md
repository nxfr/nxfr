# NXFR Protocol

*Secure, zero-configuration file transfer for the modern LAN.*

<div class="grid cards" markdown>

- :material-shield-lock: **Secure by Default**

  ---

  All transfers are encrypted with TLS 1.3. Devices authenticate using ECDSA P-256 identity keys with Trust On First Use (TOFU) and SAS pairing. The `device_id` is pinned after pairing.

- :material-access-point: **Zero Configuration**

  ---

  Discover peers instantly on the local network using mDNS/DNS-SD (`_nxfr._tcp`). No accounts, no cloud servers, and no manual IP entry required. Hidden by default for privacy.

- :material-restart: **Resumable Transfers**

  ---

  Survive network drops with chunk-level resume. NXFR uses a robust CBOR-based framing layer and persists transfer states, ensuring you never re-send completed work.

</div>

## Quick Links

[Read the Spec Overview](spec/overview.md){ .md-button .md-button--primary }
[Security Architecture](security/threat-model.md){ .md-button }

## Architecture Overview

NXFR is organized in five clear, testable layers:

```text
┌─────────────────────────────────────────┐
│          Application / UI Layer         │   User interaction, consent, file picking
├─────────────────────────────────────────┤
│          Transfer Layer (§11-14)        │   Transfer state machine, resume, directory
├─────────────────────────────────────────┤
│          Session Layer (§9-10)          │   HELLO, pairing, message dispatch
├─────────────────────────────────────────┤
│          Framing Layer (§7-8)           │   Frame parsing, CBOR encoding, chunking
├─────────────────────────────────────────┤
│          Transport Layer (§6)           │   TCP + TLS 1.3
├─────────────────────────────────────────┤
│          Discovery Layer (§5)           │   mDNS/DNS-SD
└─────────────────────────────────────────┘
```

## Protocol Comparison

| Feature | NXFR | AirDrop | Quick Share | Warpinator |
|---------|------|---------|-------------|------------|
| **Transport** | TCP + TLS 1.3 | Apple Wireless Direct | Wi-Fi Direct / BLE | TCP (unencrypted by default) |
| **Authentication** | mTLS + SAS Pairing | Apple ID | Google Account | Passphrase |
| **Cross-Platform** | Yes (Linux, Android, Windows, Mac, iOS) | Apple ecosystem only | Android/Windows/Chromebook | Yes |
| **Cloud Dependency** | None | None | None | None |
| **Resumable** | Yes | No | Partial | No |
