# NXFR Engineering Ledger & Walkthrough (v0.3.0)

Chronological engineering ledger detailing the architecture, security hardening, and flagship user interface of the NXFR Protocol from Phase 9.7 through Phase 10-ID.

---

## 📋 Table of Contents
1. [Phase 9.7–9.9: Stability & Storage Architecture](#phase-9799-stability--storage-architecture)
2. [Phase 9.10–9.12: Hardening, JNI Crash Guards & Freshness Gates](#phase-910912-hardening-jni-crash-guards--freshness-gates)
3. [Phase 9.13–9.16: UX, Trust Store & Honest Lifecycle Contract](#phase-913916-ux-trust-store--honest-lifecycle-contract)
4. [Phase 9.17–9.19: Scanners, Fragment Links & Animation Contracts](#phase-917919-scanners-fragment-links--animation-contracts)
5. [Phase 9.20–9.23: Flagship Send, Zero-Permission Matrix & Notifications](#phase-920923-flagship-send-zero-permission-matrix--notifications)
6. [Phase 10-ID: Instrument Deck Identity Overhaul](#phase-10-id-instrument-deck-identity-overhaul)
7. [Verification & Build Health](#verification--build-health)

---

## Phase 9.7–9.9: Stability & Storage Architecture

### 1. Consent Pipeline & 120s Offer Expiry ([`#9.7a`](https://github.com/nxfr/nxfr/commit/51273b3), [`#9.7c`](https://github.com/nxfr/nxfr/commit/a339976))
- **Interactive Consent Flow**: Wired incoming offer sessions from the Rust core through JNI callbacks to `NxfrState.Offering`. File transmission does not begin until explicit user consent is confirmed.
- **Offer Timeout**: Unacknowledged incoming offers expire automatically after 120 seconds, releasing connection resources and TCP file handles.
- **Path-Jail Enforcement**: Replaced insecure temporary directory references with a hardened path-jail sanitization layer (`canonical.starts_with(canonical_inbox)`) preventing path-traversal attacks (`../`).

### 2. Configurable Receive Directory & 3-Tier Storage Engine ([`#9.9b`](https://github.com/nxfr/nxfr/commit/02765cd), [`#9.9c`](https://github.com/nxfr/nxfr/commit/6bbcca2))
- **Configurable FFI Receive Path**: Removed all hardcoded `/tmp` assumptions in native crates, allowing Android and CLI callers to pass sandboxed storage paths.
- **3-Tier Storage Publication**:
  - **Tier 1 (MediaStore)**: Automatically indexes incoming images, video, and audio directly into the Android MediaStore for immediate gallery visibility.
  - **Tier 2 (Storage Access Framework)**: Writes structured payloads and folders directly into user-selected SAF document trees.
  - **Tier 3 (App Sandbox Cache)**: Graceful fallback ensuring zero data loss if external media permissions or document providers fail.
- **EROFS Resilience**: Proper read-only filesystem handling during staging and transfers.

---

## Phase 9.10–9.12: Hardening, JNI Crash Guards & Freshness Gates

### 1. Socket Teardown & Visibility Truth ([`#9.11b`](https://github.com/nxfr/nxfr/commit/3113a05), [`#9.12b`](https://github.com/nxfr/nxfr/commit/1677bc9))
- **Honest Visibility Toggle**: When the user switches off visibility, the native listener socket is completely dropped, socket handles are purged from the global registry, and the port is freed with `SO_REUSEADDR`.
- **Global Identity Unification**: Unified identity key generation so the main mTLS socket (port 17394) and the browser web-upload endpoint (port 17396) share the same underlying Ed25519/P-256 persistent identity keys.

### 2. JNI Crash Guards & Native Freshness Gate ([`#9.11a`](https://github.com/nxfr/nxfr/commit/6eeff05), [`#9.12a`](https://github.com/nxfr/nxfr/commit/5611745))
- **JNI Crash Guard**: Wrapped all native JNI entry points with structured catch boundaries preventing Rust panics or unhandled exceptions from terminating the Android runtime.
- **Gradle `verifyNativeFresh` Gate**: Added automated verification tasks (`verifyNativeFresh` and `verifyNativeSymbols`) to `build.gradle.kts` ensuring `libnxfr_ffi.so` binaries in `jniLibs` are rebuilt whenever Rust source files change.

---

## Phase 9.13–9.16: UX, Trust Store & Honest Lifecycle Contract

### 1. Diagnostic Logging & Transfer History SQLite ([`#9.13`](https://github.com/nxfr/nxfr/commit/6445ae4), [`#9.14a`](https://github.com/nxfr/nxfr/commit/d1fa985))
- **`android_logger` Integration**: Routed all native Rust logs through Android Logcat with structured log levels (`INFO`, `WARN`, `ERROR`).
- **Transfer History Database**: Implemented a local SQLite database recording past sessions (file manifest, peer call-sign, bytes transferred, direction `TX`/`RX`, duration, and completion timestamp) with full privacy (records never leave the device).

### 2. Honest Background Lifecycle & Completion Sheets ([`#9.14b`](https://github.com/nxfr/nxfr/commit/f605283), [`#9.15`](https://github.com/nxfr/nxfr/commit/e96d855))
- **Foreground Service Lifetime**: Bound `NxfrService` lifecycle to active transfers with `foregroundServiceType="dataSync"` and low-power standby modes when idle.
- **Completion Action Sheet**: Post-transfer sheet offering instant file opening via Android Intents, one-tap SHA-256 verification hash copy, and "Send Another" shortcut.

---

## Phase 9.17–9.19: Scanners, Fragment Links & Animation Contracts

### 1. Fragment-Only Tokenized Web Links ([`#9.17`](https://github.com/nxfr/nxfr/commit/fe917bd))
- **URL Hash Privacy**: Changed all browser upload links to use URL fragment hashes (`https://<ip>:17396/#t=<token>`). Because fragment identifiers are processed exclusively by clientside JavaScript, auth tokens are never transmitted in HTTP request lines or written to web server access logs.

### 2. Animations Contract & QR Camera Scanner ([`#9.18`](https://github.com/nxfr/nxfr/commit/b065c71), [`#9.19`](https://github.com/nxfr/nxfr/commit/57ee5ef))
- **Accessibility & Motion Preference**: Wired Compose UI animations to `LocalAnimationsEnabled` and the system's `ANIMATOR_DURATION_SCALE`. When animations are disabled, sweeps and transitions instantly snap to static states.
- **Send-Tab QR Scanner**: Built-in ZXing QR scanner launcher on the Send tab capable of parsing NXFR connect tickets and initiating instant peer pairing.

---

## Phase 9.20–9.23: Flagship Send, Zero-Permission Matrix & Notifications

### 1. Zero-Permission Staging Matrix ([`#9.20a`](https://github.com/nxfr/nxfr/commit/9ac5a8c), [`#9.20b`](https://github.com/nxfr/nxfr/commit/d2d74f3), [`#9.20c`](https://github.com/nxfr/nxfr/commit/ccaf44d))
- **Privacy Differentiator**: Staging files requires **zero runtime permissions** (`READ_EXTERNAL_STORAGE` is never requested).
- **7-Way Staging Rail**:
  - **Files**: Multi-document picker (`ACTION_OPEN_DOCUMENT`).
  - **Media**: Android 13+ Photo Picker (`ACTION_PICK_IMAGES`) with automatic fallback.
  - **Folders**: Full directory tree picker (`ACTION_OPEN_DOCUMENT_TREE`).
  - **Contacts**: `ACTION_PICK` exporting standard `.vcf` vCards directly without `READ_CONTACTS`.
  - **Apps**: Installed application manifest picker exporting APK binaries.
  - **Text**: In-app snippet composer staging raw `.txt` files.
  - **Paste**: Direct clipboard text and image extraction.

### 2. Send Modes & Web Download Server ([`#9.21a`](https://github.com/nxfr/nxfr/commit/2ac7b5e), [`#9.21b`](https://github.com/nxfr/nxfr/commit/f55adce), [`#9.21c`](https://github.com/nxfr/nxfr/commit/a5887ea))
- **Three Core Send Modes**:
  - **Single Recipient**: Staging selection clears automatically after a successful transfer.
  - **Multiple Recipients**: Selection persists in the queue to dispatch to multiple nodes sequentially.
  - **Share via Link**: Sender hosts a token-gated HTTPS server on port 17396 for browser downloads without requiring NXFR installed on the receiver.

### 3. Live Transfer Notifications & Keep-Alive ([`#9.23`](https://github.com/nxfr/nxfr/commit/450952b))
- **`nxfr_transfers` Notification Channel**: Throttled ($\le 4\text{ Hz}$) live progress notification displaying active transfer direction, percentage, speed ($MB/s$), and ETA.
- **Background Keep-Alive**: Prevents OS battery optimization from terminating active transfer pipelines during app switching.

---

## Phase 10-ID: Instrument Deck Identity Overhaul

### 1. P0 — Tokens & Typography Roles ([`#10-id-a`](https://github.com/nxfr/nxfr/commit/b749761))
- **`DeckColors` Token Engine**: Replaced generic Material palettes with `DarkDeckColors` (Cockpit Obsidian `#0B0F17`), `LightDeckColors` (Drafting Paper `#F8FAFC`), and `OledDeckColors` (Pure `#000000`).
- **Signal-Only Cyan**: Reserved `SignalBeam` (`#00E5FF`) strictly for active network states.
- **Design Manifesto (v2.0)**: Updated `DESIGN.md` establishing `Inter` for UI headers and `FontFamily.Monospace` for all telemetry readouts.

### 2. P1 — The Home Deck ([`#10-id-b`](https://github.com/nxfr/nxfr/commit/515096e))
- **Telemetry Ribbon (`TelemetryRibbon.kt`)**: Top cockpit strip showing cipher, TCP listener state, and TOFU trust count.
- **Station Call-Sign Bar (`IdentityDeckBar.kt`)**: Station name, `#shortId`, IP tag, and details sheet.
- **The Beam Visualizer (`BeamVisualizer.kt`)**: Cryptographic transmission wire between local node and broadcast target with scanning sweeps and live packet dots.
- **Industrial Breaker Switch (`BreakerSwitch.kt`)**: Mechanical physical switch controlling network visibility.
- **Action Rail & Recent Sessions (`ActionRail.kt`, `RecentSessionsCard.kt`)**: Quick actions and recent history feed with direction tags.

### 3. P2 — Send Compose & Transfer Console ([`#10-id-c`](https://github.com/nxfr/nxfr/commit/e15422c))
- **Attach Chip Rail (`AttachChipRail.kt`)**: 7 angular chips (`[+ FILE]`, `[📷 MEDIA]`, `[📝 TEXT]`, `[📋 PASTE]`, `[📁 FOLDER]`, `[📦 APP]`, `[👤 CONTACT]`).
- **Staged Filmstrip (`StagedFilmstrip.kt`)**: Horizontal filmstrip with angular remove buttons and monospace byte counts.
- **Device Telemetry Cards (`DeviceDeckCard.kt`)**: Cockpit device cards with `[17394/TLS]`, `[PAIRED]`/`[TOFU]`, and `[QUEUED]` badges.
- **Packet-Stream Visualizer (`PacketStreamVisualizer.kt`)**: Dynamic packet frequency scaling with throughput ($MB/s$) and 16-block chunk matrix (`CHUNKS: [■■■■■■■■■■□□□□□□] 62%`).
- **Terminal Stats Block (`TerminalStatsBlock.kt`)**: Recessed well with strict monospace telemetry readouts.

### 4. P3 — Consent Verification & Settings Ledger ([`#10-id-d`](https://github.com/nxfr/nxfr/commit/84dfcbb))
- **Cryptographic Consent Manifest (`ConsentDialog.kt`)**: Verification sheet featuring security stamp seals (`[TLS 1.3 MUTUAL AUTH]`, `[TOFU STATUS]`), sender telemetry, large SAS auth digits (`● 123 456 ●`), and tabular file manifest.
- **Settings Ledger Overhaul (`SettingsScreen.kt`)**: Structured ledger layout with 0.5dp hairline dividers, uppercase tracked headers, security stamp seals (`[SEALED: TLS 1.3]`, `[TRUSTED: N]`, `[VERIFIED]`), and monospace socket telemetry parameters.

---

## 🛠️ Verification & Build Health

```
========================================================================
BUILD SUCCESSFUL: Gradle 8.11 / Kotlin 1.9.24 / Cargo NDK 3.6.0
========================================================================
- Exported JNI Symbols: arm64-v8a ✓ | x86_64 ✓
- Native Freshness Gate (:app:verifyNativeFresh): PASSED
- Unit Tests (:app:testDebugUnitTest): 100% PASSED (0 failures)
- Release Compilation (:app:assembleRelease): CLEAN
```
