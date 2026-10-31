//! What the ribbon needs to draw its buttons: which commands can't run right now (and why) and
//! which toggles are on. Read from the engine's own state, the same sources the egui ribbon uses.

use serde_json::{Map, Value, json};
use wordcraft_doc::Align;
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
    json!({"disabled": disabled, "on": on})
}
