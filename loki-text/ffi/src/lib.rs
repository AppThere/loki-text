//! C ABI over the WordCraft engine, for Loki Text's Kotlin Multiplatform front end.
//!
//! The surface is small on purpose. Everything the UI does is a command or a control-protocol
//! method (`docs/control-protocol.md`), so one JSON call covers it:
//!
//! * [`loki_session_new`] / [`loki_session_free`]: an in-process editing session.
//! * [`loki_call`]: `method` + JSON `params` in, `{"ok":true,"result":…}` or
//!   `{"ok":false,"error":"…"}` out (free with [`loki_string_free`]).
//! * [`loki_render_page`]: one page as straight-alpha RGBA8 (free with [`loki_buffer_free`]).
//!
//! Never crash: every entry point catches panics and reports them as errors; null and non-UTF-8
//! arguments are errors, not UB. The one `unsafe` area is this file's pointer handling.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]

use std::ffi::{CStr, CString, c_char};
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::ptr;

use serde_json::{Value, json};
use wordcraft_mcp::{Backend, Headless};

/// Opaque to C. One per open window/document tab.
pub struct LokiSession {
    inner: Headless,
}

/// Largest page bitmap `loki_render_page` returns (width × height, pixels): 8192² ≈ 256 MiB of RGBA.
const MAX_PIXELS: u64 = 8192 * 8192;

fn reply(v: Value) -> *mut c_char {
    // JSON text never contains NUL, but fall back to a fixed error rather than panic.
    CString::new(v.to_string())
        .or_else(|_| CString::new(r#"{"ok":false,"error":"reply contained NUL"}"#))
        .map_or(ptr::null_mut(), CString::into_raw)
}

fn err(msg: impl Into<String>) -> *mut c_char {
    reply(json!({"ok": false, "error": msg.into()}))
}

/// Borrow a C string as UTF-8. `None` for null or invalid UTF-8.
///
/// # Safety
/// `p` is null or points to a NUL-terminated string valid for the call.
unsafe fn str_arg<'a>(p: *const c_char) -> Option<&'a str> {
    if p.is_null() {
        return None;
    }
    // SAFETY: non-null and NUL-terminated per the caller's contract.
    unsafe { CStr::from_ptr(p) }.to_str().ok()
}

/// Create a session holding a blank document. Free with [`loki_session_free`]. Null on failure.
#[unsafe(no_mangle)]
pub extern "C" fn loki_session_new() -> *mut LokiSession {
    catch_unwind(|| Box::into_raw(Box::new(LokiSession { inner: Headless::default() }))).unwrap_or(ptr::null_mut())
}

/// Free a session. Null is ignored. The pointer must not be used afterwards.
///
/// # Safety
/// `s` is null or came from [`loki_session_new`] and has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_session_free(s: *mut LokiSession) {
    if s.is_null() {
        return;
    }
    // SAFETY: from `Box::into_raw` in `loki_session_new`, freed once (caller's contract).
    let b = unsafe { Box::from_raw(s) };
    drop(catch_unwind(AssertUnwindSafe(move || drop(b))));
}

/// Call a control-protocol method (`engine.execute`, `engine.commands`, `document.inspect`,
/// `render.page`, …). `params_json` may be null for `{}`. Returns a JSON string to free with
/// [`loki_string_free`]; null only if allocation failed.
///
/// # Safety
/// `s` came from [`loki_session_new`] and is not used concurrently; `method` and `params_json`
/// are null or NUL-terminated.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_call(s: *mut LokiSession, method: *const c_char, params_json: *const c_char) -> *mut c_char {
    // SAFETY: caller's contract.
    let (Some(session), Some(method)) = (unsafe { s.as_mut() }, unsafe { str_arg(method) }) else {
        return err("null session or method");
    };
    // SAFETY: caller's contract.
    let params = match unsafe { str_arg(params_json) } {
        None if params_json.is_null() => json!({}),
        None => return err("params are not UTF-8"),
        Some(t) => match serde_json::from_str::<Value>(t) {
            Ok(v) => v,
            Err(e) => return err(format!("bad params JSON: {e}")),
        },
    };
    if method == "loki.selection" {
        return match catch_unwind(AssertUnwindSafe(|| selection_geometry(&mut session.inner.session))) {
            Ok(v) => reply(json!({"ok": true, "result": v})),
            Err(_) => err("internal error (panic caught) reading the selection"),
        };
    }
    if method == "loki.viewport" {
        // Reflowing views (Web Layout, Read Mode) wrap to the window; the engine keeps these
        // transient numbers on the session's view and offers no command to set them.
        let dim = |k: &str| params.get(k).and_then(Value::as_f64).filter(|v| v.is_finite()).map(|v| v.clamp(144.0, 20_000.0) as f32);
        let v = &mut session.inner.session.view;
        if let Some(w) = dim("width") {
            v.web_width = w;
            v.read_width = w;
        }
        if let Some(h) = dim("height") {
            v.read_height = h;
        }
        return reply(json!({"ok": true, "result": null}));
    }
    match catch_unwind(AssertUnwindSafe(|| session.inner.call(method, params))) {
        Ok(Ok(result)) => reply(json!({"ok": true, "result": result})),
        Ok(Err(e)) => err(e),
        Err(_) => err("internal error (panic caught); the document may have been restored"),
    }
}

