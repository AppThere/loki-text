//! What the app's own dialogs read before they open, where no engine command answers it: the
//! paragraph's drop cap (`loki.dropCap`) and today's date in each Date and Time format
//! (`loki.dateFormats`). Both only read, so no undo step and no dirty flag.

use serde_json::{Value, json};
use wordcraft_engine::Session;
use wordcraft_engine::cmd::insert::{drop_cap_state, format_date_in};

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
