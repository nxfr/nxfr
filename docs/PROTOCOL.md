# NXFR Protocol Specification v1.0

**Status:** Stable
**Date:** 2026-08-16
**Authors:** NXFR Protocol Working Group

---

## 1. Overview

The Nearby Xfer Protocol (NXFR) is an open, platform-neutral protocol for secure file
transfer between trusted nearby devices on a Local Area Network (LAN). NXFR provides
zero-configuration discovery, mutual authentication, explicit user consent, resumable
transfers, and directory streaming — all without cloud services, user accounts, or cables.

NXFR operates as a session-oriented binary protocol over TCP with TLS 1.3. Devices
discover each other via mDNS/DNS-SD, authenticate using long-term ECDSA P-256 identity
keys with Trust On First Use (TOFU) pairing, and exchange files through a multiplexed
framing layer carrying CBOR-encoded control messages and raw binary data chunks.

This document is the normative specification. Implementations MUST conform to the
requirements herein to claim NXFR v1.0 compliance.

---

## 2. Terminology & Conventions

The key words "MUST", "MUST NOT", "REQUIRED", "SHALL", "SHALL NOT", "SHOULD",
"SHOULD NOT", "RECOMMENDED", "MAY", and "OPTIONAL" in this document are to be
interpreted as described in [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119).

| Term | Definition |
|------|-----------|
| **Device** | A host running an NXFR implementation. |
| **Initiator** | The device that opens the TCP connection. |
| **Responder** | The device that accepts the TCP connection. |
| **Sender** | The device transmitting file data in a transfer. |
| **Receiver** | The device accepting file data in a transfer. |
| **Session** | A TLS-secured connection between two devices after HELLO exchange. |
| **Transfer** | A logical unit of work: one or more files sent from sender to receiver. |
| **Stream** | A per-file data channel within a transfer, identified by `stream_id`. |
| **Paired** | A state where two devices have mutually verified identity via SAS. |
| **TOFU** | Trust On First Use — accept identity on first connection, pin for future. |
| **SAS** | Short Authentication String — a human-verifiable code for pairing. |
| **device_id** | SHA-256 hash of a device's SubjectPublicKeyInfo (SPKI) DER encoding. |
| **Frame** | The atomic unit of NXFR wire communication: a 28-byte header + payload. |

---

## 3. Goals & Non-Goals

### 3.1 Goals

1. **Fast LAN transfer.** Saturate gigabit Ethernet and modern Wi-Fi links for bulk file transfer.
2. **Strong security.** Mutual authentication, encrypted transport, integrity verification.
3. **Privacy by default.** No discovery leakage when not actively receiving. No cloud telemetry.
4. **Cross-platform.** Implementable on Linux, Android, Windows, macOS, and iOS using standard libraries.
5. **User consent.** Every transfer requires explicit approval by default.
6. **Resumable transfers.** Survive network interruptions without re-sending completed work.
7. **Directory support.** Transfer directory trees preserving structure.
8. **Simple pairing.** TOFU with visual SAS verification — no passwords, no accounts.

### 3.2 Non-Goals

1. **Internet/WAN transfer.** NXFR is LAN-only. No relay servers, no NAT traversal.
2. **File synchronization.** NXFR is point-in-time transfer, not continuous sync.
3. **Remote control.** No shell access, clipboard sharing, notification mirroring, or input forwarding.
4. **Streaming media.** Not a media streaming protocol.
5. **Always-on daemon.** The protocol does not require or assume a persistent background service. Android implementations in particular MUST NOT promise daemon behavior that the OS cannot deliver.

---

## 4. Architecture Overview

NXFR is organized in five layers:

```
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

The **initiator** is the device that opens the TCP connection. The initiator MAY be
either the sender or the receiver — the protocol is symmetric after HELLO exchange.
In the common case, the sender initiates.

---

## 5. Discovery

### 5.1 Protocol

Devices MUST use mDNS/DNS-SD as specified in [RFC 6762] and [RFC 6763] for local
network discovery. No other discovery mechanism is defined in v0.1.

### 5.2 Service Registration

| Parameter | Value |
|-----------|-------|
| Service type | `_nxfr._tcp` |
| Default port | 17394 (`0x43E2`) |
| Domain | `local.` |

The service instance name SHOULD be derived from the device name but MAY be any
unique string. Implementations MUST handle instance name collisions per RFC 6762 §9.

### 5.3 TXT Record

The DNS-SD TXT record MUST contain the following key-value pairs:

| Key | Type | Required | Max Size | Description |
|-----|------|----------|----------|-------------|
| `v` | string | REQUIRED | 8 bytes | Protocol version, e.g., `"0.1"` |
| `id` | string | REQUIRED | 16 bytes | First 16 hex chars of `SHA-256(device_id \|\| YYYY-MM-DD)`. Implementations SHOULD rotate daily. See §6.3.4. |
| `name` | string | REQUIRED | 63 bytes | Human-readable device name, UTF-8 encoded |
| `plat` | string | REQUIRED | 8 bytes | Platform: `"linux"`, `"android"`, `"windows"`, `"macos"`, `"ios"` |
| `caps` | string | OPTIONAL | 128 bytes | Comma-separated capability tokens, e.g., `"blake3,zstd"` |

TXT record values MUST conform to the DNS-SD TXT record format (key=value, each
≤ 255 bytes including key, `=`, and value) per RFC 6763 §6.

### 5.4 Privacy Constraints

- A device MUST NOT advertise the `_nxfr._tcp` service unless the user has **explicitly
  enabled receiving**. This is the primary privacy mechanism: devices are invisible by default.
- A device MAY browse (scan) for `_nxfr._tcp` services when the user opens a "send"
  interface. Browsing does not reveal the browser's identity on the network.
- When the user disables receiving, the implementation MUST immediately un-register
  the mDNS service and cease responding to queries.

### 5.5 Discovery Timeout

Browsing SHOULD be time-limited. Implementations SHOULD stop active browsing after
60 seconds of inactivity in the send UI, resumable on user interaction.

### 5.6 UDP Beacon Discovery (Port 17395)

In addition to mDNS/DNS-SD, implementations MAY support UDP beacon discovery for
environments where multicast DNS is unreliable (e.g., mobile hotspots, guest Wi-Fi
networks that block mDNS traffic).

#### 5.6.1 Protocol

| Parameter | Value |
|-----------|-------|
| Transport | UDP |
| Port | 17395 (`0x43E3`) |
| Direction | Broadcast (directed + multicast `224.0.0.251`) |
| Interval | Every 1000 ms while in send/receive mode |
| Expiry | Peers not seen for 4000 ms are removed |

#### 5.6.2 Beacon Payload

The beacon is a UTF-8 JSON datagram (< 256 bytes) with the following structure:

```json
{"v":1,"advertised_id":"a1b2c3d4e5f67890","name":"My Phone","plat":"android","tcp_port":17394}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `v` | integer | REQUIRED | Beacon version. MUST be `1`. |
| `advertised_id` | string | REQUIRED | 16-char hex rotating ID (see §6.3.4). MUST NOT be the real `device_id`. |
| `name` | string | REQUIRED | Human-readable device name. |
| `plat` | string | REQUIRED | Platform identifier: `"linux"`, `"android"`, `"windows"`, etc. |
| `tcp_port` | integer | REQUIRED | TCP port for NXFR TLS connections. |

> **SECURITY: The beacon MUST NEVER broadcast the real `device_id`.** The
> `advertised_id` is derived via `SHA-256(device_id || YYYY-MM-DD)` (see §6.3.4)
> and rotates daily, preventing passive tracking. The real `device_id` is only
> exchanged inside the encrypted TLS 1.3 HELLO message after mutual authentication.

#### 5.6.3 Self-Ignore and Deduplication

Receivers MUST ignore beacons where `advertised_id` matches their own computed
`advertised_id` for the current date. Peer deduplication SHOULD use `advertised_id`
as the merge key. When a beacon peer is subsequently connected via TLS, the real
`device_id` from the peer's certificate replaces the `advertised_id` for identity
pinning and pairing checks.

#### 5.6.4 Integration with Discovery Ladder

Implementations supporting beacon discovery SHOULD integrate it as the fastest
discovery tier, with mDNS/DNS-SD as a fallback:

| Tier | Mechanism | Latency | Hotspot-Safe |
|------|-----------|---------|--------------|
| 0 | UDP Beacon (port 17395) | ~1 s | Yes |
| 1 | NSD / mDNS (DNS-SD) | 2–5 s | No |
| 2 | TCP Subnet Probe (port 17394) | 5–30 s | Yes |
| 3 | Manual IP:Port entry | User-initiated | Yes |

---

## 6. Transport & Security

### 6.1 TCP Connection

The initiator opens a TCP connection to the responder's advertised address and port.
Implementations SHOULD set `TCP_NODELAY` to reduce latency for control messages.
Implementations MAY set send/receive buffer sizes appropriate for bulk transfer
(e.g., 512 KiB or larger).

### 6.2 TLS 1.3

Immediately after TCP connection, the initiator MUST begin a TLS 1.3 handshake as
specified in [RFC 8446]. Both sides operate in mutual TLS (mTLS) mode.

#### 6.2.1 ALPN

Both sides MUST advertise the Application-Layer Protocol Negotiation (ALPN) token
`"nxfr/0"` during the TLS handshake. If ALPN negotiation fails (peer does not
support `nxfr/0`), the connection MUST be closed immediately.

#### 6.2.2 Cipher Suites

Implementations MUST support the following cipher suites, in order of preference:

