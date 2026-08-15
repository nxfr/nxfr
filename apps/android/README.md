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
│  - nxfr-crypto (Ed25519, SAS Key Exporter)               │
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

### 3. Desert Mode (Off-Grid Connectivity)
- **Wi-Fi Direct (P2P)**: Autonomous Group Owner negotiation and DNS-SD service discovery without requiring internet or Wi-Fi routers.
- **Autonomous SoftAP Hotspot**: Instant local access point generation with dynamic QR codes for rapid device joining.

### 4. 3-Tier Storage Engine
- **Tier 1 (MediaStore)**: Streams incoming media and documents directly into `Download/NXFR/` with immediate gallery indexing and orphan row cleanup on I/O interruption.
- **Tier 2 (Storage Access Framework)**: Writes directory trees preserving full relative path hierarchy.
- **Tier 3 (App Sandbox)**: Safe inbox fallback in `getExternalFilesDir(null)/inbox/` ensuring files are never silently dropped.

---

## Background Behavior & Battery Contract

NXFR respects device battery and user privacy:

1. **Visibility-Tied Lifetime**:
   - **Visible = ON**: Foreground service remains active with notification `"NXFR visible on LAN — tap to manage"`.
   - **Visible = OFF**: If no active transfer and web server is stopped, the service immediately calls `stopForeground(true)` and `stopSelf()`. The background process terminates cleanly with zero battery drain.
   - **Active Transfer / Web Upload**: Keeps service alive until the active transfer completes, fails, or is cancelled, then re-evaluates the lifecycle rule contract.

2. **App Swipe Removal (`onTaskRemoved`)**:
   - When the user swipes NXFR away from recent apps, the lifecycle contract rule engine re-evaluates immediately. If visibility is OFF and no transfer is active, the foreground service stops.

3. **Battery Optimization**:
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
