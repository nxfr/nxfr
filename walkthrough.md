# Walkthrough — Phase 10.6: Consolidated Hardening

## Overview

In Phase 10.6, we implemented consolidated hardening across the native Rust/JNI bridge, Gradle build system, JNI error containment, contacts vCard export engine, and the Instrument Deck session ledger.

---

## 🛠️ Key Deliverables

### 1. Native JNI Bindings & Symbol Verification (T1 & T2)
- **Exported JNI Mappings ([`jni_bindings.rs`](file:///home/sanro/NXFR%20protocol/crates/nxfr-ffi/src/jni_bindings.rs))**:
  - Implemented mangled JNI exports for all missing native methods: `nxfr_web_fingerprint`, `nxfr_history_add`, `nxfr_history_list`, `nxfr_history_clear`.
  - Verified symbol export proof via `nm -D` on both `arm64-v8a` and `x86_64` architectures.
- **Dynamic Gradle Symbol Gate ([`build.gradle.kts`](file:///home/sanro/NXFR%20protocol/apps/android/app/build.gradle.kts))**:
  - Automatically parses all `external fun` declarations in Kotlin source files during `preBuild`.
  - Asserts matching `Java_com_nxfr_...` mangled symbols or direct C exports in both architectures. Fails build with actionable message: `"MISSING JNI SYMBOL: <name> in <abi> — run ./gradlew rebuildNative"`.
- **Automated `rebuildNative` Task**:
  - Executes `cargo ndk` to compile `arm64-v8a` and `x86_64` `.so` libraries directly from Gradle.
- **Freshness Pre-Push Script ([`scripts/check-native-fresh.sh`](file:///home/sanro/NXFR%20protocol/scripts/check-native-fresh.sh))**:
  - Compares newest `crates/**/*.rs` modification timestamps with `jniLibs` binaries.

### 2. Universal JNI Error Containment (T3)
- Wrapped all UI entry points calling `NxfrBridge` in `try/catch (t: Throwable)` / `try/catch (e: UnsatisfiedLinkError)`:
  - `WebShareScreen`: Displays `ErrorScreen("NATIVE LIB OUTDATED", "Run ./gradlew rebuildNative and reinstall")` instead of crashing.
  - `WebUploadScreen`: Displays error state with clear user instructions.
  - `HistorySheet`, `SettingsScreen`, `TransferScreen`, `HotspotAwareDiscovery`, `NxfrNavHost`: Fully guarded against linkage failures.

### 3. Contact $\rightarrow$ vCard Exporter ([`ContactsVCardExporter.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/staging/ContactsVCardExporter.kt) — T4)
- **Lookup URI & vCard Resolvers**:
  - Queries `LOOKUP_KEY` and `DISPLAY_NAME` from picked contact URI.
  - Resolves vCard stream via `CONTENT_VCARD_URI` and fallback `CONTENT_LOOKUP_URI` (`text/x-vcard`).
- **Optional Runtime Permission Request**:
  - If direct lookup requires permission, prompts with honest rationale: *"Only used to export the contact YOU pick as .vcf — never synced, never uploaded"*.
- **Content Validation**:
  - Writes to cache and validates that the file is non-empty and contains the `"BEGIN:VCARD"` header.

### 4. Deck-Styled History Ledger ([`HistorySheet.kt`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/sheets/HistorySheet.kt) — T5)
- **Robust JSON Parsing**:
  - Per-record JSON parsing inside `try/catch`, skipping malformed or old-schema rows without dying.
- **Instrument Deck Aesthetic**:
  - Structural `DeckSurface` bottom sheet with `0.5dp` `DeckGridLine` dividers.
  - `TX ↗` / `RX ↙` direction badges, monospace session metadata (`did:nxfr:<id>`, bytes, relative time), and `SignalSuccess` / `SignalAlert` status pills.
  - Clear confirmation modal for purging history.
  - Tapping a row opens the payload via `FileProvider` if present on disk; otherwise displays toast `"PAYLOAD NO LONGER ON DEVICE"`.

---

## 📋 Verification Results

```
- Rust Workspace Tests (cargo test --workspace): 46 core + 16 crypto + 4 transport + 39 ffi PASSED
- Gradle Task :app:rebuildNative: SUCCESSFUL
- Gradle Task :app:verifyNativeFresh: PASSED
- Gradle Task :app:verifyNativeSymbols: 28/28 symbols verified on arm64-v8a & x86_64
- Android Unit Tests (:app:testDebugUnitTest & :app:testReleaseUnitTest): 100% PASSED
- Gradle Assemble Debug (:app:assembleDebug): SUCCESSFUL (APK: 33 MB, versionCode: 18)
```