1. `TLS_AES_256_GCM_SHA384` (RECOMMENDED)
2. `TLS_AES_128_GCM_SHA256`
3. `TLS_CHACHA20_POLY1305_SHA256`

Implementations MUST NOT offer or accept cipher suites not listed above.

#### 6.2.3 Key Exchange

Implementations MUST support X25519 (RECOMMENDED) and SHOULD support secp256r1
for key exchange. Other groups MUST NOT be offered.

#### 6.2.4 Certificate Requirements

Each device presents a self-signed X.509 certificate during the TLS handshake.
The certificate:

- MUST contain an ECDSA P-256 (secp256r1) public key.
- MUST be self-signed (no CA chain).
- SHOULD have a validity period of 10 years from creation.
- SHOULD use the device name as the Common Name (CN) for debuggability, but the
  CN is NOT used for authentication.

Certificate validation in NXFR does NOT use the CA trust chain. Instead:

1. Extract the peer's SubjectPublicKeyInfo (SPKI) from the presented certificate.
2. Compute `peer_device_id = SHA-256(SPKI DER)`.
3. If the peer is paired, verify `peer_device_id` matches the pinned identity.
4. If the peer is not paired, accept the identity (TOFU) and offer pairing.

#### 6.2.5 Session Restrictions

- TLS session resumption (PSK) MUST NOT be used in v0.1.
- 0-RTT data MUST NOT be sent or accepted.
- TLS 1.2 and earlier MUST NOT be accepted. If the peer cannot negotiate TLS 1.3,
  the connection MUST be closed.

### 6.3 Device Identity

#### 6.3.1 Key Generation

On first run, each device MUST generate a long-term ECDSA P-256 (secp256r1) keypair.
This keypair is the device's cryptographic identity.

**Rationale for P-256 over Ed25519:** Ed25519 has superior theoretical properties
(deterministic signatures, faster verification). However, Windows SChannel has
limited-to-no support for Ed25519 in TLS certificate authentication. Since NXFR uses
device identity keys in mutual TLS, P-256 ensures cross-platform compatibility without
requiring applications to bundle custom TLS stacks. See `DECISIONS.md` D-05.

#### 6.3.2 Device ID Computation

```
device_id = SHA-256(SubjectPublicKeyInfo_DER)
```

The `device_id` is a 32-byte (256-bit) value that uniquely identifies a device.
The input is the DER encoding of the SubjectPublicKeyInfo structure from the device's
X.509 certificate, as defined in [RFC 5280] §4.1.2.7.

#### 6.3.3 Key Storage

The private key MUST be stored using the platform's secure key storage mechanism:

| Platform | Storage |
|----------|---------|
| Linux | Secret Service API (libsecret/GNOME Keyring) or file with mode 0600 |
| Android | Android Keystore (hardware-backed when available) |
| Windows | DPAPI or Certificate Store |
| macOS | Keychain |

#### 6.3.4 Advertised ID Rotation

To prevent passive tracking via mDNS, implementations SHOULD rotate the `id`
value advertised in the DNS-SD TXT record daily:

```
advertised_id = first_16_hex_chars( SHA-256(device_id || "YYYY-MM-DD") )
```

Where `device_id` is the 32-byte identity hash and `YYYY-MM-DD` is the current
UTC date string. Paired peers can verify the advertised_id by computing the same
derivation from the pinned `device_id` and the current date.

This rotation ensures that:
- Unpaired observers cannot correlate a device across days.
- Paired peers can still identify the device by pre-computing today's `id`.
- The full `device_id` is never exposed in cleartext mDNS traffic.

---

## 7. Framing

### 7.1 Frame Header

All NXFR communication after TLS establishment uses a binary framing format. Each
frame consists of a fixed 28-byte header followed by a variable-length payload.

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    'N' (0x4E) |    'X' (0x58) |    'F' (0x46) |    'R' (0x52) |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|    version    |     kind      |            flags              |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          session_id                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                          stream_id                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                         message_id                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         payload_len                           |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
|                      payload (variable)                       |
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Size | Field | Type | Description |
|--------|------|-------|------|-------------|
| 0 | 4 | `magic` | `[u8; 4]` | Frame magic: `b"NXFR"` (`0x4E584652`). MUST be present in every frame. |
| 4 | 1 | `version` | `u8` | Frame format version. MUST be `1` for NXFR v0.1. |
| 5 | 1 | `kind` | `u8` | Frame kind: `0x01` CONTROL, `0x02` CHUNK, `0x03` KEEPALIVE. |
| 6 | 2 | `flags` | `u16` | Big-endian. Bit semantics depend on `kind` (see §7.3). |
| 8 | 4 | `session_id` | `u32` | Big-endian. Session identifier assigned by responder in HELLO_ACK. `0` in the initial HELLO frame before assignment. |
| 12 | 4 | `stream_id` | `u32` | Big-endian. `0` for session-level frames. `>0` for file-level frames. |
| 16 | 8 | `message_id` | `u64` | Big-endian. Monotonically increasing per direction per session. Used for acknowledgment correlation. |
| 24 | 4 | `payload_len` | `u32` | Big-endian. Length of payload in bytes. `0` is valid (empty payload). |
| 28 | var | `payload` | `[u8]` | Payload bytes. Interpretation depends on `kind`. |

All multi-byte integer fields MUST be encoded in big-endian (network byte order).

### 7.2 Frame Kinds

#### 7.2.1 CONTROL (0x01)

The payload is a CBOR-encoded map ([RFC 8949]). The map MUST contain an integer-valued
`"type"` field identifying the control message type (see §9). The `stream_id` MUST
be `0` for session-level control messages (HELLO, PAIR_*, SESSION_CLOSE, ERROR).
For transfer-level messages, `stream_id` MAY be `0` (using `transfer_id` in the
CBOR payload for correlation) or a specific stream ID.

Maximum payload size: **64 KiB** (65,536 bytes).

#### 7.2.2 CHUNK (0x02)

The payload carries file data with integrity metadata:

| Offset | Size | Field | Type | Description |
|--------|------|-------|------|-------------|
| 0 | 8 | `offset` | `u64` | Big-endian. Byte offset of this chunk within the file. |
| 8 | 32 | `chunk_hash` | `[u8; 32]` | SHA-256 digest of the `data` portion only. |
| 40 | var | `data` | `[u8]` | Raw file data. Length = `payload_len - 40`. |

The `stream_id` in the frame header MUST match the `stream_id` assigned in the
corresponding `FILE_METADATA` message.

Maximum payload size: **4 MiB** (4,194,304 bytes), yielding a maximum data portion
of 4,194,264 bytes.

Minimum payload size: **41 bytes** (40-byte chunk header + at least 1 byte of data).

#### 7.2.3 KEEPALIVE (0x03)

Used for connection liveness detection and RTT measurement.

- `stream_id` MUST be `0`.
- Payload is **0 bytes** (no RTT measurement) or **8 bytes** (timestamp).
- If 8 bytes: the payload is a `u64` big-endian timestamp in milliseconds since
  the Unix epoch. A PONG echoes the received PING's timestamp.

### 7.3 Frame Flags

Flags are a 16-bit big-endian field. Undefined flag bits MUST be set to `0` by
senders. Receivers MUST ignore unknown flag bits.

| Kind | Bit 0 | Bits 1-15 |
|------|-------|-----------|
| CONTROL (0x01) | Reserved (0) | Reserved (0) |
| CHUNK (0x02) | `LAST_CHUNK` — set to 1 on the final chunk of a file stream | Reserved (0) |
| KEEPALIVE (0x03) | `IS_PONG` — 0 = PING, 1 = PONG | Reserved (0) |

### 7.4 Frame Validation

A receiver MUST validate each frame header upon receipt:

1. **Magic check.** If `magic` ≠ `0x4E584652`, close the connection immediately.
   This is a fatal, non-recoverable error.
2. **Version check.** If `version` ≠ `1`, send ERROR `unsupported_version` and close.
3. **Kind check.** If `kind` is not `0x01`, `0x02`, or `0x03`, send ERROR `invalid_frame`
   and close.
4. **Payload length check.** If `payload_len` exceeds the kind-specific maximum
   (64 KiB for CONTROL, 4 MiB for CHUNK, 8 for KEEPALIVE), send ERROR
   `payload_too_large` and close.
5. **Reserved flags.** If any reserved flag bit is set to `1`, the frame SHOULD be
   accepted (for forward compatibility) but the bits MUST be ignored.

### 7.5 Message ID Assignment

Each side of the connection maintains an independent, monotonically increasing
`message_id` counter, starting at `1`. The counter increments by 1 for each frame
sent, regardless of kind. The value `0` is reserved and MUST NOT be used.

---

## 8. Encoding Rules

### 8.1 CBOR

All control message payloads (frames with `kind = 0x01`) MUST be encoded as CBOR
maps per [RFC 8949]. The following encoding rules apply:

1. **Definite-length encoding only.** Indefinite-length items MUST NOT be used.
2. **String keys.** Map keys MUST be UTF-8 text strings (CBOR major type 3).
   Integer keys MUST NOT be used.
3. **Binary data.** Binary values (device_id, transfer_id, sha256, etc.) MUST be
   encoded as CBOR byte strings (major type 2).
4. **Integers.** Integer values MUST use the smallest valid CBOR encoding.
5. **No tags.** CBOR tags (major type 6) MUST NOT be used in v0.1.
6. **Nesting depth.** The maximum CBOR nesting depth is **6** (e.g.,
   `RESUME_STATUS`: map → `files` array → map → `received_ranges` array → array → uint).
   Deeper nesting MUST be rejected. This limit remains bounded against stack exhaustion
   while accommodating the deepest production message.
