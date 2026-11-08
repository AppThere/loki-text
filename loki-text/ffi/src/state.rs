//! What the ribbon needs to draw its buttons: which commands can't run right now (and why) and
//! which toggles are on. Read from the engine's own state, the same sources the egui ribbon uses.

use serde_json::{Map, Value, json};
use wordcraft_doc::props::{HeightRule, LineSpacing, VAlign};
use wordcraft_doc::{Align, StyleKind};
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
    ("commentsPane", "view.commentsPane"),
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
    let lists = wordcraft_engine::cmd::para::list_buttons(&sess.doc, rp.as_ref().and_then(|r| r.numbering));
    if lists.bullets {
        on.push("para.bullets");
    }
    if lists.numbering {
        on.push("para.numbering");
    }
    if lists.multilevel {
        on.push("para.multilevel");
    }
    let table = table_state(sess, rp.as_ref().map(|r| r.align));
    if table.get("gridlines").and_then(Value::as_bool) == Some(true) {
        on.push("table.viewGridlines");
    }
    if table.get("repeatHeader").and_then(Value::as_bool) == Some(true) {
        on.push("table.repeatHeader");
    }
    if table.get("rtl").and_then(Value::as_bool) == Some(true) {
        on.push("table.rtl");
    }
    json!({
        "disabled": disabled,
        "on": on,
        "layout": layout_state(sess),
        "table": table,
        "picture": picture_state(sess),
        "arrange": arrange_state(sess),
        "styleAt": style_at(sess),
        "contextual": wordcraft_engine::ribbon::contextual_tabs(sess),
    })
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

/// What the Table Design and Table Layout tabs mark as current, for the table at the caret (null
/// outside a table): its style and Table Style Options, the caret row's height (null when the text
/// sets it) and rule, the caret column's width, the cell alignment as `table.cellAlign` names it
/// (null for justified text), the toggles, and the document's table styles for the gallery.
fn table_state(sess: &Session, para_align: Option<Align>) -> Value {
    let Some((tp, r, c)) = sess.sel.focus.path.cell() else { return Value::Null };
    let Some(t) = sess.doc.table(sess.sel.focus.story, &tp) else { return Value::Null };
    let row = t.rows.get(r);
    let cell = row.and_then(|x| x.cells.get(c));
    let l = &t.props.look;
    let v = match cell.and_then(|x| x.props.valign).unwrap_or_default() {
        VAlign::Top => "top",
        VAlign::Center => "center",
        VAlign::Bottom => "bottom",
    };
    let h = match para_align {
        Some(Align::Left) => Some("Left"),
        Some(Align::Center) => Some("Center"),
        Some(Align::Right) => Some("Right"),
        _ => None,
    };
    let cell_align = h.map(|h| if v == "center" && h == "Center" { "center".to_string() } else { format!("{v}{h}") });
    let mut styles: Vec<&wordcraft_doc::Style> = sess.doc.styles.styles.iter().filter(|s| s.kind == StyleKind::Table && !s.hidden).collect();
    // The document's own styles first, then the built-in ones, each by name.
    styles.sort_by(|a, b| a.builtin.cmp(&b.builtin).then_with(|| a.name.cmp(&b.name)));
    json!({
        "style": t.props.style,
        "look": {
            "headerRow": l.header_row,
            "totalRow": l.total_row,
            "bandedRows": l.banded_rows,
            "firstColumn": l.first_column,
            "lastColumn": l.last_column,
            "bandedColumns": l.banded_columns,
        },
        "rowHeight": row.and_then(|x| x.props.height).map_or(Value::Null, num),
        "exact": row.is_some_and(|x| x.props.height_rule == HeightRule::Exact),
        "columnWidth": t.grid.get(t.grid_col(r, c)).map_or(Value::Null, |w| num(*w)),
        "cellAlign": cell_align,
        "gridlines": sess.view.table_gridlines,
        "repeatHeader": row.is_some_and(|x| x.props.header),
        "rtl": t.props.bidi_visual,
        "styles": styles.iter().map(|s| json!({"id": s.id, "name": s.name})).collect::<Vec<_>>(),
        // For the dialogs: the table's shape and settings, and the caret row's and cell's.
        "columns": t.grid.len(),
        "align": match t.props.align {
            Some(Align::Center) => "center",
            Some(Align::Right) => "right",
            _ => "left",
        },
        "width": t.props.width.map_or(Value::Null, num),
        "indent": t.props.indent.map_or(Value::Null, num),
        "allowBreak": !row.is_some_and(|x| x.props.cant_split),
        "cellWidth": cell.and_then(|x| x.props.width).map_or(Value::Null, num),
        "valign": v,
        "cellMargins": cell_margins(sess, t).map(num),
        "altTitle": t.props.caption,
        "altText": t.props.description,
    })
}

