# NXFR Wire Format Specification

**Status:** Draft
**Date:** 2026-08-16
**Companion to:** [PROTOCOL.md](PROTOCOL.md)

This document defines the byte-level wire format of the NXFR protocol, provides
CBOR encoding rules, complete message schemas, golden test vectors with verified
hex encodings, and an annotated wire trace of a full transfer.

---

## 1. Conventions

- All hex values are written as `0xNN` or as contiguous hex strings.
- Byte offsets are zero-based.
- All multi-byte integers are **big-endian** (network byte order).
- CBOR diagnostic notation follows [RFC 8949] §8.
- Sizes are in bytes unless otherwise noted.

---

## 2. Byte Order

NXFR uses big-endian (network byte order) for all multi-byte integer fields in
frame headers and chunk payloads. This is consistent with standard network protocol
conventions and matches the encoding used by TCP, TLS, and DNS.

CBOR integer encoding follows RFC 8949 rules (which also uses network byte order
for multi-byte integer arguments).

---

## 3. Frame Header Layout

Every NXFR frame begins with a fixed **28-byte header**:

```
 0                   1                   2                   3
 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|          magic (4 bytes): 'N'  'X'  'F'  'R'                  |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|   version     |     kind      |           flags               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         session_id                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                         stream_id                             |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                                                               |
+                        message_id                             +
|                                                               |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
|                        payload_len                            |
+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
```

| Offset | Size | Field | Type | Encoding | Description |
|--------|------|-------|------|----------|-------------|
| 0 | 4 | `magic` | `[u8; 4]` | ASCII | Always `0x4E584652` (`"NXFR"`). |
| 4 | 1 | `version` | `u8` | — | Frame format version. `0x01` for v0.1. |
| 5 | 1 | `kind` | `u8` | — | `0x01` = CONTROL, `0x02` = CHUNK, `0x03` = KEEPALIVE. |
| 6 | 2 | `flags` | `u16` | Big-endian | Bit flags, semantics per kind (see §3.1). |
| 8 | 4 | `session_id` | `u32` | Big-endian | Session identifier. `0` before HELLO_ACK. |
| 12 | 4 | `stream_id` | `u32` | Big-endian | `0` = session-level. `>0` = file stream. |
| 16 | 8 | `message_id` | `u64` | Big-endian | Monotonically increasing per direction. |
| 24 | 4 | `payload_len` | `u32` | Big-endian | Payload length in bytes. May be `0`. |

**Total header:** 28 bytes, always.

### 3.1 Flags Bit Definitions

| Kind | Bit 0 | Bits 1–15 |
|------|-------|-----------|
| CONTROL (`0x01`) | Reserved, MUST be `0` | Reserved, MUST be `0` |
| CHUNK (`0x02`) | `LAST_CHUNK` — `1` if this is the final chunk of the file | Reserved, MUST be `0` |
| KEEPALIVE (`0x03`) | `IS_PONG` — `0` = PING, `1` = PONG | Reserved, MUST be `0` |

Receivers MUST ignore unknown flag bits (for forward compatibility).

---

## 4. Frame Kind: CONTROL (`0x01`)

### 4.1 Payload Format

The payload is a single CBOR-encoded map ([RFC 8949]). The map MUST contain a
`"type"` key with an unsigned integer value identifying the control message type.

```
Payload: [CBOR Map]
```

### 4.2 Size Limit

The maximum `payload_len` for a CONTROL frame is **65,536 bytes** (64 KiB).
Frames exceeding this limit MUST be rejected with ERROR `payload_too_large`.

### 4.3 Control Message Type Codes

| Code | Name | Code | Name |
|------|------|------|------|
| `0x01` | HELLO | `0x11` | TRANSFER_ACCEPT |
| `0x02` | HELLO_ACK | `0x12` | TRANSFER_REJECT |
| `0x03` | PAIR_REQUEST | `0x13` | FILE_METADATA |
| `0x04` | PAIR_ACCEPT | `0x14` | FILE_METADATA_ACK |
| `0x05` | PAIR_REJECT | `0x15` | CHUNK_ACK |
| `0x06` | SESSION_CLOSE | `0x16` | TRANSFER_PAUSE |
| `0x09` | ERROR | `0x17` | TRANSFER_RESUME |
| `0x10` | TRANSFER_REQUEST | `0x18` | TRANSFER_CANCEL |
| | | `0x19` | TRANSFER_COMPLETE |
| | | `0x1A` | TRANSFER_ACK |
| | | `0x20` | RESUME_QUERY |
| | | `0x21` | RESUME_STATUS |