7. **Unknown fields.** Receivers MUST ignore unknown map keys. This enables forward
   compatibility: future minor versions may add optional fields.
8. **Canonical ordering.** Map keys SHOULD be sorted lexicographically by their
   UTF-8 encoding (deterministic encoding). This is RECOMMENDED for test vector
   reproducibility but not required for interoperability.

### 8.2 CBOR Diagnostic Notation

This specification uses CBOR diagnostic notation ([RFC 8949] §8) to describe
message schemas. In diagnostic notation:
- `h'...'` denotes a byte string with hexadecimal content.
- Text strings are quoted: `"hello"`.
- Arrays use brackets: `[1, 2, 3]`.
- Maps use braces: `{"key": value}`.
- `true` / `false` denote booleans.

### 8.3 Chunk Payloads

CHUNK frame payloads (kind `0x02`) are NOT CBOR-encoded. They use a fixed binary
header (offset + hash = 40 bytes) followed by raw file data. See §7.2.2.

### 8.4 KEEPALIVE Payloads

KEEPALIVE frame payloads (kind `0x03`) are raw bytes (0 or 8 bytes). They are NOT
CBOR-encoded. See §7.2.3.

---

## 9. Message Catalog

### 9.1 Control Message Type Codes

| Code | Name | Direction | Description |
|------|------|-----------|-------------|
| `0x01` | HELLO | Initiator → Responder | Session initiation |
| `0x02` | HELLO_ACK | Responder → Initiator | Session acceptance |
| `0x03` | PAIR_REQUEST | Either → Either | Initiate pairing |
| `0x04` | PAIR_ACCEPT | Either → Either | Confirm pairing (SAS matched) |
| `0x05` | PAIR_REJECT | Either → Either | Reject pairing |
| `0x06` | SESSION_CLOSE | Either → Either | Graceful session termination |
| `0x09` | ERROR | Either → Either | Error notification |
| `0x10` | TRANSFER_REQUEST | Sender → Receiver | Propose a file transfer |
| `0x11` | TRANSFER_ACCEPT | Receiver → Sender | Accept proposed transfer |
| `0x12` | TRANSFER_REJECT | Receiver → Sender | Reject proposed transfer |
| `0x13` | FILE_METADATA | Sender → Receiver | Per-file metadata before streaming |
| `0x14` | FILE_METADATA_ACK | Receiver → Sender | Acknowledge file metadata |
| `0x15` | CHUNK_ACK | Receiver → Sender | Acknowledge received chunk |
| `0x16` | TRANSFER_PAUSE | Either → Either | Pause active transfer |
| `0x17` | TRANSFER_RESUME | Either → Either | Resume paused transfer |
| `0x18` | TRANSFER_CANCEL | Either → Either | Cancel transfer |
| `0x19` | TRANSFER_COMPLETE | Sender → Receiver | All data sent |
| `0x1A` | TRANSFER_ACK | Receiver → Sender | Final transfer acknowledgment |
| `0x20` | RESUME_QUERY | Sender → Receiver | Query for resumable state |
| `0x21` | RESUME_STATUS | Receiver → Sender | Report resumable state |

### 9.2 Message Schemas

All schemas below use CBOR diagnostic notation. Fields marked REQUIRED MUST be
present. Fields marked OPTIONAL MAY be omitted. The `"type"` field is REQUIRED
in every control message.

---

#### 9.2.1 HELLO (0x01)

Sent by the initiator immediately after TLS handshake completes. This is the first
NXFR frame on the connection.

