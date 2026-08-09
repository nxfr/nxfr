# NXFR Implementation Notes (Phase 1)

Interpretations and decisions made during nxfr-core implementation.

## 1. Path sanitizer: `///` treatment

**Spec**: §18.2 says "reject absolute paths" (starting with `/`) and separately "skip empty components".

**Interpretation**: `///` is caught first by the absolute-path check (starts with `/`), so it raises `AbsolutePath`, not `EmptyPath`. A path like `"."` or `"././."` that normalizes to zero components after stripping raises `EmptyPath`.

## 2. SAS derivation: context bytes vs hash

**Spec**: §9.2.3 says "context = sort(device_id_a, device_id_b)", yielding 64 bytes. The exporter bytes come from TLS-Exporter and are 4 bytes.

**Interpretation**: `derive_sas` returns both the SAS string and the 64-byte context to allow the transport layer to call TLS-Exporter with the context. The function itself does NOT compute any hash — it just sorts, concatenates, and applies `u32 mod 1000000`.

## 3. Chunk payload minimum: 41 bytes

**Spec**: §7.2.2 / WIRE_FORMAT §5 define the chunk header as 8-byte offset + 32-byte SHA-256 = 40 bytes. Minimum chunk payload = 40 + 1 data byte = 41.

**Interpretation**: `ChunkPayload::parse` rejects payloads < 41 bytes. The `FrameHeader::validate_payload_len` for CHUNK kind also enforces `>= 41`.

## 4. ErrorCode::from_wire_str naming

**Decision**: Clippy flagged `from_str` as confusable with `std::str::FromStr::from_str`. Renamed to `from_wire_str` since our return type is `Option<Self>`, not `Result<Self, Error>`, and the method parses wire-format (snake_case) strings, not display names.

## 5. TransferAction / TransferEvent name collision

**Issue**: Both `TransferAction` and `TransferEvent` have a variant named `SendTransferRequest`. Glob-importing both in the same match block causes ambiguity.

**Resolution**: Fully qualify all enum variants in `transfer_handle_event` match arms. No glob imports.

## 6. FrameFlags: reserved bits

**Spec**: §7.1 says "implementations MUST ignore unknown flag bits".

**Interpretation**: `FrameHeader::parse` preserves all 16 flag bits verbatim. `FrameFlags` only interprets bit 0 contextually (via `is_last_chunk` for CHUNK kind, `is_pong` for KEEPALIVE kind). Round-trip: parse → serialize preserves reserved bits.

## 7. Manifest type field

**Spec**: §9.2.8 errata says `type="dir"` entries MAY use `file_id=0`; `type="file"` entries start at 1.

