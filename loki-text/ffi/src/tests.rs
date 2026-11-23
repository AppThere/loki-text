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
    for (cmd, params) in
        [("readAloud.speed", r#"{"value":1.25}"#), ("references.toc", r#"{"levels":3}"#), ("review.newComment", r#"{"text":"Check this"}"#)]
    {
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

#[test]
fn shape_format_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    let r = run("insert.shape", r#"{"kind":"rectangle"}"#);
    assert!(r.get("error").is_none(), "{r}");
    for (cmd, params) in [
        ("shape.change", r#"{"kind":"roundedRectangle"}"#),
        ("shape.change", r#"{"kind":"heart"}"#),
        ("shape.fill", r#"{"color":"FF0000"}"#),
        ("shape.fill", r#"{"color":null}"#),
        ("shape.outline", r#"{"color":"000000","width":2.25}"#),
        ("shape.outline", r#"{"color":null,"width":1.0}"#),
        ("shape.effects", r#"{"shadow":"offsetBottomRight"}"#),
        ("shape.effects", r#"{"shadow":null}"#),
        ("shape.effects", r#"{"glow":10}"#),
        ("shape.effects", r#"{"softEdge":2.5}"#),
        ("object.altText", r#"{"text":"A box","title":"Box"}"#),
    ] {
        let r = run(cmd, params);
        assert!(r.get("error").is_none(), "{cmd} {params}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn equation_gallery_templates_insert_into_an_equation() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    let g = run("equation.gallery", "{}");
    let t = g["result"]["structures"][0]["sections"][0]["templates"][0]["id"].as_str().unwrap_or_default().to_string();
    assert!(!t.is_empty(), "no template in the gallery: {g}");
    let ch = g["result"]["symbols"][0]["chars"].as_str().unwrap_or_default().chars().next();
    assert!(ch.is_some(), "no symbol in the gallery: {g}");
    let r = run("insert.equation", r#"{"linear":"x"}"#);
    assert!(r.get("error").is_none(), "{r}");
    let r = run("equation.insertStructure", &format!(r#"{{"id":"{t}"}}"#));
    assert!(r.get("error").is_none(), "{t}: {r}");
    let r = run("equation.insertSymbol", &format!(r#"{{"char":"{}"}}"#, ch.unwrap_or('+')));
    assert!(r.get("error").is_none(), "{r}");
    for (cmd, params) in [
        ("equation.convert", r#"{"to":"linear"}"#),
        ("equation.convert", r#"{"to":"professional"}"#),
        ("equation.inputFormat", r#"{"format":"latex"}"#),
    ] {
        let r = run(cmd, params);
        assert!(r.get("error").is_none(), "{cmd}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn chart_smartart_and_outline_menus_send_params_the_engine_accepts() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    let ok = |cmd: &str, params: &str| {
        let r = run(cmd, params);
        assert!(r.get("error").is_none(), "{cmd} {params}: {r}");
    };
    // Outline levels: a number, or "body".
    run("text.insert", r#"{"text":"Heading"}"#);
    for level in ["1", "3", "\"body\""] {
        ok("outline.level", &format!(r#"{{"level":{level}}}"#));
    }
    // Show Level works in Outline view only.
    let off = run("outline.showLevel", r#"{"level":2}"#);
    assert!(off.get("error").is_some(), "showLevel outside Outline view must be refused: {off}");
    ok("view.outline", "{}");
    for level in ["2", "\"all\""] {
        ok("outline.showLevel", &format!(r#"{{"level":{level}}}"#));
    }
    ok("view.printLayout", "{}");
    // A chart: type, title (set and cleared), legend.
    ok("insert.chart", r#"{"type":"column"}"#);
    for (cmd, params) in [
        ("chart.type", r#"{"type":"pie"}"#),
        ("chart.title", r#"{"text":"Sales"}"#),
        ("chart.title", r#"{"text":null}"#),
        ("chart.legend", r#"{"pos":"bottom"}"#),
        ("chart.legend", r#"{"pos":"none"}"#),
    ] {
        ok(cmd, params);
    }
    // A SmartArt graphic: layout and colours.
    ok("insert.smartArt", r#"{"layout":"process"}"#);
    ok("smartArt.layout", r#"{"layout":"venn"}"#);
    ok("smartArt.colors", r#"{"variant":"colorful"}"#);
    unsafe { loki_session_free(s) };
}

#[test]
fn the_context_menu_engine_calls_take_the_params_the_menu_sends() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    run("text.insert", r#"{"text":"The quick brown fox"}"#);
    // What the menu asks the engine at the caret: both replies are parsed by the app, so null is a valid answer.
    for cmd in ["review.suggestions", "review.thesaurus"] {
        let r = run(cmd, "{}");
        assert!(r.get("error").is_none(), "{cmd}: {r}");
    }
    // The synonym step: select the word, then type over it as raw text.
    for (cmd, params) in [
        ("select.word", "{}"),
        ("text.insert", r#"{"text":"fast","raw":true}"#),
        // The insert collapsed the selection; Copy needs something selected.
        ("select.word", "{}"),
        ("edit.copy", "{}"),
        ("edit.pasteText", "{}"),
        ("edit.pasteMerge", "{}"),
    ] {
        let r = run(cmd, params);
        assert!(r.get("error").is_none(), "{cmd} {params}: {r}");
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn a_selected_object_reports_its_frame_and_can_be_picked_by_point() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    let object = |s| call(s, "loki.selection", None)["result"]["object"].clone();
    // Typed text alone: nothing is an object.
    run("text.insert", r#"{"text":"Some text"}"#);
    assert!(object(s).is_null(), "{}", object(s));
    // A shape just inserted is selected: its frame is reported, in points.
    let r = run("insert.shape", r#"{"kind":"rectangle","width":120,"height":60}"#);
    assert!(r.get("error").is_none(), "{r}");
    let o = object(s);
    assert_eq!(o["kind"], "shape", "{o}");
    let (x, y, w, h) =
        (o["x"].as_f64().unwrap_or(-1.0), o["y"].as_f64().unwrap_or(-1.0), o["w"].as_f64().unwrap_or(0.0), o["h"].as_f64().unwrap_or(0.0));
    assert!((w - 120.0).abs() < 0.5 && (h - 60.0).abs() < 0.5, "{o}");
    // Moving the caret away drops the frame; picking the object by a point inside it brings it back.
    run("select.collapse", r#"{"end":true}"#);
    assert!(object(s).is_null());
    let page = o["page"].as_u64().unwrap_or(0);
    let hit = run("select.objectAt", &format!(r#"{{"page":{page},"x":{},"y":{}}}"#, x + w / 2.0, y + h / 2.0));
    assert_eq!(hit["result"]["selected"], true, "{hit}");
    assert_eq!(object(s)["kind"], "shape");
    // A point far from any object picks nothing, and hostile numbers are errors, not crashes.
    run("select.collapse", r#"{"end":true}"#);
    let miss = run("select.objectAt", &format!(r#"{{"page":{page},"x":{},"y":{}}}"#, x + w + 300.0, y + h + 300.0));
    assert_eq!(miss["result"]["selected"], false, "{miss}");
    assert!(run("select.objectAt", r#"{"page":0,"x":"a","y":1}"#).get("error").is_some());
    assert!(run("select.objectAt", r#"{"page":99999999999,"x":1,"y":1}"#).get("error").is_none());
    unsafe { loki_session_free(s) };
}

#[test]
fn a_header_or_footer_is_entered_by_point_and_left_again() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    let hf = |s| call(s, "loki.selection", None)["result"]["hf"].clone();
    let tabs = |s| call(s, "loki.commandState", None)["result"]["contextual"].clone();
    let has_tab = |s| tabs(s).as_array().is_some_and(|a| a.iter().any(|t| t == "Header & Footer"));
    run("text.insert", r#"{"text":"Body text"}"#);
    run("view.printLayout", "{}");
    assert!(hf(s).is_null() && !has_tab(s));
    // Above the body: the header, created if the page had none; the ribbon gets its tab.
    let r = run("hf.enterAt", r#"{"page":0,"x":100,"y":20}"#);
    assert_eq!(r["result"]["entered"], "header", "{r}");
    assert_eq!(hf(s), "header");
    assert!(has_tab(s), "{}", tabs(s));
    // Closing puts the caret back in the body and drops the tab.
    let r = run("insert.closeHeader", "{}");
    assert!(r.get("error").is_none(), "{r}");
    assert!(hf(s).is_null() && !has_tab(s));
    // Below the body: the footer.
    let r = run("hf.enterAt", r#"{"page":0,"x":100,"y":780}"#);
    assert_eq!(r["result"]["entered"], "footer", "{r}");
    assert_eq!(hf(s), "footer");
    run("insert.closeHeader", "{}");
    // In the body, or in a view with no margins, nothing is entered.
    assert!(run("hf.enterAt", r#"{"page":0,"x":100,"y":300}"#)["result"]["entered"].is_null());
    run("view.webLayout", "{}");
    assert!(run("hf.enterAt", r#"{"page":0,"x":100,"y":20}"#)["result"]["entered"].is_null());
    assert!(hf(s).is_null());
    // Hostile input is an error or a no-op, never a crash.
    assert!(run("hf.enterAt", r#"{"page":0,"x":"a","y":1}"#).get("error").is_some());
    assert!(run("hf.enterAt", r#"{"page":99999999999,"x":1,"y":1}"#).get("error").is_none());
    unsafe { loki_session_free(s) };
}

/// The engine's command list as the Kotlin tests read it (`app/src/jvmTest/resources`). They check
/// that every command is reachable from the Compose UI against this copy, so it must follow the engine.
/// Shortcuts are left out: the engine picks them for the platform it is built for (`non_mac`), so a
/// Mac and Linux would each write a different file, and the Kotlin tests don't read them.
#[test]
fn kotlin_command_fixture_follows_the_engine() {
    let s = loki_session_new();
    let cmds = call(s, "engine.commands", None)["result"].clone();
    unsafe { loki_session_free(s) };
    let lines: Vec<String> = cmds
        .as_array()
        .into_iter()
        .flatten()
        .map(|c| {
            let mut c = c.clone();
            if let Some(o) = c.as_object_mut() {
                o.remove("shortcut");
            }
            serde_json::to_string(&c).unwrap()
        })
        .collect();
    let now = format!("[\n{}\n]\n", lines.join(",\n"));
    let path = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../app/src/jvmTest/resources/engine-commands.json");
    if std::env::var_os("LOKI_UPDATE_FIXTURES").is_some() {
        std::fs::create_dir_all(path.parent().unwrap()).unwrap();
        std::fs::write(&path, &now).unwrap();
    }
    let saved = std::fs::read_to_string(&path).unwrap_or_default();
    assert!(
        saved == now,
        "engine.commands changed: run `LOKI_UPDATE_FIXTURES=1 cargo test -p loki-ffi kotlin_command_fixture`, then `gradle :app:jvmTest`"
    );
}

#[test]
fn ui_requests_are_handed_over_once() {
    let s = loki_session_new();
    call(s, "loki.uiRequests", None);
    let r = call(s, "engine.execute", Some(r#"{"command":"view.interfaceTheme","params":{"value":"dark"}}"#));
    assert!(r.get("error").is_none(), "{r}");
    call(s, "engine.execute", Some(r#"{"command":"file.exportPdf","params":{}}"#));
    let got = call(s, "loki.uiRequests", None)["result"].clone();
    assert_eq!(got, serde_json::json!([{"interfaceTheme": "dark"}, {"open": "exportPdf"}]));
    assert_eq!(call(s, "loki.uiRequests", None)["result"], serde_json::json!([]));
    unsafe { loki_session_free(s) };
}

/// Page 0 rendered at 1 px per point, as raw RGBA.
fn page_pixels(s: *mut LokiSession) -> Vec<u8> {
    let (mut w, mut h, mut n) = (0u32, 0u32, 0u32);
    let buf = unsafe { loki_render_page(s, 0, 1.0, &mut w, &mut h, &mut n) };
    assert!(!buf.is_null());
    let len = (w * h * 4) as usize;
    let out = unsafe { std::slice::from_raw_parts(buf, len) }.to_vec();
    unsafe { loki_buffer_free(buf, len) };
    out
}

#[test]
fn show_hide_draws_formatting_marks_on_the_page() {
    // The page used to render with default options, so Show/Hide ¶ changed nothing on screen.
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Two words"}}"#));
    let plain = page_pixels(s);
    let r = call(s, "engine.execute", Some(r#"{"command":"view.marks","params":{}}"#));
    assert!(r.get("error").is_none(), "{r}");
    assert_ne!(page_pixels(s), plain, "a ¶ and a space dot should be drawn");
    call(s, "engine.execute", Some(r#"{"command":"view.marks","params":{}}"#));
    assert_eq!(page_pixels(s), plain);
    unsafe { loki_session_free(s) };
}

#[test]
fn dark_mode_darkens_the_page() {
    let s = loki_session_new();
    let light = page_pixels(s);
    call(s, "engine.execute", Some(r#"{"command":"view.darkMode","params":{}}"#));
    let dark = page_pixels(s);
    // The top-left pixel is paper: white before, dark after.
    assert_eq!(&light[..3], &[255, 255, 255]);
    assert!(dark[0] < 128, "{:?}", &dark[..4]);
    unsafe { loki_session_free(s) };
}

#[test]
fn layout_state_has_the_indents_the_ruler_drags() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"para.indents","params":{"left":36,"firstLine":-18}}"#));
    let lay = call(s, "loki.commandState", None)["result"]["layout"].clone();
    assert_eq!(lay["indentLeft"], 36.0, "{lay}");
    assert_eq!(lay["firstLine"], -18.0, "{lay}");
    unsafe { loki_session_free(s) };
}

#[test]
fn paste_special_lists_formats_then_pastes_the_chosen_one() {
    // Loki Text asks for the formats with no `as`, shows them, then pastes with the one chosen.
    let s = loki_session_new();
    for req in [
        r#"{"command":"text.insert","params":{"text":"Bold words"}}"#,
        r#"{"command":"select.all","params":{}}"#,
        r#"{"command":"format.bold","params":{}}"#,
        r#"{"command":"edit.copy","params":{}}"#,
        r#"{"command":"caret.docEnd","params":{}}"#,
    ] {
        call(s, "engine.execute", Some(req));
    }
    let listed = call(s, "engine.execute", Some(r#"{"command":"edit.pasteSpecial","params":{}}"#))["result"].clone();
    let ids: Vec<&str> = listed["formats"].as_array().into_iter().flatten().filter_map(|f| f["id"].as_str()).collect();
    assert!(ids.contains(&"formatted") && ids.contains(&"text"), "{listed}");
    let r = call(s, "engine.execute", Some(r#"{"command":"edit.pasteSpecial","params":{"as":"text"}}"#));
    assert!(r.get("error").is_none(), "{r}");
    let text = call(s, "engine.execute", Some(r#"{"command":"select.all","params":{}}"#));
    assert!(text.get("error").is_none());
    let words = call(s, "engine.execute", Some(r#"{"command":"review.wordCount","params":{}}"#))["result"]["words"].clone();
    // "Bold wordsBold words": pasted straight after the copied text.
    assert_eq!(words, 3, "the copy was pasted once more");
    unsafe { loki_session_free(s) };
}

#[test]
fn pointer_hit_tests_report_positions_and_table_spots() {
    let s = loki_session_new();
    let run = |cmd: &str, params: &str| call(s, "engine.execute", Some(&format!(r#"{{"command":"{cmd}","params":{params}}}"#)));
    run("text.insert", r#"{"text":"Some text"}"#);
    // A point on the first line of text: a position and its line; no table, no text box.
    let r = call(s, "loki.hit", Some(r#"{"page":0,"x":80,"y":90}"#));
    assert_eq!(r["ok"], true, "{r}");
    assert!(r["result"]["pos"].is_object() && r["result"]["line"].is_object() && r["result"]["textBox"].is_null(), "{r}");
    assert!(call(s, "loki.tableSpot", Some(r#"{"page":0,"x":80,"y":90}"#))["result"]["kind"].is_null());
    // Draw a table, then a press on its left border grabs it for resizing.
    run("table.draw", r#"{"page":0,"x0":100,"y0":100,"x1":400,"y1":220}"#);
    let spot = |x: f64, y: f64| call(s, "loki.tableSpot", Some(&format!(r#"{{"page":0,"x":{x},"y":{y},"reach":4}}"#)))["result"].clone();
    assert_eq!(spot(100.0, 160.0)["kind"], "resize", "{}", spot(100.0, 160.0));
    assert_eq!(spot(90.0, 87.0)["kind"], "handle", "{}", spot(90.0, 87.0));
    assert_eq!(spot(300.0, 150.0)["kind"], serde_json::Value::Null);
    assert!(spot(600.0, 700.0)["kind"].is_null());
    // Hostile input is an empty answer, not a crash.
    for bad in [r#"{}"#, r#"{"page":"a","x":1,"y":1}"#, r#"{"page":99999999999,"x":1e300,"y":-1e300}"#, r#"{"page":0,"x":null,"y":1}"#] {
        for m in ["loki.hit", "loki.tableSpot"] {
            assert_eq!(call(s, m, Some(bad))["ok"], true, "{m} {bad}");
        }
    }
    unsafe { loki_session_free(s) };
}

#[test]
fn recovery_needs_a_folder_and_then_reports_ready() {
    let s = loki_session_new();
    // Without a folder the engine has no recovery, and says so rather than pretending.
    assert_eq!(call(s, "loki.enableRecovery", Some("{}"))["ok"], false);
    let state = call(s, "engine.execute", Some(r#"{"command":"file.recover"}"#));
    assert_ne!(state["result"]["enabled"], true, "{state}");

    let dir = std::env::temp_dir().join(format!("loki-ffi-recovery-{}", std::process::id()));
    let params = json!({"dir": dir.to_str().unwrap()}).to_string();
    assert_eq!(call(s, "loki.enableRecovery", Some(&params))["ok"], true);
    // The worker scans the folder in the background; ticks take its answer in.
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(20);
    let ready = loop {
        assert_eq!(call(s, "loki.recoveryTick", None)["ok"], true);
        let r = call(s, "engine.execute", Some(r#"{"command":"file.recover"}"#));
        if r["result"]["enabled"] == true {
            assert!(r["result"]["documents"].as_array().is_some_and(|d| d.is_empty()), "{r}");
            break true;
        }
        if std::time::Instant::now() > deadline {
            break false;
        }
        std::thread::sleep(std::time::Duration::from_millis(50));
    };
    assert_eq!(call(s, "loki.recoveryDiscard", None)["ok"], true);
    // SAFETY: freed once.
    unsafe { loki_session_free(s) };
    let _ = std::fs::remove_dir_all(&dir);
    assert!(ready, "recovery never became ready");
}

/// Polls `done` until it holds; the engine's recovery worker answers in the background.
fn until(what: &str, mut done: impl FnMut() -> bool) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(40);
    while !done() {
        assert!(std::time::Instant::now() < deadline, "timed out waiting for {what}");
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}

/// Runs `file.recover` with `params` and returns the result.
fn recover(s: *mut LokiSession, params: Value) -> Value {
    let p = json!({"command": "file.recover", "params": params}).to_string();
    call(s, "engine.execute", Some(&p))
}

#[test]
fn unsaved_work_survives_the_session_and_can_be_restored() {
    let dir = std::env::temp_dir().join(format!("loki-ffi-recovery-e2e-{}", std::process::id()));
    let dir_params = json!({"dir": dir.to_str().unwrap()}).to_string();
    // A first run types and is ticked until a copy is on disk, then ends without saving.
    let a = loki_session_new();
    assert_eq!(call(a, "loki.enableRecovery", Some(&dir_params))["ok"], true);
    assert_eq!(call(a, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Unsaved words worth keeping"}}"#))["ok"], true);
    let has_copy = || {
        std::fs::read_dir(dir.join("recovery")).into_iter().chain(std::fs::read_dir(&dir)).flatten().flatten().any(|e| e.path().is_dir() || e.path().is_file())
    };
    until("the first copy", || {
        call(a, "loki.recoveryTick", None);
        has_copy()
    });
    // SAFETY: freed once. A dirty document keeps its copy through a clean close.
    unsafe { loki_session_free(a) };

    // The next run lists it and restores it into its blank document.
    let b = loki_session_new();
    assert_eq!(call(b, "loki.enableRecovery", Some(&dir_params))["ok"], true);
    let mut id = String::new();
    until("the copy to be listed", || {
        call(b, "loki.recoveryTick", None);
        let r = recover(b, json!({}));
        match r["result"]["documents"].as_array().and_then(|d| d.first()) {
            Some(d) => {
                id = d["id"].as_str().unwrap_or_default().to_string();
                true
            }
            None => false,
        }
    });
    assert_eq!(recover(b, json!({"restore": id}))["ok"], true);
    until("the copy to open", || {
        call(b, "loki.recoveryTick", None);
        call(b, "document.inspect", None)["result"].to_string().contains("Unsaved words worth keeping")
    });
    // SAFETY: freed once.
    unsafe { loki_session_free(b) };
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn a_preview_is_taken_back_and_never_undoes_earlier_work() {
    let s = loki_session_new();
    assert_eq!(call(s, "engine.execute", Some(r#"{"command":"text.insert","params":{"text":"Kept"}}"#))["ok"], true);
    let text = |s| call(s, "document.inspect", None)["result"].to_string();
    // A preview that changes the document is in it until the next one or the end.
    let p = call(s, "loki.preview", Some(r#"{"command":"text.insert","params":{"text":" shown"}}"#));
    assert_eq!(p["result"]["applied"], true, "{p}");
    assert!(text(s).contains("Kept shown"));
    // The next preview starts from where the drag began.
    call(s, "loki.preview", Some(r#"{"command":"text.insert","params":{"text":" again"}}"#));
    assert!(text(s).contains("Kept again") && !text(s).contains("shown"));
    assert_eq!(call(s, "loki.endPreview", None)["ok"], true);
    assert!(text(s).contains("Kept") && !text(s).contains("again"));
    // A preview that changes nothing leaves nothing to take back, so ending it can't undo the typing before it.
    let none = call(s, "loki.preview", Some(r#"{"command":"view.ruler","params":{}}"#));
    assert_eq!(none["result"]["applied"], false, "{none}");
    call(s, "loki.endPreview", None);
    call(s, "loki.endPreview", None);
    assert!(text(s).contains("Kept"), "earlier work survives");
    // SAFETY: freed once.
    unsafe { loki_session_free(s) };
}

#[test]
fn the_caret_paragraphs_tab_stops_reach_the_ruler() {
    let s = loki_session_new();
    call(s, "engine.execute", Some(r#"{"command":"para.tabs","params":{"set":[{"pos":72,"align":"center"}]}}"#));
    let st = call(s, "loki.commandState", None);
    let tabs = &st["result"]["layout"]["tabs"];
    assert_eq!(tabs[0]["pos"].as_f64(), Some(72.0), "{st}");
    // SAFETY: freed once.
    unsafe { loki_session_free(s) };
}
