//! JNI mangled bindings for `com.nxfr.android.service.NxfrService$NxfrBridge`.
//!
//! The JVM's default JNI lookup expects mangled symbol names for nested classes:
//! - `$` in class name → `_00024`
//! - `_` in method name → `_1`
//!
//! Each wrapper here delegates to the existing C-ABI `nxfr_*` functions (or their
//! internal implementations) so that host tests keep working with plain names while
//! Android gets the mangled names it expects.
//!
//! Type mapping (Kotlin → JNI → Rust):
//! - `String` → `JString` → `&str`
//! - `Long`   → `jlong`
//! - `Int`    → `jint`
//! - `Boolean`→ `jboolean` (u8: 0=false, 1=true)
//! - `ByteArray` → `jbyteArray` → `&[u8]`
//! - Return `String` → `jstring`
//! - Return `Unit` → `()`

use jni::objects::{JByteArray, JClass, JString};
use jni::sys::{jboolean, jint, jlong, jstring};
use jni::JNIEnv;
use std::ffi::CStr;

/// Prefix for all JNI symbols in this class.
/// `com.nxfr.android.service.NxfrService$NxfrBridge` →
/// `Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_`
const _PREFIX: &str = "Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_";

// ─── Helpers ────────────────────────────────────────────────────────────

/// Convert a JNI JString to a Rust String. Returns Err(json) on failure.
fn jstring_to_string(env: &mut JNIEnv, s: &JString) -> Result<String, String> {
    env.get_string(s)
        .map(|js| js.into())
        .map_err(|e| format!("JNI get_string failed: {e}"))
}

/// Call one of the existing C-ABI nxfr_* functions that returns *mut c_char,
/// convert the result to a Java String, and free the C string.
fn c_result_to_jstring(env: &mut JNIEnv, ptr: *mut std::os::raw::c_char) -> jstring {
    if ptr.is_null() {
        return make_error_jstring(env, "null result from FFI");
    }
    let c_str = unsafe { CStr::from_ptr(ptr) };
    let result = env
        .new_string(c_str.to_string_lossy().as_ref())
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut());
    // Free the C string allocated by json_ok/json_err.
    unsafe { super::nxfr_string_free(ptr) };
    result
}

fn make_error_jstring(env: &mut JNIEnv, msg: &str) -> jstring {
    let json = format!("{{\"error\":\"{}\"}}", msg.replace('"', "\\\""));
    env.new_string(&json)
        .map(|s| s.into_raw())
        .unwrap_or(std::ptr::null_mut())
}

// ─── Identity ───────────────────────────────────────────────────────────

/// `external fun nxfr_identity_generate(storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1identity_1generate(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_str = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_identity_generate(c_str.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_identity_load(storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1identity_1load(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_str = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_identity_load(c_str.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Connection ─────────────────────────────────────────────────────────

