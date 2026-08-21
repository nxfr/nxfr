# NXFR Android Application

The Android implementation of the **Nearby Xfer Protocol (NXFR)**, featuring the **Instrument Deck** interface, Rust cryptographic core, and local peer-to-peer architecture.

---

## Architecture & Subsystems

```
┌──────────────────────────────────────────────────────────┐
│                   Jetpack Compose UI                     │
│  [ReceiveScreen]    [SendScreen]    [SettingsScreen]     │
│  [TransferScreen]   [WebShareScreen] [WebUploadScreen]   │
│  [DesertSheet]      [ActionRail]     [TelemetryRibbon]   │
└────────────────────────────┬─────────────────────────────┘
                             │ StateFlow & Intent Binding
┌────────────────────────────▼─────────────────────────────┐
│                    NxfrService (FGS)                     │
│  - Foreground Lifecycle & Background Contract            │
│  - HotspotAwareDiscovery (UDP Beacon + mDNS)             │
│  - NxfrP2pManager (Wi-Fi Direct / Desert Mode)           │
│  - StagingRepository & FilePublisher (3-Tier Storage)    │
└────────────────────────────┬─────────────────────────────┘
                             │ JNI (libnxfr_ffi.so)
┌────────────────────────────▼─────────────────────────────┐
│                    Rust Native Core                      │
│  - nxfr-transport (TLS 1.3 mTLS, TCP 17394)              │
│  - nxfr-web (Token/PIN HTTPS, TCP 17396)                 │
│  - nxfr-crypto (ECDSA P-256, SAS Key Exporter)           │
│  - nxfr-storage (Resumable Chunk Engine & Checksums)     │
└──────────────────────────────────────────────────────────┘
```

---

## Key Features

### 1. Instrument Deck UI
- **The Beam Visualizer**: Linear directional transmission channel with scanning pulses and active data flows (replacing circular radar metaphors).
- **Physical Breaker Switches**: Industrial mechanical switches for listener socket control.
- **Packet-Stream Console**: Real-time throughput graph with dynamic packet density dots and 16-block chunk matrix (`[■■■■■■■■□□□□□□□□]`).
- **Telemetry Ribbon**: Top status bar displaying live TLS 1.3 invariants, listener state, and paired node status.
- **Cryptographic Consent Stamps**: Security seals (`[TLS 1.3 MUTUAL AUTH]`), SAS verification digits (`● 123 456 ●`), and tabular payload manifests.

### 2. Share & Receive via Link with PIN Protection
- **Direct Browser Transfer**: Share files with or receive files from any device with a modern browser on port `17396`.
- **Security PIN Gate**:
  - Optional 4 to 8 digit numeric PIN.
  - Automatic random PIN generator and custom PIN editor.
  - Web UI presents an interactive PIN gate dialog before granting access.
  - 5-attempt rate limiter locks out offending IPs for 5 minutes.
- **Fragment-Only Tokens**: In direct mode, access tokens are isolated in `#t=<token>` URL fragments.
- **Inactivity/Silence Expiry Timer**: 10-minute silence timer defers shutdown while byte streams are actively flowing and displays `TRANSFER ACTIVE — auto-stop deferred` in the UI. Graceful draining ensures downloads never get cut mid-transfer.

### 3. Desert Mode (Off-Grid Connectivity)
- **Wi-Fi Direct (P2P)**: Autonomous Group Owner negotiation, DNS-SD service discovery, and process network binding without requiring internet or Wi-Fi routers.
- **Autonomous SoftAP Hotspot**: Instant local access point generation with dynamic QR codes for rapid device joining.
- **Zero-Copy Stream-Hashing**: Large files (1GB+) are hashed in 64 KB streaming chunks to prevent native OOM spikes during direct transfer staging.

