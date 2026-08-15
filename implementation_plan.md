# Implementation Plan — NXFR Protocol (v0.3.1)

Master engineering implementation roadmap tracking completed architectural milestones and upcoming protocol phases.

---

## 🏁 Completed Milestones (Phases 9.7 – 10.6)

### Phase 9.7–9.9: Stability & Storage Architecture
- [x] **Interactive Consent Flow**: Native JNI bridge to `NxfrState.Offering` with 120s automatic offer timeout ([`#9.7a`](https://github.com/nxfr/nxfr/commit/51273b3), [`#9.7c`](https://github.com/nxfr/nxfr/commit/a339976)).
- [x] **Path-Jail Sanitization**: Canonical path boundary enforcement preventing directory traversal attacks.
- [x] **3-Tier Storage Engine**: MediaStore automated indexing, SAF directory tree writing, and sandbox fallback ([`#9.9b`](https://github.com/nxfr/nxfr/commit/02765cd), [`#9.9c`](https://github.com/nxfr/nxfr/commit/6bbcca2)).
- [x] **EROFS Filesystem Handling**: Sandboxed cache staging for read-only system images.

### Phase 9.10–9.12: Hardening, JNI Guards & Freshness Gates
- [x] **Honest Visibility Toggle**: Dropping listener sockets, unbinding ports with `SO_REUSEADDR` ([`#9.11b`](https://github.com/nxfr/nxfr/commit/3113a05)).
- [x] **JNI Panic Boundary**: Crash-guarded bridge methods preventing native aborts from crashing the JVM ([`#9.11a`](https://github.com/nxfr/nxfr/commit/6eeff05)).
- [x] **Gradle Native Freshness Gate**: Automated `:app:verifyNativeFresh` and `:app:verifyNativeSymbols` tasks ([`#9.12a`](https://github.com/nxfr/nxfr/commit/5611745)).
- [x] **Global Identity Unification**: Persistent identity keys shared across mTLS and browser endpoints ([`#9.12c`](https://github.com/nxfr/nxfr/commit/be569e9)).

### Phase 9.13–9.16: UX, Trust Store & Lifecycle
- [x] **Diagnostic Logging**: Native logcat piping via `android_logger` ([`#9.13`](https://github.com/nxfr/nxfr/commit/6445ae4)).
- [x] **Transfer History SQLite Database**: Local session recording with direction tags (`TX`/`RX`) and clear actions ([`#9.14a`](https://github.com/nxfr/nxfr/commit/d1fa985)).
- [x] **Foreground Service Keep-Alive**: `dataSync` foreground service binding ([`#9.14b`](https://github.com/nxfr/nxfr/commit/f605283)).
- [x] **Completion Action Sheet**: Post-transfer sheet with Intent opener and SHA-256 hash copy ([`#9.15`](https://github.com/nxfr/nxfr/commit/e96d855)).

### Phase 9.17–9.19: Scanners & Polish
- [x] **Fragment-Only Web Tokens**: URL fragment hashes (`/#t=<token>`) preventing token leakage to server logs ([`#9.17`](https://github.com/nxfr/nxfr/commit/fe917bd)).
- [x] **Animations Contract**: Respecting `LocalAnimationsEnabled` and system `ANIMATOR_DURATION_SCALE` ([`#9.18`](https://github.com/nxfr/nxfr/commit/b065c71)).
- [x] **Network Validation**: Strict range checking on ports (1024–65535), timeouts, and multicast addresses.
- [x] **QR Camera Scanner**: Send-tab camera scanner with ticket parsing ([`#9.19`](https://github.com/nxfr/nxfr/commit/57ee5ef)).

### Phase 9.20–9.23: Flagship Send & Notifications
- [x] **Zero-Permission Staging Matrix**: 7-way attach rail (Files, Media, Folders, Contacts vCard without `READ_CONTACTS`, Apps, Text, Paste) ([`#9.20a`](https://github.com/nxfr/nxfr/commit/9ac5a8c), [`#9.20b`](https://github.com/nxfr/nxfr/commit/d2d74f3)).
- [x] **Send Modes**: Single recipient auto-clear, Multiple recipient queue, Share via Link web server ([`#9.21a`](https://github.com/nxfr/nxfr/commit/2ac7b5e), [`#9.21b`](https://github.com/nxfr/nxfr/commit/f55adce), [`#9.21c`](https://github.com/nxfr/nxfr/commit/a5887ea)).
- [x] **Live Transfer Notifications**: Throttled ($\le 4\text{ Hz}$) foreground notification channel with progress bar ([`#9.23`](https://github.com/nxfr/nxfr/commit/450952b)).

### Phase 10-ID: Instrument Deck Identity Overhaul
- [x] **P0 — Token Engine & Architecture**: `DeckColors` data class (`DarkDeckColors`, `LightDeckColors`, `OledDeckColors`), signal-only cyan rule, typography roles ([`#10-id-a`](https://github.com/nxfr/nxfr/commit/b749761)).
- [x] **P1 — The Home Deck**: `TelemetryRibbon`, `IdentityDeckBar`, `BeamVisualizer`, industrial `BreakerSwitch`, `ActionRail`, and `RecentSessionsCard` ([`#10-id-b`](https://github.com/nxfr/nxfr/commit/515096e)).
- [x] **P2 — Send Compose & Transfer Console**: `AttachChipRail`, `StagedFilmstrip`, `DeviceDeckCard`, `PacketStreamVisualizer`, and `TerminalStatsBlock` ([`#10-id-c`](https://github.com/nxfr/nxfr/commit/e15422c)).
- [x] **P3 — Consent Verification & Settings Ledger**: Cryptographic consent modal manifest, SAS auth digits, and structured settings ledger with stamp seals ([`#10-id-d`](https://github.com/nxfr/nxfr/commit/84dfcbb)).

### Phase 10.1: Manual Connect Parity
- [x] **T1 — Entry Points**: Header `[⌖ ADD NODE]` action button, Diagnostics sheet `[ENTER ADDRESS MANUALLY]`, and refined empty state buttons ([`557530b`](https://github.com/nxfr/nxfr/commit/557530b)).
- [x] **T2 — ManualConnectSheet**: IPv4/IPv6/hostname address parser, MRU recent nodes persistence (last 5), and failure feedback.
- [x] **T3 — Tests & Gates**: Unit tests for parser edge cases, version bump to `0.3.0-alpha` (code 17).

### Phase 10.6: Consolidated Hardening
- [x] **T1 & T2 — Native Symbol Proof & Dynamic Gradle Gate**: Missing JNI mangled exports added in `jni_bindings.rs`, dynamic `external fun` reflection in `build.gradle.kts`, `rebuildNative` task, and `scripts/check-native-fresh.sh` ([`408f044`](https://github.com/nxfr/nxfr/commit/408f044)).
- [x] **T3 — Universal JNI Error Containment**: All UI and service native entry points guarded with `try/catch (UnsatisfiedLinkError)` and `ErrorScreen` fallbacks ([`45b2fd2`](https://github.com/nxfr/nxfr/commit/45b2fd2)).
- [x] **T4 — Contact $\rightarrow$ vCard Exporter**: Lookup URI + typed vCard resolution with optional permission request and `"BEGIN:VCARD"` content validation ([`c6db1d8`](https://github.com/nxfr/nxfr/commit/c6db1d8)).
- [x] **T5 — Deck-Styled History Ledger**: Robust JSON row parsing, monospace metadata, `TX ↗`/`RX ↙` direction badges, and purge modal ([`0a89116`](https://github.com/nxfr/nxfr/commit/0a89116)).
- [x] **T6 — Tests & Versioning**: Unit tests for parser, model, vCard validation; version bump to `0.3.1-alpha` (code 18) ([`9055715`](https://github.com/nxfr/nxfr/commit/9055715)).

---

## 🎯 Protocol Scope & Non-Goals

> [!NOTE]
> NXFR is LAN + off-grid by design; internet relay mode is out of scope for v1.x.

---

## 🚀 Next Milestones (Roadmap to v1.0)

### 🏜️ Phase 11: Desert Mode (Off-Grid Wi-Fi Direct & SoftAP)
- **Goal**: Enable sovereign file transfer in the field with zero local Wi-Fi router or infrastructure.
- **Components**:
  - **Android Wi-Fi P2P (`WifiP2pManager`)**: Automated group negotiation (Group Owner / Client).
  - **Autonomous SoftAP Hotspot**: Temporary local hotspot broadcasting SSID `NXFR-<short_id>` with QR code ticket.
  - **Hotspot-Aware Socket Rebind**: Automatic daemon listener migration to the `192.168.49.1` or `192.168.43.1` interface upon hotspot ignition.

### 📦 Phase 12: Packaging & Distribution
- **Goal**: Frictionless, trust-verified distribution channels across platforms.
- **Components**:
  - **Android**: F-Droid reproducible build recipe and Google Play Store release.
  - **Linux**: Flathub Flatpak, Snapcraft, AUR (`nxfr-git`), and native Debian/Ubuntu (`.deb`) and Fedora/RHEL (`.rpm`) repositories.
  - **Windows & macOS**: `winget install nxfr`, `brew install nxfr`.

### 💻 Phase 13: Desktop & iOS Shells
- **Goal**: Bring the Instrument Deck visual identity to Linux, macOS, Windows, and iOS.
- **Components**:
  - **Linux (GTK4 / Libadwaita / Rust)**: Native desktop client integrating with FreeDesktop notifications and file managers.
  - **Windows (WinUI 3 / Rust)**: Windows desktop shell with shell extension "Send to NXFR".
  - **iOS (SwiftUI / Rust via UniFFI)**: Native iOS client using MultipeerConnectivity & AirDrop-adjacent intent extensions.
