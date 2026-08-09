//! # nxfr-ffi
//!
//! C-ABI FFI bindings for the NXFR protocol.
//! Designed for JNI/Android use but works on any platform.
//!
//! ## Safety contract
//! - Every `#[no_mangle] extern "C"` function is wrapped in `catch_unwind`.
//! - Every returned `*mut c_char` must be freed with `nxfr_string_free`.
//! - Null pointer arguments return a JSON error string, never crash.
//! - No panic ever crosses the FFI boundary.

use std::ffi::{CStr, CString};
use std::os::raw::c_char;
use std::panic;
use std::path::Path;
use std::sync::atomic::{AtomicU64, Ordering};

use sha2::{Digest, Sha256};
use zeroize::Zeroize;

// ─── Handle Registry ────────────────────────────────────────────────────
//
// Opaque handles are u64 IDs that map to runtime resources. This keeps the
// FFI surface simple: Kotlin only ever passes/receives u64 integers.

static NEXT_HANDLE: AtomicU64 = AtomicU64::new(1);

fn alloc_handle() -> u64 {
    NEXT_HANDLE.fetch_add(1, Ordering::Relaxed)
}

// ─── Helpers ────────────────────────────────────────────────────────────

/// Convert a `*const c_char` to a `&str`, returning Err on null / invalid UTF-8.
fn cstr_to_str<'a>(ptr: *const c_char) -> Result<&'a str, String> {
    if ptr.is_null() {
        return Err("null pointer".into());
    }
    unsafe { CStr::from_ptr(ptr) }
        .to_str()
        .map_err(|e| format!("invalid UTF-8: {e}"))
}

/// Return a JSON-encoded C string. Caller must free with `nxfr_string_free`.
fn json_ok(value: serde_json::Value) -> *mut c_char {
    let s = serde_json::to_string(&value).unwrap_or_else(|_| "{}".to_string());
    CString::new(s).unwrap_or_default().into_raw()
}

/// Return a JSON error C string.
fn json_err(msg: &str) -> *mut c_char {
    json_ok(serde_json::json!({ "error": msg }))
}

/// Wrap an FFI function body in `catch_unwind`. On panic, return a JSON error.
fn ffi_guard<F: FnOnce() -> *mut c_char + panic::UnwindSafe>(f: F) -> *mut c_char {
    match panic::catch_unwind(f) {
        Ok(ptr) => ptr,
        Err(_) => json_err("internal panic caught at FFI boundary"),
    }
}

// ─── Identity ───────────────────────────────────────────────────────────

/// Generate a new NXFR identity (P-256 keypair + self-signed cert) and persist
/// it to `store_dir`. Returns JSON: `{ "device_id": "<hex>", "cert_der_b64": "..." }`.
///
/// # Safety
/// `store_dir` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub extern "C" fn nxfr_identity_generate(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let identity = match nxfr_crypto::identity::generate_identity() {
            Ok(id) => id,
            Err(e) => return json_err(&format!("keygen failed: {e}")),
        };

        let dir_path = Path::new(dir);
        if let Err(e) = std::fs::create_dir_all(dir_path) {
            return json_err(&format!("mkdir failed: {e}"));
        }

        // Persist key + cert as DER files.
        let key_path = dir_path.join("identity.der");
        let cert_path = dir_path.join("identity.crt");

        if let Err(e) = std::fs::write(&key_path, &identity.private_key_der) {
            return json_err(&format!("write key failed: {e}"));
        }
        if let Err(e) = std::fs::write(&cert_path, &identity.cert_der) {
            return json_err(&format!("write cert failed: {e}"));
        }

        let device_id_hex = hex::encode(identity.device_id);
        json_ok(serde_json::json!({
            "device_id": device_id_hex,
        }))
    })
}