### 4. 3-Tier Storage Engine & Cache Management
- **Tier 1 (MediaStore)**: Streams incoming media and documents directly into `Download/NXFR/` with immediate gallery indexing and orphan row cleanup on I/O interruption.
- **Tier 2 (Storage Access Framework)**: Writes directory trees preserving full relative path hierarchy.
- **Tier 3 (App Sandbox)**: Safe inbox fallback in `getExternalFilesDir(null)/inbox/` ensuring files are never silently dropped.
- **Automated Cache Cleaner**: `CacheCleaner.kt` automatically purges temporary staging directories (`staging_*`, `web-share-staging`, `send_*`, `apps/`, `debug_bundle_*`) on startup and transfer completion, keeping app cache footprint near 0 MB.

### 5. Transfer Integrity
- SHA-256 file verification runs off the main thread using a streaming 64KB buffer (`Dispatchers.IO`).
- This prevents UI jank during checksum verification of large files.

### 6. Standalone Pairing (PROTO-2)
- **Device-Initiated Pairing**: Tap "Pair" on any unpaired device in the device picker to initiate SAS-based pairing without starting a transfer.
- **SAS Verification Dialog**: `PairingDialog` displays a 6-digit Short Authentication String for out-of-band verification.
- **Navigation-Integrated**: Pairing flow is wired into `NxfrNavHost` with proper back-stack management and success/failure callbacks.

### 7. Accessibility & Design Tokens
- **WCAG AA Touch Targets**: All interactive elements meet the 48dp minimum touch target standard (`SendScreen` actions, `ActionRail` chips, `StagedFilmstrip` delete buttons, `ConsentDialog` buttons).
- **Design Token System**: All colors in `WebShareScreen` use centralized `MaterialTheme.deckColors` tokens, ensuring consistent rendering across Dark, OLED, and Light themes.
- **Standardized Terminology**: User-facing labels use approachable language ("Device Name" instead of "Call-Sign", "PAIRED & VERIFIED" instead of "TOFU: PAIRED & TRUSTED").

---

## Background Behavior & Battery Contract

NXFR respects device battery, radio state, and user privacy:

1. **Adaptive Discovery Beacon Ladder (`UdpBeacon.kt`)**:
   - **`ACTIVE` (1s)**: When the app is in the foreground or the Send device picker is open, UDP discovery packets broadcast every 1,000ms for instant peer discovery.
   - **`BACKGROUND` (5s)**: When the app is backgrounded while an active transfer is streaming, the beacon slows to 5,000ms.
   - **`LOW_POWER` (30s)**: When the app is deep in the background and idle, UDP broadcast steps down to 30,000ms (relying on passive mDNS browsing) to allow Wi-Fi radios to enter low-power sleep states.

2. **Visibility-Tied Lifetime & Lifecycle Rule Engine**:
   - **Visible = ON**: Foreground service remains active with notification `"NXFR visible on LAN — tap to manage"`.
   - **Visible = OFF**: When visibility is toggled off and no transfer or web session is active, the service immediately invokes `stopForeground(true)` and `stopSelf()`.
   - **Terminal State Re-evaluation**: Transfer completion, error, or cancellation instantly re-evaluates the lifecycle contract, stepping down beacon intervals and tearing down background services when idle.
   - `updateActivePortAndRebind()` properly tears down the old listener (cancels job, closes native handle, resets state) before starting a new one.

3. **App Swipe Removal (`onTaskRemoved`) & OS Timeout (`onTimeout`)**:
   - Swiping NXFR away from recent apps immediately terminates web share portals (`nxfr_web_stop()`), unbinds TCP port `17396`, and stops the foreground service if visibility is disabled.
   - Implements `onTimeout(startId)` for Android 14+ (API 34) runtime compliance, safely flushing resume state and releasing network sockets before OS-enforced termination.

4. **Battery Optimization**:
   - Access **Settings → Battery & background** to disable OEM battery optimization for long-running multi-gigabyte transfers.

---

## Building & Verification

### Build Commands
```bash
# In apps/android directory:

# 1. Rebuild Rust native JNI libraries for all ABIs (arm64-v8a, x86_64)
./gradlew rebuildNative

# 2. Run Kotlin unit tests and assemble debug APK
./gradlew testDebugUnitTest assembleDebug

# 3. Install to connected device via ADB
adb -s <DEVICE_ID> install -r app/build/outputs/apk/debug/app-debug.apk
```