/// Where the caret and selection are drawn, in points from each page's top-left: `caret` is
/// `{page, x, top, height, width}` (null when the focus has no place on a page) and `rects` the
/// selection's `{page, x, y, w, h}` boxes (empty for a caret). Handled here rather than by the
/// engine because the engine's replies carry positions, not geometry.
fn selection_geometry(sess: &mut wordcraft_engine::Session) -> Value {
    let (anchor, focus, hint) = (sess.sel.anchor.clone(), sess.sel.focus.clone(), sess.page_hint);
    let collapsed = sess.sel.is_collapsed();
    let l = sess.layout();
    let caret = l.caret_on(&focus, hint).map(|c| json!({"page": c.page, "x": c.x, "top": c.top, "height": c.height, "width": c.width}));
    let rects: Vec<Value> = if collapsed {
        Vec::new()
    } else {
        l.selection_rects(&sess.doc, &anchor, &focus, hint).into_iter().map(|(pg, r)| json!({"page": pg, "x": r.x, "y": r.y, "w": r.w, "h": r.h})).collect()
    };
    json!({"caret": caret, "rects": rects, "pages": l.pages.len()})
}

/// Free a string returned by [`loki_call`]. Null is ignored.
///
/// # Safety
/// `p` is null or came from [`loki_call`] and has not been freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_string_free(p: *mut c_char) {
    if !p.is_null() {
        // SAFETY: from `CString::into_raw` in `reply`, freed once.
        drop(unsafe { CString::from_raw(p) });
    }
}

/// Render page `page` (0-based) at `scale` pixels per point as straight-alpha RGBA8.
/// On success returns the buffer and sets `*width`, `*height`, `*pages` (total pages); the buffer
/// is `width * height * 4` bytes, freed with [`loki_buffer_free`]. Null on any failure.
///
/// # Safety
/// `s` is as for [`loki_call`]; `width`, `height`, `pages` are null or writable.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_render_page(s: *mut LokiSession, page: u32, scale: f32, width: *mut u32, height: *mut u32, pages: *mut u32) -> *mut u8 {
    // SAFETY: caller's contract.
    let Some(session) = (unsafe { s.as_mut() }) else { return ptr::null_mut() };
    let scale = if scale.is_finite() { scale.clamp(0.1, 4.0) } else { 1.0 };
    let out = catch_unwind(AssertUnwindSafe(|| {
        let sess = &mut session.inner.session;
        let l = sess.layout();
        let pg = l.pages.get(usize::try_from(page).ok()?)?;
        let img = wordcraft_render::render_page(&sess.doc, pg, scale, &Default::default());
        if u64::from(img.width) * u64::from(img.height) > MAX_PIXELS {
            return None;
        }
        Some((img.to_straight(), img.width, img.height, u32::try_from(l.pages.len()).unwrap_or(u32::MAX)))
    }));
    let Ok(Some((px, w, h, n))) = out else { return ptr::null_mut() };
    // SAFETY: each out-pointer is null or writable (caller's contract).
    unsafe {
        if let Some(r) = width.as_mut() {
            *r = w;
        }
        if let Some(r) = height.as_mut() {
            *r = h;
        }
        if let Some(r) = pages.as_mut() {
            *r = n;
        }
    }
    Box::into_raw(px.into_boxed_slice()).cast::<u8>()
}

/// Free a buffer from [`loki_render_page`]; `len` is `width * height * 4`. Null is ignored.
///
/// # Safety
/// `p`/`len` are exactly what [`loki_render_page`] returned, and not yet freed.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn loki_buffer_free(p: *mut u8, len: usize) {
    if !p.is_null() {
        // SAFETY: a boxed slice of exactly `len` bytes made in `loki_render_page`.
        drop(unsafe { Box::from_raw(ptr::slice_from_raw_parts_mut(p, len)) });
    }
}

#[cfg(test)]
mod tests;
