#!/usr/bin/env python3
"""Tests for wire_dialogs.py: `python3 scripts/test_wire_dialogs.py`."""
import io
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import wire_dialogs as w  # noqa: E402

FIND_DOC = "{what, with, matchCase?, wholeCell?, all?: true, within?: sheet|workbook|selection} → {replaced, skipped?} (a note)"
SORT_DOC = "{range?, header?: bool, keys: [{column: \"B\", order: asc|desc, by?: values|cellColor|fontColor, customList?: [..]}], orientation?: rows|columns, matchCase?}"


def keys(doc):
    return {p.name: p for p in w.parse_doc(doc)}


class Doc(unittest.TestCase):
    def test_keys_optional_and_types(self):
        k = keys(FIND_DOC)
        self.assertEqual(list(k), ["what", "with", "matchCase", "wholeCell", "all", "within"])
        self.assertFalse(k["what"].optional)
        self.assertTrue(k["matchCase"].optional)
        self.assertEqual((k["within"].kind, k["within"].options), ("enum", ["sheet", "workbook", "selection"]))

    def test_a_list_of_objects_has_its_keys(self):
        k = keys(SORT_DOC)
        self.assertEqual(k["keys"].kind, "array")
        self.assertEqual([c.name for c in k["keys"].children], ["column", "order", "by", "customList"])
        self.assertEqual(k["header"].kind, "bool")

    def test_alternate_names_and_no_keys(self):
        self.assertEqual(list(keys("{ids | id, toggle?: bool}")), ["ids", "id", "toggle"])
        self.assertEqual(w.parse_doc("{}"), [])
        self.assertEqual(w.parse_doc("no braces here"), [])
        self.assertEqual(w.parse_doc("{unclosed, a"), [])

    def test_brackets_inside_strings_do_not_confuse_it(self):
        self.assertEqual(list(keys('{a?: "x}y", b?: bool}')), ["a", "b"])


DIALOG = '''package app.loki.x.dialogs

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
fun FindFindDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf("") }
    var f1 by remember { mutableStateOf(false) }
    var f2 by remember { mutableStateOf(false) }
    var f3 by remember { mutableStateOf(false) }
    SheetDialog(
        "Find find", "", onDismiss,
        buttons = {
            TextButton(onClick = {
                val params = buildJsonObject {
                if (f0.isNotBlank()) put("what", f0)
                put("case", f1)
                put("whole", f2)
                put("wb", f3)
                }
                onRun("edit.replace", params)
            }) { Text("OK") }
        },
    ) {
        Column {
            OutlinedTextField(f0, { f0 = it }, label = { Text("Find what") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().clickable { f1 = !f1 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f1, { f1 = it })
                Text("Match case")
            }
            Row(Modifier.fillMaxWidth().clickable { f2 = !f2 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f2, { f2 = it })
                Text("Match entire cell contents")
            }
            Row(Modifier.fillMaxWidth().clickable { f3 = !f3 }, verticalAlignment = Alignment.CenterVertically) {
                Checkbox(f3, { f3 = it })
                Text("Within workbook")
            }
        }
    }
}
'''

SORT = '''package app.loki.x.dialogs

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

fun DataSortDialog(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {
    var f0 by remember { mutableStateOf("") }
    var f1 by remember { mutableStateOf("") }
    var f2 by remember { mutableStateOf("") }
    SheetDialog(
        "Data sort", "", onDismiss,
        buttons = {
                val params = buildJsonObject {
                if (f0.isNotBlank()) put("col", f0)
                if (f1.isNotBlank()) put("by", f1)
                if (f2.isNotBlank()) put("desc", f2)
                }
                onRun("data.sort", params)
        },
    ) {
            OutlinedTextField(f0, { f0 = it }, label = { Text("Sort by") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("By", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f1 == "\\"values\\".into()", { f1 = "\\"values\\".into()" }, label = { Text("Cell Values") })
                FilterChip(f1 == "\\"cellColor\\".into()", { f1 = "\\"cellColor\\".into()" }, label = { Text("Cell Color") })
            }
            Text("Desc", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(f2 == "false", { f2 = "false" }, label = { Text("A to Z") })
                FilterChip(f2 == "true", { f2 = "true" }, label = { Text("Z to A") })
            }
    }
}
'''


