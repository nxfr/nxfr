!!! info "Protocol v1.0"
    This is the v1.0 specification. For the normative text, see the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory.

# Framing & Encoding

All NXFR communication after TLS establishment uses a binary framing format. Each frame consists of a fixed 28-byte header followed by a variable-length payload.

## Frame Header

```text
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

| Offset | Size | Field | Description |
|--------|------|-------|-------------|
| 0 | 4 | `magic` | Always `0x4E584652` (`"NXFR"`). |
| 4 | 1 | `version` | Frame format version. `0x01` for v0.1. |
| 5 | 1 | `kind` | `0x01` = CONTROL, `0x02` = CHUNK, `0x03` = KEEPALIVE. |
| 6 | 2 | `flags` | Bit flags, semantics depend on `kind`. Big-endian. |
| 8 | 4 | `session_id` | Session identifier. `0` before HELLO_ACK. Big-endian. |
| 12 | 4 | `stream_id` | `0` for session-level. `>0` for file stream. Big-endian. |
| 16 | 8 | `message_id`| Monotonically increasing per direction. Big-endian. |
| 24 | 4 | `payload_len` | Payload length in bytes. Big-endian. |

## Frame Kinds & Size Limits

| Kind | Code | Payload Format | Max Size |
|------|------|----------------|----------|
| CONTROL | `0x01` | CBOR-encoded map containing a `"type"` key. | 64 KiB (65,536 bytes) |
| CHUNK | `0x02` | 40-byte header (offset, hash) + raw file data. | 4 MiB (4,194,304 bytes) |
| KEEPALIVE | `0x03` | Empty (PING/PONG) or 8-byte timestamp (`u64` BE). | 8 bytes |

For CHUNK frames, the 40-byte header format within the payload is:
- **offset** (8 bytes, `u64` BE): Byte offset of this chunk.
- **chunk_hash** (32 bytes): SHA-256 of the `data` portion only.
- **data** (variable): Raw file data.

## CBOR Encoding Rules

All control message payloads (frames with `kind = 0x01`) MUST follow strict CBOR rules ([RFC 8949]):

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
