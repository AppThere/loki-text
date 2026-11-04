//! What the ribbon needs to draw its buttons: which commands can't run right now (and why) and
//! which toggles are on. Read from the engine's own state, the same sources the egui ribbon uses.

use serde_json::{Map, Value, json};
use wordcraft_doc::Align;
use wordcraft_doc::props::LineSpacing;
use wordcraft_engine::Session;

/// `format.state` keys that are toggles, with the command that flips each.
const CHAR_TOGGLES: &[(&str, &str)] = &[
    ("bold", "format.bold"),
    ("italic", "format.italic"),
    ("underline", "format.underline"),
    ("strike", "format.strikethrough"),
    ("subscript", "format.subscript"),
    ("superscript", "format.superscript"),
];

/// `view.state` boolean keys that are toggles, with the command that flips each.
const VIEW_TOGGLES: &[(&str, &str)] = &[
    ("marks", "view.marks"),
    ("ruler", "view.ruler"),
    ("gridlines", "view.gridlines"),
    ("navPane", "view.navigationPane"),
    ("fullScreen", "view.fullScreen"),
    ("focusMode", "view.focus"),
    ("darkMode", "view.darkMode"),
    ("readMode", "view.readMode"),
];

/// `view.state` `mode` values, with the command that selects each.
const VIEW_MODES: &[(&str, &str)] = &[("print", "view.printLayout"), ("web", "view.webLayout"), ("draft", "view.draft"), ("outline", "view.outline")];

fn flag(v: &Value, k: &str) -> bool {
    v.get(k).and_then(Value::as_bool).unwrap_or(false)
}

/// `{"disabled": {id: reason}, "on": [id, …]}` for the session as it is now.
pub fn command_state(sess: &mut Session) -> Value {
    let mut disabled = Map::new();
    for spec in sess.registry.all() {
        if let Some(why) = sess.why_disabled(spec.id) {
            disabled.insert(spec.id.to_string(), json!(why));
        }
    }
    let mut on: Vec<&str> = Vec::new();

    if let Ok(st) = sess.run("format.state", &json!({})) {
        on.extend(CHAR_TOGGLES.iter().filter(|(k, _)| flag(&st, k)).map(|(_, id)| *id));
    }
    let view = sess.run("view.state", &json!({})).unwrap_or(Value::Null);
    let read = flag(&view, "readMode");
    on.extend(VIEW_TOGGLES.iter().filter(|(k, _)| flag(&view, k)).map(|(_, id)| *id));
    // Read Mode is a mode of its own: the layout it sits on top of isn't also "on".
    if !read {
        let mode = view.get("mode").and_then(Value::as_str).unwrap_or("");
        on.extend(VIEW_MODES.iter().filter(|(m, _)| *m == mode).map(|(_, id)| *id));
    }
    if sess.doc.settings.track_changes {
        on.push("review.trackChanges");
    }

    let rp = sess.doc.para_at(&sess.sel.focus).map(|p| sess.doc.styles.resolve_para(&p.props));
    if let Some(r) = &rp {
        on.push(match r.align.visual(r.bidi) {
            Align::Left => "para.alignLeft",
            Align::Center => "para.alignCenter",
            Align::Right => "para.alignRight",
            Align::Justify => "para.justify",
            Align::Distribute => "para.distribute",
        });
        on.push(if r.bidi { "para.rtl" } else { "para.ltr" });
    }
    let lists = wordcraft_engine::cmd::para::list_buttons(&sess.doc, rp.and_then(|r| r.numbering));
    if lists.bullets {
        on.push("para.bullets");
    }
    if lists.numbering {
        on.push("para.numbering");
    }
    if lists.multilevel {
        on.push("para.multilevel");
    }
    json!({"disabled": disabled, "on": on, "layout": layout_state(sess), "contextual": wordcraft_engine::ribbon::contextual_tabs(sess)})
}

/// A number the UI may show: finite, else null (a document can carry anything).
fn num(v: f32) -> Value {
    if v.is_finite() { json!(v) } else { Value::Null }
}

/// What the Layout tab's menus mark as current: the paragraph at the caret (indents and spacing in
/// points, line spacing as a multiple when it is one) and its section (orientation, paper,
/// margins and columns presets, as the engine names them; null when none matches).
fn layout_state(sess: &Session) -> Value {
    let para = sess.doc.para_at(&sess.sel.focus).map(|p| sess.doc.styles.resolve_para(&p.props));
    let sect = wordcraft_engine::cmd::page::sect(sess);
    let multiple = para.as_ref().and_then(|r| match r.line_spacing {
        LineSpacing::Multiple(m) => Some(m),
        _ => None,
    });
    json!({
        "indentLeft": para.as_ref().map_or(Value::Null, |r| num(r.indent_left)),
        "indentRight": para.as_ref().map_or(Value::Null, |r| num(r.indent_right)),
        "firstLine": para.as_ref().map_or(Value::Null, |r| num(r.indent_first)),
        "spaceBefore": para.as_ref().map_or(Value::Null, |r| num(r.space_before)),
        "spaceAfter": para.as_ref().map_or(Value::Null, |r| num(r.space_after)),
        "lineSpacing": multiple.map_or(Value::Null, num),
        "landscape": sect.landscape,
        "paper": wordcraft_geom::paper_name(sect.page_w, sect.page_h),
        "margins": wordcraft_engine::cmd::page::margins_preset_at(sess),
        "columns": wordcraft_engine::cmd::page::columns_preset_at(sess),
    })
}
