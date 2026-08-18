!!! info "Protocol v1.0"
    This is the v1.0 specification. For the normative text, see the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory.

# Threat Model

The transition to a purely local, peer-to-peer approach introduces unique security challenges. By operating strictly over the LAN, NXFR eliminates cloud infrastructure risks but exposes the protocol to hostile local networks.

## Security Objectives

- **Confidentiality:** All control messages and file data are encrypted using TLS 1.3.
- **Integrity:** TLS 1.3 AEAD MACs protect transport, and per-chunk SHA-256 verification protects application data.
- **Authentication:** Devices use mutual TLS with self-signed ECDSA P-256 certificates, backed by TOFU and SAS pairing.
- **Authorization:** No data is transferred without explicit user consent by default.
- **Privacy:** Devices are hidden by default via mDNS, leaking no identifying information unless receiving mode is explicitly enabled.

## Trust Model

### What is Trusted
- The local operating system running the NXFR implementation.
- The platform keystore (Android Keystore, macOS Keychain, Windows DPAPI, Linux Secret Service).
- The user (to accurately verify the SAS during pairing).
- Physical proximity during pairing.

### What is NOT Trusted
- The Local Area Network (LAN) — treated as fully hostile.
- DNS and mDNS subsystems.
- Unpaired peers.
- The internet connection (not used).

## Threat Matrix

| Threat | Description | Mitigation | Residual Risk |
|--------|-------------|------------|---------------|
| **T1: Passive Eavesdropper** | Attacker sniffs LAN traffic | TLS 1.3 encryption | Traffic analysis (timing, sizes) |
| **T2: Active MITM** | ARP spoofing or rogue AP | Peer identity pinning; SAS derived from TLS exporter | User negligence (clicking Accept without checking SAS) |
| **T3: Malicious Peer** | Sending offensive files | Explicit consent UI; blocking capability | Social engineering user into accepting malware |
| **T4: Malicious File Paths** | Directory traversal attacks | Strict path sanitization rules (rejecting `../`, absolute paths, null bytes) | Implementation bugs in path logic |
| **T5: Resource Exhaustion** | DoS via oversized payloads | Strict frame size limits (64 KiB control, 4 MiB chunks); in-flight window limits | Network bandwidth consumption |
| **T6: Notification Spam** | Flooding transfer requests | Rate limiting; request coalescing | Bypassing limits via rotating IDs |
| **T7: Downgrade Attacks** | Forcing weak cryptography | Enforced TLS 1.3 only; strict cipher suite list | None |
| **T8: Replay Attacks** | Replaying valid sessions | TLS nonces, unique `session_id`, monotonically increasing `message_id` | None |
| **T9: Device Tracking** | Tracking via mDNS ID | Hidden-by-default policy; daily rotation of the advertised ID prefix | Tracking while receiving is actively enabled |

!!! important "Path Sanitization & Web Upload Security"
    Implementations MUST enforce rigorous path sanitization on the `relative_path` provided in the `TRANSFER_REQUEST` to prevent catastrophic directory traversal attacks.
    For web uploads, rendered links are fragment-only (`/#t=<token>`) to prevent tokens from appearing in HTTP request lines or server access logs; `?t=` is accepted solely as a testing convenience and is flagged in the audit log.
