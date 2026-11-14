#!/usr/bin/env python3
"""Tests for egui2compose.py: `python3 scripts/test_egui2compose.py`.

The source reader is measured against the one engine whose true command list is in this
repository: WordCraft's own (`app/src/jvmTest/resources/engine-commands.json`, which
`cargo test -p loki-ffi` keeps in step with the engine).
"""
import json
import os
import sys
import tempfile
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import egui2compose as e  # noqa: E402

REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
FIXTURE = os.path.join(HERE, "..", "app", "src", "jvmTest", "resources", "engine-commands.json")


class Lexing(unittest.TestCase):
    def test_strings_raw_strings_and_escapes(self):
        self.assertEqual(e.string_value('"a\\"b\\n"'), 'a"b\n')
        self.assertEqual(e.string_value('r#"{"k": "v"}"#'), '{"k": "v"}')
        self.assertEqual(e.string_value('"\\u{2026}"'), "…")
        self.assertEqual(e.string_value('"one \\\n      two"'), "one two")
        self.assertIsNone(e.string_value('"a" + "b"'))
        self.assertIsNone(e.string_value("name"))
        self.assertEqual(e.first_string('t("Label")'), "Label")
        self.assertEqual(e.string_list('&["File", "New"]'), ["File", "New"])
        self.assertIsNone(e.string_list("&[A, B]"))

    def test_comments_go_and_strings_stay(self):
        src = 'a // "x.y", "Not Code"\nb /* "p.q" */ c "kept // here"'
        out = e.blank_comments(src)
        self.assertEqual(len(out), len(src))
        self.assertNotIn("Not Code", out)
        self.assertNotIn("p.q", out)
        self.assertIn("kept // here", out)

    def test_arguments_split_only_at_the_top_level(self):
        self.assertEqual(e.split_args('"a, b", [1, 2], f(x, y), |s, p| g(s, p)'), ['"a, b"', "[1, 2]", "f(x, y)", "|s", "p| g(s, p)"])
        self.assertEqual(e.split_args('"{\\"k\\": 1}", \'}\', x'), ['"{\\"k\\": 1}"', "'}'", "x"])

    def test_test_modules_are_dropped(self):
        src = 'fn a() {}\n#[cfg(test)]\nmod tests { fn t() { cmd!("x.y", "Fake", [], None, "{}", a, b); } }\nfn b() {}'
        out = e.drop_test_modules(src)
        self.assertNotIn("Fake", out)
        self.assertIn("fn b()", out)

    def test_hostile_source_never_raises(self):
        for junk in ["", '"', 'r#"', "(((", "CommandSpec {", 'cmd!("a.b", "L"', "/* /* */", "'", "fn f(", "\x00\x00", 'CommandSpec::new("a", "B").params(']:
            e.blank_comments(junk)
            e.drop_test_modules(junk)
            e.split_args(junk)
            e.const_tables(junk)
            e.scan_function_widgets(junk, set())


class Declarations(unittest.TestCase):
    def scan(self, files: dict) -> dict:
        with tempfile.TemporaryDirectory() as d:
            for name, text in files.items():
                p = os.path.join(d, name)
                os.makedirs(os.path.dirname(p), exist_ok=True)
                with open(p, "w", encoding="utf-8") as f:
                    f.write(text)
            return {c["id"]: c for c in e.scan_commands(d, [])}

    def test_the_forms_the_craft_engines_use(self):
        got = self.scan({
            "cmd.rs": '''
                pub struct CommandSpec { pub id: &'static str }
                macro_rules! cmd { ($id:literal, $label:literal, [$($m:literal),*], $sc:expr, $p:literal, $en:expr, $run:expr) => { CommandSpec { id: $id, label: $label } }; }
                macro_rules! toggle { ($id:literal, $label:literal, [$($m:literal),*], $sc:expr, $f:ident) => { cmd!(noundo $id, $label, [$($m),*], $sc, "{value?: bool}", always, f) }; }
                const ALIGN: &[&str] = &["Object", "Align"];
                fn spec(id: &'static str, label: &'static str, menu: &'static [&'static str]) -> CommandSpec { todo() }
                const fn row(key: &'static str, label: &'static str) -> Row { todo() }
                fn all() -> Vec<CommandSpec> { vec![
                    cmd!("edit.copy", "Copy", ["Edit"], Some("Cmd+C"), "{}", has_doc, copy),
                    cmd!(query "doc.info", "Document Info", [], None, "{} → {pages}", always, info),
                    toggle!("view.grid", "Show Grid", ["View"], Some("Cmd+'"), grid),
                    spec("object.alignLeft", "Align Left", ALIGN),
                    CommandSpec { id: "file.open", label: "Open…", menu: &["File"], shortcut: Some("Cmd+O"), params: r#"{"path":str}"#, enabled: always, run: open, journal: false },
                    CommandSpec::new("layout.margins", "Margins", "Layout › Page Setup", run).params(r#"{"value": "normal|narrow"}"#,).key(mac_or("Cmd+M", "Ctrl+M")).pure(),
                    row("general.language", "Language"),
                ] }
                fn test_like() { run(&mut s, "edit.copy", json!({})); attrs("style.create", "para", p); }
            ''',
            "tests.rs": 'fn t() { cmd!("fake.one", "Fake", [], None, "{}", a, b); }',
        })
        self.assertEqual(sorted(got), ["doc.info", "edit.copy", "file.open", "layout.margins", "object.alignLeft", "view.grid"])
        self.assertEqual(got["edit.copy"]["menu"], ["Edit"])
        self.assertEqual(got["edit.copy"]["shortcut"], "Cmd+C")
        self.assertFalse(got["doc.info"]["mutates"])
        self.assertEqual(got["object.alignLeft"]["menu"], ["Object", "Align"])   # a constant, resolved
        self.assertEqual(got["file.open"]["params"], '{"path":str}')
        self.assertFalse(got["file.open"]["mutates"])
        self.assertEqual(got["layout.margins"]["menu"], ["Layout", "Page Setup"])
        self.assertEqual(got["layout.margins"]["shortcut"], "Ctrl+M")            # not the Mac's
        self.assertFalse(got["layout.margins"]["mutates"])
        # A macro's own body is not expanded: the toggle's params are not known, and say so.
        self.assertFalse(got["view.grid"]["paramsKnown"])

    def test_widgets_are_read_in_order_with_labels_and_keys(self):
        body = '''{
            ui.label("Radius:");
            ui.add(egui::DragValue::new(&mut self.opts.blur_radius));
            ui.checkbox(&mut self.opts.preview, t("Preview"));
            ui.add(egui::Slider::new(&mut d.amount, 0.0..=100.0).text("Amount"));
            egui::ComboBox::from_label("Mode").show_ui(ui, |ui| {
                ui.selectable_value(&mut d.mode, Mode::Normal, "Normal");
                ui.selectable_value(&mut d.mode, Mode::Multiply, "Multiply");
            });
            ui.radio_value(&mut d.unit, Unit::Px, "Pixels");
            ui.radio_value(&mut d.unit, Unit::Pt, "Points");
            ui.text_edit_singleline(&mut d.name);
            if ui.button("Apply").clicked() { app.run("filter.blur", json!({"radius": r})); }
        }'''
        got = e.scan_function_widgets(body, {"filter.blur"})
        kinds = [(w["kind"], w["label"], w["key"]) for w in got["widgets"]]
        self.assertEqual(kinds[0], ("number", "Radius", "blurRadius"))
        self.assertEqual(kinds[1], ("checkbox", "Preview", "preview"))
        self.assertEqual(kinds[2][:2], ("slider", "Amount"))
        self.assertEqual((got["widgets"][2]["min"], got["widgets"][2]["max"]), ("0.0", "100.0"))
        mode = got["widgets"][3]
        self.assertEqual((mode["kind"], mode["label"], [o["label"] for o in mode["options"]]), ("choice", "Mode", ["Normal", "Multiply"]))
        unit = got["widgets"][4]
        self.assertEqual((unit["kind"], unit["key"], [o["value"] for o in unit["options"]]), ("choice", "unit", ["Px", "Pt"]))
        self.assertEqual(got["widgets"][-1].get("command"), "filter.blur")
        self.assertEqual(got["commands"], ["filter.blur"])