/// The table's default cell margins in points as `[top, left, bottom, right]`: its own, else its
/// style's, else Word's default (`table.cellMargins` starts from the same values).
fn cell_margins(sess: &Session, t: &wordcraft_doc::Table) -> [f32; 4] {
    let style = t.props.style.as_deref().and_then(|id| sess.doc.styles.table_style(id));
    t.props.cell_margins.or(style.and_then(|st| st.parts.cell_margins)).unwrap_or([0.0, 5.4, 0.0, 5.4])
}

/// The selected picture for Picture Format (null when none): its display size in points, its crop
/// as fractions (left, top, right, bottom), and its alt text, title and decorative mark.
fn picture_state(sess: &Session) -> Value {
    let Some((_, o)) = wordcraft_engine::cmd::objects::selected(sess) else { return Value::Null };
    let wordcraft_doc::para::InlineObject::Image { w, h, crop, float, .. } = &o else { return Value::Null };
    json!({
        "width": num(*w),
        "height": num(*h),
        "crop": crop.iter().map(|c| num(*c)).collect::<Vec<_>>(),
        "alt": o.alt_text(),
        "title": float.alt.title,
        "decorative": float.alt.decorative,
    })
}

/// The selected picture, shape, text box or group for the Arrange controls (null when none): its
/// wrapping as `arrange.wrap` names it, and whether it moves with its paragraph.
fn arrange_state(sess: &Session) -> Value {
    use wordcraft_doc::para::{Anchor, Wrap};
    let Some((_, o)) = wordcraft_engine::cmd::objects::selected(sess) else { return Value::Null };
    let Some((_, _, f)) = o.frame() else { return Value::Null };
    let wrap = match f.wrap {
        Wrap::Inline => "inline",
        Wrap::Square => "square",
        Wrap::Tight => "tight",
        Wrap::Through => "through",
        Wrap::TopAndBottom => "topAndBottom",
        Wrap::BehindText => "behindText",
        Wrap::InFrontOfText => "inFrontOfText",
    };
    json!({"wrap": wrap, "moveWithText": f.v_rel == Anchor::Paragraph})
}

/// The styles at the caret for the Styles gallery: the paragraph's (its document's default
/// paragraph style when it names none) and, when the text there has one, the character style.
fn style_at(sess: &Session) -> Value {
    let Some(p) = sess.doc.para_at(&sess.sel.focus) else { return Value::Null };
    let styles = &sess.doc.styles.styles;
    let default_of = |kind: StyleKind| styles.iter().find(|s| s.kind == kind && s.default).map(|s| s.id.clone());
    let para = p.props.style.clone().or_else(|| default_of(StyleKind::Paragraph)).unwrap_or_else(|| "Normal".into());
    // The default character style (Default Paragraph Font) is no style of its own.
    let chr = p.props_at(sess.sel.focus.off).style.clone().filter(|id| Some(id) != default_of(StyleKind::Character).as_ref());
    json!({"paragraph": para, "character": chr})
}
