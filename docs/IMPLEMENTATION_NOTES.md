# NXFR Implementation Notes

Technical decisions, rationale, and protocol interpretations across NXFR crates and client implementations.

---

## 1. Core Protocol and Framing

### 1.1 Path Sanitizer: `///` Treatment
- **Rule**: §18.2 specifies rejecting absolute paths (starting with `/`) and separately skipping empty components.
- **Decision**: `///` is caught first by the absolute path check (starts with `/`), raising `AbsolutePath`. A path like `"."` or `"././."` that normalizes to zero components after stripping raises `EmptyPath`.

### 1.2 SAS Derivation: Context Bytes vs Hash
- **Rule**: §9.2.3 specifies `context = sort(device_id_a, device_id_b)` (64 bytes). The exporter bytes come from TLS-Exporter (4 bytes).
- **Decision**: `derive_sas` returns both the SAS string and the 64-byte context to allow the transport layer to call TLS-Exporter with the context. The function sorts, concatenates, and applies `u32 mod 1000000`.

### 1.3 Chunk Payload Minimum: 41 Bytes
- **Rule**: §7.2.2 and WIRE_FORMAT §5 define the chunk header as 8-byte offset + 32-byte SHA-256 = 40 bytes. Minimum chunk payload = 40 + 1 data byte = 41.
- **Decision**: `ChunkPayload::parse` rejects payloads < 41 bytes. `FrameHeader::validate_payload_len` for CHUNK kind enforces `>= 41`.

### 1.4 ErrorCode Parsing Method Naming
- **Decision**: Named `from_wire_str` since the return type is `Option<Self>` and parses wire-format strings rather than implementing `FromStr`.

### 1.5 FrameFlags Reserved Bits
- **Rule**: §7.1 specifies implementations MUST ignore unknown flag bits.
- **Decision**: `FrameHeader::parse` preserves all 16 flag bits verbatim. Round-trip serialization preserves reserved bits.

### 1.6 Manifest Type Field
- **Rule**: §9.2.8 errata specifies `type="dir"` entries MAY use `file_id=0`; `type="file"` entries start at 1.
- **Decision**: `type="file"` is omitted by default unless the manifest contains mixed types.

### 1.7 CBOR Max Nesting Depth
- **Rule**: Set to 6 to support RESUME_STATUS schemas (map → files[] → map → received_ranges[][] → uint).
- **Decision**: `check_nesting_depth` enforces maximum depth of 6 and returns `CodecError::TagNotAllowed` immediately upon encountering any CBOR tag.

---

## 2. Transport and Cryptography

### 2.1 TLS Exporter Label and Context
- **Rule**: §9.2.3 requires `sas_bytes = TLS-Exporter("NXFR-SAS-v0", context, 4)` where `context = sort(device_id_a, device_id_b)` (64 bytes).
- **Decision**: `connect_to_peer()` extracts exporter bytes using `export_keying_material(&mut [0u8; 4], b"NXFR-SAS-v0", Some(&sas_context))` before consuming the TLS stream.

### 2.2 rustls Certificate & Signature Verification
- **Decision**: `NoServerVerifier` and `NoClientVerifier` in `nxfr-transport::tls` bypass X.509 PKI certificate chain validation because NXFR operates on a Trust On First Use (TOFU) model where public keys are pinned at the application layer (`nxfr-storage`).
- **Signature Verification**: Crucially, custom verifiers MUST NOT bypass handshake signature verification. Both verifiers invoke `rustls::crypto::verify_tls13_signature` and `verify_tls12_signature` using Ring's algorithm provider. This cryptographically proves that the connecting peer possesses the private key corresponding to the presented certificate.

### 2.3 TLS 1.3 Protocol Enforcement
- **Decision**: Both client and server configs pass `&[&rustls::version::TLS13]` to `with_protocol_versions()`. ALPN is set to `"nxfr/0"`.

### 2.4 tokio-util Framed Codec
- **Decision**: `NxfrCodec` implements `Decoder` and `Encoder` for `(FrameHeader, Vec<u8>)`. Header parsing validates magic bytes, frame kinds, and payload bounds.

### 2.5 Key Material Zeroization
- **Decision**: Sensitive key material and exporter bytes (`[u8; 4]`) are zeroized via `zeroize::Zeroize` immediately after SAS derivation.