/// `external fun nxfr_connect(addr: String, storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1connect(
    mut env: JNIEnv,
    _class: JClass,
    addr: JString,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let addr_str = match jstring_to_string(&mut env, &addr) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_addr = std::ffi::CString::new(addr_str).unwrap_or_default();
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_connect(c_addr.as_ptr(), c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_listen(port: Int, storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1listen(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_listen(port as u16, c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_accept(listener: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1accept(
    mut env: JNIEnv,
    _class: JClass,
    listener: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_accept(listener as u64);
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Transfer ───────────────────────────────────────────────────────────

/// `external fun nxfr_send_file(handle: Long, path: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1send_1file(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    path: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path_str = match jstring_to_string(&mut env, &path) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_path = std::ffi::CString::new(path_str).unwrap_or_default();
        let ptr = super::nxfr_send_file(handle as u64, c_path.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_pump(handle: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1pump(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_pump(handle as u64);
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_confirm(handle: Long, accepted: Boolean): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1confirm(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    accepted: jboolean,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_confirm(handle as u64, accepted != 0);
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_close(handle: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1close(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_close(handle as u64);
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Pairing ────────────────────────────────────────────────────────────

/// `external fun nxfr_pair_begin(handle: Long): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1pair_1begin(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_pair_begin(handle as u64);
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_pair_confirm(handle: Long, accepted: Boolean, storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1pair_1confirm(
    mut env: JNIEnv,
    _class: JClass,
    handle: jlong,
    accepted: jboolean,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir_str = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir_str).unwrap_or_default();
        let ptr = super::nxfr_pair_confirm(handle as u64, accepted != 0, c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_paired_list(storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1paired_1list(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir_str = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir_str).unwrap_or_default();
        let ptr = super::nxfr_paired_list(c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_unpair(storeDir: String, deviceId: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1unpair(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
    device_id: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir_str = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let did_str = match jstring_to_string(&mut env, &device_id) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir_str).unwrap_or_default();
        let c_did = std::ffi::CString::new(did_str).unwrap_or_default();
        let ptr = super::nxfr_unpair(c_dir.as_ptr(), c_did.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_set_auto_accept(storeDir: String, deviceId: String, policy: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1set_1auto_1accept(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
    device_id: JString,
    policy: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir_str = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let did_str = match jstring_to_string(&mut env, &device_id) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let pol_str = match jstring_to_string(&mut env, &policy) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir_str).unwrap_or_default();
        let c_did = std::ffi::CString::new(did_str).unwrap_or_default();
        let c_pol = std::ffi::CString::new(pol_str).unwrap_or_default();
        let ptr = super::nxfr_set_auto_accept(c_dir.as_ptr(), c_did.as_ptr(), c_pol.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_set_name(storeDir: String, name: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1set_1name(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
    name: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir_str = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let name_str = match jstring_to_string(&mut env, &name) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir_str).unwrap_or_default();
        let c_name = std::ffi::CString::new(name_str).unwrap_or_default();
        let ptr = super::nxfr_set_name(c_dir.as_ptr(), c_name.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Utilities ──────────────────────────────────────────────────────────

/// `external fun nxfr_sanitize_path(path: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1sanitize_1path(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path_str = match jstring_to_string(&mut env, &path) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_path = std::ffi::CString::new(path_str).unwrap_or_default();
        let ptr = super::nxfr_sanitize_path(c_path.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_sha256(data: ByteArray): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1sha256(
    mut env: JNIEnv,
    _class: JClass,
    data: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let bytes = match env.convert_byte_array(data) {
            Ok(b) => b,
            Err(e) => return make_error_jstring(&mut env, &format!("convert_byte_array: {e}")),
        };
        let ptr = unsafe { super::nxfr_sha256(bytes.as_ptr(), bytes.len()) };
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_advertised_id(deviceIdHex: String, dateStr: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1advertised_1id(
    mut env: JNIEnv,
    _class: JClass,
    device_id_hex: JString,
    date_str: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id_hex = match jstring_to_string(&mut env, &device_id_hex) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let date = match jstring_to_string(&mut env, &date_str) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_id = std::ffi::CString::new(id_hex).unwrap_or_default();
        let c_date = std::ffi::CString::new(date).unwrap_or_default();
        let ptr = super::nxfr_advertised_id(c_id.as_ptr(), c_date.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_derive_sas(deviceIdAHex: String, deviceIdBHex: String, exporterBytes: ByteArray): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1derive_1sas(
    mut env: JNIEnv,
    _class: JClass,
    device_id_a_hex: JString,
    device_id_b_hex: JString,
    exporter_bytes: JByteArray,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let id_a = match jstring_to_string(&mut env, &device_id_a_hex) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let id_b = match jstring_to_string(&mut env, &device_id_b_hex) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let exp_bytes = match env.convert_byte_array(exporter_bytes) {
            Ok(b) => b,
            Err(e) => return make_error_jstring(&mut env, &format!("convert_byte_array: {e}")),
        };
        let c_id_a = std::ffi::CString::new(id_a).unwrap_or_default();
        let c_id_b = std::ffi::CString::new(id_b).unwrap_or_default();
        let ptr =
            unsafe { super::nxfr_derive_sas(c_id_a.as_ptr(), c_id_b.as_ptr(), exp_bytes.as_ptr()) };
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Memory Management ─────────────────────────────────────────────────

/// `external fun nxfr_string_free(ptr: Long)`
/// Note: Kotlin `Long` maps to `jlong`. The C-ABI version takes `*mut c_char`,
/// but Kotlin has no pointer type, so it passes the address as a Long.
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1string_1free(
    _env: JNIEnv,
    _class: JClass,
    ptr: jlong,
) {
    let _ = std::panic::catch_unwind(|| {
        if ptr != 0 {
            unsafe { super::nxfr_string_free(ptr as *mut std::os::raw::c_char) };
        }
    });
}

// ─── Storage ────────────────────────────────────────────────────────────

/// `external fun nxfr_set_receive_dir(path: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1set_1receive_1dir(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let path_str = match jstring_to_string(&mut env, &path) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_path = std::ffi::CString::new(path_str).unwrap_or_default();
        let ptr = super::nxfr_set_receive_dir(c_path.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

// ─── Web Upload Server ──────────────────────────────────────────────────

/// `external fun nxfr_web_start(port: Int, storeDir: String, pin: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1web_1start(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    store_dir: JString,
    pin: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let pin_str = jstring_to_string(&mut env, &pin).unwrap_or_default();
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let c_pin = std::ffi::CString::new(pin_str).unwrap_or_default();
        let ptr = super::nxfr_web_start(port as u16, c_dir.as_ptr(), c_pin.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_web_stop(): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1web_1stop(
    mut env: JNIEnv,
    _class: JClass,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let ptr = super::nxfr_web_stop();
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_web_share_start(port: Int, storeDir: String, pin: String, manifestJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1web_1share_1start(
    mut env: JNIEnv,
    _class: JClass,
    port: jint,
    store_dir: JString,
    pin: JString,
    manifest_json: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let pin_str = jstring_to_string(&mut env, &pin).unwrap_or_default();
        let manifest_str = match jstring_to_string(&mut env, &manifest_json) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let c_pin = std::ffi::CString::new(pin_str).unwrap_or_default();
        let c_manifest = std::ffi::CString::new(manifest_str).unwrap_or_default();
        let ptr = super::nxfr_web_share_start(
            port as u16,
            c_dir.as_ptr(),
            c_pin.as_ptr(),
            c_manifest.as_ptr(),
        );
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_web_fingerprint(storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1web_1fingerprint(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_web_fingerprint(c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_history_add(jsonRecord: String, storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1history_1add(
    mut env: JNIEnv,
    _class: JClass,
    json_record: JString,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let record = match jstring_to_string(&mut env, &json_record) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_record = std::ffi::CString::new(record).unwrap_or_default();
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_history_add(c_record.as_ptr(), c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_history_list(limit: Int, storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1history_1list(
    mut env: JNIEnv,
    _class: JClass,
    limit: jint,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_history_list(limit as u32, c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}

/// `external fun nxfr_history_clear(storeDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_nxfr_android_service_NxfrService_00024NxfrBridge_nxfr_1history_1clear(
    mut env: JNIEnv,
    _class: JClass,
    store_dir: JString,
) -> jstring {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        let dir = match jstring_to_string(&mut env, &store_dir) {
            Ok(s) => s,
            Err(e) => return make_error_jstring(&mut env, &e),
        };
        let c_dir = std::ffi::CString::new(dir).unwrap_or_default();
        let ptr = super::nxfr_history_clear(c_dir.as_ptr());
        c_result_to_jstring(&mut env, ptr)
    }));
    result.unwrap_or(std::ptr::null_mut())
}