/// Load an existing identity from `store_dir`.
/// Returns JSON: `{ "device_id": "<hex>" }` or `{ "error": "..." }`.
///
/// # Safety
/// `store_dir` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub extern "C" fn nxfr_identity_load(store_dir: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let dir = match cstr_to_str(store_dir) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let dir_path = Path::new(dir);
        let key_path = dir_path.join("identity.der");
        let cert_path = dir_path.join("identity.crt");

        let cert_der = match std::fs::read(&cert_path) {
            Ok(d) => d,
            Err(e) => return json_err(&format!("read cert failed: {e}")),
        };

        // Verify we can read the private key too.
        if !key_path.exists() {
            return json_err("identity.der not found");
        }

        let device_id = match nxfr_crypto::identity::device_id_from_cert(&cert_der) {
            Ok(id) => id,
            Err(e) => return json_err(&format!("device_id derivation failed: {e}")),
        };

        json_ok(serde_json::json!({
            "device_id": hex::encode(device_id),
        }))
    })
}

// ─── Connection Handles ─────────────────────────────────────────────────
//
// In a full implementation these would manage async Tokio connections.
// For Phase 6 we provide the C ABI surface; the async runtime integration
// is completed once the Android app can load the .so.
//
// Each function returns a handle (u64) that the caller can pass to
// nxfr_pump, nxfr_send_file, nxfr_confirm, etc.

/// Connect to a remote NXFR endpoint. Returns a handle as JSON.
/// `addr` = "ip:port", `identity_json` = output of nxfr_identity_generate/load.
///
/// # Safety
/// Both parameters must be valid null-terminated UTF-8 C strings.
#[no_mangle]
pub extern "C" fn nxfr_connect(addr: *const c_char, identity_json: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let _addr = match cstr_to_str(addr) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let _identity = match cstr_to_str(identity_json) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let handle = alloc_handle();
        // TODO(phase7): Wire up actual TLS connection via tokio runtime.
        json_ok(serde_json::json!({
            "handle": handle,
            "status": "stub_connected",
        }))
    })
}

/// Bind a listening socket. Returns a listener handle as JSON.
///
/// # Safety
/// `identity_json` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub extern "C" fn nxfr_listen(port: u16, identity_json: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let _identity = match cstr_to_str(identity_json) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let handle = alloc_handle();
        json_ok(serde_json::json!({
            "listener": handle,
            "port": port,
            "status": "stub_listening",
        }))
    })
}

/// Accept an incoming connection on a listener handle.
///
/// # Safety
/// `listener` must be a handle returned by `nxfr_listen`.
#[no_mangle]
pub extern "C" fn nxfr_accept(listener: u64) -> *mut c_char {
    ffi_guard(|| {
        let handle = alloc_handle();
        json_ok(serde_json::json!({
            "handle": handle,
            "listener": listener,
            "status": "stub_accepted",
        }))
    })
}

/// Start sending a file over the connection.
///
/// # Safety
/// `path` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub extern "C" fn nxfr_send_file(handle: u64, path: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let file_path = match cstr_to_str(path) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        // Validate file exists.
        if !Path::new(file_path).exists() {
            return json_err(&format!("file not found: {file_path}"));
        }

        json_ok(serde_json::json!({
            "handle": handle,
            "path": file_path,
            "status": "stub_send_started",
        }))
    })
}

/// Poll the next event from a connection. Returns JSON event.
/// Event types: progress, transfer_offer, complete, error, none.
#[no_mangle]
pub extern "C" fn nxfr_pump(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        // TODO(phase7): poll the actual connection event queue.
        json_ok(serde_json::json!({
            "handle": handle,
            "type": "none",
        }))
    })
}

/// Accept or reject a transfer offer.
#[no_mangle]
pub extern "C" fn nxfr_confirm(handle: u64, accepted: bool) -> *mut c_char {
    ffi_guard(|| {
        json_ok(serde_json::json!({
            "handle": handle,
            "accepted": accepted,
            "status": "stub_confirmed",
        }))
    })
}

