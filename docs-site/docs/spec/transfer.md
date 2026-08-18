!!! info "Protocol v1.0"
    This is the v1.0 specification. For the normative text, see the [`docs/`](https://github.com/nxfr/nxfr/tree/main/docs) directory.

# Transfer & Resume

## Transfer Flow

A transfer operates over an established session. It follows this sequence:
1. Sender sends `TRANSFER_REQUEST` containing a manifest (up to 500 entries) of files/directories.
2. Receiver prompts user for consent (or auto-accepts based on policy) and replies with `TRANSFER_ACCEPT`.
3. For each file in the manifest:
   - Sender sends `FILE_METADATA` to signal the start of the file stream.
   - Receiver acknowledges with `FILE_METADATA_ACK`.
   - Sender streams the file data using `CHUNK` frames.
   - Receiver sends `CHUNK_ACK` frames acknowledging successful data reception.
4. Once all files are sent, sender sends `TRANSFER_COMPLETE`.
5. Receiver confirms all data is written with `TRANSFER_ACK`.

### Receiver Completion

On the receiver side, the state machine tracks completion explicitly:

1. After the last chunk is received and verified, the receiver transitions to **Completing**.
2. The receiver sends `TRANSFER_ACK` with the final verification status.
3. Only after the ack is sent does the receiver transition to **Complete**.

This prevents the receiver from reporting success before the ack is actually on the wire.

## Chunk Format

File data is transmitted in CHUNK frames (Kind `0x02`). The payload is NOT CBOR-encoded. It consists of a 40-byte header followed by raw data:
- `offset` (8 bytes, `u64`): Byte offset of the chunk.
- `chunk_hash` (32 bytes): SHA-256 digest of the `data` portion only.
- `data` (variable): Raw file bytes (up to ~4 MiB).

The final chunk of a stream has the `LAST_CHUNK` flag (`0x0001`) set in the frame header.

## Resume Protocol

If a transfer is interrupted, it can be seamlessly resumed:
1. The sender connects and initiates a new session.
2. The sender sends a `RESUME_QUERY` with the original `transfer_id`.
3. The receiver replies with a `RESUME_STATUS` containing the bytes/ranges already received for each file.
4. The sender resumes streaming chunks, skipping the acknowledged ranges.

## Directory Transfer

NXFR supports directory tree transfers by specifying `"transfer_type": "directory"` in the request. The manifest contains entries with `type="file"` and `type="dir"`. A `"dir"` entry instructs the receiver to create a directory (preserving structure and empty directories).

## Path Validation Rules

Path sanitization is critical to prevent path traversal attacks:
- **Relative Paths Only**: Paths MUST NOT start with `/`, `\`, or drive letters.
- **No Traversal**: Paths containing `../` or `..\` MUST be rejected.
- **No Null Bytes**: Paths containing `\0` MUST be rejected.
- **Reserved Names**: Windows reserved names (e.g., `CON`, `PRN`, `AUX`, `COM1`) MUST be rejected on all platforms to ensure interoperability.
