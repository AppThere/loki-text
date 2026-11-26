//! What the app's own dialogs read before they open, where no engine command answers it: the
//! paragraph's drop cap (`loki.dropCap`), its tab stops, borders and shading (`loki.paraLines`)
//! and today's date in each Date and Time format (`loki.dateFormats`). All only read, so no undo
//! step and no dirty flag. And how a dialog that needs several commands runs them as one undo step
//! (`loki.runSteps`).

use serde_json::{Value, json};
use wordcraft_engine::Session;
use wordcraft_engine::cmd::insert::{drop_cap_state, format_date_in};
use wordcraft_mcp::{Backend, Headless};

/// Date and time pictures (Word field pictures) offered for English (United States); the desktop
/// front end's list (`ui-egui` `dialogs_insert::field::DATE_FORMATS`).
const EN_US_FORMATS: &[&str] = &[
    "M/d/yyyy",
    "dddd, MMMM d, yyyy",
    "MMMM d, yyyy",
    "M/d/yy",
    "yyyy-MM-dd",
    "d-MMM-yy",
    "M.d.yyyy",
    "d MMMM yyyy",
    "MMMM yy",
    "MMM-yy",
    "M/d/yyyy h:mm am/pm",
    "M/d/yyyy h:mm:ss am/pm",
    "h:mm am/pm",
    "h:mm:ss am/pm",
    "HH:mm",
    "HH:mm:ss",
];

/// The same for Portuguese (Brazil); the desktop's `PT_DATE_FORMATS`.
const PT_BR_FORMATS: &[&str] = &[
    "dd/MM/yyyy",
    "dddd, d 'de' MMMM 'de' yyyy",
    "d 'de' MMMM 'de' yyyy",
    "dd/MM/yy",
    "yyyy-MM-dd",
    "d-MMM-yy",
    "dd.MM.yyyy",
    "d MMMM yyyy",
    "MMM-yy",
    "dd/MM/yyyy HH:mm",
    "dd/MM/yyyy HH:mm:ss",
    "HH:mm",
    "HH:mm:ss",
];

/// `{language, formats: [{format, sample}]}`: each picture with now written in it. `language` is
/// `pt-BR` for any Portuguese tag, else `en-US` (the languages `insert.dateTime` names).
pub(crate) fn date_formats(params: &Value) -> Value {
    let asked = params.get("language").and_then(Value::as_str).unwrap_or("");
    let portuguese = asked.trim().get(..2).is_some_and(|p| p.eq_ignore_ascii_case("pt"));
    let (language, list) = if portuguese { ("pt-BR", PT_BR_FORMATS) } else { ("en-US", EN_US_FORMATS) };
    let formats: Vec<Value> = list.iter().map(|f| json!({"format": f, "sample": format_date_in(f, Some(language))})).collect();
    json!({"language": language, "formats": formats})
}

/// The caret paragraph's drop cap as `insert.dropCap` takes it: `{position, lines, distance, font,
/// textFont}`; `font` is null when the letter has no font of its own.
pub(crate) fn drop_cap(s: &Session) -> Value {
    let st = drop_cap_state(s);
    json!({
        "position": st.position,
        "lines": st.lines,
        "distance": st.distance,
        "font": st.font,
        "textFont": st.text_font,
    })
}

/// The caret paragraph's tab stops, borders and shading, as the Tabs and the Borders and Shading
/// dialogs open with them: `{tabs: [{pos, align, leader}], defaultTab, borders: {top, left, bottom,
/// right, between: {style, width, color} | null}, shading: "RRGGBB" | null}`. The stops are the
/// ones in force, the style's among them.
pub(crate) fn para_lines(s: &Session) -> Value {
    let rp = s.doc.para_at(&s.sel.focus).map(|p| s.doc.styles.resolve_para(&p.props));
    let tabs: Vec<Value> = rp
        .iter()
        .flat_map(|r| &r.tabs)
        .filter(|t| t.pos.is_finite())
        .filter_map(|t| serde_json::to_value(t).ok())
        .filter(|t| t["align"] != "clear")
        .collect();
    let borders = rp.as_ref().and_then(|r| r.borders).unwrap_or_default();
    let side = |b: Option<_>| {
        b.filter(wordcraft_doc::props::Border::is_visible)
            .map(|b| json!({"style": b.style.ooxml(), "width": b.width, "color": b.color.map(|c| c.hex())}))
    };
    json!({
        "tabs": tabs,
        "defaultTab": s.doc.settings.default_tab,
        "borders": {
            "top": side(borders.top),
            "left": side(borders.left),
            "bottom": side(borders.bottom),
            "right": side(borders.right),
            "between": side(borders.between),
        },
        "shading": rp.and_then(|r| r.shading).map(|c| c.hex()),
    })
}

/// Most commands one dialog runs.
const MAX_STEPS: usize = 16;

/// `{steps: [{command, params?}]}` run in order as one undo step. If one fails, the document, the
/// selection and undo are put back as they were before the first, and its error is returned.
pub(crate) fn run_steps(headless: &mut Headless, params: &Value) -> Result<Value, String> {
    let steps = params.get("steps").and_then(Value::as_array).ok_or("steps is a list of {command, params}")?;
    if steps.len() > MAX_STEPS {
        return Err(format!("at most {MAX_STEPS} steps"));
    }
    headless.session.close_typing();
    let snapshot = headless.session.edit_snapshot();
    let start = headless.session.undo_mark();
    for step in steps {
        // Only a step this run made is joined: after commands that changed nothing, the next
        // one starts its own rather than joining what the person did before.
        if headless.session.undo_mark() != start {
            headless.session.join_next_undo();
        }
        if let Err(e) = headless.call("engine.execute", step.clone()) {
            headless.session.restore(snapshot);
            return Err(e.to_string());
        }
    }
    Ok(Value::Null)
}