Type codes `0x07`, `0x08`, `0x0A`–`0x0F`, `0x1B`–`0x1F`, and `0x22`+ are reserved.

---

## 5. Frame Kind: CHUNK (`0x02`)

### 5.1 Payload Format

The CHUNK payload has a fixed 40-byte header followed by raw file data:

```
Offset  Size  Field        Type      Description
0       8     offset       u64 BE    Byte offset of this chunk within the file.
8       32    chunk_hash   [u8;32]   SHA-256 of the 'data' portion only.
40      var   data         [u8]      Raw file data. Length = payload_len - 40.
```

### 5.2 Size Limits

- Maximum `payload_len`: **4,194,304 bytes** (4 MiB).
- Minimum `payload_len`: **41 bytes** (40-byte header + 1 byte of data).
- Maximum `data` portion: 4,194,264 bytes.

### 5.3 Integrity

The `chunk_hash` field contains the SHA-256 digest computed over ONLY the `data`
portion (bytes from offset 40 to end of payload). It does NOT include the offset
or the hash itself.

### 5.4 LAST_CHUNK Flag

When `flags` bit 0 is set (`flags = 0x0001`), this is the final chunk of the file
identified by `stream_id`. The receiver uses this to know when to verify the
whole-file SHA-256 digest.

---

## 6. Frame Kind: KEEPALIVE (`0x03`)

### 6.1 Payload Format

KEEPALIVE frames have either an **empty payload** (0 bytes) or an **8-byte
timestamp payload**.

| Payload Size | Contents |
|-------------|----------|
| 0 bytes | No RTT measurement. Liveness check only. |
| 8 bytes | `u64` big-endian timestamp in milliseconds since Unix epoch. |

### 6.2 PING vs PONG

- `flags = 0x0000`: This is a **PING**. The receiver MUST respond with a PONG.
- `flags = 0x0001`: This is a **PONG**. If the PING included a timestamp, the
  PONG MUST echo the same timestamp value.

### 6.3 Size Limit

Maximum `payload_len` for KEEPALIVE: **8 bytes**. Larger payloads MUST be rejected.

---

## 7. CBOR Encoding Rules

All control message payloads MUST follow these CBOR encoding rules:

| Rule | Requirement | Rationale |
|------|-------------|-----------|
| Definite-length only | MUST use definite-length encoding for all items | Enables bounded memory allocation |
| String keys | Map keys MUST be UTF-8 text strings (major type 3) | Debuggability, consistency |
| Binary data | Binary values MUST use byte strings (major type 2) | Efficiency for hashes, IDs |
| Integer minimality | Integers MUST use the smallest valid CBOR encoding | Interoperability |
| No tags | CBOR tags (major type 6) MUST NOT be used in v0.1 | Simplicity |
| Max nesting | Maximum nesting depth: 6 (RESUME_STATUS requires depth 6) | Prevents stack overflow |
| Unknown fields | Receivers MUST ignore unknown map keys | Forward compatibility |
| Deterministic keys | Map keys SHOULD be sorted lexicographically | Reproducible test vectors |

### 7.1 CBOR Major Types Used

| Major Type | Description | Used For |
|------------|-------------|----------|
| 0 | Unsigned integer | `type`, `file_id`, `stream_id`, `offset`, `size`, etc. |
| 2 | Byte string | `device_id`, `transfer_id`, `sha256`, `chunk_hash` |
| 3 | Text string | `device_name`, `platform`, `code`, `reason`, map keys |
| 4 | Array | `protocol_version`, `capabilities`, `manifest`, `received_ranges` |
| 5 | Map | All control messages, manifest entries |
| 7 | Simple/float | `true` (`0xF5`), `false` (`0xF4`) |

---

## 8. Control Message CBOR Schemas

Each schema is shown in CBOR diagnostic notation ([RFC 8949] §8). Fields are
listed with their CBOR type and whether they are required (R) or optional (O).

### 8.1 HELLO (type `0x01`)

```cbor-diag
{
  "type":             1,
  "protocol_version": [0, 1],
  "device_id":        h'15345a9ebc1613a2f80110e2c5c24329cf16a76dc8ff08c3e299a17180d2f03f',
  "device_name":      "Alice-Laptop",
  "platform":         "linux",
  "capabilities":     [],
  "is_paired":        false
}
```

### 8.2 HELLO_ACK (type `0x02`)