**Interpretation**: During encoding, `type="file"` is omitted (it's the default) unless the manifest contains mixed types (files + dirs), in which case it's explicit for clarity.

## 8. CBOR tag rejection

**Spec**: §8 says "CBOR tags are not used in v0.1".

**Interpretation**: `check_nesting_depth` returns `CodecError::TagNotAllowed` immediately upon encountering any tag, before recursing into the tag's content.

---

# Phase 2: Transport & Crypto

## 9. Loopback CONTROL verification: semantic, not byte-exact

**Context**: The §9 golden vectors encode CBOR maps with keys in a specific order (e.g., `type` first). Our encoder uses `BTreeMap` which sorts lexicographically, producing different byte sequences but semantically identical CBOR.

**Decision**: The loopback test compares **decoded field values** for CONTROL frames (type, device_id, session_id, transfer_id, etc.), not raw hex bytes. CHUNK frames are compared byte-for-byte since their format is fixed binary (offset + hash + data). This aligns with PROTOCOL.md §8.1: "map keys SHOULD be sorted lexicographically."

## 10. rustls NoVerifier: preserves peer certificate chain

**Context**: rustls 0.23 requires implementing `ServerCertVerifier` / `ClientCertVerifier` to accept self-signed certificates. NXFR handles identity pinning at the application layer (post-handshake device_id derivation from SPKI).

**Decision**: Implemented `NoServerVerifier` and `NoClientVerifier` in `nxfr-transport::tls` that accept any cert via `assertion()`. Critically, these do NOT strip the peer certificate chain — `Connection::peer_certificates()` remains accessible post-handshake for SPKI extraction. Verified via unit test (`test_spki_accessible_for_peer_cert`) and the loopback test itself (both Alice and Bob extract and verify peer device_id after TLS handshake).

**Caveat**: This is NOT production-appropriate. A future phase must implement trust-on-first-use (TOFU) or paired-device pinning.

## 11. TLS 1.3 only enforcement

**Decision**: Both `build_client_config` and `build_server_config` explicitly pass `&[&rustls::version::TLS13]` to `with_protocol_versions()`. rustls 0.23 with the `ring` crypto provider will reject TLS 1.2 ClientHello from downgrade-attempting peers. ALPN is set to `"nxfr/0"` on both sides.

## 12. tokio-util Framed codec

**Decision**: `NxfrCodec` implements `Decoder` and `Encoder` for `(FrameHeader, Vec<u8>)`. The decoder peeks at 28 header bytes to determine `payload_len`, then waits for the full frame before yielding. The encoder serializes header bytes (via `FrameHeader::serialize`) followed by the payload. Magic/version validation happens during decode (inside `FrameHeader::parse`).

## 13. Fuzz harnesses: cargo-fuzz + in-tree property tests

**Decision**: Three `cargo-fuzz` targets exist in `fuzz/` (require nightly + C++ compiler for ASAN). Additionally, three in-tree property tests in `nxfr-core::fuzz_property_tests` run 100K random inputs each using a deterministic xorshift64 PRNG. The property tests verify the same invariants (no panics, round-trip fidelity) without external toolchain requirements.

**Results**: cargo-fuzz ran 58.6M (frame), 1.84M (CBOR), 4.48M (path) iterations with zero crashes.

---

# Phase 3: Daemon, Storage, Discovery

## 14. Identity persistence

**Context**: The daemon needs a stable `device_id` across restarts. `NxfrIdentity::generate_identity()` creates a fresh keypair each time.

**Decision**: Store PKCS#8 DER key at `~/.local/share/nxfr/identity.der` and self-signed cert at `~/.local/share/nxfr/identity.crt`. On first run, generate and write both files. On subsequent runs, load from disk and re-derive `device_id` from the stored cert's SPKI. The `PersistentIdentity` struct wraps raw bytes and produces `PrivateKeyDer<'static>` / `CertificateDer<'static>` on demand.

## 15. Identity change detection (§10.4)

**Context**: When a paired peer connects, we must verify their TLS cert SPKI matches the pinned value in the paired DB. If it doesn't, this is a potential MITM or key rotation.

**Decision**: `PairedDeviceDb::verify_identity(device_id, spki)` returns `IdentityCheck::Matched`, `::Changed`, or `::Unknown`. If `Changed`, the handler sends `ERROR identity_changed` (fatal=true) and closes the connection immediately. We do NOT silently accept changed identities.

## 16. Consent logic (Phase 3 interim)

**Context**: Full consent UI requires IPC to a GUI/CLI client. Phase 3 doesn't have that.

**Decision**: Auto-accept transfers only from paired devices with `auto_accept='always'`. All other transfers (unpaired or `auto_accept='prompt'`) are rejected with `TransferReject { reason: "consent_required" }`. This is safe: no file is written without explicit trust.

## 17. Path sanitization enforcement

**Context**: Every incoming `relative_path` in `FILE_METADATA` must be sanitized (PROTOCOL §18, SECURITY §6).

**Decision**: The handler calls `nxfr_core::path::sanitize_path()` before any directory creation or file write. If sanitization fails, sends `ERROR path_rejected` (non-fatal) + `FILE_METADATA_ACK accepted=false`, and the file is skipped entirely. No exception.

## 18. .part file and atomic rename

**Decision**: Receiver writes to `<receive_dir>/<path>.part`. On completion (LAST_CHUNK + hash verified), atomically renames to the final path. If the final path exists, appends ` (1)`, ` (2)`, etc. (browser convention). `.part` files are cleaned up on transfer cancellation.

## 19. fsync on resume journal

**Decision**: After writing each chunk to the `.part` file and updating the resume journal JSON, `file.sync_all()` (fsync) is called on both the data file and the journal. The journal write uses write-to-tmp + fsync + atomic rename to survive power loss without corruption.

## 20. In-flight window enforcement

**Decision**: The sender tracks unacknowledged CHUNK frames and pauses when 8 are in-flight. It resumes when a CHUNK_ACK arrives. The receiver sends CHUNK_ACK after each chunk is written and fsync'd. This prevents memory exhaustion on slow links.

## 21. mDNS TXT `id` rotation

**Context**: PROTOCOL §5.3 requires `id = first_16_hex(SHA-256(device_id || "YYYY-MM-DD"))`, rotated daily.

**Decision**: `compute_advertised_id(device_id, date_str)` is a pure function. The `DiscoveryManager` computes it at registration time. A background task can re-register at midnight UTC; not implemented in Phase 3 (the daemon restarts to rotate).

## 22. mdns-sd threading model

**Decision**: `mdns-sd::ServiceDaemon` spawns its own background thread. It communicates via `flume` channels which support `recv_async().await` for tokio compatibility. The daemon's mDNS thread is separate from tokio's thread pool. The `DiscoveryManager` wraps `ServiceDaemon` and provides register/unregister/browse methods.

## 23. IPC protocol design

**Decision**: Unix domain socket at `~/.local/state/nxfr/nxfr.sock`. Line-delimited JSON, one request → one response. Commands: `status`, `send`, `set_receiving`, `pair`. The `send` command requires `target_addr` (IP:port) in Phase 3 since browse cache isn't implemented yet. The `pair` command is a stub that logs.

## 24. Outbound send requires explicit address

**Context**: When the IPC `send` command arrives, the daemon must connect to the target. But the daemon may not have the peer's address cached from a recent browse.

**Decision**: Phase 3 `send` command requires `target_addr` (IP:port) alongside `target_device_id`. Full browse→connect will be wired in Phase 4 with the CLI.

---

# Phase 4: CLI, Streaming IPC, Pairing

## 25. TLS exporter label and context correction

**Spec**: §9.2.3 is explicit: `sas_bytes = TLS-Exporter("NXFR-SAS-v0", context, 4)` where `context = sort(device_id_a, device_id_b)` (64 bytes).

**Previous error**: An earlier plan proposed label `b"NXFR-SAS-v1"` with `context: None`. Both were wrong.

**Decision**: `connect_to_peer()` extracts exporter bytes using `export_keying_material(&mut [0u8; 4], b"NXFR-SAS-v0", Some(&sas_context))` before consuming the TLS stream. The 64-byte context is computed by `derive_sas` (which sorts and concatenates device IDs). The exporter bytes are then passed to `derive_sas` to produce the 6-digit SAS code. This is an **interop requirement** — any spec-compliant implementation must use the same label and context.

## 26. Outbound connect for `pair` command

**Context**: The original plan returned "device not connected" if the target wasn't in `active_connections`. This makes `nxfr pair` unusable from cold start.

**Decision**: Extracted `connect_to_peer()` from `handle_outbound_send` as a shared helper. Both `pair` and `send` use it. The `pair` IPC command: (1) checks active_connections, (2) if absent, connects outbound using address from mDNS browse cache or `--addr`, (3) completes HELLO, (4) extracts TLS exporter, (5) derives SAS. Only errors if the target is neither connected nor addressable.

## 27. Streaming IPC events

**Decision**: The IPC protocol is upgraded from single request→response to streaming for `send` and `pair` commands. The `IpcEvent` enum uses `#[serde(tag = "type")]` for discriminated JSON. Simple commands (`status`, `devices`, `set_receiving`) return one `Response` event. Streaming commands keep the connection open and push events:
- `send`: `Response` → `Progress*` → `TransferComplete` | `Error`
- `pair`: `Response` → `SasPrompt` → (client sends `pair_confirm`) → `PairSuccess` | `PairFailed`

## 28. Key material zeroization

**Decision**: Exporter bytes (`[u8; 4]`) are zeroized via `zeroize::Zeroize` immediately after SAS derivation, per SECURITY §10. In `connect_to_peer()`, the caller receives the exporter bytes and is responsible for zeroizing after use. `handle_outbound_send` zeroizes immediately (doesn't need SAS for send). The `pair` flow zeroizes after `derive_sas` returns.

## 29. Responder auto-reject without IPC watcher

**Decision**: When a `PairRequest` arrives on an inbound connection and no IPC client is attached (i.e., no interactive user), the daemon auto-rejects with `PairReject { reason: "no_interactive_client" }`. This is acceptable for Phase 4 — the GUI becomes the watcher in Phase 5.

## 30. Daemon as library crate

**Decision**: `nxfr-daemon` is now both a library (`src/lib.rs`) and binary (`src/main.rs`). The lib exports `DaemonState`, `ActiveConnection`, `ConnectionCommand`, `PairingResult`, and all modules. This allows E2E integration tests to import daemon types directly without subprocess orchestration.

## 31. E2E test strategy

**Decision**: Two integration tests in `crates/nxfr-daemon/tests/`:
- `e2e_transfer.rs`: Two daemons, pre-paired (auto_accept=always), 10 MB file transfer, SHA-256 verified, atomic rename confirmed.
- `e2e_pairing.rs`: Two daemons, fresh identities (unpaired), outbound connect, TLS exporter extraction on BOTH sides, **SAS equality asserted**, PAIR_REQUEST/PAIR_ACCEPT exchanged, both SQLite DBs verified with `trust_level='paired'`.

---

# Phase 5: Consent, Resume, Directories, Packaging

## 32. CBOR max nesting: 4 → 6

**Spec contradiction**: §8.1 capped nesting at 4, but §9.2.20 RESUME_STATUS schema (map → files[] → map → received_ranges[][] → uint) requires depth 6. The implementation correctly raised `MAX_CBOR_NESTING` to 6; the spec documents were updated to match.

## 33. consent_timeout reason extension

**Decision**: When the 120s consent timer expires, TRANSFER_REJECT carries `reason: "consent_timeout"` (not `"user_declined"`). Senders can distinguish timeout from active rejection.

## 34. Reserved names rejected on all platforms

**Ruling**: Windows reserved names (`CON`, `NUL`, `AUX`, etc.) rejected on Linux too, per PROTOCOL §18.2 cross-platform safety.

## 35. First-confirm-wins on consent offers

**Decision**: `PendingOffer.respond_to` is `Option<oneshot::Sender<bool>>`. First `transfer_confirm` `.take()`s the sender; subsequent confirms receive "already resolved".

## 36. Offer replay to late-joining watchers

**Decision**: New watchers receive all unresolved pending offers before the "watching" ack.

## 37. Resume Fix A: outbound journal on TRANSFER_ACCEPT

**Decision**: `handle_outbound_send` persists an outbound resume journal immediately upon receiving `TRANSFER_ACCEPT`.

## 38. Resume Fix B: peer device_id verification on RESUME_QUERY

**Decision**: RESUME_QUERY handler verifies connecting peer's `device_id` matches journal's `peer_device_id`. Mismatch → `resumable=false`.

## 39. Browse cache: persistent Receiver, degraded state

**Bug**: `browse_snapshot()` called `daemon.browse()` every 15s, creating a new Receiver each time. Dropped Receiver triggered closed-channel errors. Events between polls went to old receivers — cache always empty.

**Fix**: Single persistent `Receiver` stored in `DiscoveryManager`. `browse_snapshot()` drains via `try_recv()`. Channel disconnect sets `degraded` flag, logged once.

## 40. Single-instance guard

**Bug**: Second daemon unconditionally removed first's IPC socket. **Fix**: Status ping before socket removal; abort if another instance responds.

## 41. Failed toggle must not mutate observable state

**Bug**: `cmd_set_receiving` flipped config before attempting mDNS operations. On failure, config was already mutated.

**Fix**: Perform fallible operations first; only mutate config on success.

## 42. CLI key mismatches

**Bug**: CLI used wrong JSON keys (`receiving` vs `receiving_enabled`, `to` vs `target_device_id`, `status=ok` vs `ok=true`). Caused silent failures.

**Fix**: CLI matches daemon's exact JSON keys.

---

# Final Bug-Fix Pass

## 43. BUG 1: Watch socket was unidirectional — consent never reached the daemon

**Root cause**: `cmd_watch` (`ipc.rs:525`) took over the IPC connection as a one-way event stream. The watch CLI sent `transfer_confirm` back on the same socket (`watch.rs:47`), but `cmd_watch` only polled `rx.recv()` from the mpsc channel — it never read from the socket's read half. The JSON sat unread in the kernel buffer until the 120s consent timer expired, producing `consent_timeout` on both sides.

**Fix** (`ipc.rs:525`): `cmd_watch` now accepts `&mut lines` (the reader) and uses `tokio::select!` to poll **both** the mpsc event channel (outbound events → watcher) **and** the socket reader (inbound commands from the watch CLI). `transfer_confirm` commands received on the watch socket are dispatched to `cmd_transfer_confirm` inline. The watch CLI prints `"Accept sent."` immediately after sending for unambiguous feedback.

## 44. BUG 2: `--to NAME` passed the literal name as `target_device_id`

**Root cause**: `send_cmd.rs:15` passed the raw `--to` string as `target_device_id` regardless of format. When the user gave a human name like `NXFR-Test-Linux`, `handler.rs:752` correctly rejected it because the TLS peer's device_id (64-char hex) didn't match.

**Fix** (`send_cmd.rs`): `--to` input is now classified:
- 64-char hex → device_id, used as-is.
- Otherwise → name resolution against (1) paired devices, (2) discovered devices, (3) the daemon's own identity (self-send). Self-match defaults `--addr` to `127.0.0.1:17394`. Unresolvable names exit with: `"device '<name>' not discovered or paired; use its device_id or check 'nxfr devices'."` The daemon (`ipc.rs:647`) also validates `target_device_id` is 64-char hex as defense-in-depth. The identity mismatch error was improved to: `"connected device's identity does not match the requested target (…wrong address or possible MITM)"`.

**Note**: `mdns-sd` suppresses self-discovery on Linux. Self-resolution via the daemon's own `device_name` from the status response is the sanctioned single-machine testing path.

## 45. BUG 3: mdns-sd unregister errors during toggle cycles

**Root cause**: `stop_advertising` (`manager.rs:93`) propagated `daemon.unregister()` errors. After rapid disable→enable cycles, mdns-sd's internal state sometimes had already cleaned up the registration (timeout/retry paths), producing `sending on a closed channel` and `UnregisterResend` errors on the next unregister call.

**Fix** (`manager.rs`):
- `stop_advertising` swallows unregister errors with a `warn!` log instead of propagating. The intent (stop advertising) is achieved regardless since `registered_fullname` is `.take()`n.
- `start_advertising` guards against double-register: if `registered_fullname.is_some()`, it returns `Ok(())` immediately.
- 5 rapid cycles in test `T3` produce zero errors.