def wired(src, doc, command, name="XDialog.kt"):
    with tempfile.TemporaryDirectory() as t:
        d = os.path.join(t, "app", "src", "commonMain", "kotlin", "app", "x", "dialogs")
        os.makedirs(d)
        path = os.path.join(d, name)
        open(path, "w").write(src)
        cmds = os.path.join(t, "c.json")
        import json
        json.dump([{"id": command, "label": "Command", "params": doc}], open(cmds, "w"))
        out = io.StringIO()
        stats = w.run(t, cmds, write=True, out=out)
        return open(path).read(), stats, out.getvalue()


class Wiring(unittest.TestCase):
    def test_names_labels_and_an_enum_flag_are_fixed(self):
        text, stats, _ = wired(DIALOG, FIND_DOC, "edit.replace")
        self.assertIn('put("what", f0)', text)
        self.assertIn('put("matchCase", f1)', text)
        self.assertIn('put("wholeCell", f2)', text)
        self.assertIn('if (f3) put("within", "workbook")', text)
        self.assertEqual((stats["renamed"], stats["ok"], stats["unknown"]), (3, 1, 0))

    def test_a_nested_list_a_rust_value_and_a_true_false_choice(self):
        text, stats, _ = wired(SORT, SORT_DOC, "data.sort")
        self.assertIn('put("keys", buildJsonArray {', text)
        self.assertIn('put("column", f0)', text)
        self.assertIn('put("order", if (f2 == "true") "desc" else "asc")', text)
        self.assertIn('f1 == "values"', text)
        self.assertNotIn(".into()", text)
        self.assertIn("import kotlinx.serialization.json.buildJsonArray", text)
        self.assertEqual(stats["nested"], 3)
        # The block lands inside buildJsonObject: before the brace that closes it, which comes before the call that runs the command.
        close = text.index('}\n                onRun("data.sort", params)')
        self.assertLess(text.index('put("keys", buildJsonArray {'), close)
        self.assertLess(text.index('put("column", f0)'), close)

    def test_it_is_idempotent(self):
        once, _, _ = wired(SORT, SORT_DOC, "data.sort")
        twice, stats, _ = wired(once, SORT_DOC, "data.sort")
        self.assertEqual(once, twice)

    def test_a_key_that_is_already_right_is_left_alone(self):
        src = DIALOG.replace('"case"', '"matchCase"').replace('"whole"', '"wholeCell"').replace('put("wb", f3)', 'if (f3) put("within", "workbook")')
        text, stats, _ = wired(src, FIND_DOC, "edit.replace")
        self.assertEqual(stats["renamed"], 0)

    def test_a_lookalike_inside_a_longer_word_is_not_a_match(self):
        # rot is not protection; path is not sample.
        f = w.Field(0, "slider", label="Orientation", key="rot")
        self.assertLess(w.score(f, w.Param("protection", True, "any")), w.CLEAR)
        g = w.Field(0, "text", label="Picture file path", key="path")
        self.assertLess(w.score(g, w.Param("sample", True, "text")), w.CLEAR)

    def test_an_abbreviation_matches_its_word(self):
        f = w.Field(0, "text", label="Name", key="docName")
        self.assertGreaterEqual(w.score(f, w.Param("documentName", True, "text")), w.CLEAR)

    def test_a_dialog_with_no_command_gets_the_one_its_title_names(self):
        src = DIALOG.replace('onRun("edit.replace", params)', "/* TODO: which command this dialog runs was not found */").replace('"Find find"', '"Command"')
        text, _, out = wired(src, FIND_DOC, "edit.replace")
        self.assertIn('onRun("edit.replace", params)', text)
        self.assertIn("title is its label", out)


class Check(unittest.TestCase):
    def test_choice_values_are_cleaned(self):
        self.assertEqual(w.clean_value('"values".into()'), "values")
        self.assertEqual(w.clean_value('String::from("x")'), "x")
        self.assertEqual(w.clean_value("true"), "true")


if __name__ == "__main__":
    unittest.main()
