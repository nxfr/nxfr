# Android Architecture

!!! info "Alpha Implementation"
    The Android client is in **alpha** stage (Phase 7). FFI-based file transfers
    work; UI is placeholder; no resume journal; auto-accept for incoming offers.

## FFI Architecture

All protocol logic lives in Rust (`nxfr-ffi` crate). The Kotlin layer is a thin
wrapper that calls C-ABI exports via JNI. **No CBOR, no frame parsing, no TLS
config in Kotlin.**

```
┌─────────────────────────────────────────────┐
│  Kotlin / Jetpack Compose UI               │
│  ┌───────────────────────────────────────┐  │
│  │  NxfrService  (foreground, dataSync)  │  │
│  │  ├─ listen + accept loop (IO)         │  │
│  │  ├─ pump coroutine → StateFlow        │  │
│  │  └─ send flow (connect → send_file)   │  │
│  └───────────────────────────────────────┘  │
│                    ↕ JNI                     │
│  ┌───────────────────────────────────────┐  │
│  │  libnxfr_ffi.so  (Rust, cdylib)       │  │
│  │  ├─ OnceLock<Runtime> (tokio, 2 wkrs) │  │
│  │  ├─ SESSIONS: Mutex<HashMap<u64, ..>> │  │
│  │  ├─ LISTENERS: Mutex<HashMap<u64,..>> │  │
│  │  ├─ TLS 1.3 via tokio-rustls          │  │
│  │  ├─ NxfrConnection framing + codec    │  │
│  │  └─ SHA-256 per-chunk verification    │  │
│  └───────────────────────────────────────┘  │
└─────────────────────────────────────────────┘
```

## FFI Exports (Phase 7)

| Function | Purpose |
|----------|---------|
| `nxfr_identity_generate(store_dir)` | Generate P-256 keypair + self-signed cert |
| `nxfr_identity_load(store_dir)` | Load existing identity |
| `nxfr_connect(addr, store_dir)` | TLS 1.3 client connect + HELLO exchange |
| `nxfr_listen(port, store_dir)` | Bind TLS listener, spawn accept loop |
| `nxfr_accept(listener)` | Pop pending connection, do HELLO exchange |
| `nxfr_send_file(handle, path)` | Spawn sender task (non-blocking) |
| `nxfr_pump(handle)` | Non-blocking event poll (JSON) |
| `nxfr_confirm(handle, accepted)` | Accept/reject incoming transfer offer |
| `nxfr_close(handle)` | Send SessionClose, release resources |
| `nxfr_pair_begin(handle)` | Send PairRequest, derive SAS code |
| `nxfr_pair_confirm(handle, accepted)` | Confirm/reject pairing |

## Event Flow

```mermaid
sequenceDiagram
    participant K as Kotlin (NxfrService)
    participant F as Rust FFI
    participant R as Remote Device

    K->>F: nxfr_listen(17394, storeDir)
    F-->>K: {listener: 1, port: 17394}
    K->>F: nxfr_accept(1) [blocks]
    R->>F: TCP + TLS connect
    F->>R: HELLO_ACK
    F-->>K: {handle: 2, peer_name: "Laptop"}

    R->>F: TransferRequest
    K->>F: nxfr_pump(2)
    F-->>K: {event: "offer", ...}
    K->>F: nxfr_confirm(2, true)
    F->>R: TransferAccept

    loop Chunks
        R->>F: DATA_CHUNK
        F->>R: CHUNK_ACK
        K->>F: nxfr_pump(2)
        F-->>K: {event: "progress", ...}
    end

    R->>F: TransferComplete
    F->>R: TransferAck
    K->>F: nxfr_pump(2)
    F-->>K: {event: "complete", file_path: "..."}
```

## Build Requirements

- Rust targets: `aarch64-linux-android`, `x86_64-linux-android`
- NDK r26+ (tested with r27c)
- `cargo-ndk` for cross-compilation
- JDK 17 (via Android Studio JBR)
- AGP 8.7 + Kotlin 2.0 + Jetpack Compose

## Discovery (4-Tier Ladder)

The Android client uses a multi-tier discovery strategy for hotspot-resilient
peer finding. All discovery runs off-main via `Dispatchers.IO`.

| Tier | Mechanism | Implementation | Latency | Hotspot-Safe |
|------|-----------|----------------|---------|--------------|
| 0 | **UDP Beacon** | `UdpBeacon.kt` — broadcasts on port 17395 every 1 s | ~1 s | ✅ Yes |
| 1 | **NSD (mDNS/DNS-SD)** | `NsdDiscovery.kt` — `android.net.nsd.NsdManager` | 2–5 s | ❌ No |
| 2 | **TCP Subnet Probe** | `HotspotAwareDiscovery.kt` — scan /24 on port 17394 | 5–30 s | ✅ Yes |
| 3 | **Manual Connect** | UI-driven IP:port → `nxfr_connect()` | User-initiated | ✅ Yes |

`HotspotAwareDiscovery.kt` orchestrates all tiers, merging results into a
single `StateFlow<List<DeviceUiModel>>` with deduplication by `advertised_id`
(beacon) or `device_id` (NSD/probe).

### Privacy: Beacon advertised_id

!!! warning "Privacy-Critical"
    The UDP beacon **NEVER** broadcasts the real `device_id`. Beacons use a
    daily-rotating `advertised_id` derived via `SHA-256(device_id || YYYY-MM-DD)`.

On `UdpBeacon.start()`, the Kotlin layer calls
`NxfrBridge.nxfr_advertised_id(deviceIdHex, LocalDate.now().toString())` to
compute today's rotating ID via the Rust FFI (HKDF-SHA256). The beacon payload
is:

```json
{"v":1,"advertised_id":"a1b2c3d4e5f67890","name":"My Phone","plat":"android","tcp_port":17394}
```

The real `device_id` is only revealed after the TLS 1.3 handshake, inside the
encrypted HELLO message. This two-layer model prevents passive Wi-Fi observers
from fingerprinting or tracking devices across networks.

## Current Limitations

- **No resume journal** — interrupted transfers restart from zero.
- **Single session** — one active transfer at a time.
- **No Android Keystore** — identity stored in app private files.