```cbor-diag
{
  "type":             2,
  "protocol_version": [0, 1],
  "device_id":        h'cdf00682a6d1ff7085cfc40c737f1ab987929c4c3ee5a26a74ba9f0249224c8b',
  "device_name":      "Bob-Phone",
  "platform":         "android",
  "capabilities":     [],
  "is_paired":        false,
  "session_id":       4660
}
```

### 8.3 PAIR_REQUEST (type `0x03`)

```cbor-diag
{ "type": 3, "sas_method": "numeric-6" }
```

### 8.4 PAIR_ACCEPT (type `0x04`)

```cbor-diag
{ "type": 4 }
```

### 8.5 PAIR_REJECT (type `0x05`)

```cbor-diag
{ "type": 5, "reason": "user_declined" }
```

### 8.6 SESSION_CLOSE (type `0x06`)

```cbor-diag
{ "type": 6, "reason": "normal" }
```

### 8.7 ERROR (type `0x09`)

```cbor-diag
{
  "type":    9,
  "code":    "checksum_mismatch",
  "message": "Hash verification failed",
  "fatal":   false
}
```

### 8.8 TRANSFER_REQUEST (type `0x10`)

```cbor-diag
{
  "type":          16,
  "transfer_id":   h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  "transfer_type": "files",
  "display_name":  "test.bin",
  "total_files":   1,
  "total_size":    16,
  "manifest": [
    {
      "file_id":       1,
      "relative_path": "test.bin",
      "size":          16,
      "sha256":        h'be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991'
    }
  ]
}
```

### 8.9 TRANSFER_ACCEPT (type `0x11`)

```cbor-diag
{ "type": 17, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }
```

### 8.10 TRANSFER_REJECT (type `0x12`)

```cbor-diag
{ "type": 18, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', "reason": "user_declined" }
```

### 8.11 FILE_METADATA (type `0x13`)

```cbor-diag
{
  "type":          19,
  "transfer_id":   h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  "file_id":       1,
  "stream_id":     1,
  "relative_path": "test.bin",
  "size":          16,
  "sha256":        h'be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991',
  "mime_type":     "application/octet-stream"
}
```

### 8.12 FILE_METADATA_ACK (type `0x14`)

```cbor-diag
{
  "type":        20,
  "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  "file_id":     1,
  "stream_id":   1,
  "accepted":    true
}
```

### 8.13 CHUNK_ACK (type `0x15`)

```cbor-diag
{ "type": 21, "stream_id": 1, "message_id": 5, "offset": 0, "length": 1048576 }
```

### 8.14 TRANSFER_PAUSE (type `0x16`)

```cbor-diag
{ "type": 22, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }
```

### 8.15 TRANSFER_RESUME (type `0x17`)

```cbor-diag
{ "type": 23, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }
```

### 8.16 TRANSFER_CANCEL (type `0x18`)

```cbor-diag
{ "type": 24, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', "reason": "user_cancelled" }
```

### 8.17 TRANSFER_COMPLETE (type `0x19`)

```cbor-diag
{ "type": 25, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA' }
```

### 8.18 TRANSFER_ACK (type `0x1A`)

```cbor-diag
{ "type": 26, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', "status": "success", "failed_files": [] }
```

### 8.19 RESUME_QUERY (type `0x20`)

```cbor-diag
{ "type": 32, "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA', "file_ids": [1] }
```

### 8.20 RESUME_STATUS (type `0x21`)

```cbor-diag
{
  "type":        33,
  "transfer_id": h'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  "resumable":   true,
  "files": [
    {
      "file_id":         1,
      "received_bytes":  8,
      "received_ranges": [[0, 8]]
    }
  ]
}
```

---

## 9. Golden Test Vectors

The following test vectors use these fixed inputs:

| Input | Value |
|-------|-------|
| Device A SPKI | `"test-device-a-spki"` (ASCII) |
| Device A `device_id` | `SHA-256("test-device-a-spki")` = `15345a9ebc1613a2f80110e2c5c24329cf16a76dc8ff08c3e299a17180d2f03f` |
| Device B SPKI | `"test-device-b-spki"` (ASCII) |
| Device B `device_id` | `SHA-256("test-device-b-spki")` = `cdf00682a6d1ff7085cfc40c737f1ab987929c4c3ee5a26a74ba9f0249224c8b` |
| Device A name | `"Alice-Laptop"` |
| Device B name | `"Bob-Phone"` |
| `session_id` | `0x00001234` (4660 decimal) |
| `transfer_id` | 16 bytes of `0xAA` |
| Chunk test data | `0x000102030405060708090a0b0c0d0e0f` (16 bytes) |
| Chunk test hash | `SHA-256(0x00..0f)` = `be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991` |