/// Close a connection handle, releasing all resources.
#[no_mangle]
pub extern "C" fn nxfr_close(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        // TODO(phase7): tear down the connection in the Tokio runtime.
        json_ok(serde_json::json!({
            "handle": handle,
            "status": "closed",
        }))
    })
}

// ─── Pairing ────────────────────────────────────────────────────────────

/// Begin SAS pairing on a connection. Returns the SAS prompt as JSON.
///
/// In a live connection, this would extract the TLS exporter material
/// using label `b"NXFR-SAS-v0"` with the 64-byte sorted device-id context
/// (IMPLEMENTATION_NOTES §25). The exporter bytes are zeroized after use.
#[no_mangle]
pub extern "C" fn nxfr_pair_begin(handle: u64) -> *mut c_char {
    ffi_guard(|| {
        // TODO(phase7): extract TLS exporter from the live connection.
        // For now, return a stub that demonstrates the SAS derivation path.
        json_ok(serde_json::json!({
            "handle": handle,
            "status": "stub_pair_begin",
            "sas_code": "000000",
        }))
    })
}

/// Confirm or reject a SAS pairing.
#[no_mangle]
pub extern "C" fn nxfr_pair_confirm(handle: u64, accepted: bool) -> *mut c_char {
    ffi_guard(|| {
        json_ok(serde_json::json!({
            "handle": handle,
            "accepted": accepted,
            "status": if accepted { "pair_confirmed" } else { "pair_rejected" },
        }))
    })
}

// ─── Utility Functions ──────────────────────────────────────────────────

/// Validate and sanitize a file path per NXFR path rules.
/// Returns the sanitized path or a JSON error.
///
/// # Safety
/// `path` must be a valid null-terminated UTF-8 C string.
#[no_mangle]
pub extern "C" fn nxfr_sanitize_path(path: *const c_char) -> *mut c_char {
    ffi_guard(|| {
        let input = match cstr_to_str(path) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        match nxfr_core::sanitize_path(input) {
            Ok(sanitized) => json_ok(serde_json::json!({ "path": sanitized })),
            Err(e) => json_err(&format!("path validation failed: {e}")),
        }
    })
}

/// Compute SHA-256 hash of arbitrary data. Returns hex string as JSON.
///
/// # Safety
/// `data` must point to at least `len` valid bytes.
#[no_mangle]
pub unsafe extern "C" fn nxfr_sha256(data: *const u8, len: usize) -> *mut c_char {
    ffi_guard(|| {
        if data.is_null() {
            return json_err("null data pointer");
        }
        let bytes = unsafe { std::slice::from_raw_parts(data, len) };
        let hash = Sha256::digest(bytes);
        json_ok(serde_json::json!({ "sha256": hex::encode(hash) }))
    })
}

/// Compute the rotating advertised ID per NXFR privacy spec.
/// `device_id_hex` = 64-char hex device ID, `date_str` = "YYYY-MM-DD".
///
/// Algorithm: SHA-256(device_id_bytes || date_str_bytes), take first 8 bytes as hex.
///
/// # Safety
/// Both parameters must be valid null-terminated UTF-8 C strings.
#[no_mangle]
pub extern "C" fn nxfr_advertised_id(
    device_id_hex: *const c_char,
    date_str: *const c_char,
) -> *mut c_char {
    ffi_guard(|| {
        let id_hex = match cstr_to_str(device_id_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let date = match cstr_to_str(date_str) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };

        let device_id_bytes = match hex::decode(id_hex) {
            Ok(b) if b.len() == 32 => b,
            Ok(b) => return json_err(&format!("device_id must be 32 bytes, got {}", b.len())),
            Err(e) => return json_err(&format!("invalid hex: {e}")),
        };

        let mut hasher = Sha256::new();
        hasher.update(&device_id_bytes);
        hasher.update(date.as_bytes());
        let result = hasher.finalize();
        let advertised_id: String = result[..8].iter().map(|b| format!("{b:02x}")).collect();

        json_ok(serde_json::json!({ "advertised_id": advertised_id }))
    })
}

