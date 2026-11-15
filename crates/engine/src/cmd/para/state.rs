//! `para.state`: the Paragraph dialog's fields for the selected paragraphs.

use serde_json::{Map, Value, json};

use crate::{CmdResult, Session};

/// One paragraph as the Paragraph dialog shows it, keyed like `para.set` takes it: `align`,
/// `left` and `right` as seen on the page, spacing in points, line spacing as a multiple.
fn fields_of(rp: &wordcraft_doc::resolve::ResolvedPara) -> Map<String, Value> {
    let line = match rp.line_spacing {
        wordcraft_doc::props::LineSpacing::Multiple(m) => m,
        _ => 1.0,
    };
    let (left, right) = rp.visual_indents();
    let v = json!({
        "bidi": rp.bidi,
        "align": format!("{:?}", rp.align.visual(rp.bidi)).to_lowercase(),
        "left": left, "right": right, "indentFirst": rp.indent_first,
        "spaceBefore": rp.space_before, "spaceAfter": rp.space_after,
        "lineSpacing": {"rule": "multiple", "value": line},
        "keepNext": rp.keep_next, "keepLines": rp.keep_lines, "pageBreakBefore": rp.page_break_before, "widowControl": rp.widow_control,
        "mirrorIndents": rp.mirror_indents, "contextualSpacing": rp.contextual_spacing,
        "suppressLineNumbers": rp.suppress_line_numbers, "suppressHyphens": rp.suppress_hyphens,
    });
    match v {
        Value::Object(m) => m,
        _ => Map::new(),
    }
}

/// What every selected paragraph shares, with `null` for a field they differ in (the dialog shows
/// it blank and leaves it alone). Read only.
pub(super) fn para_state(s: &mut Session, _: &Value) -> CmdResult {
    let story = s.sel.focus.story;
    let mut each = crate::cmd::selected_paths(s)
        .into_iter()
        .filter_map(|path| s.doc.para(story, &path))
        .map(|p| fields_of(&s.doc.styles.resolve_para(&p.props)))
        .collect::<Vec<_>>();
    if each.is_empty()
        && let Some(p) = s.doc.para_at(&s.sel.focus)
    {
        each.push(fields_of(&s.doc.styles.resolve_para(&p.props)));
    }
    let mut it = each.into_iter();
    let Some(mut shared) = it.next() else { return Ok(json!({})) };
    for other in it {
        for (k, v) in shared.iter_mut() {
            if other.get(k) != Some(&*v) {
                *v = Value::Null;
            }
        }
    }
    Ok(Value::Object(shared))
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    #[test]
    fn mixed_fields_are_null_and_shared_ones_are_kept() {
        let mut s = crate::Session::new(wordcraft_doc::Document::default());
        s.run("text.insert", &json!({"text": "One"})).unwrap();
        s.run("text.newParagraph", &json!({})).unwrap();
        s.run("text.insert", &json!({"text": "Two"})).unwrap();
        s.run("select.all", &json!({})).unwrap();
        s.run("para.alignCenter", &json!({})).unwrap();
        let st = s.run("para.state", &json!({})).unwrap();
        assert_eq!(st["align"], "center");
        // Differ in one paragraph only.
        s.run("caret.docStart", &json!({})).unwrap();
        s.run("para.set", &json!({"props": {"spaceAfter": 30.0}})).unwrap();
        s.run("select.all", &json!({})).unwrap();
        let st = s.run("para.state", &json!({})).unwrap();
        assert_eq!(st["align"], "center");
        assert!(st["spaceAfter"].is_null());
        assert_eq!(st["lineSpacing"]["rule"], "multiple");
    }
}