All CBOR encodings and hex values below have been verified using the `cbor2` Python
library and standard `sha256sum`. Implementations MUST reproduce these exact byte
sequences for the given inputs.

**Note:** The `protocol_version` tuple in production is now `[1, 0]` (v1.0), but these test vectors are preserved as-is with `[0, 1]` for backward compatibility testing. The actual hex bytes are verified test fixtures and have not been changed.

---

### 9.1 Vector 1: HELLO Frame

**Human-readable values:**
- Kind: CONTROL (0x01)
- Session ID: 0 (not yet assigned)
- Stream ID: 0 (session-level)
- Message ID: 1
- Type: HELLO (1)
- Protocol version: [0, 1]
- Device ID: Alice's device_id (32 bytes)
- Device name: "Alice-Laptop"
- Platform: "linux"
- Capabilities: [] (empty)
- Is paired: false

**Frame header (28 bytes):**
```
4e 58 46 52   magic = "NXFR"
01            version = 1
01            kind = CONTROL
00 00         flags = 0x0000
00 00 00 00   session_id = 0
00 00 00 00   stream_id = 0
00 00 00 00 00 00 00 01   message_id = 1
00 00 00 88   payload_len = 136 (0x88)
```

**CBOR payload (136 bytes):**
```
a7                        -- map(7)
  64 74 79 70 65          -- text(4) "type"
  01                      -- unsigned(1)
  70 70 72 6f 74 6f 63 6f 6c 5f 76 65 72 73 69 6f 6e
                          -- text(16) "protocol_version"
  82                      -- array(2)
    00                    -- unsigned(0)
    01                    -- unsigned(1)
  69 64 65 76 69 63 65 5f 69 64
                          -- text(9) "device_id"
  58 20                   -- bytes(32)
    15 34 5a 9e bc 16 13 a2 f8 01 10 e2 c5 c2 43 29
    cf 16 a7 6d c8 ff 08 c3 e2 99 a1 71 80 d2 f0 3f
  6b 64 65 76 69 63 65 5f 6e 61 6d 65
                          -- text(11) "device_name"
  6c 41 6c 69 63 65 2d 4c 61 70 74 6f 70
                          -- text(12) "Alice-Laptop"
  68 70 6c 61 74 66 6f 72 6d
                          -- text(8) "platform"
  65 6c 69 6e 75 78      -- text(5) "linux"
  6c 63 61 70 61 62 69 6c 69 74 69 65 73
                          -- text(12) "capabilities"
  80                      -- array(0)
  69 69 73 5f 70 61 69 72 65 64
                          -- text(9) "is_paired"
  f4                      -- false
```

**Complete frame hex (164 bytes):**
```
4e584652 0101 0000 00000000 00000000 0000000000000001 00000088
a76474797065017070726f746f636f6c5f76657273696f6e820001
696465766963655f6964582015345a9ebc1613a2f80110e2c5c243
29cf16a76dc8ff08c3e299a17180d2f03f6b6465766963655f6e61
6d656c416c6963652d4c6170746f7068706c6174666f726d656c69
6e75786c6361706162696c6974696573806969735f706169726564f4
```

---

### 9.2 Vector 2: HELLO_ACK Frame

**Human-readable values:**
- Kind: CONTROL (0x01)
- Session ID: 0x00001234
- Message ID: 1
- Type: HELLO_ACK (2)
- Device ID: Bob's device_id
- Session ID (CBOR): 0x1234 (4660)

**Frame header (28 bytes):**
```
4e 58 46 52   magic = "NXFR"
01            version = 1
01            kind = CONTROL
00 00         flags = 0x0000
00 00 12 34   session_id = 0x1234
00 00 00 00   stream_id = 0
00 00 00 00 00 00 00 01   message_id = 1
00 00 00 95   payload_len = 149 (0x95)
```