### 2.6 C-ABI and JNI Memory Safety Invariants
- **Decision**: All C-ABI functions in `nxfr-ffi` accepting byte buffers MUST receive explicit buffer length parameters (`exporter_len: usize`). Pointers must never be dereferenced or converted to fixed-size slices without length validation. In `jni_bindings.rs`, array lengths (`exp_bytes.len()`) are checked prior to passing raw pointers across the FFI boundary, preventing out-of-bounds reads and undefined behavior on malformed inputs.

### 2.7 Async Concurrency & Mutex Acquisition in Session Reader Tasks
- **Decision**: To prevent deadlocks between reader tasks and `nxfr_close`, the reader task takes ownership of the connection (`guard.take()`) and drops the Tokio `Mutex` before awaiting `recv_frame()`. Upon completion, the connection is restored only if the session remains active (`Arc::strong_count > 1`). `nxfr_close` uses bounded lock acquisition timeouts (3s) to guarantee prompt session teardown without hanging the JNI calling thread.

### 2.8 Listener Concurrency, Timeouts, and Error Backoff
- **Decision**: To mitigate Slowloris attacks, TLS handshake completion is wrapped in a 10-second timeout. In-flight handshakes and connections are bounded using Tokio `Semaphore` primitives (100 permits in FFI, 200 in daemon). On TCP `accept()` errors (e.g. `EMFILE`/`ENFILE`), listeners sleep for 50ms before retrying, preventing 100% CPU busy-spin loops under file descriptor starvation.

---

## 3. Daemon, Storage, and Discovery

### 3.1 Persistent Identity
- **Decision**: Stored as PKCS#8 DER key at `~/.local/share/nxfr/identity.der` and self-signed certificate at `~/.local/share/nxfr/identity.crt`. `device_id` is derived from the stored cert SPKI.

### 3.2 Identity Change Detection
- **Decision**: `PairedDeviceDb::verify_identity(device_id, spki)` checks against pinned SPKI hashes. Mismatched certificates trigger an immediate `ERROR identity_changed` and connection closure.

### 3.3 Path Sanitization Enforcement
- **Decision**: Incoming file paths are validated against canonical inbox directory boundaries before creating files. Traversal attempts (`../`) are rejected with `ERROR path_rejected`.

### 3.4 Resumable Transfer Journaling and Atomic Rename
- **Decision**: Receiver streams chunks into `<receive_dir>/<path>.part`. Upon full verification, files are atomically renamed to their final path. Each chunk write updates the resume journal with `fsync` durability.

### 3.5 In-Flight Window Control
- **Decision**: Sender tracks unacknowledged chunks and bounds in-flight frames to 8, resuming on receipt of `CHUNK_ACK`.

### 3.6 Ephemeral Advertised IDs
- **Decision**: mDNS TXT records and UDP discovery packets advertise `id = first_16_hex(SHA-256(device_id || "YYYY-MM-DD"))` to prevent passive tracking.

### 3.7 Discovery Cache Lifecycle
- **Decision**: `DiscoveryManager` maintains a persistent mDNS event receiver and drains events on demand, avoiding channel re-registration churn.

### 3.8 Single-Instance Daemon Guard
- **Decision**: Before unlinking the IPC socket on startup, the daemon checks for an existing active instance via status ping.

---

## 4. Android Client and Web Integration

### 4.1 Companion Object Initialization Order
- **Issue**: Eager evaluation of companion object properties before child objects are constructed can result in uninitialized references.
- **Resolution**: Evaluated navigation item lists lazily via property getters.

### 4.2 Foreground Service Lifecycle Guards
- **Issue**: Android 12+ throws `ForegroundServiceStartNotAllowedException` when starting foreground services from background states.
- **Resolution**: Wrapped foreground transitions in exception guards with appropriate state fallbacks.

### 4.3 MediaStore Publish Atomicity
- **Issue**: Failed stream copies to `MediaStore.Downloads` leave orphaned `IS_PENDING=1` entries.
- **Resolution**: `FilePublisher.kt` tracks inserted URIs and issues explicit deletions on failure.

### 4.4 Web Share Token Isolation and PIN Protection
- **Decision**: Browser download/upload portals on port `17396` isolate tokens in URL fragments (`/#t=<token>`) and provide optional PIN protection with exponential rate limiting.