```cbor-diag
{
  "type":             1,                    / uint, REQUIRED /
  "protocol_version": [1, 0],              / array[uint, uint], REQUIRED. [major, minor] /
  "device_id":        h'<32 bytes>',       / bstr, REQUIRED. SHA-256 of SPKI DER /
  "device_name":      "My Laptop",         / tstr, REQUIRED. UTF-8, max 63 bytes /
  "platform":         "linux",             / tstr, REQUIRED. See §5.3 /
  "capabilities":     ["blake3"],          / array[tstr], OPTIONAL. Default: [] /
  "is_paired":        false                / bool, REQUIRED. Initiator's view of peer /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `type` | uint | R | Always `1` (0x01). |
| `protocol_version` | [uint, uint] | R | Protocol major and minor version. Incompatible major versions → ERROR. |
| `device_id` | bstr(32) | R | Sender's device identity. MUST match the SPKI hash from the TLS certificate. |
| `device_name` | tstr | R | Human-readable name for UI display. Max 63 bytes UTF-8. Aligned with DNS-SD name limit. |
| `platform` | tstr | R | One of: `"linux"`, `"android"`, `"windows"`, `"macos"`, `"ios"`. |
| `capabilities` | [tstr] | O | List of optional capabilities supported. Empty if omitted. |
| `is_paired` | bool | R | `true` if initiator considers responder a paired device. |

The `device_id` in the HELLO MUST match the SHA-256 hash of the SPKI from the peer's
TLS certificate. If it does not match, the receiver MUST send ERROR `identity_changed`
(fatal) and close.

The `session_id` in the frame header MUST be `0` for the HELLO frame (session not yet
assigned).

---

#### 9.2.2 HELLO_ACK (0x02)

Sent by the responder in response to a valid HELLO.

```cbor-diag
{
  "type":             2,                    / uint, REQUIRED /
  "protocol_version": [1, 0],              / array[uint, uint], REQUIRED /
  "device_id":        h'<32 bytes>',       / bstr, REQUIRED /
  "device_name":      "My Phone",          / tstr, REQUIRED /
  "platform":         "android",           / tstr, REQUIRED /
  "capabilities":     [],                  / array[tstr], OPTIONAL /
  "is_paired":        false,               / bool, REQUIRED /
  "session_id":       4660                 / uint, REQUIRED. Assigned by responder. /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `type` | uint | R | Always `2` (0x02). |
| `protocol_version` | [uint, uint] | R | Responder's protocol version. |
| `device_id` | bstr(32) | R | Responder's device identity. |
| `device_name` | tstr | R | Human-readable name. Max 63 bytes UTF-8. |
| `platform` | tstr | R | Platform identifier. |
| `capabilities` | [tstr] | O | Supported capabilities. |
| `is_paired` | bool | R | Responder's view of initiator. |
| `session_id` | uint | R | Session identifier for all subsequent frames. Non-zero. |

The `session_id` MUST be a random non-zero 32-bit value generated by the responder.
All subsequent frames from both sides MUST use this `session_id` in the frame header.

**Version negotiation:** Both sides MUST compare major versions. If the major versions
differ, the side with the higher version MUST send ERROR `unsupported_version` (fatal)
and close. If major versions match but minor versions differ, the session proceeds
using the lower minor version's feature set.

**Capability negotiation:** The active capability set is the intersection of both
sides' `capabilities` arrays. Capabilities not supported by both sides MUST NOT be used.

---

#### 9.2.3 PAIR_REQUEST (0x03)

Initiates the pairing process. Either side MAY send this after HELLO exchange.

```cbor-diag
{
  "type":       3,              / uint, REQUIRED /
  "sas_method": "numeric-6"    / tstr, REQUIRED /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `type` | uint | R | Always `3` (0x03). |
| `sas_method` | tstr | R | SAS display method. v0.1 defines only `"numeric-6"`. |

**SAS Derivation:**

Both sides independently compute the Short Authentication String:

1. Compute `context = sort(device_id_a, device_id_b)` where `sort` is lexicographic
   ordering of the two raw 32-byte device_id values. This yields a 64-byte context.
2. Derive keying material: `sas_bytes = TLS-Exporter("NXFR-SAS-v0", context, 4)`
   using the TLS 1.3 exporter interface ([RFC 8446] §7.5).
3. Compute the display value: `sas_value = BigEndian_u32(sas_bytes) mod 1000000`.
4. Display as a zero-padded 6-digit decimal number (e.g., `"042857"`).

Both devices display the SAS. The user verifies the codes match on both screens.
If they match, the user confirms on both devices, triggering PAIR_ACCEPT.
If they do not match (indicating a possible MITM), the user rejects on either device.

---

#### 9.2.4 PAIR_ACCEPT (0x04)

Confirms that the user has verified the SAS and trusts the peer's identity.

```cbor-diag
{
  "type": 4    / uint, REQUIRED /
}
```

Upon receiving PAIR_ACCEPT, the device MUST persist the peer's `device_id` and
public key in the paired device database. Subsequent connections from this peer
are authenticated by verifying the `device_id` matches the pinned value.

---

#### 9.2.5 PAIR_REJECT (0x05)

Rejects pairing. The session continues but devices remain unpaired.

```cbor-diag
{
  "type":   5,                   / uint, REQUIRED /
  "reason": "user_declined"      / tstr, OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `reason` | tstr | O | Human-readable reason. Suggested: `"user_declined"`, `"sas_mismatch"`. |

---

#### 9.2.6 SESSION_CLOSE (0x06)

Initiates graceful session termination.

```cbor-diag
{
  "type":   6,              / uint, REQUIRED /
  "reason": "normal"        / tstr, OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `reason` | tstr | O | Reason for closing. Suggested: `"normal"`, `"error"`, `"timeout"`, `"user_request"`. |

After sending SESSION_CLOSE, the sender MUST NOT send any further frames except
KEEPALIVE PONGs. The receiver SHOULD send its own SESSION_CLOSE and then close the
TLS connection.

---

#### 9.2.7 ERROR (0x09)

Reports an error condition to the peer.

```cbor-diag
{
  "type":    9,                         / uint, REQUIRED /
  "code":    "checksum_mismatch",       / tstr, REQUIRED /
  "message": "Chunk hash mismatch at offset 1048576",  / tstr, OPTIONAL /
  "fatal":   false,                     / bool, REQUIRED /
  "details": {}                         / map, OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `code` | tstr | R | Machine-readable error code from the error table (§15). |
| `message` | tstr | O | Human-readable description for logging/debugging. |
| `fatal` | bool | R | If `true`, the sender will close the session after this message. |
| `details` | map | O | Additional structured error context (e.g., `{"stream_id": 1, "offset": 1048576}`). |

If `fatal` is `true`, the sender MUST close the connection after sending the ERROR.
The receiver SHOULD log the error and clean up session state.

---

#### 9.2.8 TRANSFER_REQUEST (0x10)

Proposes a file transfer to the peer.

```cbor-diag
{
  "type":          16,                        / uint, REQUIRED /
  "transfer_id":   h'<16 bytes>',            / bstr(16), REQUIRED /
  "transfer_type": "files",                  / tstr, REQUIRED. "files" or "directory" /
  "display_name":  "vacation_photos",        / tstr, REQUIRED /
  "total_files":   3,                        / uint, REQUIRED /
  "total_size":    15728640,                 / uint, REQUIRED. Total bytes /
  "manifest": [                              / array, REQUIRED /
    {
      "file_id":       1,                    / uint, REQUIRED /
      "relative_path": "beach.jpg",          / tstr, REQUIRED /
      "size":          5242880,              / uint, REQUIRED for type="file" /
      "sha256":        h'<32 bytes>',        / bstr(32), REQUIRED for type="file" /
      "type":          "file"               / tstr, OPTIONAL. Default "file". "file" or "dir" /
    },
    {
      "file_id":       2,                    / uint, REQUIRED /
      "relative_path": "sunset.jpg",         / tstr, REQUIRED /
      "size":          5242880,              / uint, REQUIRED for type="file" /
      "sha256":        h'<32 bytes>',        / bstr(32), REQUIRED for type="file" /
      "type":          "file"               / tstr, OPTIONAL /
    },
    {
      "file_id":       0,                    / uint, REQUIRED /
      "relative_path": "empty_dir",          / tstr, REQUIRED /
      "type":          "dir"                / tstr, REQUIRED for empty dirs /
    }
  ]
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `transfer_id` | bstr(16) | R | Random 128-bit identifier unique to this transfer. |
| `transfer_type` | tstr | R | `"files"` for unrelated files, `"directory"` for a directory tree. |
| `display_name` | tstr | R | Name shown in the consent UI. Filename for single files, directory name for directories. |
| `total_files` | uint | R | Number of **file** entries (excludes `"dir"` entries). Manifest array length = `total_files` + number of `"dir"` entries. |
| `total_size` | uint | R | Sum of all file sizes in bytes (excludes `"dir"` entries). |
| `manifest` | array | R | List of file/directory descriptors. Max 500 entries. The encoded TRANSFER_REQUEST MUST fit within the 64 KiB CONTROL payload limit. |
| `manifest[].file_id` | uint | R | Unique within this transfer. `type="dir"` entries MAY use `0`; `type="file"` entries start at `1`. |
| `manifest[].relative_path` | tstr | R | Forward-slash-separated relative path. MUST pass path safety validation (§18). |
| `manifest[].size` | uint | R* | File size in bytes. REQUIRED when `type = "file"`. Absent for `type = "dir"`. |
| `manifest[].sha256` | bstr(32) | R* | SHA-256 digest of the complete file. REQUIRED when `type = "file"`. Absent for `type = "dir"`. |
| `manifest[].type` | tstr | O | `"file"` (default if omitted) or `"dir"`. A `"dir"` entry creates a directory; no stream/chunks are associated. |

The receiver MUST present the transfer offer to the user for explicit Accept/Reject
unless an auto-accept policy applies for this paired peer.

The encoded TRANSFER_REQUEST message MUST fit within the 64 KiB CONTROL frame
payload limit. If a directory contains more entries than can fit in a single
TRANSFER_REQUEST, the sender MUST send ERROR `manifest_too_large` and the
transfer cannot proceed. Manifest paging is deferred to v0.2.

---

#### 9.2.9 TRANSFER_ACCEPT (0x11)

User accepted the transfer.

```cbor-diag
{
  "type":        17,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>'   / bstr(16), REQUIRED /
}
```

---

#### 9.2.10 TRANSFER_REJECT (0x12)

User rejected the transfer or an error prevents acceptance.

```cbor-diag
{
  "type":        18,                    / uint, REQUIRED /
  "transfer_id": h'<16 bytes>',        / bstr(16), REQUIRED /
  "reason":      "user_declined"        / tstr, OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `reason` | tstr | O | `"user_declined"`, `"disk_full"`, `"busy"`, `"path_rejected"`. |

---

#### 9.2.11 FILE_METADATA (0x13)

Sent by the sender before streaming each file. Provides per-file details.

```cbor-diag
{
  "type":          19,                    / uint, REQUIRED /
  "transfer_id":   h'<16 bytes>',        / bstr(16), REQUIRED /
  "file_id":       1,                    / uint, REQUIRED /
  "stream_id":     1,                    / uint, REQUIRED /
  "relative_path": "photos/beach.jpg",   / tstr, REQUIRED /
  "size":          5242880,              / uint, REQUIRED /
  "sha256":        h'<32 bytes>',        / bstr(32), REQUIRED /
  "mime_type":     "image/jpeg",         / tstr, OPTIONAL /
  "modified_time": 1720000000            / uint, OPTIONAL. Unix epoch seconds /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `transfer_id` | bstr(16) | R | Transfer this file belongs to. |
| `file_id` | uint | R | Must match the `file_id` from the manifest. |
| `stream_id` | uint | R | Assigned by sender. Used in CHUNK frame headers. Unique within the session. Non-zero. |
| `relative_path` | tstr | R | Must match the manifest. MUST pass path safety (§18). |
| `size` | uint | R | File size in bytes. Must match the manifest. |
| `sha256` | bstr(32) | R | Whole-file SHA-256. Must match the manifest. |
| `mime_type` | tstr | O | MIME type for receiver UI (e.g., preview, intent handling). |
| `modified_time` | uint | O | Last modification time as Unix epoch seconds. Receiver MAY use for file metadata. |

---

#### 9.2.12 FILE_METADATA_ACK (0x14)

Receiver's response to FILE_METADATA. Confirms readiness to receive the file.

```cbor-diag
{
  "type":        20,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>',  / bstr(16), REQUIRED /
  "file_id":     1,              / uint, REQUIRED /
  "stream_id":   1,              / uint, REQUIRED /
  "accepted":    true            / bool, REQUIRED /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `accepted` | bool | R | `false` if the receiver rejects this specific file (e.g., path sanitization failure, file already exists and user declined). The transfer continues for remaining files. |

---

#### 9.2.13 CHUNK_ACK (0x15)

Acknowledges successful receipt and verification of a chunk.

```cbor-diag
{
  "type":       21,           / uint, REQUIRED /
  "stream_id":  1,            / uint, REQUIRED /
  "message_id": 42,           / uint, REQUIRED /
  "offset":     0,            / uint, REQUIRED /
  "length":     1048576       / uint, REQUIRED /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `stream_id` | uint | R | The stream this ACK pertains to. |
| `message_id` | uint | R | The `message_id` from the CHUNK frame header being acknowledged. |
| `offset` | uint | R | Starting byte offset of the acknowledged chunk. |
| `length` | uint | R | Number of bytes acknowledged. |

The sender MUST track unacknowledged chunks. The in-flight window MUST NOT exceed
**8 chunks**. The sender MUST pause sending when 8 chunks are unacknowledged and
resume when ACKs are received.

---

#### 9.2.14 TRANSFER_PAUSE (0x16)

Pauses an active transfer. Either side may send.

```cbor-diag
{
  "type":        22,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>'   / bstr(16), REQUIRED /
}
```

The sender MUST stop sending CHUNK frames after receiving TRANSFER_PAUSE. Chunks
already in-flight SHOULD still be acknowledged.

---

#### 9.2.15 TRANSFER_RESUME (0x17)

Resumes a paused transfer. Either side may send.

```cbor-diag
{
  "type":        23,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>'   / bstr(16), REQUIRED /
}
```

---

#### 9.2.16 TRANSFER_CANCEL (0x18)

Cancels a transfer. Either side may send at any point during a transfer.

```cbor-diag
{
  "type":        24,                       / uint, REQUIRED /
  "transfer_id": h'<16 bytes>',           / bstr(16), REQUIRED /
  "reason":      "user_cancelled"          / tstr, OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `reason` | tstr | O | `"user_cancelled"`, `"error"`, `"checksum_mismatch"`. |

After sending TRANSFER_CANCEL, no further CHUNK frames for this transfer MUST be sent.
The receiver SHOULD delete partial files unless resume state is being preserved.

---

#### 9.2.17 TRANSFER_COMPLETE (0x19)

Sent by the sender after the last CHUNK of the last file has been transmitted.

```cbor-diag
{
  "type":        25,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>'   / bstr(16), REQUIRED /
}
```

This message signals that the sender has no more data to send. The receiver MUST
now verify whole-file SHA-256 digests and respond with TRANSFER_ACK.

---

#### 9.2.18 TRANSFER_ACK (0x1A)

Sent by the receiver after verifying all received files.

```cbor-diag
{
  "type":         26,                 / uint, REQUIRED /
  "transfer_id":  h'<16 bytes>',     / bstr(16), REQUIRED /
  "status":       "success",         / tstr, REQUIRED /
  "failed_files": []                 / array[uint], OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `status` | tstr | R | `"success"` — all files verified. `"partial_failure"` — some files failed. |
| `failed_files` | [uint] | O | `file_id` values of files that failed verification. Present if `status` = `"partial_failure"`. |

Upon receiving TRANSFER_ACK with `status = "success"`, the sender MAY consider the
transfer complete. The receiver MUST atomically rename `.part` files to their final
paths only after successful verification.

---

#### 9.2.19 RESUME_QUERY (0x20)

Sent by the sender to check if a previous transfer can be resumed.

```cbor-diag
{
  "type":        32,              / uint, REQUIRED /
  "transfer_id": h'<16 bytes>',  / bstr(16), REQUIRED /
  "file_ids":    [1, 2, 3]       / array[uint], OPTIONAL /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `transfer_id` | bstr(16) | R | The `transfer_id` from the original TRANSFER_REQUEST. |
| `file_ids` | [uint] | O | Query specific files. If omitted, query all files in the transfer. |

---

#### 9.2.20 RESUME_STATUS (0x21)

Receiver's response to RESUME_QUERY.

```cbor-diag
{
  "type":        33,                     / uint, REQUIRED /
  "transfer_id": h'<16 bytes>',         / bstr(16), REQUIRED /
  "resumable":   true,                  / bool, REQUIRED /
  "files": [                            / array, REQUIRED if resumable=true /
    {
      "file_id":         1,             / uint, REQUIRED /
      "received_bytes":  3145728,       / uint, REQUIRED /
      "received_ranges": [[0, 3145728]],/ array[array[uint,uint]], REQUIRED /
      "partial_sha256":  h'<32 bytes>'  / bstr(32), OPTIONAL /
    }
  ],
  "expiry": 1720086400                  / uint, OPTIONAL. Unix epoch seconds /
}
```

| Field | Type | Req | Semantics |
|-------|------|-----|-----------|
| `resumable` | bool | R | `false` if state has expired, been corrupted, or is not available. |
| `files` | array | R* | Present only if `resumable = true`. Per-file status. |
| `files[].file_id` | uint | R | File identifier from the manifest. |
| `files[].received_bytes` | uint | R | Total bytes successfully received for this file. |
| `files[].received_ranges` | [[uint, uint]] | R | List of `[offset, length]` pairs representing contiguous received byte ranges. |
| `files[].partial_sha256` | bstr(32) | O | SHA-256 of all received bytes in offset order. For integrity verification of partial state. |
| `expiry` | uint | O | Unix epoch timestamp when partial state will be purged. |

---

## 10. Session State Machine

### 10.1 States

| State | Description |
|-------|-------------|
| `DISCONNECTED` | No TCP connection exists. |
| `CONNECTING` | TCP + TLS handshake in progress. |
| `HELLO_WAIT` | TLS established. Awaiting HELLO (responder) or HELLO_ACK (initiator). |
| `ESTABLISHED` | HELLO exchange complete. Session active, devices may or may not be paired. |
| `PAIRING` | SAS displayed, awaiting user confirmation on both sides. |
| `ACTIVE` | Session fully operational. Devices are either paired or the user accepted an unpaired session. Transfers may proceed. |
| `CLOSING` | SESSION_CLOSE sent. Awaiting peer's close or timeout. |
| `CLOSED` | Connection terminated. All resources released. |

### 10.2 State Diagram

```
                          ┌──────────────┐
                          │ DISCONNECTED │
                          └──────┬───────┘
                                 │ User initiates connection
                                 ▼
                          ┌──────────────┐
              ┌───────────│  CONNECTING  │───────────┐
              │           └──────┬───────┘           │
              │                  │ TLS complete       │ TLS fails / timeout 10s
              │                  ▼                    │
              │           ┌──────────────┐           │
              │           │  HELLO_WAIT  │───────────┤
              │           └──────┬───────┘           │ Timeout 10s / version mismatch
              │                  │ HELLO+HELLO_ACK    │
              │                  ▼                    │
              │           ┌──────────────┐           │
              │   ┌───────│ ESTABLISHED  │────┐      │
              │   │       └──────┬───────┘    │      │
              │   │ Both paired  │            │      │
              │   │ & IDs match  │ PAIR_REQ   │      │
              │   │              ▼            │      │
              │   │       ┌──────────────┐   │      │
              │   │       │   PAIRING    │───┼──────┤
              │   │       └──────┬───────┘   │      │ Timeout 60s
              │   │    PAIR_ACC  │  PAIR_REJ │      │
              │   │              ▼            │      │
              │   └──────►┌──────────────┐   │      │
              │           │    ACTIVE    │   │      │
              │           └──────┬───────┘   │      │
              │                  │ SESSION_CLOSE      │
              │                  ▼            │      │
              │           ┌──────────────┐   │      │
              │           │   CLOSING    │───┘      │
              │           └──────┬───────┘          │
              │                  │ Peer close /      │
              │                  │ timeout 5s        │
              │                  ▼                   │
              │           ┌──────────────┐           │
              └──────────►│    CLOSED    │◄──────────┘
                          └──────────────┘
                       (also: Any → CLOSED on fatal error)
```

### 10.3 Transitions

| From | To | Trigger | Actions |
|------|----|---------|---------|
| DISCONNECTED | CONNECTING | User initiates connection | Open TCP socket, begin TLS handshake |
| CONNECTING | HELLO_WAIT | TLS handshake success | **Initiator:** Send HELLO. **Responder:** Wait for HELLO. |
| CONNECTING | CLOSED | TLS failure, ALPN mismatch, or timeout (10s) | Log error, release resources |
| HELLO_WAIT | ESTABLISHED | Valid HELLO received, compatible version | **Responder:** Send HELLO_ACK. Record session_id. |
| HELLO_WAIT | CLOSED | Timeout (10s), or incompatible major version | Send ERROR `unsupported_version` if version mismatch |
| ESTABLISHED | PAIRING | Either side sends PAIR_REQUEST | Display SAS on both devices |
| ESTABLISHED | ACTIVE | Both `is_paired=true` AND peer's `device_id` matches pinned identity | Skip pairing, proceed to transfers |
| ESTABLISHED | ACTIVE | Transfer initiated without pairing (allowed for unpaired sessions) | User accepts unpaired session |
| PAIRING | ACTIVE | Both sides send PAIR_ACCEPT | Pin peer identity in paired database |
| PAIRING | ESTABLISHED | Either side sends PAIR_REJECT | Session continues unpaired |
| PAIRING | CLOSED | Timeout (60s) without PAIR_ACCEPT/PAIR_REJECT | Close connection |
| ACTIVE | CLOSING | Either side sends SESSION_CLOSE | Stop new transfers |
| ACTIVE | CLOSED | Connection drops, fatal ERROR received | Cleanup all transfers |
| CLOSING | CLOSED | Peer sends SESSION_CLOSE or timeout (5s) | Close TLS, close TCP |
| Any | CLOSED | Fatal error | Send ERROR (if possible), close connection |

### 10.4 Identity Change Handling

If a previously paired peer connects with a different `device_id` than the pinned
value, the implementation:

1. MUST NOT silently accept the new identity.
2. MUST send ERROR `identity_changed` (fatal).
3. MUST present a hard warning to the user.
4. MUST require explicit re-pairing to trust the new identity.

---

## 11. Transfer State Machine

### 11.1 States

Each transfer operates an independent state machine identified by `transfer_id`.

| State | Description |
|-------|-------------|
| `IDLE` | No transfer in progress for this transfer_id. |
| `OFFERED` | Sender has sent TRANSFER_REQUEST, awaiting response. |
| `PENDING` | Receiver has received TRANSFER_REQUEST, awaiting user decision. |
| `NEGOTIATING` | Transfer accepted, exchanging FILE_METADATA / FILE_METADATA_ACK. |
| `STREAMING` | Actively sending/receiving CHUNK frames. |
| `PAUSED` | Transfer paused by either side. |
| `COMPLETING` | Sender: all chunks sent, awaiting TRANSFER_ACK. Receiver: all chunks received and verified, sending TRANSFER_ACK. |
| `COMPLETE` | Transfer verified and acknowledged. Terminal state. |
| `CANCELLED` | Transfer cancelled by either side. Terminal state. |
| `FAILED` | Transfer failed due to unrecoverable error. Terminal state. |

### 11.2 State Diagram

```
            ┌──────┐
            │ IDLE │
            └──┬───┘
   Send REQ    │    Receive REQ
   ┌───────────┼───────────┐
   ▼           │           ▼
┌─────────┐   │   ┌──────────┐
│ OFFERED │   │   │ PENDING  │
└────┬────┘   │   └────┬─────┘
     │ ACCEPT │        │ User accepts
     ▼        │        ▼
┌──────────────────────────┐
│       NEGOTIATING        │  (FILE_METADATA exchange)
└────────────┬─────────────┘
             │ All files negotiated
             ▼
      ┌─────────────┐◄────────────┐
      │  STREAMING   │             │
      └──┬───┬───┬──┘   TRANSFER_ │
         │   │   │      RESUME    │
  PAUSE  │   │   │ LAST_CHUNK    │
         ▼   │   ▼               │
  ┌────────┐ │ ┌────────────┐    │
  │ PAUSED │─┘ │ COMPLETING │    │
  └────────┘   └─────┬──────┘    │
                     │ TRANSFER_ACK
                     ▼
              ┌──────────┐
              │ COMPLETE │
              └──────────┘

  (Any active state → CANCELLED via TRANSFER_CANCEL)
  (Any active state → FAILED via fatal error or disconnect)
```

### 11.3 Transitions

| From | To | Trigger | Timeout | Actions |
|------|----|---------|---------|---------|
| IDLE | OFFERED | Sender sends TRANSFER_REQUEST | 120s | Start consent timer |
| IDLE | PENDING | Receiver gets TRANSFER_REQUEST | 120s | Display consent UI |
| PENDING | NEGOTIATING | User accepts; send TRANSFER_ACCEPT | — | Begin FILE_METADATA exchange |
| PENDING | CANCELLED | User rejects; send TRANSFER_REJECT | — | Notify sender |
| OFFERED | NEGOTIATING | Receive TRANSFER_ACCEPT | — | Begin FILE_METADATA exchange |
| OFFERED | CANCELLED | Receive TRANSFER_REJECT | — | Notify user |
| OFFERED | FAILED | Consent timeout (120s) | — | Auto-reject, notify user |
| NEGOTIATING | STREAMING | All FILE_METADATA_ACK received (at least one `accepted=true`) | 30s per file | Sender begins CHUNK frames |
| NEGOTIATING | FAILED | All files rejected or timeout | — | Cancel transfer |
| STREAMING | PAUSED | TRANSFER_PAUSE from either side | — | Sender stops sending chunks |
| STREAMING | COMPLETING | Last CHUNK sent (LAST_CHUNK flag on last file) | — | Sender sends TRANSFER_COMPLETE |
| STREAMING | CANCELLED | TRANSFER_CANCEL from either side | — | Cleanup partial files |
| STREAMING | FAILED | Checksum mismatch, disk_full, connection loss | — | Send ERROR if possible |
| STREAMING | COMPLETING | Receiver: last CHUNK received and hash verified | — | Begin whole-file verification, send TRANSFER_ACK |
| PAUSED | STREAMING | TRANSFER_RESUME from either side | 300s | Resume chunk transmission |
| PAUSED | CANCELLED | TRANSFER_CANCEL from either side | — | Cleanup |
| PAUSED | FAILED | Pause timeout (300s) | — | Auto-cancel |
| COMPLETING | COMPLETE | Receive TRANSFER_ACK with `status="success"` | 60s | Transfer done |
| COMPLETING | COMPLETE | Receiver: TRANSFER_ACK successfully sent | — | Transfer done |
| COMPLETING | FAILED | Receive TRANSFER_ACK with `status="partial_failure"` or checksum fails | — | Log failures |
| COMPLETING | FAILED | Completion timeout (60s) | — | Assume failure |
| Any active | CANCELLED | TRANSFER_CANCEL | — | Both sides clean up |
| Any active | FAILED | Fatal ERROR or connection loss | — | Preserve resume state |

---

## 12. Transfer Flows

### 12.1 Single-File Transfer

```
Sender                                              Receiver
  │                                                    │
  │──── TRANSFER_REQUEST (1 file in manifest) ────────►│
  │                                                    │ User sees consent UI
  │                                                    │ User taps "Accept"
  │◄──── TRANSFER_ACCEPT ─────────────────────────────│
  │                                                    │
  │──── FILE_METADATA (stream_id=1) ──────────────────►│
  │◄──── FILE_METADATA_ACK (accepted=true) ───────────│
  │                                                    │
  │──── CHUNK (stream_id=1, offset=0, 1MiB) ─────────►│
  │──── CHUNK (stream_id=1, offset=1M, 1MiB) ────────►│ Write to .part file
  │──── CHUNK (stream_id=1, offset=2M, 1MiB) ────────►│ Verify chunk hashes
  │◄──── CHUNK_ACK (offset=0, length=1M) ────────────│
  │◄──── CHUNK_ACK (offset=1M, length=1M) ───────────│
  │──── CHUNK (stream_id=1, offset=3M, LAST_CHUNK) ──►│
  │◄──── CHUNK_ACK (offset=2M, length=1M) ───────────│
  │◄──── CHUNK_ACK (offset=3M, ...) ─────────────────│
  │                                                    │
  │──── TRANSFER_COMPLETE ────────────────────────────►│
  │                                                    │ Verify whole-file SHA-256
  │                                                    │ Rename .part → final
  │◄──── TRANSFER_ACK (status="success") ─────────────│
  │                                                    │
```

**Key points:**
- The sender sends up to 8 chunks before waiting for ACKs (in-flight window = 8).
- ACKs may arrive out of order due to processing time.
- The receiver writes each chunk to a `.part` temporary file.
- After all chunks are received and verified, the `.part` file is atomically
  renamed to the final path.

### 12.2 Directory Transfer

The directory flow is identical to multi-file transfer but with `transfer_type = "directory"`:

1. `TRANSFER_REQUEST` with `transfer_type = "directory"` and a manifest listing all
   files with relative paths preserving directory structure.
2. User accepts the transfer (consent UI shows directory name and total size).
3. Files are streamed **sequentially** — one complete file at a time:
   - Sender sends `FILE_METADATA` for file 1, waits for `FILE_METADATA_ACK`.
   - Sender streams all CHUNKs for file 1, with `LAST_CHUNK` on the final chunk.
   - Sender sends `FILE_METADATA` for file 2, and so on.
4. `TRANSFER_COMPLETE` after the last chunk of the last file.
5. Receiver verifies all files and sends `TRANSFER_ACK`.

The receiver MUST create directory structure as needed, using the `relative_path`
from each file's metadata. All path safety rules (§18) apply.

### 12.3 Flow with Pairing

If devices are not paired, pairing can occur before or after a transfer:

```
Initiator                                           Responder
  │──── HELLO ─────────────────────────────────────────►│
  │◄──── HELLO_ACK (is_paired=false) ──────────────────│
  │                                                     │
  │──── PAIR_REQUEST (sas_method="numeric-6") ─────────►│
  │     [Both display SAS: "042857"]                    │
  │     [User confirms on both devices]                 │
  │◄──── PAIR_ACCEPT ──────────────────────────────────│
  │──── PAIR_ACCEPT ───────────────────────────────────►│
  │     [Identities pinned]                             │
  │                                                     │
  │──── TRANSFER_REQUEST ──────────────────────────────►│
  │     ... (transfer flow) ...                         │
```

---

## 13. Resume Protocol

### 13.1 Overview

NXFR supports resuming interrupted transfers. The receiver is responsible for
maintaining partial transfer state. The sender queries the receiver for resume
status and adapts its transmission accordingly.

### 13.2 Resume Flow

```
Sender                                              Receiver
  │                                                    │
  │──── HELLO ─────────────────────────────────────────►│
  │◄──── HELLO_ACK ───────────────────────────────────│
  │                                                    │
  │──── RESUME_QUERY (transfer_id=<prev>) ────────────►│
  │                                                    │ Check local state
  │◄──── RESUME_STATUS (resumable=true, ranges) ──────│
  │                                                    │
  │──── FILE_METADATA (file_id=1, stream_id=1) ──────►│
  │◄──── FILE_METADATA_ACK (accepted=true) ───────────│
  │                                                    │
  │     [Skip chunks for received ranges]              │
  │──── CHUNK (offset=3145728, ...) ──────────────────►│ Resume from offset
  │──── CHUNK (offset=4194304, LAST_CHUNK) ───────────►│
  │◄──── CHUNK_ACK ──────────────────────────────────│
  │                                                    │
  │──── TRANSFER_COMPLETE ────────────────────────────►│
  │◄──── TRANSFER_ACK ───────────────────────────────│
```

### 13.3 Receiver State Persistence

The receiver MUST persist partial transfer state to survive application restarts:

- **`.part` files:** Partially received file data, written to the receive directory
  with a `.part` suffix.
- **State journal:** A file (JSON or CBOR) recording per-file received byte ranges,
  the transfer_id, manifest, and timestamps.
- **Crash safety:** The state journal MUST be written with `fsync` after each chunk
  acknowledgment to survive power loss.

### 13.4 Resume State Expiry

Partial transfer state MUST be automatically purged after **24 hours** (default).
This prevents unbounded disk usage from abandoned transfers. The expiry period
SHOULD be configurable by the user.

### 13.5 Non-Resumable Cases

If `RESUME_STATUS` returns `resumable = false`, the sender MUST initiate a new
transfer via `TRANSFER_REQUEST`. This occurs when:

- Partial state has expired (past the 24-hour window).
- Partial state is corrupted (journal read failure).
- The receiver has no record of the `transfer_id`.
- The original manifest has changed (sender-side file modifications).

### 13.6 Resume Metadata Validation

When a sender resumes a transfer, it re-sends `FILE_METADATA` for each file. The
receiver MUST compare the resumed `FILE_METADATA` fields (`size`, `sha256`) against
the values stored in its resume state journal for that `transfer_id` and `file_id`.

- If `size` or `sha256` differ from the stored manifest, the file has been modified
  since the original transfer. The receiver MUST respond with `FILE_METADATA_ACK`
  with `accepted = false`.
- The sender SHOULD treat a rejected resumed file as non-resumable and initiate a
  fresh `TRANSFER_REQUEST` with a new `transfer_id` for the affected files.
- Implementations MUST NOT attempt to merge partial data from a previous transfer
  with a modified file, as this would produce a corrupt result.

---

## 14. Directory Transfer

### 14.1 Manifest

A directory transfer uses a manifest in the `TRANSFER_REQUEST` listing all files
with relative paths. The paths use forward slash (`/`) as separator and preserve
the directory hierarchy.

Example manifest for a "photos" directory:
```
photos/
├── vacation/
│   ├── beach.jpg
│   └── sunset.jpg
└── README.txt
```

Manifest entries:
```
[
  { "file_id": 1, "relative_path": "vacation/beach.jpg",  "size": 5242880, "sha256": ... },
  { "file_id": 2, "relative_path": "vacation/sunset.jpg", "size": 3145728, "sha256": ... },
  { "file_id": 3, "relative_path": "README.txt",          "size": 1024,    "sha256": ... }
]
```

### 14.2 Sequential Streaming

Files within a directory MUST be streamed sequentially (one at a time). Parallel
file streaming is NOT supported in v0.1. The sender MUST:

1. Send `FILE_METADATA` for file N.
2. Wait for `FILE_METADATA_ACK`.
3. Stream all CHUNK frames for file N.
4. Set `LAST_CHUNK` on the final chunk of file N.
5. Proceed to file N+1.

### 14.3 Receiver Reconstruction

The receiver MUST:

1. Create necessary directories under the user-chosen receive directory.
2. Validate each `relative_path` against path safety rules (§18) before creating
   files or directories.
3. Write each file to a `.part` temporary path.
4. After the complete transfer is verified, atomically rename all `.part` files
   to their final paths.

### 14.4 Empty Directories

Empty directories MAY be represented in the manifest using entries with
`"type": "dir"`. A `"dir"` entry:

- MUST have `file_id` and `relative_path` fields.
- MUST NOT have `size`, `sha256`, or `stream_id` fields.
- MUST NOT generate `FILE_METADATA`, `FILE_METADATA_ACK`, or CHUNK frames.
- MUST pass all path safety rules (§18).

The receiver MUST create the directory during reconstruction (§14.3). If the
directory already exists, the receiver MUST treat it as a no-op.

Senders that do not need to preserve empty directories MAY omit `"dir"` entries
entirely; intermediate directories implied by file paths are always created.

### 14.5 Symlinks

Symlinks MUST NOT be included in a directory manifest. If a sender encounters a
symlink in the directory tree, it MUST either skip it or resolve it to the target
file (following the link). The `relative_path` MUST NOT contain symlink references.

---

## 15. Error Handling

### 15.1 Error Code Table

| Code | Fatal | Retryable | Description |
|------|-------|-----------|-------------|
| `unsupported_version` | Yes | No | Peer's major version is not supported. |
| `invalid_frame` | Yes | No | Frame header is malformed (bad magic, unknown kind, etc.). |
| `payload_too_large` | Yes | No | Payload exceeds kind-specific size limit. |
| `invalid_cbor` | Yes | No | Control frame payload cannot be decoded as valid CBOR. |
| `unknown_message_type` | No | No | Control message type code is not recognized. Log and ignore. |
| `session_timeout` | Yes | No | No KEEPALIVE response within timeout period. |
| `checksum_mismatch` | No | Yes | SHA-256 of chunk or file does not match expected value. |
| `disk_full` | No | No | Receiver cannot write to disk. Transfer fails. |
| `path_rejected` | No | No | A file path failed safety validation. File is skipped. |
| `transfer_not_found` | No | No | The referenced `transfer_id` is unknown. |
| `stream_not_found` | No | No | The referenced `stream_id` is unknown. |
| `identity_changed` | Yes | No | Peer's device_id does not match pinned identity. |
| `pair_required` | No | No | Operation requires the peer to be paired. |
| `rate_limited` | No | Yes | Too many requests. Back off and retry. |
| `internal_error` | Yes | No | Implementation bug or unrecoverable state. |
| `manifest_too_large` | No | No | Encoded TRANSFER_REQUEST exceeds 64 KiB CONTROL payload limit. |

### 15.2 Error Processing Rules

1. **Fatal errors** MUST cause the session to close. The sender of a fatal ERROR
   MUST close the connection after sending the message.
2. **Non-fatal errors** are informational. The session continues. The affected
   transfer may fail, but other transfers are unaffected.
3. **Retryable errors** indicate the operation may succeed if retried after a delay.
   Implementations SHOULD use exponential backoff with a base of 1 second.
4. **Unknown error codes** MUST be treated as non-fatal. Implementations MUST NOT
   close the session for unrecognized error codes.
5. **Forward compatibility**: Implementations MUST gracefully handle unknown
   error code strings. An error code not listed in §15.1 MUST be preserved
   as-is (e.g., wrapped in an `Unknown` variant) and treated as non-fatal.
   Implementations MUST NOT crash or close the session upon receiving an
   unrecognized error code.

### 15.3 Checksum Mismatch Handling

When a chunk's SHA-256 does not match `chunk_hash`:

1. Receiver sends ERROR with `code = "checksum_mismatch"`, `fatal = false`.
2. Receiver includes `details` with `stream_id` and `offset`.
3. Sender MAY retransmit the chunk (same offset).
4. After 3 consecutive checksum mismatches for the same chunk, the transfer
   SHOULD be cancelled.

When a whole-file SHA-256 (after all chunks) does not match the manifest:

1. Receiver sends TRANSFER_ACK with `status = "partial_failure"` and the
   failed `file_id` in `failed_files`.
2. The partial file MUST be deleted (not renamed to final path).

---

## 16. Versioning & Capabilities

### 16.1 Protocol Version

The protocol version consists of a major and minor number: `[major, minor]`.

- **Major increment:** Breaking change. Implementations with different major
  versions MUST NOT interoperate. The higher-version side sends ERROR
  `unsupported_version` and closes.
- **Minor increment:** Additive change only. New optional fields, new optional
  capabilities. Lower-minor implementations ignore unknown fields.

The current version is `[1, 0]` (v1.0).

### 16.2 Frame Format Version

The `version` field in the frame header tracks frame format changes independently
of the protocol version. NXFR v0.1 uses frame format version `1`.

### 16.3 Capability Negotiation

Capabilities are optional protocol extensions negotiated during the HELLO exchange.
Both sides advertise their supported capabilities. The active set is the intersection.

v0.1 defines no mandatory capabilities. Future capabilities:

| Token | Description |
|-------|-------------|
| `blake3` | BLAKE3 as alternative hash algorithm for chunk/file integrity. |
| `zstd` | zstd compression for CHUNK payloads. |

Implementations MUST ignore unknown capability tokens.

### 16.4 Unknown Fields

Implementations MUST ignore unknown fields in CBOR maps. This is the primary
extensibility mechanism: future minor versions add optional fields to existing
messages without breaking older implementations.

---

## 17. Limits & Timeouts

### 17.1 Limits Table

| Parameter | Value | Rationale |
|-----------|-------|-----------|
| Control payload max | 64 KiB (65,536 B) | Prevents memory exhaustion from oversized control messages. Hard upper bound on any single CONTROL frame, including TRANSFER_REQUEST. |
| Chunk payload max | 4 MiB (4,194,304 B) | Balances throughput (fewer frames per file) against memory pressure. 8 in-flight × 4 MiB = 32 MiB max buffer. |
| Default chunk size | 1 MiB (1,048,576 B) | Good default for gigabit LAN. Sender MAY use any size in [1 KiB, 4 MiB]. |
| In-flight chunk window | 8 | Keeps the network pipe full without unbounded buffering. At 1 MiB chunks, 8 MiB max unacknowledged data. |
| Max concurrent transfers | 4 per session | Prevents resource exhaustion on constrained devices. |
| Max concurrent sessions | 8 per device | Prevents connection exhaustion. |
| Max manifest entries | 500 | Encoded TRANSFER_REQUEST MUST fit in 64 KiB. At ~100 bytes per entry (path + hash), 500 entries ≈ 50 KiB CBOR. Paging deferred to v0.2. |
| Max device name | 63 bytes (UTF-8) | Aligned with DNS-SD TXT value limit per RFC 6763. |
| Max path component | 255 bytes | Filesystem compatibility (ext4, NTFS, APFS). |
| Max relative path | 4,096 bytes | Filesystem compatibility (PATH_MAX on Linux). |
| Max file size | 2^64 - 1 bytes | No artificial limit. Bounded by u64 offset field. |

### 17.2 Timeouts Table

| Timeout | Value | Rationale |
|---------|-------|-----------|
| TLS handshake | 10 s | Generous for LAN. Prevents indefinite hang on unresponsive peers. |
| HELLO exchange | 10 s | Both HELLO and HELLO_ACK must complete within this window. |
| Pairing SAS confirmation | 60 s | User needs time to compare codes on both screens and confirm. |
| Transfer consent | 120 s | User may need to unlock phone, find notification, review transfer. |
| KEEPALIVE interval | 30 s | Send a PING every 30 seconds of inactivity. |
| KEEPALIVE timeout | 90 s | 3 × interval. If no response to 3 consecutive PINGs, declare connection dead. |
| Chunk ACK | 30 s | Per-chunk. Allows for slow disk writes or verification. |
| Transfer completion | 60 s | After TRANSFER_COMPLETE, wait for TRANSFER_ACK. |
| Session close grace | 5 s | After SESSION_CLOSE, wait for peer's close before force-closing. |
| Pause timeout | 300 s | Auto-cancel a transfer paused for more than 5 minutes. |
| Resume state expiry | 24 h | Purge partial files and state after 24 hours. Configurable. |

---

## 18. Path Safety

### 18.1 Overview

The receiver MUST treat ALL paths received from the sender as potentially hostile.
Path sanitization is a critical security requirement. Malicious paths could enable
directory traversal, overwriting system files, or other attacks.

### 18.2 Validation Rules

The receiver MUST apply the following rules to every `relative_path`:

1. **Forward slash only.** The path separator MUST be `/`. Backslashes (`\`) MUST
   be rejected or replaced with `/`.
2. **No absolute paths.** Paths starting with `/`, `\`, or a drive letter (e.g.,
   `C:\`) MUST be rejected.
3. **No parent traversal.** Any path component equal to `..` MUST cause rejection.
4. **No current-directory markers.** Path components equal to `.` MUST be stripped.
5. **No null bytes.** Paths containing `0x00` MUST be rejected.
6. **No control characters.** Bytes in the range `0x00-0x1F` and `0x7F` MUST cause
   rejection.
7. **Windows reserved names.** Path components matching (case-insensitive) `CON`,
   `PRN`, `AUX`, `NUL`, `COM1` through `COM9`, or `LPT1` through `LPT9` SHOULD
   be rejected on all platforms (for cross-platform safety).
8. **Path normalization.** Collapse multiple consecutive slashes into one. Remove
   trailing slashes.
9. **Length limits.** Each path component MUST be ≤ 255 bytes. Total path MUST be
   ≤ 4,096 bytes.

### 18.3 Write Strategy

1. Compute `final_path = receive_directory / normalized_relative_path`.
2. Verify `final_path` is within `receive_directory` (canonicalize and check prefix).
3. Create necessary parent directories.
4. Write data to `final_path.part` (temporary file).
5. After successful whole-file hash verification, atomically rename
   `final_path.part` → `final_path`.
6. If `final_path` already exists, append a numeric suffix: `file(1).jpg`,
   `file(2).jpg`, etc.

### 18.4 Symlink Prevention

Implementations MUST NOT create symlinks from received path data. Implementations
MUST NOT follow symlinks in the receive directory when resolving write paths. If a
symlink exists at any component of the final path, the write MUST be rejected.

---

## 19. Test Strategy

### 19.1 Unit Tests

Implementations MUST include unit tests for:

- **Frame parser:** Correctly parse valid frames. Reject truncated headers, bad magic,
  unknown kinds, oversized payloads.
- **CBOR encoder/decoder:** Round-trip all message types. Correctly handle optional
  fields, unknown fields, edge cases (empty arrays, maximum-size integers).
- **Path sanitizer:** Reject all attack patterns from §18. Accept valid paths.
  Test Unicode edge cases.
- **SAS derivation:** Given known device_id values and TLS exporter output, produce
  the expected 6-digit code.
- **State machine transitions:** Verify all valid transitions. Verify invalid
  transitions are rejected. Test timeout transitions.

### 19.2 Golden Test Vectors

The `WIRE_FORMAT.md` document defines canonical test vectors: specific frame bytes
and CBOR encodings that all compliant implementations MUST produce for given inputs.
These enable cross-implementation compatibility testing.

### 19.3 Fuzz Targets

Implementations SHOULD expose fuzz targets for:

1. **Frame parser:** Feed arbitrary bytes, verify no crashes/panics.
2. **CBOR decoder:** Feed arbitrary CBOR, verify graceful rejection of malformed data.
3. **Path sanitizer:** Feed adversarial path strings, verify no escapes.
4. **Resume state deserializer:** Feed corrupt state journals.

### 19.4 Integration Scenarios

1. **Happy path single file:** Send a 10 MiB file between two instances. Verify
   content matches.
2. **Happy path directory:** Send a directory with 100 files. Verify all paths
   and contents.
3. **Resume after disconnect:** Interrupt at 50%, reconnect, resume to completion.
4. **Pairing flow:** Full TOFU + SAS verification between two new devices.
5. **Version mismatch:** Connect with incompatible major version. Verify clean error.
6. **Cancel mid-transfer:** Cancel at various points. Verify cleanup.
7. **Reject transfer:** Reject at consent UI. Verify sender notification.
8. **Checksum mismatch:** Inject corruption. Verify detection and handling.
9. **Disk full simulation:** Fill receiver disk. Verify graceful failure.
10. **Multiple concurrent transfers:** Run 4 simultaneous transfers. Verify isolation.

### 19.5 Network Impairment Testing

Use `tc`/`netem` (Linux) to test under adverse conditions:

- **High latency:** 100ms, 500ms RTT. Verify timeouts don't trigger prematurely.
- **Packet loss:** 1%, 5%, 10%. Verify TCP recovery and no protocol-level issues.
- **Bandwidth limits:** 1 Mbps, 10 Mbps. Verify flow control and progress reporting.
- **Connection reset:** Force TCP RST at random points. Verify cleanup.

### 19.6 Interoperability Requirements

The protocol MUST be implementable without exotic primitives in:

| Platform | TLS Stack | CBOR Library | Hash Library |
|----------|-----------|--------------|--------------|
| Rust | rustls | ciborium | ring / sha2 |
| Kotlin/JVM | BoringSSL (Conscrypt) | cbor-java / jackson-cbor | java.security.MessageDigest |
| C# (.NET) | SslStream (SChannel) | PeterO.Cbor | System.Security.Cryptography |

All three implementations MUST interoperate: a Rust sender to a Kotlin receiver,
a C# sender to a Rust receiver, etc.

---

## 20. Web Portal Protocol Extension (Share & Upload via Browser)

To support interoperability with devices without native NXFR software installed (e.g. iOS, Windows, macOS, smart TVs), implementations MAY provide an ad-hoc HTTPS Web Portal.

### 20.1 Transport & Binding
- **Port:** Default `17396` TCP (TLS 1.3).
- **Certificate:** Uses the device's self-signed X.509 certificate without requiring client certificate validation.
- **Session Lifetime:** Automatically terminates after 10 minutes of inactivity or upon explicit session stop.

### 20.2 Authorization Model
1. **Fragment-Only Tokens (`/#t=<token>`):**
   - 128-bit cryptographically secure random hex string generated upon server start.
   - Kept in URL fragment identifiers so it is processed strictly by browser JavaScript and never transmitted in raw HTTP request lines.
2. **Security PIN Protection (`pin: Option<String>`):**
   - Optional 4 to 8 digit numeric PIN configured by the host node.
   - When active, the share link omits the token (`https://<ip>:17396/`).
   - The browser UI presents an interactive PIN entry dialog before granting access.
   - Authorized requests MUST include `Authorization: Bearer <pin>` header or `?t=<pin>` query parameter.
3. **Endpoint Routing:**
   - `GET /` — Serves self-contained single-page application (`HTML_DOWNLOAD_PAGE` or `HTML_PAGE`) with embedded certificate SPKI fingerprint and file manifest.
   - `GET /auth` — Verifies provided token or PIN, returning `200 {"status": "authenticated"}` or `403 {"error": "Invalid PIN"}`.
   - `GET /dl/:id` — Streams requested file payload from manifest with chunked transfer encoding and SHA-256 validation.
   - `POST /upload` — Receives `multipart/form-data` file upload into sandboxed `web-inbox/` with strict filename sanitization.
   - `GET /dl/all.zip` — Streams all manifest files as a ZIP archive using chunked transfer encoding (RFC 9112). Individual file entries are streamed directly from disk without buffering the full archive in memory.
4. **Brute-Force Mitigation:**
   - Client IPs exceeding 5 consecutive failed authorization attempts are immediately throttled and blocked for 5 minutes (`403 Forbidden`).
5. **I/O Timeouts:**
   - HTTP header read: 15 seconds maximum.
   - Chunk/body I/O: 30 seconds maximum per read/write operation.
   - Connections that stall beyond these limits MUST be dropped.
6. **Temporary File Safety:**
   - Upload temp files MUST use random filenames to prevent collision.
   - Temp files MUST be cleaned up on error or cancellation (RAII guard pattern).
   - Final filenames MUST resolve collisions by appending `(1)`, `(2)`, etc.

---

## 21. References

| Reference | Title |
|-----------|-------|
| [RFC 2119](https://datatracker.ietf.org/doc/html/rfc2119) | Key words for use in RFCs to Indicate Requirement Levels |
| [RFC 5280](https://datatracker.ietf.org/doc/html/rfc5280) | Internet X.509 PKI Certificate and CRL Profile |
| [RFC 5869](https://datatracker.ietf.org/doc/html/rfc5869) | HMAC-based Extract-and-Expand Key Derivation Function (HKDF) |
| [RFC 6762](https://datatracker.ietf.org/doc/html/rfc6762) | Multicast DNS |
| [RFC 6763](https://datatracker.ietf.org/doc/html/rfc6763) | DNS-Based Service Discovery |
| [RFC 7301](https://datatracker.ietf.org/doc/html/rfc7301) | Transport Layer Security (TLS) ALPN Extension |
| [RFC 8446](https://datatracker.ietf.org/doc/html/rfc8446) | The Transport Layer Security (TLS) Protocol Version 1.3 |
| [RFC 8949](https://datatracker.ietf.org/doc/html/rfc8949) | Concise Binary Object Representation (CBOR) |
| [FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) | Secure Hash Standard (SHS) — SHA-256 |
| [FIPS 186-4](https://csrc.nist.gov/pubs/fips/186-4/final) | Digital Signature Standard (DSS) — ECDSA |

---

*End of NXFR Protocol Specification v1.0*
