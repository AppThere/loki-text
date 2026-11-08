//! The Navigation pane's data: the document's headings and search results with context.

use serde_json::{Value, json};
use wordcraft_doc::numbering::{Counters, LevelSuffix};
use wordcraft_doc::section::NumFormat;
use wordcraft_doc::{Document, Path, StoryRef};

use super::pos_json;
use crate::{CmdResult, CommandSpec, Session};

/// The most results the pane lists.
const MAX_RESULTS: usize = 500;

/// Characters of context kept before and after a match.
const BEFORE: usize = 30;
const AFTER: usize = 40;

pub fn specs() -> Vec<CommandSpec> {
    vec![
        CommandSpec::new("nav.headings", "Document Headings", "View › Show", |s, _| Ok(headings_json(s)))
            .params(r#"(no params) → {"headings": [{"pos": Pos, "level": 1-9, "text": string (a numbered heading starts with its number)}], "current": index of the heading the caret is under | null}"#)
            .pure(),
        CommandSpec::new("nav.search", "Search Results", "View › Show", search_results)
            .params(r#"{"text": string, …options of edit.find} → {"count": n, "results": [{"start": Pos, "end": Pos, "before": string, "hit": string, "after": string}]} (at most 500, in document order; the matches are also highlighted as by edit.advancedFind)"#)
            .pure(),
    ]
}

/// The body's headings (paragraphs with an outline level) in document order, as (path, level,
/// text); the level is 0 for Heading 1. A numbered heading starts with the number it shows (`2.1 Method`), as in Word.
pub fn headings(doc: &Document) -> Vec<(Path, u8, String)> {
    // Every numbered paragraph advances the list counters, headings or not.
    let mut counters = Counters::default();
    let mut items = Vec::new();
    for path in doc.para_paths(StoryRef::Body) {
        let Some(p) = doc.para(StoryRef::Body, &path) else { continue };
        let rp = doc.styles.resolve_para(&p.props);
        let number = p
            .props
            .numbering
            .or(rp.numbering)
            .filter(|n| n.num != 0)
            .and_then(|n| counters.next_label(&doc.numbering, n.num, n.level))
            .filter(|(label, def)| !label.is_empty() && !matches!(def.format, NumFormat::Bullet | NumFormat::None));
        if let Some(lv) = rp.outline_level {
            let txt = p.plain_text().trim().to_string();
            if txt.is_empty() || rp.style.starts_with("TOC") {
                continue;
            }
            let txt = match number {
                Some((label, def)) if def.suffix == LevelSuffix::Nothing => format!("{label}{txt}"),
                Some((label, _)) => format!("{label} {txt}"),
                None => txt,
            };
            items.push((path, lv, txt));
        }
    }
    items
}

fn headings_json(s: &Session) -> Value {
    let items = headings(&s.doc);
    let caret = &s.sel.focus;
    let current = if caret.story == StoryRef::Body { items.iter().rposition(|(path, ..)| *path <= caret.path) } else { None };
    let list: Vec<Value> = items
        .into_iter()
        .map(|(path, level, text)| json!({"pos": pos_json(&wordcraft_doc::Pos { story: StoryRef::Body, path, off: 0 }), "level": u16::from(level) + 1, "text": text}))
        .collect();
    json!({"headings": list, "current": current})
}

fn search_results(s: &mut Session, v: &Value) -> CmdResult {
    s.run("edit.advancedFind", v)?;
    let total = s.find.results.len();
    let results: Vec<Value> = s
        .find
        .results
        .iter()
        .take(MAX_RESULTS)
        .filter_map(|(a, b)| {
            let p = s.doc.para_at(a)?;
            let from = p.clamp(a.off.saturating_sub(BEFORE));
            let to = p.clamp(b.off.saturating_add(AFTER).min(p.len()));
            // `get` and not an index: a match across paragraphs ends past this one.
            let (pre, hit, post) =
                (p.text.get(from..a.off)?, p.text.get(a.off..b.off.min(p.len())).unwrap_or(""), p.text.get(b.off.min(p.len())..to).unwrap_or(""));
            Some(json!({"start": pos_json(a), "end": pos_json(b), "before": pre, "hit": hit, "after": post}))
        })
        .collect();
    Ok(json!({"count": total, "results": results}))
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    fn session() -> crate::Session {
        crate::Session::new(wordcraft_doc::Document::from_text("Intro\nThe quick brown fox\nMethod\nThe fox again"))
    }

    #[test]
    fn headings_follow_styles_and_the_caret() {
        let mut s = session();
        for (para, style) in [(0, "Heading1"), (2, "Heading2")] {
            s.run("caret.set", &json!({"pos": {"story": "body", "path": [para], "off": 0}})).unwrap();
            s.run("para.style", &json!({"style": style})).unwrap();
        }
        s.run("caret.set", &json!({"pos": {"story": "body", "path": [3], "off": 2}})).unwrap();
        let r = s.run("nav.headings", &json!({})).unwrap();
        let hs = r["headings"].as_array().unwrap();
        assert_eq!(hs.len(), 2);
        assert_eq!((hs[0]["level"].as_u64(), hs[0]["text"].as_str()), (Some(1), Some("Intro")));
        assert_eq!(hs[1]["level"], 2);
        // The caret is under "Method".
        assert_eq!(r["current"], 1);
    }

    #[test]
    fn search_results_carry_context_and_cap() {
        let mut s = session();
        let r = s.run("nav.search", &json!({"text": "fox"})).unwrap();
        assert_eq!(r["count"], 2);
        let first = &r["results"][0];
        assert_eq!((first["before"].as_str(), first["hit"].as_str(), first["after"].as_str()), (Some("The quick brown "), Some("fox"), Some("")));
        assert_eq!(r["results"][1]["before"], "The ");
        assert_eq!(r["results"][1]["after"], " again");
        let none = s.run("nav.search", &json!({"text": "zzz"})).unwrap();
        assert_eq!((none["count"].as_u64(), none["results"].as_array().map(Vec::len)), (Some(0), Some(0)));
    }
}