**CBOR payload (149 bytes):**
```
a8                        -- map(8)
  64 74 79 70 65          -- "type"
  02                      -- unsigned(2)
  70 70 72 6f 74 6f ...   -- "protocol_version"
  82 00 01                -- [0, 1]
  69 64 65 76 69 63 65 5f 69 64  -- "device_id"
  58 20                   -- bytes(32)
    cd f0 06 82 a6 d1 ff 70 85 cf c4 0c 73 7f 1a b9
    87 92 9c 4c 3e e5 a2 6a 74 ba 9f 02 49 22 4c 8b
  6b 64 65 76 69 63 65 5f 6e 61 6d 65  -- "device_name"
  69 42 6f 62 2d 50 68 6f 6e 65  -- "Bob-Phone"
  68 70 6c 61 74 66 6f 72 6d  -- "platform"
  67 61 6e 64 72 6f 69 64  -- "android"
  6c 63 61 70 61 62 69 6c 69 74 69 65 73  -- "capabilities"
  80                      -- array(0)
  69 69 73 5f 70 61 69 72 65 64  -- "is_paired"
  f4                      -- false
  6a 73 65 73 73 69 6f 6e 5f 69 64  -- "session_id"
  19 12 34                -- unsigned(4660)
```

**Complete frame hex (177 bytes):**
```
4e584652 0101 0000 00001234 00000000 0000000000000001 00000095
a86474797065027070726f746f636f6c5f76657273696f6e820001
696465766963655f69645820cdf00682a6d1ff7085cfc40c737f1a
b987929c4c3ee5a26a74ba9f0249224c8b6b6465766963655f6e61
6d6569426f622d50686f6e6568706c6174666f726d67616e64726f
69646c6361706162696c6974696573806969735f706169726564f4
6a73657373696f6e5f6964191234
```

---

### 9.3 Vector 3: CHUNK Frame (LAST_CHUNK, 16 bytes data)

**Human-readable values:**
- Kind: CHUNK (0x02)
- Flags: 0x0001 (LAST_CHUNK)
- Session ID: 0x00001234
- Stream ID: 1
- Message ID: 5
- Offset: 0
- Data: 0x000102030405060708090a0b0c0d0e0f (16 bytes)
- Chunk hash: SHA-256(data) = `be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991`

**Frame header (28 bytes):**
```
4e 58 46 52   magic = "NXFR"
01            version = 1
02            kind = CHUNK
00 01         flags = 0x0001 (LAST_CHUNK)
00 00 12 34   session_id = 0x1234
00 00 00 01   stream_id = 1
00 00 00 00 00 00 00 05   message_id = 5
00 00 00 38   payload_len = 56 (0x38 = 8 + 32 + 16)
```

**Chunk payload (56 bytes):**
```
00 00 00 00 00 00 00 00   offset = 0 (u64 BE)
be 45 cb 26 05 bf 36 be   chunk_hash (SHA-256, bytes 0-7)
bd e6 84 84 1a 28 f0 fd   chunk_hash (bytes 8-15)
43 c6 98 50 a3 dc e5 fe   chunk_hash (bytes 16-23)
db a6 99 28 ee 3a 89 91   chunk_hash (bytes 24-31)
00 01 02 03 04 05 06 07   data (bytes 0-7)
08 09 0a 0b 0c 0d 0e 0f   data (bytes 8-15)
```

**Complete frame hex (84 bytes):**
```
4e584652 0102 0001 00001234 00000001 0000000000000005 00000038
0000000000000000
be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a8991
000102030405060708090a0b0c0d0e0f
```

---

### 9.4 Vector 4: KEEPALIVE PING (empty payload)

**Human-readable values:**
- Kind: KEEPALIVE (0x03)
- Flags: 0x0000 (PING)
- Session ID: 0x00001234
- Stream ID: 0
- Message ID: 10
- Payload: empty (0 bytes)

**Complete frame hex (28 bytes):**
```
4e584652 0103 0000 00001234 00000000 000000000000000a 00000000
```

```
4e 58 46 52   magic = "NXFR"
01            version = 1
03            kind = KEEPALIVE
00 00         flags = 0x0000 (PING)
00 00 12 34   session_id = 0x1234
00 00 00 00   stream_id = 0
00 00 00 00 00 00 00 0a   message_id = 10
00 00 00 00   payload_len = 0
```

---

### 9.5 Vector 5: KEEPALIVE PONG (with timestamp)

**Human-readable values:**
- Kind: KEEPALIVE (0x03)
- Flags: 0x0001 (PONG)
- Session ID: 0x00001234
- Message ID: 11
- Timestamp: 1720000000000 ms (0x0000019077FD3000)

**Complete frame hex (36 bytes):**
```
4e584652 0103 0001 00001234 00000000 000000000000000b 00000008
0000019077fd3000
```