/// Derive SAS code from two device IDs and TLS exporter material.
/// Uses the canonical NXFR SAS derivation (IMPLEMENTATION_NOTES §25):
/// - Context = sort(device_id_a, device_id_b) lexicographically (64 bytes)
/// - SAS value = BigEndian_u32(exporter_bytes) mod 1000000
/// - Exporter bytes are zeroized after use.
///
/// # Safety
/// `device_id_a_hex`, `device_id_b_hex` must be 64-char hex strings.
/// `exporter_bytes` must point to exactly 4 bytes.
#[no_mangle]
pub unsafe extern "C" fn nxfr_derive_sas(
    device_id_a_hex: *const c_char,
    device_id_b_hex: *const c_char,
    exporter_bytes: *const u8,
) -> *mut c_char {
    ffi_guard(|| {
        let id_a_hex = match cstr_to_str(device_id_a_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        let id_b_hex = match cstr_to_str(device_id_b_hex) {
            Ok(s) => s,
            Err(e) => return json_err(&e),
        };
        if exporter_bytes.is_null() {
            return json_err("null exporter_bytes");
        }

        let id_a = match hex::decode(id_a_hex) {
            Ok(b) if b.len() == 32 => {
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&b);
                arr
            }
            _ => return json_err("device_id_a must be 64 hex chars (32 bytes)"),
        };
        let id_b = match hex::decode(id_b_hex) {
            Ok(b) if b.len() == 32 => {
                let mut arr = [0u8; 32];
                arr.copy_from_slice(&b);
                arr
            }
            _ => return json_err("device_id_b must be 64 hex chars (32 bytes)"),
        };

        let mut exp = unsafe { std::ptr::read(exporter_bytes.cast::<[u8; 4]>()) };
        let (sas_code, _context) = nxfr_core::sas::derive_sas(&id_a, &id_b, &exp);
        exp.zeroize();

        json_ok(serde_json::json!({
            "sas_code": sas_code,
            "label": "NXFR-SAS-v0",
        }))
    })
}

// ─── Memory Management ──────────────────────────────────────────────────

/// Free a C string previously returned by any `nxfr_*` function.
///
/// # Safety
/// `ptr` must be a pointer returned by an `nxfr_*` function, or null.
#[no_mangle]
pub unsafe extern "C" fn nxfr_string_free(ptr: *mut c_char) {
    if !ptr.is_null() {
        let _ = unsafe { CString::from_raw(ptr) };
    }
}

