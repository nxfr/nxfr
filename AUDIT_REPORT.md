# NXFR Deep Audit Report
**Date:** 2026-08-14  
**Scope:** Full Workspace (Rust Crates + Android App)  
**System Invariants Tested:** TLS 1.3 mTLS, Zero-Permission Discovery, 3-Tier Storage Fallback, Instrument Deck UI Tokens, JNI Panic Safety.

---

## Executive Summary
An exhaustive static and architectural audit was performed across all 8 Rust crates (`crates/nxfr-ffi`, `crates/nxfr-core`, `crates/nxfr-web`, `crates/nxfr-transport`, `crates/nxfr-storage`, `crates/nxfr-discovery`, `crates/nxfr-crypto`, `crates/nxfr-daemon`) and all Kotlin/Jetpack Compose components in `apps/android/`.

The codebase demonstrates high security discipline with strict TLS 1.3 enforcement, zero-permission UDP beaconing, and JNI exception fences (`ffi_guard`). A total of **2 Critical**, **4 High**, **5 Medium**, and **4 Low** issues were identified with verified File:Line citations and concrete remediation steps.

---

## 🔴 Critical (Crashes, Security Bypasses, Data Loss)

### 1. MediaStore Orphan Pending Rows on Publish Failure
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/storage/FilePublisher.kt:83-153`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/storage/FilePublisher.kt#L83-L153)
- **Vulnerability / Impact:** When publishing an incoming file via `MediaStore.Downloads`, `resolver.insert` creates a row with `IS_PENDING = 1`. If the stream copy fails (e.g. disk write interruption, sudden socket drop, or I/O error), execution jumps to `catch (e: Exception)` on line 151 without deleting the inserted `uri`. This permanently strands a 0-byte or corrupt ghost entry in the user's Android MediaStore that Android's media scanner will keep in a zombie pending state.
- **Proposed Fix:**
  ```kotlin
  var insertedUri: Uri? = null
  try {
      val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: ...
      insertedUri = uri
      resolver.openOutputStream(uri)?.use { out -> ... }
      values.clear()
      values.put(MediaStore.MediaColumns.IS_PENDING, 0)
      resolver.update(uri, values, null, null)
  } catch (e: Exception) {
      insertedUri?.let { uriToClean ->
          try { resolver.delete(uriToClean, null, null) } catch (_: Throwable) {}
      }
      logE("[publishToDownloads] MediaStore publish failed: ${e.message}", e)
  }
  ```

### 2. Path Traversal Edge Case on Multipart Dot-Filename in Web Server
- **File & Line:** [`crates/nxfr-web/src/lib.rs:102-118`](file:///home/sanro/NXFR%20protocol/crates/nxfr-web/src/lib.rs#L102-L118) and [`crates/nxfr-web/src/lib.rs:1056-1078`](file:///home/sanro/NXFR%20protocol/crates/nxfr-web/src/lib.rs#L1056-L1078)
- **Vulnerability / Impact:** `sanitize_filename` preserves `.` characters. If an attacker submits a multipart upload with `filename=".."` or `filename="."`, `sanitize_filename` returns `".."` or `"."`. Then `inbox_dir.join(&clean_name)` resolves to the parent directory of `inbox_dir`. Calling `tokio::fs::rename(&tmp_path, &final_path)` would attempt to overwrite or rename into the parent directory outside the path jail.
- **Proposed Fix:**
  Trim leading dots and validate that the sanitized name is neither `"."` nor `".."` nor empty:
  ```rust
  fn sanitize_filename(name: &str) -> String {
      let trimmed = name.trim_matches(|c| c == '.' || c == '/' || c == '\\');
      let sanitized: String = trimmed
          .chars()
          .map(|c| if c.is_alphanumeric() || c == '.' || c == '-' || c == '_' { c } else { '_' })
          .collect();
      if sanitized.is_empty() || sanitized == "." || sanitized == ".." {
          format!("uploaded_file_{}.bin", hex::encode(&rand::thread_rng().gen::<[u8; 4]>()))
      } else {
          sanitized
      }
  }
  ```

---

## 🟠 High (Lifecycle Leaks, Logic Errors, Broken Features)

### 3. Missing Error Message in Transfer Failure Notifications (`"error"` vs `"message"`)
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/service/NxfrService.kt:754`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/service/NxfrService.kt#L754)
- **Bug / Impact:** In `NxfrService.kt`, the pump loop handles `"error"` events by executing `val raw = event.optString("message")`. However, in `crates/nxfr-ffi/src/lib.rs`, `FfiEvent::Error { msg }` serializes to `{"event": "error", "error": msg}`. Because the key is `"error"` and not `"message"`, `raw` evaluates to an empty string `""`, causing error notifications and toasts to show generic or blank reasons instead of the actual error (e.g. "Storage full", "Connection closed", "Hash mismatch").
- **Proposed Fix:**
  ```kotlin
  val raw = if (event.has("error")) event.optString("error") else event.optString("message", "Unknown error")
  ```

