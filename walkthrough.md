# Walkthrough — Phase 10.1: Manual Connect Parity

## Overview

In Phase 10.1, we delivered direct manual node connection with address parsing, recent targets history, and honest UI entry points matching the Instrument Deck design language.

---

## 🛠️ Key Deliverables

### 1. Entry Points (T1)
- **NEARBY NODES Header Action**: Added `[⌖ ADD NODE]` target crosshair icon button (`Icons.Outlined.GpsFixed`) in `SignalBeam` directly between the Mode chip and the QR scanner.
- **Connection & Diagnostics Sheet**: Added `[ENTER ADDRESS MANUALLY]` button above "Run Live Diagnostics" inside `TroubleshootSheet.kt`.
- **Empty-State Refinement**: Renamed the primary button to `[DIAGNOSTICS]` and added a dedicated outlined `[⌖ MANUAL CONNECT]` button alongside it.

### 2. ManualConnectSheet (T2 — [`ManualConnectSheet.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/sheets/ManualConnectSheet.kt))
- **Instrument Deck Styling**: Structural `DeckSurface` card, `[TLS 1.3 / TCP 17394]` badge, monospace input field with clear action.
- **Address Parser ([`AddressParser.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/transfer/AddressParser.kt))**:
  - Handles IPv4 (`192.168.1.104`), IPv4:port (`192.168.1.104:17394`), bracketed IPv6 (`[fe80::1]`, `[fe80::1]:17394`), bare IPv6 (`fe80::1`), and LAN hostnames (`laptop.local:9000`).
  - Automatically strips URL prefixes (`http://`, `https://`, `nxfr://`) and trailing path slashes.
  - Returns `null` on invalid port numbers ($<1$ or $>65535$), unclosed brackets, or garbage strings.
- **Recent Target Nodes ([`RecentNodesRepository.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/transfer/RecentNodesRepository.kt))**:
  - Automatically stores the last 5 successful manual connections in `SharedPreferences`.
  - Displayed as quick-tap monospace chips (`⌖ 192.168.1.104:17394`) above the input field.
- **Failure Resilience**: Connection errors or socket timeouts produce the honest message: `"NODE UNREACHABLE — check IP, port, firewall"`.

### 3. Unit Tests & Versioning (T3)
- **Unit Tests ([`AddressParserTest.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/test/java/com/nxfr/android/transfer/AddressParserTest.kt))**:
  - `testParse_plainIpv4_defaultsPort`
  - `testParse_ipv4WithPort_parsesCorrectly`
  - `testParse_bracketedIpv6_defaultsPort`
  - `testParse_bracketedIpv6WithPort_parsesCorrectly`
  - `testParse_bareIpv6_defaultsPort`
  - `testParse_hostname_defaultsPort`
  - `testParse_hostnameWithPort_parsesCorrectly`
  - `testParse_urlPrefixes_strippedGracefully`
  - `testParse_whitespaceTrimmed`
  - `testParse_invalidInputs_returnsNull`
- **Version Bump**: `versionCode = 17`, `versionName = "0.3.0-alpha"`.

---

## 📋 Verification Results

```
- Android Unit Tests (:app:testDebugUnitTest): 100% PASSED
- Rust Workspace Tests (cargo test --workspace): 46 core + 16 crypto + 4 transport + 39 ffi PASSED
- Gradle Assemble Debug (:app:assembleDebug): SUCCESSFUL (0 errors)
- Commit: feat(android): manual connect parity — address sheet + recent nodes (#10.1) [557530b]
```
