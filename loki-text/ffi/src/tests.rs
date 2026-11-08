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

#[test]
fn command_state_reports_the_layout_at_the_caret() {
    let s = loki_session_new();
    let layout = |s| call(s, "loki.commandState", None)["result"]["layout"].clone();
    let l = layout(s);
    // A new document: portrait, one column, no indent or spacing set.
    assert_eq!(l["landscape"], false, "{l}");
    assert_eq!(l["columns"], "one", "{l}");
    assert!(l["indentLeft"].is_number() && l["spaceAfter"].is_number(), "{l}");

    call(s, "engine.execute", Some(r#"{"command":"layout.orientation","params":{"value":"landscape"}}"#));
    call(s, "engine.execute", Some(r#"{"command":"layout.columns","params":{"count":2}}"#));
    call(s, "engine.execute", Some(r#"{"command":"layout.margins","params":{"preset":"narrow"}}"#));
    call(s, "engine.execute", Some(r#"{"command":"para.indents","params":{"left":36}}"#));
    call(s, "engine.execute", Some(r#"{"command":"para.spacing","params":{"after":12}}"#));
    let l = layout(s);
    assert_eq!(l["landscape"], true, "{l}");
    assert_eq!(l["columns"], "two", "{l}");
    assert_eq!(l["margins"], "narrow", "{l}");
    assert_eq!(l["indentLeft"], 36.0, "{l}");
    assert_eq!(l["spaceAfter"], 12.0, "{l}");
    unsafe { loki_session_free(s) };
}

#[test]
fn insert_ribbon_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| {
        let req = format!(r#"{{"command":"{cmd}","params":{params}}}"#);
        call(s, "engine.execute", Some(&req))
    };
    for (cmd, params) in [
        ("insert.table", r#"{"rows":3,"cols":3}"#),
        ("insert.header", r#"{"preset":"blankThree"}"#),
        ("insert.footer", r#"{"preset":"title"}"#),
        ("insert.pageNumber", r#"{"position":"bottom","align":"center"}"#),
        ("insert.symbol", r#"{"char":"©"}"#),
        ("insert.shape", r#"{"kind":"heart"}"#),
    ] {
        let r = run(cmd, params);
        assert!(r.get("error").is_none(), "{cmd}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn command_state_names_the_contextual_tabs_of_the_selection() {
    let s = loki_session_new();
    let tabs = |s| call(s, "loki.commandState", None)["result"]["contextual"].clone();
    assert_eq!(tabs(s), serde_json::json!([]));
    call(s, "engine.execute", Some(r#"{"command":"insert.table","params":{"rows":2,"cols":2}}"#));
    let t = tabs(s);
    assert!(t.as_array().is_some_and(|a| a.iter().any(|x| x == "Table Layout")), "{t}");
    unsafe { loki_session_free(s) };
}

#[test]
fn command_state_reports_the_caret_table() {
    let s = loki_session_new();
    let state = |s| call(s, "loki.commandState", None)["result"].clone();
    let run = |s, req: &str| {
        let r = call(s, "engine.execute", Some(req));
        assert!(r.get("error").is_none(), "{req}: {r}");
    };
    assert!(state(s)["table"].is_null());
    run(s, r#"{"command":"insert.table","params":{"rows":2,"cols":2}}"#);
    let t = state(s)["table"].clone();
    assert_eq!(t["cellAlign"], "topLeft", "{t}");
    assert!(t["styles"].as_array().is_some_and(|a| !a.is_empty()), "{t}");
    assert!(t["columnWidth"].as_f64().is_some_and(|w| w > 0.0), "{t}");
    assert_eq!(t["columns"], 2, "{t}");
    assert_eq!(t["align"], "left", "{t}");
    assert_eq!(t["allowBreak"], true, "{t}");
    assert!(t["cellMargins"].as_array().is_some_and(|m| m.len() == 4), "{t}");
    let banded = t["look"]["bandedRows"].as_bool().unwrap_or(false);

    run(s, r#"{"command":"table.cellAlign","params":{"value":"bottomRight"}}"#);
    run(s, &format!(r#"{{"command":"table.look","params":{{"bandedRows":{}}}}}"#, !banded));
    run(s, r#"{"command":"table.rowHeight","params":{"height":30,"rule":"exact"}}"#);
    run(s, r#"{"command":"table.repeatHeader","params":{}}"#);
    run(s, r#"{"command":"table.altText","params":{"title":"Prices","text":"Prices by year"}}"#);
    let st = state(s);
    let t = &st["table"];
    assert_eq!(t["cellAlign"], "bottomRight", "{t}");
    assert_eq!(t["look"]["bandedRows"], !banded, "{t}");
    assert_eq!(t["rowHeight"], 30.0, "{t}");
    assert_eq!(t["exact"], true, "{t}");
    let on = st["on"].as_array().cloned().unwrap_or_default();
    assert!(on.iter().any(|x| x == "table.repeatHeader"), "{st}");
    assert_eq!(t["altTitle"], "Prices", "{t}");
    assert_eq!(t["altText"], "Prices by year", "{t}");
    unsafe { loki_session_free(s) };
}

#[test]
fn table_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"insert.table","params":{"rows":2,"cols":2}}"#));
    let style = call(s, "loki.commandState", None)["result"]["table"]["styles"][0]["id"].clone();
    let mut reqs = vec![
        format!(r#"{{"command":"table.style","params":{{"style":{style}}}}}"#),
        r#"{"command":"table.shading","params":{"color":"FFC000"}}"#.to_string(),
        r#"{"command":"table.shading","params":{"color":null}}"#.to_string(),
        r#"{"command":"table.columnWidth","params":{"width":90}}"#.to_string(),
        r#"{"command":"table.rowHeight","params":{"height":20,"rule":"atLeast"}}"#.to_string(),
    ];
    for kind in ["all", "outside", "inside", "top", "bottom", "left", "right", "none"] {
        reqs.push(format!(r#"{{"command":"table.borders","params":{{"kind":"{kind}"}}}}"#));
    }
    for mode in ["contents", "window", "fixed"] {
        reqs.push(format!(r#"{{"command":"table.autofit","params":{{"mode":"{mode}"}}}}"#));
    }
    // Convert to Text removes the table, so it goes last.
    reqs.push(r#"{"command":"table.toText","params":{"separator":"tab"}}"#.to_string());
    for req in reqs {
        let r = call(s, "engine.execute", Some(&req));
        assert!(r.get("error").is_none(), "{req}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn table_dialogs_send_params_the_engine_accepts() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"insert.table","params":{"rows":3,"cols":2}}"#));
    for req in [
        r#"{"command":"table.split","params":{"columns":3,"rows":1,"mergeFirst":true}}"#,
        r#"{"command":"table.sort","params":{"column":0,"descending":true,"header":false}}"#,
        r#"{"command":"table.formula","params":{"formula":"=SUM(LEFT)"}}"#,
        r#"{"command":"table.altText","params":{"title":"","text":""}}"#,
        r#"{"command":"table.cellMargins","params":{"top":0,"left":7.2,"bottom":0,"right":7.2}}"#,
        r#"{"command":"table.properties","params":{"align":"center","width":300,"indent":0,"rowHeight":20,"rowHeightRule":"atLeast","allowBreak":false,"headerRow":true,"columnWidth":100,"cellWidth":100,"valign":"center"}}"#,
        r#"{"command":"table.properties","params":{"width":null,"rowHeight":null,"cellWidth":null}}"#,
    ] {
        let r = call(s, "engine.execute", Some(req));
        assert!(r.get("error").is_none(), "{req}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn draw_table_from_page_points_makes_a_table() {
    let s = loki_session_new();
    let tabs = |s| call(s, "loki.commandState", None)["result"]["contextual"].clone();
    // The pen's stroke as the page reports it: a rectangle in page points on the first page.
    let r = call(s, "engine.execute", Some(r#"{"command":"table.draw","params":{"page":0,"x0":100,"y0":100,"x1":400,"y1":220}}"#));
    assert!(r.get("error").is_none(), "{r}");
    let t = tabs(s);
    assert!(t.as_array().is_some_and(|a| a.iter().any(|x| x == "Table Layout")), "{t}");
    unsafe { loki_session_free(s) };
}

/// A 4×4 red PNG, for picture commands.
const PNG: &str = "iVBORw0KGgoAAAANSUhEUgAAAAQAAAAECAYAAACp8Z5+AAAAEklEQVR4nGM4YaPxHxkzkC4AAJdYIrFAMbGxAAAAAElFTkSuQmCC";

#[test]
fn command_state_reports_the_selected_picture() {
    let s = loki_session_new();
    let state = |s| call(s, "loki.commandState", None)["result"].clone();
    assert!(state(s)["picture"].is_null());
    let req = format!(r#"{{"command":"insert.picture","params":{{"data":"{PNG}","width":144}}}}"#);
    let r = call(s, "engine.execute", Some(&req));
    assert!(r.get("error").is_none(), "{r}");
    let st = state(s);
    assert!(st["contextual"].as_array().is_some_and(|a| a.iter().any(|x| x == "Picture Format")), "{st}");
    let p = &st["picture"];
    assert_eq!(p["width"], 144.0, "{p}");
    assert_eq!(p["height"], 144.0, "{p}");
    assert_eq!(p["crop"], serde_json::json!([0.0, 0.0, 0.0, 0.0]), "{p}");
    let r = call(s, "engine.execute", Some(r#"{"command":"picture.altText","params":{"text":"A red square","title":"Red","decorative":false}}"#));
    assert!(r.get("error").is_none(), "{r}");
    assert_eq!(st["arrange"]["wrap"], "inline", "{st}");
    let r = call(s, "engine.execute", Some(r#"{"command":"arrange.wrap","params":{"wrap":"square"}}"#));
    assert!(r.get("error").is_none(), "{r}");
    assert_eq!(state(s)["arrange"]["wrap"], "square");
    let p = state(s)["picture"].clone();
    assert_eq!(p["alt"], "A red square", "{p}");
    assert_eq!(p["title"], "Red", "{p}");
    unsafe { loki_session_free(s) };
}

#[test]
fn picture_format_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    let req = format!(r#"{{"command":"insert.picture","params":{{"data":"{PNG}","width":144}}}}"#);
    call(s, "engine.execute", Some(&req));
    for req in [
        r#"{"command":"picture.corrections","params":{"brightness":20,"contrast":-20}}"#,
        r#"{"command":"picture.corrections","params":{"sharpen":40}}"#,
        r#"{"command":"picture.color","params":{"mode":"saturation","saturation":200}}"#,
        r#"{"command":"picture.color","params":{"mode":"grayscale"}}"#,
        r#"{"command":"picture.color","params":{"mode":"sepia"}}"#,
        r#"{"command":"picture.color","params":{"mode":"washout"}}"#,
        r#"{"command":"picture.color","params":{"mode":"blackAndWhite"}}"#,
        r#"{"command":"picture.effects","params":{"effect":"blur"}}"#,
        r#"{"command":"picture.effects","params":{"effect":"none"}}"#,
        r#"{"command":"picture.transparency","params":{"percent":50}}"#,
        r#"{"command":"picture.style","params":{"style":"rounded"}}"#,
        r#"{"command":"picture.border","params":{"width":4,"color":"2060D0"}}"#,
        r#"{"command":"picture.size","params":{"width":108,"lockAspect":true}}"#,
        r#"{"command":"picture.size","params":{"original":true}}"#,
        r#"{"command":"picture.crop","params":{"left":0.1,"top":0,"right":0.1,"bottom":0}}"#,
        r#"{"command":"picture.removeBackground","params":{}}"#,
        r#"{"command":"picture.compress","params":{}}"#,
        r#"{"command":"picture.reset","params":{}}"#,
        r#"{"command":"picture.iconColor","params":{"color":"2060D0"}}"#,
        r#"{"command":"arrange.wrap","params":{"wrap":"tight"}}"#,
        r#"{"command":"arrange.position","params":{"preset":"middleCenter"}}"#,
        r#"{"command":"arrange.moveWithText","params":{"value":false}}"#,
        r#"{"command":"arrange.rotate","params":{"direction":"right"}}"#,
        r#"{"command":"arrange.rotate","params":{"direction":"flipHorizontal"}}"#,
        r#"{"command":"arrange.align","params":{"value":"center"}}"#,
        r#"{"command":"arrange.bringForward","params":{}}"#,
        r#"{"command":"arrange.wrap","params":{"wrap":"inline"}}"#,
    ] {
        let r = call(s, "engine.execute", Some(req));
        assert!(r.get("error").is_none(), "{req}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn engine_shortcuts_reach_the_commands_people_expect() {
    // Loki Text takes each chord from the first command (in engine.commands order) that lists it.
    let s = loki_session_new();
    let cmds = call(s, "engine.commands", None)["result"].clone();
    let first = |chord: &str| {
        cmds.as_array().into_iter().flatten().find_map(|c| {
            let id = c["id"].as_str()?;
            let listed = c["shortcut"].as_str()?.split(" / ").any(|x| x.trim() == chord);
            (listed && !id.starts_with("ui.")).then(|| id.to_string())
        })
    };
    for (chord, id) in [
        ("Mod+O", "file.open"),
        ("Mod+N", "file.new"),
        ("Mod+S", "file.save"),
        ("Mod+P", "file.print"),
        ("Mod+Z", "edit.undo"),
        ("Mod+Y", "edit.redo"),
        ("Mod+B", "format.bold"),
    ] {
        assert_eq!(first(chord).as_deref(), Some(id), "{chord}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn command_state_names_the_style_at_the_caret() {
    let s = loki_session_new();
    let style = |s| call(s, "loki.commandState", None)["result"]["styleAt"].clone();
    call(s, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Title"}}"#));
    assert_eq!(style(s)["paragraph"], "Normal");
    assert!(style(s)["character"].is_null());
    let r = call(s, "engine.execute", Some(r#"{"command":"styles.apply","params":{"style":"Heading1"}}"#));
    assert!(r.get("error").is_none(), "{r}");
    assert_eq!(style(s)["paragraph"], "Heading1");
    unsafe { loki_session_free(s) };
}

#[test]
fn review_and_references_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Hello"}}"#));
    for (cmd, params) in [
        ("readAloud.speed", r#"{"value":1.25}"#),
        ("references.toc", r#"{"levels":3}"#),
        ("review.newComment", r#"{"text":"Check this"}"#),
    ] {
        let req = format!(r#"{{"command":"{cmd}","params":{params}}}"#);
        let r = call(s, "engine.execute", Some(&req));
        assert!(r.get("error").is_none(), "{cmd}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn grabbing_an_end_keeps_the_selection_and_moves_the_other_side() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    run("text.insert", r#"{"text":"alpha beta gamma"}"#);
    let r = run("select.word", "{}");
    assert!(r.get("error").is_none(), "{r}");
    let before = call(s, "loki.selection", None)["result"]["rects"].clone();
    assert!(before.as_array().is_some_and(|a| !a.is_empty()), "select.word selected nothing: {before}");
    for end in ["start", "end"] {
        let r = run("select.grabEnd", &format!(r#"{{"end":"{end}"}}"#));
        assert!(r.get("error").is_none(), "{end}: {r}");
        let after = call(s, "loki.selection", None)["result"]["rects"].clone();
        assert_eq!(before, after, "grabbing the {end} must not change what is selected");
    }
    let bad = run("select.grabEnd", r#"{"end":"middle"}"#);
    assert!(bad.get("error").is_some(), "{bad}");
    unsafe { loki_session_free(s) };
}