### 4.5 FFI Mutex Lock Poisoning Recovery
- **Decision**: Rust JNI bridge uses `.lock().unwrap_or_else(|e| e.into_inner())` across session and listener maps to prevent permanent lock poisoning across FFI calls.

### 4.6 Web Share Inactivity Timer and Stream Deferral (`nxfr-web`)
- **Decision**: The 10-minute web share lifetime is implemented as a silence/idle timer rather than a flat countdown. `last_activity` is bumped on every accepted HTTP request and on every chunk transferred in `/dl/:id` and `/upload`.
- **Active Streams**: An `ActiveTransferGuard` increments an atomic stream counter (`active_transfers`). As long as `active_transfers > 0`, the server defers expiry and drains live connections before shutting down listeners.
- **FFI Status Query**: Exposed `nxfr_web_status()` returning `{"running": bool, "active_transfers": usize, "port": u16}` allowing `WebShareScreen.kt` to dynamically update UI telemetry.

### 4.7 Zero-Copy Streaming Hasher for Large File Transfers (`nxfr-ffi`)
- **Issue**: Calling `std::fs::read` on large files (e.g. 1.2 GB) in `scan_send_path` attempts to allocate single contiguous byte buffers in RAM, triggering native OOM crashes / `SIGABRT` on memory-constrained Android devices.
- **Resolution**: Replaced whole-file memory buffering with `hash_file_stream`, which computes SHA-256 and file size in fixed 64 KB chunks, maintaining constant low memory overhead regardless of payload size.

### 4.8 Staging & Temporary Cache Lifecycle (`CacheCleaner.kt`)
- **Decision**: Temporary staging folders (`staging_*`, `web-share-staging`, `send_*`, `nxfr_paste`, `apps/`, `debug_bundle_*`) created in `context.cacheDir` are purged synchronously on `NxfrApp.onCreate` and asynchronously upon transfer completion or cancellation to prevent storage buildup.

### 4.9 Empty Transfer Prevention in Direct & Desert Mode
- **Decision**: `SendScreen.kt` and `StagingRepository.prepareStagingDirectory` strictly guard against empty item lists (`stagedItems.isEmpty()`), prompting the user to select files first rather than generating empty directories that fail native manifest validation.

### 4.10 Adaptive UDP Beacon Power Ladder (`UdpBeacon.kt`)
- **Issue**: A fixed 1-second beacon interval drains battery and keeps Wi-Fi radios awake unnecessarily when the application is backgrounded or idle.
- **Decision**: Implemented a 3-tier state-aware frequency mode:
  - `ACTIVE` (1,000ms): Foreground UI active or Device Picker open — fast discovery.
  - `BACKGROUND` (5,000ms): App in background with an active transfer in flight.
  - `LOW_POWER` (30,000ms): App in deep background with no active transfer — relies primarily on mDNS.
- **Lifecycle Coupling**: `evaluateLifecycleContract()` automatically recalculates beacon mode whenever a transfer completes, errors, or is cancelled, ensuring the `BACKGROUND → LOW_POWER` transition fires immediately without waiting for activity lifecycle events.

### 4.11 Android 14+ onTimeout & Android 12+ Notification Actions
- **Decision**: `NxfrService` implements `onTimeout(startId)` for Android 14+ (API 34) compliance, gracefully flushing active state and unbinding sockets when OS runtime limits are reached.
- **Notification PendingIntents**: Transfer notification actions use `PendingIntent.getForegroundService()` to prevent `IllegalStateException` crashes on Android 12+ (API 31+) when invoked while the app is backgrounded.

### 4.12 Storage Access Framework (SAF) SecurityException Handling
- **Decision**: All `contentResolver.openInputStream()` calls in `StagingRepository` and `copyDocumentTree` are wrapped in `SecurityException` guards. If permission is revoked for a specific URI, the file is logged, a warning toast is shown, and the batch transfer continues with the remaining valid files rather than failing the entire operation.

### 4.13 Web Portal Security: DOM Construction and Credential Hygiene
- **Decision**: In `nxfr-web`, manifest item rendering is implemented using safe DOM construction (`createElement` + `textContent`) rather than template string `innerHTML` interpolation to completely eliminate DOM-based XSS from untrusted filenames.
- **Log Hygiene**: Authentication tokens are strictly redacted from logs (`token=****`) to prevent credential leakage into Android logcat or terminal consoles.