```
4e 58 46 52   magic
01            version = 1
03            kind = KEEPALIVE
00 01         flags = 0x0001 (PONG)
00 00 12 34   session_id
00 00 00 00   stream_id = 0
00 00 00 00 00 00 00 0b   message_id = 11
00 00 00 08   payload_len = 8
00 00 01 90 77 fd 30 00   timestamp = 1720000000000 ms
```

---

### 9.6 Vector 6: TRANSFER_REQUEST (1 file)

**Human-readable values:**
- Type: TRANSFER_REQUEST (16)
- Transfer ID: 16 × 0xAA
- Transfer type: "files"
- Display name: "test.bin"
- Total files: 1
- Total size: 16 bytes
- Manifest: 1 file (file_id=1, path="test.bin", size=16)

**CBOR payload (193 bytes):**
```
a7                        -- map(7)
  64 74 79 70 65          -- "type"
  10                      -- unsigned(16)
  6b 74 72 61 6e 73 66 65 72 5f 69 64  -- "transfer_id"
  50 aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa
                          -- bytes(16) = 16×0xAA
  6d 74 72 61 6e 73 66 65 72 5f 74 79 70 65  -- "transfer_type"
  65 66 69 6c 65 73      -- "files"
  6c 64 69 73 70 6c 61 79 5f 6e 61 6d 65  -- "display_name"
  68 74 65 73 74 2e 62 69 6e  -- "test.bin"
  6b 74 6f 74 61 6c 5f 66 69 6c 65 73  -- "total_files"
  01                      -- unsigned(1)
  6a 74 6f 74 61 6c 5f 73 69 7a 65  -- "total_size"
  10                      -- unsigned(16)
  68 6d 61 6e 69 66 65 73 74  -- "manifest"
  81                      -- array(1)
    a4                    -- map(4)
      67 66 69 6c 65 5f 69 64  -- "file_id"
      01                  -- unsigned(1)
      6d 72 65 6c 61 74 69 76 65 5f 70 61 74 68  -- "relative_path"
      68 74 65 73 74 2e 62 69 6e  -- "test.bin"
      64 73 69 7a 65      -- "size"
      10                  -- unsigned(16)
      66 73 68 61 32 35 36  -- "sha256"
      58 20               -- bytes(32)
        be 45 cb 26 05 bf 36 be bd e6 84 84 1a 28 f0 fd
        43 c6 98 50 a3 dc e5 fe db a6 99 28 ee 3a 89 91
```

**Frame header payload_len:** 193 (0xC1)

---

### 9.7 Vector 7: CHUNK_ACK

**Human-readable values:**
- Type: CHUNK_ACK (21)
- Stream ID: 1
- Message ID: 5 (acknowledging chunk with message_id=5)
- Offset: 0
- Length: 1048576 (1 MiB)

**CBOR payload (50 bytes):**
```
a5                        -- map(5)
  64 74 79 70 65          -- "type"
  15                      -- unsigned(21)
  69 73 74 72 65 61 6d 5f 69 64  -- "stream_id"
  01                      -- unsigned(1)
  6a 6d 65 73 73 61 67 65 5f 69 64  -- "message_id"
  05                      -- unsigned(5)
  66 6f 66 66 73 65 74    -- "offset"
  00                      -- unsigned(0)
  66 6c 65 6e 67 74 68    -- "length"
  1a 00 10 00 00          -- unsigned(1048576)
```

CBOR hex: `a56474797065156973747265616d5f6964016a6d6573736167655f696405666f666673657400666c656e6774681a00100000`

---

### 9.8 Vector 8: ERROR

**Human-readable values:**
- Type: ERROR (9)
- Code: "checksum_mismatch"
- Message: "Hash verification failed"
- Fatal: false

**CBOR payload (71 bytes):**
```
a4                        -- map(4)
  64 74 79 70 65          -- "type"
  09                      -- unsigned(9)
  64 63 6f 64 65          -- "code"
  71 63 68 65 63 6b 73 75 6d 5f 6d 69 73 6d 61 74 63 68
                          -- text(17) "checksum_mismatch"
  67 6d 65 73 73 61 67 65  -- "message"
  78 18 48 61 73 68 20 76 65 72 69 66 69 63 61 74 69 6f 6e
  20 66 61 69 6c 65 64    -- text(24) "Hash verification failed"
  65 66 61 74 61 6c       -- "fatal"
  f4                      -- false
```

