use std::ffi::{CStr, CString};

use super::*;

fn call(s: *mut LokiSession, method: &str, params: Option<&str>) -> Value {
    let m = CString::new(method).unwrap();
    let p = params.map(|p| CString::new(p).unwrap());
    // SAFETY: valid session and NUL-terminated strings.
    let out = unsafe { loki_call(s, m.as_ptr(), p.as_ref().map_or(ptr::null(), |p| p.as_ptr())) };
    assert!(!out.is_null());
    // SAFETY: just returned by `loki_call`.
    let v = serde_json::from_str(unsafe { CStr::from_ptr(out) }.to_str().unwrap()).unwrap();
    unsafe { loki_string_free(out) };
    v
}

#[test]
fn type_inspect_render_roundtrip() {
    let s = loki_session_new();
    let r = call(s, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Hello"}}"#));
    assert_eq!(r["ok"], true, "{r}");
    let r = call(s, "document.inspect", None);
    assert!(r["result"].to_string().contains("Hello"));
    let (mut w, mut h, mut n) = (0, 0, 0);
    // SAFETY: valid session and out-pointers.
    let px = unsafe { loki_render_page(s, 0, 1.0, &mut w, &mut h, &mut n) };
    assert!(!px.is_null() && w > 0 && h > 0 && n >= 1);
    unsafe { loki_buffer_free(px, w as usize * h as usize * 4) };
    unsafe { loki_session_free(s) };
}

#[test]
fn hostile_arguments_are_errors_not_crashes() {
    let s = loki_session_new();
    assert_eq!(call(s, "engine.execute", Some("not json"))["ok"], false);
    assert_eq!(call(s, "no.such.method", None)["ok"], false);
    // SAFETY: null arguments are part of the contract.
    unsafe {
        let out = loki_call(ptr::null_mut(), ptr::null(), ptr::null());
        assert!(!out.is_null());
        loki_string_free(out);
        assert!(loki_render_page(s, u32::MAX, f32::NAN, ptr::null_mut(), ptr::null_mut(), ptr::null_mut()).is_null());
        loki_session_free(ptr::null_mut());
        loki_buffer_free(ptr::null_mut(), 0);
        loki_session_free(s);
    }
}