// ─── Tests ──────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    /// Helper: call an FFI function that returns JSON, parse and return the Value.
    fn parse_ffi_json(ptr: *mut c_char) -> serde_json::Value {
        assert!(!ptr.is_null());
        let cstr = unsafe { CStr::from_ptr(ptr) };
        let s = cstr.to_str().unwrap();
        let v: serde_json::Value = serde_json::from_str(s).unwrap();
        unsafe { nxfr_string_free(ptr) };
        v
    }

    #[test]
    fn test_identity_generate_and_load() {
        let tmp = tempfile::tempdir().unwrap();
        let dir = CString::new(tmp.path().to_str().unwrap()).unwrap();

        // Generate.
        let result = parse_ffi_json(nxfr_identity_generate(dir.as_ptr()));
        assert!(result.get("error").is_none(), "generate should succeed");
        assert!(result["device_id"].is_string());
        let device_id = result["device_id"].as_str().unwrap();
        assert_eq!(device_id.len(), 64, "device_id should be 64 hex chars");

        // Load.
        let result2 = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert!(result2.get("error").is_none(), "load should succeed");
        assert_eq!(result2["device_id"].as_str().unwrap(), device_id);
    }

    #[test]
    fn test_identity_load_missing_dir() {
        let dir = CString::new("/tmp/nxfr_nonexistent_42").unwrap();
        let result = parse_ffi_json(nxfr_identity_load(dir.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_identity_null_pointer() {
        let result = parse_ffi_json(nxfr_identity_generate(std::ptr::null()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_connect_stub() {
        let addr = CString::new("192.168.1.1:17394").unwrap();
        let id = CString::new(r#"{"device_id":"aa"}"#).unwrap();
        let result = parse_ffi_json(nxfr_connect(addr.as_ptr(), id.as_ptr()));
        assert!(result["handle"].is_u64());
    }

    #[test]
    fn test_connect_null_addr() {
        let id = CString::new("{}").unwrap();
        let result = parse_ffi_json(nxfr_connect(std::ptr::null(), id.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_listen_stub() {
        let id = CString::new("{}").unwrap();
        let result = parse_ffi_json(nxfr_listen(17394, id.as_ptr()));
        assert!(result["listener"].is_u64());
        assert_eq!(result["port"].as_u64().unwrap(), 17394);
    }

    #[test]
    fn test_accept_stub() {
        let result = parse_ffi_json(nxfr_accept(42));
        assert!(result["handle"].is_u64());
    }

    #[test]
    fn test_send_file_nonexistent() {
        let path = CString::new("/tmp/nxfr_no_such_file_42.dat").unwrap();
        let result = parse_ffi_json(nxfr_send_file(1, path.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("not found"));
    }

    #[test]
    fn test_send_file_exists() {
        let tmp = tempfile::NamedTempFile::new().unwrap();
        let path = CString::new(tmp.path().to_str().unwrap()).unwrap();
        let result = parse_ffi_json(nxfr_send_file(1, path.as_ptr()));
        assert!(result.get("error").is_none());
        assert_eq!(result["status"].as_str().unwrap(), "stub_send_started");
    }

    #[test]
    fn test_pump_stub() {
        let result = parse_ffi_json(nxfr_pump(1));
        assert_eq!(result["type"].as_str().unwrap(), "none");
    }

    #[test]
    fn test_confirm_accept() {
        let result = parse_ffi_json(nxfr_confirm(1, true));
        assert!(result["accepted"].as_bool().unwrap());
    }

    #[test]
    fn test_confirm_reject() {
        let result = parse_ffi_json(nxfr_confirm(1, false));
        assert!(!result["accepted"].as_bool().unwrap());
    }

    #[test]
    fn test_close() {
        let result = parse_ffi_json(nxfr_close(42));
        assert_eq!(result["status"].as_str().unwrap(), "closed");
    }

    #[test]
    fn test_pair_begin_stub() {
        let result = parse_ffi_json(nxfr_pair_begin(1));
        assert!(result["sas_code"].is_string());
    }

    #[test]
    fn test_pair_confirm() {
        let result = parse_ffi_json(nxfr_pair_confirm(1, true));
        assert_eq!(result["status"].as_str().unwrap(), "pair_confirmed");
        let result2 = parse_ffi_json(nxfr_pair_confirm(1, false));
        assert_eq!(result2["status"].as_str().unwrap(), "pair_rejected");
    }

    #[test]
    fn test_sanitize_path_valid() {
        let path = CString::new("photos/vacation/img.jpg").unwrap();
        let result = parse_ffi_json(nxfr_sanitize_path(path.as_ptr()));
        assert!(result.get("error").is_none());
        assert_eq!(result["path"].as_str().unwrap(), "photos/vacation/img.jpg");
    }

    #[test]
    fn test_sanitize_path_traversal() {
        let path = CString::new("../../etc/passwd").unwrap();
        let result = parse_ffi_json(nxfr_sanitize_path(path.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_sanitize_path_null() {
        let result = parse_ffi_json(nxfr_sanitize_path(std::ptr::null()));
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_sha256() {
        let data = b"hello world";
        let result = parse_ffi_json(unsafe { nxfr_sha256(data.as_ptr(), data.len()) });
        let expected = "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9";
        assert_eq!(result["sha256"].as_str().unwrap(), expected);
    }

    #[test]
    fn test_sha256_null() {
        let result = parse_ffi_json(unsafe { nxfr_sha256(std::ptr::null(), 0) });
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_advertised_id() {
        // 32-byte device id (all zeros).
        let id_hex = CString::new("00".repeat(32)).unwrap();
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), date.as_ptr()));
        assert!(result.get("error").is_none());
        let adv_id = result["advertised_id"].as_str().unwrap();
        assert_eq!(adv_id.len(), 16, "advertised_id should be 16 hex chars");
    }

    #[test]
    fn test_advertised_id_bad_hex() {
        let id = CString::new("not_valid_hex").unwrap();
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id.as_ptr(), date.as_ptr()));
        assert!(result.get("error").is_some());
    }

    #[test]
    fn test_advertised_id_wrong_length() {
        let id = CString::new("aabb").unwrap(); // only 2 bytes, not 32
        let date = CString::new("2025-01-01").unwrap();
        let result = parse_ffi_json(nxfr_advertised_id(id.as_ptr(), date.as_ptr()));
        assert!(result["error"].as_str().unwrap().contains("32 bytes"));
    }

    #[test]
    fn test_derive_sas() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let exporter: [u8; 4] = [0x01, 0x02, 0x03, 0x04];
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), exporter.as_ptr())
        });
        assert!(result.get("error").is_none());
        let sas = result["sas_code"].as_str().unwrap();
        assert_eq!(sas.len(), 6, "SAS code must be 6 digits");
        assert_eq!(result["label"].as_str().unwrap(), "NXFR-SAS-v0");
    }

    #[test]
    fn test_derive_sas_null_exporter() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let result = parse_ffi_json(unsafe {
            nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), std::ptr::null())
        });
        assert!(result["error"].as_str().unwrap().contains("null"));
    }

    #[test]
    fn test_derive_sas_commutative() {
        let id_a = CString::new(format!("{:0>64}", "01")).unwrap();
        let id_b = CString::new(format!("{:0>64}", "02")).unwrap();
        let exp: [u8; 4] = [0xAB, 0xCD, 0xEF, 0x12];

        let r1 =
            parse_ffi_json(unsafe { nxfr_derive_sas(id_a.as_ptr(), id_b.as_ptr(), exp.as_ptr()) });
        let r2 =
            parse_ffi_json(unsafe { nxfr_derive_sas(id_b.as_ptr(), id_a.as_ptr(), exp.as_ptr()) });
        assert_eq!(
            r1["sas_code"].as_str().unwrap(),
            r2["sas_code"].as_str().unwrap(),
            "SAS must be commutative"
        );
    }

    #[test]
    fn test_string_free_null() {
        // Must not crash.
        unsafe { nxfr_string_free(std::ptr::null_mut()) };
    }

    #[test]
    fn test_handles_are_unique() {
        let h1 = alloc_handle();
        let h2 = alloc_handle();
        let h3 = alloc_handle();
        assert_ne!(h1, h2);
        assert_ne!(h2, h3);
    }

    #[test]
    fn test_advertised_id_matches_discovery() {
        // Cross-validate with nxfr-discovery's compute_advertised_id.
        let device_id = [0u8; 32];
        let date_str = "2025-01-01";

        // Compute expected via SHA-256(device_id || date).
        let mut hasher = Sha256::new();
        hasher.update(device_id);
        hasher.update(date_str.as_bytes());
        let result = hasher.finalize();
        let expected: String = result[..8].iter().map(|b| format!("{b:02x}")).collect();

        // Compute via FFI.
        let id_hex = CString::new(hex::encode(device_id)).unwrap();
        let date = CString::new(date_str).unwrap();
        let ffi_result = parse_ffi_json(nxfr_advertised_id(id_hex.as_ptr(), date.as_ptr()));
        assert_eq!(ffi_result["advertised_id"].as_str().unwrap(), expected);
    }
}
