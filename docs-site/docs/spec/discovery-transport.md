!!! info "Protocol v1.0"
    This is the v1.0 specification. For the normative text, see the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory.

# Discovery & Transport

## mDNS Discovery

Devices MUST use mDNS/DNS-SD for local network discovery. No other discovery mechanism is defined in v0.1.

| Parameter | Value |
|-----------|-------|
| Service type | `_nxfr._tcp` |
| Default port | 17394 (`0x43E2`) |
| Domain | `local.` |

### TXT Record

The DNS-SD TXT record MUST contain the following key-value pairs:

| Key | Type | Required | Max Size | Description |
|-----|------|----------|----------|-------------|
| `v` | string | REQUIRED | 8 bytes | Protocol version, e.g., `"0.1"` |
| `id` | string | REQUIRED | 16 bytes | First 16 hex chars of `SHA-256(device_id \|\| YYYY-MM-DD)`. Implementations SHOULD rotate daily. |
| `name` | string | REQUIRED | 63 bytes | Human-readable device name, UTF-8 encoded |
| `plat` | string | REQUIRED | 8 bytes | Platform: `"linux"`, `"android"`, `"windows"`, `"macos"`, `"ios"` |
| `caps` | string | OPTIONAL | 128 bytes | Comma-separated capability tokens, e.g., `"blake3,zstd"` |

### Privacy Constraints

A device MUST NOT advertise the `_nxfr._tcp` service unless the user has explicitly enabled receiving. This is the primary privacy mechanism: devices are invisible by default. When the user disables receiving, the implementation MUST immediately un-register the mDNS service and cease responding to queries.

## Transport Layer

The initiator opens a TCP connection to the responder's advertised address and port. Implementations SHOULD set `TCP_NODELAY` to reduce latency for control messages.

### TLS 1.3 Requirements

Immediately after TCP connection, the initiator MUST begin a TLS 1.3 handshake. Both sides operate in mutual TLS (mTLS) mode.

- **ALPN**: Both sides MUST advertise the Application-Layer Protocol Negotiation (ALPN) token `"nxfr/0"`.
- **Cipher Suites**:
  1. `TLS_AES_256_GCM_SHA384` (RECOMMENDED)
  2. `TLS_AES_128_GCM_SHA256`
  3. `TLS_CHACHA20_POLY1305_SHA256`
- **Key Exchange**: X25519 (RECOMMENDED) and secp256r1.
- **Session Restrictions**: TLS session resumption (PSK) and 0-RTT data MUST NOT be used in v0.1. TLS 1.2 and earlier MUST NOT be accepted.

### Certificate Requirements

Each device presents a self-signed X.509 certificate during the TLS handshake:
- MUST contain an ECDSA P-256 (secp256r1) public key.
- MUST be self-signed (no CA chain).
- SHOULD have a validity period of 10 years from creation.

### Device Identity

On first run, each device MUST generate a long-term ECDSA P-256 keypair. The `device_id` is a 32-byte (256-bit) value that uniquely identifies a device:

```rust
device_id = SHA256(SubjectPublicKeyInfo_DER)
```

The input is the DER encoding of the SubjectPublicKeyInfo structure from the device's X.509 certificate.