### 4. Bottom Navigation Bar Leaks Into Fullscreen Modal Flows
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/navigation/NxfrNavHost.kt:73-102`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/navigation/NxfrNavHost.kt#L73-L102)
- **Bug / Impact:** `NxfrNavHost` unconditionally attaches `NavigationBar` inside `Scaffold(bottomBar = { ... })`. When the user navigates into `NxfrScreen.Transfer`, `NxfrScreen.WebUpload`, or `NxfrScreen.WebShare`, the bottom navigation bar remains rendered, allowing the user to tap navigation items while a modal web-share/transfer session is in progress, leaving the socket in an orphaned state.
- **Proposed Fix:**
  ```kotlin
  val mainTabs = listOf(NxfrScreen.Receive.route, NxfrScreen.Send.route, NxfrScreen.Settings.route)
  val showBottomBar = currentDestination?.route in mainTabs
  if (showBottomBar) {
      NavigationBar(...) { ... }
  }
  ```

### 5. `rememberCoroutineScope` Invoked Inside Conditional Composable Branches
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/screens/WebShareScreen.kt:212`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/screens/WebShareScreen.kt#L212) and [`apps/android/app/src/main/java/com/nxfr/android/ui/screens/WebUploadScreen.kt:242`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/screens/WebUploadScreen.kt#L242)
- **Bug / Impact:** `rememberCoroutineScope()` is called within conditional inner layouts (`if (shareUrl.isNotEmpty())`) rather than at the top level of the screen composable. Under rapid recomposition or state changes, the scope is reallocated unnecessarily.
- **Proposed Fix:** Hoist `val coroutineScope = rememberCoroutineScope()` to the top of `WebShareScreen` and `WebUploadScreen`.

