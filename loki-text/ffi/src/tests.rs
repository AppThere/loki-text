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

#[test]
fn command_state_reports_disabled_commands_and_toggles() {
    let s = loki_session_new();
    let r = call(s, "loki.commandState", None);
    assert_eq!(r["ok"], true, "{r}");
    let st = &r["result"];
    // Nothing is selected, so Cut can't run; it says why.
    assert!(st["disabled"]["edit.cut"].as_str().is_some_and(|w| !w.is_empty()), "{st}");
    assert!(st["disabled"].get("format.bold").is_none());
    let on = |st: &Value, id: &str| st["on"].as_array().is_some_and(|a| a.iter().any(|v| v == id));
    assert!(on(st, "para.alignLeft") && on(st, "view.printLayout") && !on(st, "format.bold"), "{st}");

    call(s, "engine.execute", Some(r#"{"command":"format.bold"}"#));
    call(s, "engine.execute", Some(r#"{"command":"para.alignCenter"}"#));
    call(s, "engine.execute", Some(r#"{"command":"view.webLayout"}"#));
    let st = &call(s, "loki.commandState", None)["result"];
    assert!(on(st, "format.bold") && on(st, "para.alignCenter") && !on(st, "para.alignLeft"), "{st}");
    assert!(on(st, "view.webLayout") && !on(st, "view.printLayout"), "{st}");
    unsafe { loki_session_free(s) };
}