CBOR hex: `a464747970650964636f646571636865636b73756d5f6d69736d61746368676d65737361676578184861736820766572696669636174696f6e206661696c656465666174616cf4`

---

### 9.9 Vector 9: TRANSFER_REQUEST with Directory Entry

**Human-readable values:**
- Type: TRANSFER_REQUEST (16)
- Transfer ID: 16 × 0xAA
- Transfer type: "directory"
- Display name: "project"
- Total files: 1 (only file entries count)
- Total size: 16 bytes
- Manifest: 1 dir entry (file_id=0, path="src/utils", type="dir") +
            1 file entry (file_id=1, path="src/main.rs", size=16)

**CBOR payload (252 bytes):**
```
a7                        -- map(7)
  64 74 79 70 65          -- "type"
  10                      -- unsigned(16)
  6b 74 72 61 6e 73 66 65 72 5f 69 64  -- "transfer_id"
  50 aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa aa
                          -- bytes(16) = 16×0xAA
  6d 74 72 61 6e 73 66 65 72 5f 74 79 70 65  -- "transfer_type"
  69 64 69 72 65 63 74 6f 72 79  -- "directory"
  6c 64 69 73 70 6c 61 79 5f 6e 61 6d 65  -- "display_name"
  67 70 72 6f 6a 65 63 74  -- "project"
  6b 74 6f 74 61 6c 5f 66 69 6c 65 73  -- "total_files"
  01                      -- unsigned(1)
  6a 74 6f 74 61 6c 5f 73 69 7a 65  -- "total_size"
  10                      -- unsigned(16)
  68 6d 61 6e 69 66 65 73 74  -- "manifest"
  82                      -- array(2)
    a3                    -- map(3) [dir entry]
      67 66 69 6c 65 5f 69 64  -- "file_id"
      00                  -- unsigned(0)
      6d 72 65 6c 61 74 69 76 65 5f 70 61 74 68  -- "relative_path"
      69 73 72 63 2f 75 74 69 6c 73  -- "src/utils"
      64 74 79 70 65      -- "type"
      63 64 69 72          -- "dir"
    a5                    -- map(5) [file entry]
      67 66 69 6c 65 5f 69 64  -- "file_id"
      01                  -- unsigned(1)
      6d 72 65 6c 61 74 69 76 65 5f 70 61 74 68  -- "relative_path"
      6b 73 72 63 2f 6d 61 69 6e 2e 72 73  -- "src/main.rs"
      64 73 69 7a 65      -- "size"
      10                  -- unsigned(16)
      66 73 68 61 32 35 36  -- "sha256"
      58 20               -- bytes(32)
        be 45 cb 26 05 bf 36 be bd e6 84 84 1a 28 f0 fd
        43 c6 98 50 a3 dc e5 fe db a6 99 28 ee 3a 89 91
      64 74 79 70 65      -- "type"
      64 66 69 6c 65      -- "file"
```

**Frame header payload_len:** 252 (0xFC)

CBOR hex: `a76474797065106b7472616e736665725f696450aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa6d7472616e736665725f74797065696469726563746f72796c646973706c61795f6e616d656770726f6a6563746b746f74616c5f66696c6573016a746f74616c5f73697a6510686d616e696665737482a36766696c655f6964006d72656c61746976655f70617468697372632f7574696c73647479706563646972a56766696c655f6964016d72656c61746976655f706174686b7372632f6d61696e2e72736473697a6510667368613235365820be45cb2605bf36bebde684841a28f0fd43c69850a3dce5fedba69928ee3a899164747970656466696c65`

---

## 10. Wire Trace Example

A complete annotated trace of a single-file transfer of 16 bytes between
Alice (sender/initiator, Linux) and Bob (receiver/responder, Android).