### 6. Mutex Lock Poisoning on Background Thread Panic
- **File & Line:** [`crates/nxfr-ffi/src/lib.rs:470, 608, 904, 1729, 1742`](file:///home/sanro/NXFR%20protocol/crates/nxfr-ffi/src/lib.rs#L470)
- **Bug / Impact:** Session and listener maps use `std::sync::Mutex`. Calls use `.lock().unwrap()`. If any background task panics while holding the lock (even though JNI catches the top-level panic), the standard mutex enters a poisoned state. Subsequent FFI calls calling `.lock().unwrap()` will panic unconditionally.
- **Proposed Fix:** Use `.lock().unwrap_or_else(|e| e.into_inner())` or switch to `parking_lot::Mutex` which does not poison on panic.

---

## 🟡 Medium (UI/UX Inconsistencies, A11y Gaps, Tech Debt)

### 7. Hardcoded Hex Color Token in `TroubleshootSheet.kt`
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/sheets/TroubleshootSheet.kt:285`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/sheets/TroubleshootSheet.kt#L285)
- **Issue:** Hardcoded `Color(0xFF22C55E)` is used instead of the design token `LocalDeckColors.current.signalSuccess`.
- **Proposed Fix:** Replace `Color(0xFF22C55E)` with `deck.signalSuccess`.

### 8. `SendScreen.kt` Root Container Missing Explicit `deck.rootBackground`
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/screens/SendScreen.kt:338`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/screens/SendScreen.kt#L338)
- **Issue:** `ReceiveScreen.kt`, `SettingsScreen.kt`, and `TransferScreen.kt` explicitly apply `.background(deck.rootBackground)` to their root columns. `SendScreen.kt` relies on the parent `Surface`, which can cause visual background mismatch when switching to OLED black mode.
- **Proposed Fix:** Add `.background(deck.rootBackground)` to `SendScreen.kt` root `Column`.

### 9. Missing Screen-Reader Semantics on Canvas Visualizers
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/components/BeamVisualizer.kt:64`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/components/BeamVisualizer.kt#L64) and [`apps/android/app/src/main/java/com/nxfr/android/ui/components/PacketStreamVisualizer.kt:69`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/components/PacketStreamVisualizer.kt#L69)
- **Issue:** `Canvas` visual elements lack accessibility semantics, making the active beam state invisible to TalkBack screen readers.
- **Proposed Fix:** Add `Modifier.semantics { contentDescription = "Transmission channel: ${if (isPowered) "active" else "standby"}" }`.

### 10. `saveState` and `restoreState` Missing in Bottom Navigation
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/navigation/NxfrNavHost.kt:91-96`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/navigation/NxfrNavHost.kt#L91-L96)
- **Issue:** Navigation tab clicks use `launchSingleTop = true` without `saveState = true` / `restoreState = true`, causing discovery/staging lists to reset scroll position on tab switch.
- **Proposed Fix:** Add `saveState = true` inside `popUpTo` and `restoreState = true` on `navigate`.

### 11. Staging Directory Cache Not Garbage Collected on App Abrupt Kill
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/staging/StagingRepository.kt:66-70`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/staging/StagingRepository.kt#L66-L70)
- **Issue:** Staging files are written to `cacheDir/staging_<ts>/`. If the user kills the app process mid-staging, older `staging_*` directories remain until normal completion cleanup.
- **Proposed Fix:** Call `StagingRepository.cleanStagingCache(context)` inside `MainActivity.onCreate()` or `NxfrApp.onCreate()`.

---

## 🟢 Low (Nits, Refactoring Opportunities)

### 12. Deprecated `WindowInsets` & Status Bar Properties in `Theme.kt`
- **File & Line:** [`apps/android/app/src/main/java/com/nxfr/android/ui/theme/Theme.kt:191-193`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/java/com/nxfr/android/ui/theme/Theme.kt#L191-L193)
- **Nit:** Deprecated `window.statusBarColor` and `window.navigationBarColor` calls with `@Suppress("DEPRECATION")` can be removed in favor of standard AndroidX edge-to-edge controllers.

### 13. Redundant `chunk_offset` Payload Conversion Unwrap
- **File & Line:** [`crates/nxfr-ffi/src/lib.rs:1376`](file:///home/sanro/NXFR%20protocol/crates/nxfr-ffi/src/lib.rs#L1376)
- **Nit:** `payload[0..8].try_into().unwrap()` is preceded by `payload.len() < 41` check so it cannot panic, but `unwrap_or_default()` or slice pattern matching is idiomatic.

### 14. Query All Packages Permission Note
- **File & Line:** [`apps/android/app/src/main/AndroidManifest.xml:19`](file:///home/sanro/NXFR%20protocol/apps/android/app/src/main/AndroidManifest.xml#L19)
- **Nit:** `QUERY_ALL_PACKAGES` is used for the APK sharing feature. If publishing to Google Play Store, prepare declaration for core file-sharing utility policy.

### 15. Native Stripping Gradle Warning
- **File & Line:** `build.gradle.kts`
- **Nit:** Gradle outputs `Unable to strip libnxfr_ffi.so` in debug builds. Specify `packaging { jniLibs { keepDebugSymbols.add("**/libnxfr_ffi.so") } }` in debug buildType to silence the warning.

---

## Verification & Remediation Status (Phase 10.7)

| Item | Description | Severity | Status | Resolution / Verification |
| :--- | :--- | :--- | :--- | :--- |
| **T1** | MediaStore Orphan Cleanup | 🔴 Critical | **RESOLVED** | `FilePublisher.kt` tracks `insertedUri` and deletes orphaned rows in `catch`. |
| **T2** | Web Upload Path Traversal | 🔴 Critical | **RESOLVED** | `nxfr-web/src/lib.rs` validates dot filenames (`"."`, `".."`), replacing with random hex `.bin`. |
| **T3** | FFI Error Key Mismatch | 🟠 High | **RESOLVED** | `NxfrService.kt` parses `"error"` key with `"message"` fallback. |
| **T4** | Modal Navigation Bar Leaks | 🟠 High | **RESOLVED** | `NxfrNavHost.kt` conditionally hides bottom bar on modal routes and enables `saveState`/`restoreState`. |
| **T5** | FFI Mutex Poisoning | 🟠 High | **RESOLVED** | `nxfr-ffi/src/lib.rs` replaced all `.lock().unwrap()` with `.lock().unwrap_or_else(...)`. |
| **UX-1** | Web Share PIN Protection | 🛡️ Security | **IMPLEMENTED** | Interactive browser PIN gate card, `/auth` route, and Compose PIN manager added. |
| **UX-2** | Web Inbox Multi-Dir Ingestion | 🟠 High | **RESOLVED** | `WebUploadScreen.kt` polls all internal and external web inbox storage directories. |
| **ST-1** | Startup Companion Object Crash | 🔴 Critical | **RESOLVED** | `NxfrScreen.kt` `bottomNavItems` converted to lazy property getter. |
| **ST-2** | Foreground Service Android 12+ Crash | 🟠 High | **RESOLVED** | `startForegroundWithType` safely wrapped in `try/catch`. |

- **Rust Workspace Unit & Integration Tests:** `190+ passed, 0 failed` (`cargo test --workspace`)
- **Android Gradle Tests & Build:** `BUILD SUCCESSFUL` (`./gradlew testDebugUnitTest assembleDebug`)
- **JNI Exported Symbols Verification:** `28/28 verified` for both `arm64-v8a` and `x86_64`
- **Live Device Verification:** Tested on physical device (`10BC9L1698000OH`) via ADB.
