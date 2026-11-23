//! What is under a pointer, for the gestures the engine drives with page positions: the text
//! position and line there, the selection bar, a text box, and the table's border, handle,
//! insert button or selection area. The engine's commands take the positions these return (or
//! the page point itself); the hit-testing lives in the layout, which has no command, so the
//! Kotlin front end asks here before it starts a drag.

use serde_json::{Value, json};
use wordcraft_doc::StoryRef;
use wordcraft_engine::Session;
use wordcraft_engine::cmd::table_mouse::{self, Spot};

/// Page points to look at: a point on a page, or none when a number is missing or not finite.
fn point(v: &Value) -> Option<(usize, f32, f32)> {
    let num = |k: &str| v.get(k).and_then(Value::as_f64).filter(|n| n.is_finite()).map(|n| n as f32);
    let page = usize::try_from(v.get("page").and_then(Value::as_u64)?).ok()?;
    Some((page, num("x")?, num("y")?))
}

/// `loki.hit`: `{page, x, y, story?}` → `{pos, line, bar, textBox}` (each a position or null):
/// the nearest text position (in `story`, default the caret's), the body line level with `y`,
/// the line whose selection bar holds the point, and the text box under it.
pub fn hit(sess: &mut Session, v: &Value) -> Value {
    let Some((page, x, y)) = point(v) else { return json!({"pos": null, "line": null, "bar": null, "textBox": null}) };
    let story = v.get("story").and_then(|s| serde_json::from_value::<StoryRef>(s.clone()).ok()).unwrap_or(sess.sel.focus.story);
    let l = sess.layout();
    let text_box =
        l.find_object(page, |o| o.page == page && o.text_box.is_some() && o.rect.contains(wordcraft_geom::Point::new(x, y))).map(|o| o.pos());
    json!({
        "pos": l.hit(page, x, y, story),
        "line": l.body_line_at(page, y),
        "bar": l.selection_bar_at(page, x, y),
        "textBox": text_box,
    })
}

/// `loki.tableSpot`: `{page, x, y, reach?}` → what a press there grabs in a table of the caret's
/// story: `{kind: "resize"}` on a column or row border or the resize handle (`table.resizeDrag`,
/// `table.fitColumn`), else `"handle"` (the move handle; `movable` says whether a drag moves
/// it), `"insertRow"`, `"insertColumn"`, `"column"`, `"row"`, `"cell"` (selection areas), or
/// `{kind: null}`.
pub fn table_spot(sess: &mut Session, v: &Value) -> Value {
    let none = json!({"kind": null});
    let Some((page, x, y)) = point(v) else { return none };
    let reach = v.get("reach").and_then(Value::as_f64).filter(|n| n.is_finite()).map_or(3.0, |n| n as f32);
    let story = sess.sel.focus.story;
    let l = sess.layout();
    let doc = &sess.doc;
    if wordcraft_engine::cmd::table_resize::resize_target(&l, doc, page, x, y, reach).is_some() {
        return json!({"kind": "resize"});
    }
    let Some(t) = table_mouse::target_at(&l, doc, story, page, x, y, reach) else { return none };
    let kind = match t.spot {
        Spot::Handle => "handle",
        Spot::InsertRow(_) => "insertRow",
        Spot::InsertColumn(_) => "insertColumn",
        Spot::Column(_) => "column",
        Spot::Row(_) => "row",
        Spot::Cell(..) => "cell",
    };
    json!({"kind": kind, "movable": table_mouse::movable(&t.geom)})
}