class Kotlin(unittest.TestCase):
    def test_strings_and_names_are_safe_kotlin(self):
        self.assertEqual(e.kt('a"b$c\\d\ne'), '"a\\"b\\$c\\\\d\\ne"')
        self.assertEqual(e.ident("blend-options", upper=True), "BlendOptions")
        self.assertEqual(e.ident("3d_view", upper=True), "N3dView")
        self.assertEqual(e.ident("class"), "class_")
        self.assertEqual(e.ident("!!!"), "x")


@unittest.skipUnless(os.path.isdir(os.path.join(REPO, "crates", "engine", "src")) and os.path.isfile(FIXTURE), "needs the WordCraft engine source and the command fixture")
class AgainstWordCraft(unittest.TestCase):
    """The source reader against the engine's own listing. These floors may rise, not fall."""

    @classmethod
    def setUpClass(cls):
        with open(FIXTURE, encoding="utf-8") as f:
            cls.true = {c["id"]: c for c in json.load(f)}
        cls.read = {c["id"]: c for c in e.scan_commands(os.path.join(REPO, "crates", "engine", "src"), [])}

    def test_every_command_is_found_and_nothing_else(self):
        self.assertEqual(sorted(set(self.true) - set(self.read)), [], "commands the reader missed")
        self.assertEqual(sorted(set(self.read) - set(self.true)), [], "things the reader took for commands")

    def test_what_is_read_about_each_is_right(self):
        n = len(self.true)
        same = lambda f: sum(1 for i in self.true if i in self.read and f(self.true[i], self.read[i]))
        self.assertEqual(same(lambda t, r: t["label"] == r["label"]), n)
        self.assertEqual(same(lambda t, r: t["location"] == " › ".join(r["menu"])), n)
        self.assertEqual(same(lambda t, r: t["shortcut"] == r["shortcut"]), n)
        self.assertEqual(same(lambda t, r: t["mutates"] == r["mutates"]), n)
        # Params docs built with `concat!` or a function are not read; nearly all are literals.
        self.assertGreaterEqual(same(lambda t, r: t["params"].strip() == r["params"].strip()), n * 99 // 100)

    def test_a_project_is_written_from_the_engines_own_listing(self):
        with tempfile.TemporaryDirectory() as out:
            self.assertEqual(e.main(["--repo", REPO, "--commands", FIXTURE, "--out", out, "--max-dialogs", "5"]), 0)
            for rel in ("REPORT.md", "ui-model.json", "settings.gradle.kts", "app/build.gradle.kts", "app/src/jvmTest/resources/engine-commands.json"):
                self.assertTrue(os.path.isfile(os.path.join(out, rel)), rel)
            with open(os.path.join(out, "ui-model.json"), encoding="utf-8") as f:
                model = json.load(f)
            self.assertEqual(len(model["commands"]), len(self.true))
            self.assertEqual(model["tabs"][:4], ["File", "Home", "Insert", "Draw"])   # the front end's order, not the engine's
            self.assertTrue(model["uiRequests"] and model["headless"])
            self.assertEqual(len(model["dialogs"]), 5)


if __name__ == "__main__":
    unittest.main()