```
=== TCP Connection ===
Alice → Bob: TCP SYN to port 17394
Bob → Alice: TCP SYN-ACK
Alice → Bob: TCP ACK

=== TLS 1.3 Handshake ===
Alice → Bob: ClientHello (ALPN: "nxfr/0", key_share: X25519)
Bob → Alice: ServerHello, EncryptedExtensions, Certificate, CertificateVerify, Finished
Alice → Bob: Certificate, CertificateVerify, Finished
[TLS established, mutual authentication complete]

=== NXFR Session Setup ===

Frame 1: Alice → Bob  HELLO
  Header: 4e584652 0101 0000 00000000 00000000 0000000000000001 00000088
  Payload: [136 bytes CBOR - HELLO message]
  Fields: type=1, version=[0,1], device_id=Alice's, name="Alice-Laptop",
          platform="linux", capabilities=[], is_paired=false

Frame 2: Bob → Alice  HELLO_ACK
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000001 00000095
  Payload: [149 bytes CBOR - HELLO_ACK message]
  Fields: type=2, version=[0,1], device_id=Bob's, name="Bob-Phone",
          platform="android", capabilities=[], is_paired=false,
          session_id=0x1234

=== Transfer ===

Frame 3: Alice → Bob  TRANSFER_REQUEST
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000002 000000c1
  Payload: [193 bytes CBOR - TRANSFER_REQUEST]
  Fields: type=16, transfer_id=16×0xAA, transfer_type="files",
          display_name="test.bin", total_files=1, total_size=16,
          manifest=[{file_id:1, path:"test.bin", size:16, sha256:...}]

  [Bob's device shows notification: "Alice-Laptop wants to send test.bin (16 B)"]
  [Bob taps Accept]

Frame 4: Bob → Alice  TRANSFER_ACCEPT
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000002 00000015
  Payload: [21 bytes CBOR]
  Fields: type=17, transfer_id=16×0xAA

Frame 5: Alice → Bob  FILE_METADATA
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000003 000000XX
  Payload: [CBOR]
  Fields: type=19, transfer_id=16×0xAA, file_id=1, stream_id=1,
          relative_path="test.bin", size=16, sha256=...,
          mime_type="application/octet-stream"

Frame 6: Bob → Alice  FILE_METADATA_ACK
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000003 000000XX
  Payload: [CBOR]
  Fields: type=20, transfer_id=16×0xAA, file_id=1, stream_id=1,
          accepted=true

Frame 7: Alice → Bob  CHUNK (LAST_CHUNK, all data)
  Header: 4e584652 0102 0001 00001234 00000001 0000000000000004 00000038
  Payload: [56 bytes = 8 (offset) + 32 (hash) + 16 (data)]
  Fields: offset=0, chunk_hash=be45cb26..., data=000102...0f

Frame 8: Bob → Alice  CHUNK_ACK
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000004 00000032
  Payload: [50 bytes CBOR]
  Fields: type=21, stream_id=1, message_id=4, offset=0, length=16

Frame 9: Alice → Bob  TRANSFER_COMPLETE
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000005 00000015
  Payload: [21 bytes CBOR]
  Fields: type=25, transfer_id=16×0xAA

  [Bob verifies whole-file SHA-256]
  [Bob renames test.bin.part → test.bin]

Frame 10: Bob → Alice  TRANSFER_ACK
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000005 000000XX
  Payload: [CBOR]
  Fields: type=26, transfer_id=16×0xAA, status="success", failed_files=[]

=== Session Teardown ===

Frame 11: Alice → Bob  SESSION_CLOSE
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000006 000000XX
  Payload: [CBOR]
  Fields: type=6, reason="normal"

Frame 12: Bob → Alice  SESSION_CLOSE
  Header: 4e584652 0101 0000 00001234 00000000 0000000000000006 000000XX
  Fields: type=6, reason="normal"

  [TLS close_notify]
  [TCP FIN exchange]
```

---

## 11. Limits Summary

| Limit | Frame Kind | Value |
|-------|-----------|-------|
| CONTROL payload | `0x01` | ≤ 65,536 bytes (64 KiB) |
| CHUNK payload | `0x02` | ≤ 4,194,304 bytes (4 MiB) |
| CHUNK minimum payload | `0x02` | ≥ 41 bytes (40 header + 1 data) |
| KEEPALIVE payload | `0x03` | 0 or 8 bytes only |
| Frame header | All | Exactly 28 bytes |
| CBOR max nesting | CONTROL | 6 levels |
| Manifest entries | TRANSFER_REQUEST | ≤ 500 (encoded TRANSFER_REQUEST MUST fit 64 KiB) |
| In-flight chunks | CHUNK | ≤ 8 unacknowledged |

---

## 12. References

| Reference | Title |
|-----------|-------|
| [RFC 8949](https://datatracker.ietf.org/doc/html/rfc8949) | Concise Binary Object Representation (CBOR) |
| [FIPS 180-4](https://csrc.nist.gov/pubs/fips/180-4/upd1/final) | Secure Hash Standard (SHA-256) |
| [PROTOCOL.md](PROTOCOL.md) | NXFR Protocol Specification v1.0 |

---

*End of NXFR Wire Format Specification*
