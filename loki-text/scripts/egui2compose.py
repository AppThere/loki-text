#!/usr/bin/env python3
"""egui2compose: start a Compose Multiplatform front end for a Craft app from its egui one.

    scripts/egui2compose.py --repo ../../vecthere --out /tmp/vecthere-compose
    scripts/egui2compose.py --repo .. --commands commands.json --out /tmp/out --name "Loki Text"

It reads a Craft repository (crates/engine, crates/ui-egui) and writes a Gradle project on Loki Kit
(../kit) that compiles, runs against a recorded command list with no engine, and has a coverage
test. What it carries over:

  * the commands (id, label, menu path, shortcut, params doc), from `--commands` (the engine's own
    `engine.commands` JSON: always prefer it) or, without it, read from the engine's source;
  * the menu order, the panels, the tools and the dialogs the egui front end declares;
  * each dialog's widgets (check boxes, sliders, number and text boxes, choices, buttons), as a
    Compose dialog with the same controls in the same order;
  * the theme's colours.

What it cannot carry over is everything egui paints by hand: the canvas, the timeline, custom
widgets, and how a dialog's values reach its command. REPORT.md in the output says, per item,
what was translated and what is left, so the result is a start to finish by hand, not a port.

Python 3.9+, standard library only. It never runs code from the repository it reads.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from collections import OrderedDict

# --------------------------------------------------------------------------------------------
# Reading Rust source without running it
# --------------------------------------------------------------------------------------------

MAX_FILE_BYTES = 4 * 1024 * 1024  # a source file larger than this is generated data, not UI code


def read_text(path: str) -> str:
    try:
        if os.path.getsize(path) > MAX_FILE_BYTES:
            return ""
        with open(path, encoding="utf-8", errors="replace") as f:
            return f.read()
    except OSError:
        return ""


def is_test_path(path: str) -> bool:
    name = os.path.basename(path)
    parts = path.replace("\\", "/").split("/")
    return "tests" in parts or name == "tests.rs" or name.endswith("_tests.rs") or name.endswith("_test.rs") or name.startswith("tests_") or "testkit" in parts


def rust_files(root: str) -> list[str]:
    out = []
    for d, dirs, files in os.walk(root):
        dirs[:] = sorted(x for x in dirs if x not in ("target", ".git", "node_modules"))
        for f in sorted(files):
            if f.endswith(".rs"):
                p = os.path.join(d, f)
                if not is_test_path(p):
                    out.append(p)
    return out


def blank_comments(src: str) -> str:
    """`src` with comments turned to spaces (so offsets hold) and string contents untouched."""
    out = list(src)
    i, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
        elif c == "r" and re.match(r'r#*"', src[i:i + 12]) and (i == 0 or not (src[i - 1].isalnum() or src[i - 1] == "_")):
            i = skip_string(src, i)
        elif c == "'" and i + 2 < n and (src[i + 2] == "'" or (src[i + 1] == "\\" and "'" in src[i + 2:i + 8])):
            i = src.index("'", i + 2) + 1  # a char literal, which may hold a quote or a slash
        elif src.startswith("//", i):
            j = src.find("\n", i)
            j = n if j < 0 else j
            for k in range(i, j):
                out[k] = " "
            i = j
        elif src.startswith("/*", i):
            depth, j = 1, i + 2
            while j < n and depth:
                if src.startswith("/*", j):
                    depth, j = depth + 1, j + 2
                elif src.startswith("*/", j):
                    depth, j = depth - 1, j + 2
                else:
                    j += 1
            for k in range(i, j):
                if out[k] != "\n":
                    out[k] = " "
            i = j
        else:
            i += 1
    return "".join(out)


def skip_string(src: str, i: int) -> int:
    """The index after the string literal that starts at `i` (`"…"` or `r#"…"#`)."""
    n = len(src)
    if src[i] == "r":
        m = re.match(r'r(#*)"', src[i:i + 12])
        if not m:
            return i + 1
        end = src.find('"' + m.group(1), i + len(m.group(0)))
        return n if end < 0 else end + 1 + len(m.group(1))
    j = i + 1
    while j < n:
        if src[j] == "\\":
            j += 2
        elif src[j] == '"':
            return j + 1
        else:
            j += 1
    return n


def drop_test_modules(src: str) -> str:
    """`src` without its `#[cfg(test)]` items, which declare commands and widgets only for tests."""
    out, i = [], 0
    for m in re.finditer(r"#\[cfg\((?:all\()?test\b[^\]]*\]", src):
        if m.start() < i:
            continue
        brace = src.find("{", m.end())
        semi = src.find(";", m.end())
        if brace < 0 or (0 <= semi < brace):
            continue
        end = match_close(src, brace)
        if end < 0:
            continue
        out.append(src[i:m.start()])
        i = end + 1
    out.append(src[i:])
    return "".join(out)


OPEN, CLOSE = "([{", ")]}"


def match_close(src: str, i: int) -> int:
    """The index of the bracket closing the one at `i`, or -1. Strings are skipped."""
    depth, n = 0, len(src)
    while i < n:
        c = src[i]
        if c == '"' or (c == "r" and re.match(r'r#*"', src[i:i + 12]) and not (src[i - 1].isalnum() or src[i - 1] == "_")):
            i = skip_string(src, i)
            continue
        if c == "'" and i + 2 < n and (src[i + 2] == "'" or src[i + 1] == "\\"):
            j = src.find("'", i + 2)
            if 0 < j - i < 8:
                i = j + 1
                continue
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
            if depth == 0:
                return i
        i += 1
    return -1


def split_args(body: str) -> list[str]:
    """`body` (the inside of a call) cut at its top-level commas."""
    out, depth, start, i, n = [], 0, 0, 0, len(body)
    while i < n:
        c = body[i]
        if c == '"' or (c == "r" and re.match(r'r#*"', body[i:i + 12]) and (i == 0 or not (body[i - 1].isalnum() or body[i - 1] == "_"))):
            i = skip_string(body, i)
            continue
        if c == "'" and i + 2 < n and (body[i + 2] == "'" or body[i + 1] == "\\"):
            j = body.find("'", i + 2)
            if 0 < j - i < 8:
                i = j + 1
                continue
        if c in OPEN:
            depth += 1
        elif c in CLOSE:
            depth -= 1
        elif c == "," and depth == 0:
            out.append(body[start:i])
            start = i + 1
        i += 1
    tail = body[start:]
    if tail.strip():
        out.append(tail)
    return [a.strip() for a in out]


ESCAPES = {"n": "\n", "t": "\t", "r": "\r", "\\": "\\", '"': '"', "'": "'", "0": "\0"}


def string_value(lit: str):
    """The text of the Rust string literal `lit`, or None when `lit` is not exactly one."""
    lit = lit.strip()
    m = re.fullmatch(r'r(#*)"(.*)"\1', lit, re.S)
    if m:
        return m.group(2)
    if len(lit) < 2 or lit[0] != '"' or skip_string(lit, 0) != len(lit):
        return None
    body, out, i = lit[1:-1], [], 0
    while i < len(body):
        c = body[i]
        if c != "\\":
            out.append(c)
            i += 1
            continue
        nxt = body[i + 1:i + 2]
        if nxt == "u":
            m = re.match(r"\{([0-9a-fA-F_]{1,8})\}", body[i + 2:])
            if m:
                try:
                    out.append(chr(int(m.group(1).replace("_", ""), 16)))
                except (ValueError, OverflowError):
                    pass
                i += 2 + len(m.group(0))
                continue
        if nxt == "\n":  # a line continuation: the newline and the next line's indent are dropped
            i += 2
            while i < len(body) and body[i] in " \t\n":
                i += 1
            continue
        out.append(ESCAPES.get(nxt, nxt))
        i += 2
    return "".join(out)


def first_string(expr: str):
    """The first string literal anywhere in `expr` (`t("Label")`, `&format!("…")`), or None."""
    i, n = 0, len(expr)
    while i < n:
        c = expr[i]
        if c == '"' or (c == "r" and re.match(r'r#*"', expr[i:i + 12]) and (i == 0 or not (expr[i - 1].isalnum() or expr[i - 1] == "_"))):
            j = skip_string(expr, i)
            return string_value(expr[i:j])
        i += 1
    return None


def string_list(expr: str):
    """The strings of `["a", "b"]` or `&["a", "b"]`, or None when `expr` is not such a list."""
    e = expr.strip().lstrip("&").strip()
    if not (e.startswith("[") and e.endswith("]")):
        return None
    items = [string_value(a) for a in split_args(e[1:-1])]
    return None if any(x is None for x in items) else items


# --------------------------------------------------------------------------------------------
# Commands
# --------------------------------------------------------------------------------------------

ID = re.compile(r"^[a-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$")
ANY_ID = re.compile(r"^[A-Za-z][A-Za-z0-9_.-]{1,80}$")
SHORTCUT = re.compile(r"^((Cmd|Ctrl|Alt|Shift|Mod|Meta|Option)\+)+.{1,12}$|^(F\d{1,2}|Delete|Backspace|Enter|Escape|Space|Tab|Home|End|PageUp|PageDown|[A-Z0-9\[\]\\/;',.=`-])$")
# Names that call or test a command rather than declare one.
NOT_DECLARATIONS = {
    "run", "exec", "execute", "call", "invoke", "dispatch", "command", "assert", "assert_eq", "assert_ne", "json", "format", "println", "eprintln",
    "write", "writeln", "panic", "expect", "ok", "err", "Some", "Ok", "Err", "vec", "matches", "debug_assert", "log", "info", "warn", "error", "trace",
    "bail", "anyhow", "contains", "starts_with", "ends_with", "eq", "insert", "get", "remove", "push", "extend", "replace", "find", "t", "tr",
}
QUERY_WORDS = ("query", "q", "get", "list", "info", "state", "probe")


def looks_like_label(s: str) -> bool:
    return bool(s) and len(s) <= 120 and "\n" not in s and (s[0].isupper() or s[0].isdigit() or " " in s or ord(s[0]) > 127) and not s.startswith("{")


def const_tables(src: str) -> dict:
    """Constants of a file that a declaration may name instead of writing a list or a doc out."""
    out = {}
    for m in re.finditer(r"\b(?:const|static)\s+([A-Z][A-Z0-9_]*)\s*:\s*[^=;]{1,80}=\s*", src):
        end = src.find(";", m.end())
        if end < 0 or end - m.end() > 4000:
            continue
        expr = src[m.end():end]
        lst = string_list(expr)
        val = string_value(expr)
        if lst is not None:
            out[m.group(1)] = lst
        elif val is not None:
            out[m.group(1)] = val
    return out


def resolve(expr: str, consts: dict, kind):
    """`expr` as a string or a list of strings: written out, or a constant of the file that holds one."""
    e = expr.strip()
    val = string_value(e) if kind is str else string_list(e)
    if val is None and re.fullmatch(r"(?:\w+::)*[A-Z][A-Z0-9_]*", e):
        c = consts.get(e.split("::")[-1])
        val = c if isinstance(c, kind) else None
    return val


def shortcut_of(expr: str, consts: dict):
    """The shortcut `expr` gives. `mac_or(mac, other)` and its like name the Mac's keys first: the other platforms' are taken."""
    e = expr.strip()
    direct = resolve(e, consts, str)
    if direct is not None:
        return direct
    found = [string_value(m) for m in re.findall(r'r?#*"(?:[^"\\]|\\.)*"#*', e)]
    found = [f for f in found if f]
    if not found:
        return None
    return found[-1] if re.search(r"\bmac(_or|os)?\b|\bcfg!", e) else found[0]


def declaring_callees(engine_src: str) -> set:
    """
    Macros and functions that declare a command: ones whose own definition builds a `CommandSpec`,
    and macros that expand to one of those. A helper shaped the same way that builds something else
    (a preferences entry, a gallery item) is not one, so its ids are not taken for commands.
    """
    macros, fns = {}, set()
    for path in rust_files(engine_src):
        src = blank_comments(read_text(path))
        if "CommandSpec" not in src and "macro_rules!" not in src:
            continue
        for m in re.finditer(r"macro_rules!\s+([a-z_][a-z0-9_]*)\s*\{", src):
            end = match_close(src, m.end() - 1)
            if end > 0:
                macros[m.group(1)] = macros.get(m.group(1), "") + src[m.end():end]
        for m in re.finditer(r"\bfn\s+([a-z_][a-z0-9_]*)\s*(?:<[^>{]*>)?\s*\(", src):
            close = match_close(src, m.end() - 1)
            if close < 0:
                continue
            brace = src.find("{", close)
            if brace < 0:
                continue
            if re.search(r"->\s*(?:Vec<\s*)?(?:\w+::)*CommandSpec\b", src[close:brace]):
                fns.add(m.group(1))
        # let c = |id, label, params, run| CommandSpec { … };
        for m in re.finditer(r"\blet\s+([a-z_][a-z0-9_]*)\s*=\s*(?:move\s*)?\|[^|]{0,200}\|", src):
            if "CommandSpec" in src[m.end():m.end() + 600].split(";")[0]:
                fns.add(m.group(1))
    out = {name + "!" for name, body in macros.items() if "CommandSpec" in body}
    changed = True
    while changed:
        changed = False
        for name, body in macros.items():
            if name + "!" in out:
                continue
            if any(re.search(r"(?<![\w])%s\s*\(" % re.escape(c), body) for c in out | fns):
                out.add(name + "!")
                changed = True
    return out | fns


def spec_from_args(callee: str, args: list[str], consts: dict):
    """A command from the arguments of a declaring call (`cmd!(id, label, [menu], shortcut, params, …)`), or None."""
    kind = ""
    if args and re.fullmatch(r"(query|noundo|view|pure)\s+.*", args[0], re.S):
        kind, args[0] = args[0].split(None, 1)
    if len(args) < 2:
        return None
    cid, label = string_value(args[0]), string_value(args[1])
    if cid is None or label is None or not ID.match(cid) or not looks_like_label(label):
        return None
    menu, shortcut, params = None, "", None
    for a in args[2:]:
        lst = resolve(a, consts, list)
        if lst is not None:
            if menu is None:
                menu = lst
            continue
        m = re.fullmatch(r"Some\((.*)\)", a, re.S)
        val = shortcut_of(m.group(1), consts) if m else resolve(a, consts, str)
        if val is None:
            continue
        if (m or SHORTCUT.match(val)) and not shortcut and not val.startswith("{"):
            shortcut = val
        elif params is None and (val.startswith("{") or val.startswith("(")):
            params = val
    name = callee.rstrip("!").lower()
    mutates = not (kind == "query" or name in QUERY_WORDS or name.startswith("query"))
    return {"id": cid, "label": label, "menu": menu or [], "shortcut": shortcut, "params": params if params is not None else "{}", "mutates": mutates,
            "declaredBy": callee, "paramsKnown": params is not None, "menuKnown": menu is not None}


def scan_commands(engine_src: str, notes: list[str]) -> list[dict]:
    """Commands read from the engine crate's source: struct literals, builder calls, and declaring macros or helpers."""
    found: "OrderedDict[str, dict]" = OrderedDict()
    declaring = declaring_callees(engine_src)

    def keep(spec: dict, path: str):
        spec["source"] = os.path.relpath(path, engine_src)
        old = found.get(spec["id"])
        # The fuller of two sightings wins (a macro's expansion site and its use, say).
        score = lambda s: (s.get("menuKnown", False), s.get("paramsKnown", False), len(s["params"]))
        if old is None or score(spec) > score(old):
            found[spec["id"]] = spec

    for path in rust_files(engine_src):
        src = drop_test_modules(blank_comments(read_text(path)))
        if "CommandSpec" not in src and not re.search(r'[a-z_]+!?\(\s*(?:query\s+|noundo\s+)?"[a-z]\w*\.', src):
            continue
        consts = const_tables(src)
        # CommandSpec { id: "…", label: "…", menu: &[…], shortcut: …, params: "…", … }
        for m in re.finditer(r"\bCommandSpec\s*\{", src):
            end = match_close(src, m.end() - 1)
            if end < 0:
                continue
            fields = {}
            for part in split_args(src[m.end():end]):
                k, _, v = part.partition(":")
                if re.fullmatch(r"\s*[a-z_]+\s*", k):
                    fields[k.strip()] = v.strip()
            cid, label = string_value(fields.get("id", "")), string_value(fields.get("label", ""))
            if cid is None or label is None:
                continue
            sc = fields.get("shortcut", "")
            scm = re.fullmatch(r"Some\((.*)\)", sc, re.S)
            params = string_value(fields.get("params", ""))
            menu = string_list(fields.get("menu", ""))
            undo = fields.get("undoable", fields.get("journal", fields.get("mutates", "true")))
            keep({"id": cid, "label": label, "menu": menu or [], "shortcut": (string_value(scm.group(1)) if scm else string_value(sc)) or "",
                  "params": params if params is not None else "{}", "mutates": undo.strip() != "false", "declaredBy": "CommandSpec {}",
                  "paramsKnown": params is not None, "menuKnown": menu is not None}, path)
        # CommandSpec::new("id", "Label", [location,] run).params("…").menu(&[…]).key("…")
        for m in re.finditer(r"\bCommandSpec::new\s*\(", src):
            end = match_close(src, m.end() - 1)
            if end < 0:
                continue
            args = split_args(src[m.end():end])
            if len(args) < 2:
                continue
            cid, label = string_value(args[0]), string_value(args[1])
            if cid is None or label is None or not ANY_ID.match(cid):
                continue
            spec = {"id": cid, "label": label, "menu": [], "shortcut": "", "params": "{}", "mutates": True, "declaredBy": "CommandSpec::new",
                    "paramsKnown": False, "menuKnown": False}
            location = resolve(args[2], consts, str) if len(args) > 2 else None
            if location is not None:
                spec["menu"], spec["menuKnown"] = [p for p in re.split(r"\s*›\s*", location) if p], True
            # The builder calls that follow, up to the end of the expression.
            i = end + 1
            while True:
                bm = re.match(r"\s*\.\s*([a-z_]+)\s*\(", src[i:])
                if not bm:
                    break
                bend = match_close(src, i + bm.end() - 1)
                if bend < 0:
                    break
                parts = split_args(src[i + bm.end():bend])
                arg = parts[0] if parts else ""
                name = bm.group(1)
                if name == "params" and resolve(arg, consts, str) is not None:
                    spec["params"], spec["paramsKnown"] = resolve(arg, consts, str), True
                elif name == "menu" and resolve(arg, consts, list) is not None:
                    spec["menu"], spec["menuKnown"] = resolve(arg, consts, list), True
                elif name in ("key", "shortcut", "keys") and shortcut_of(arg, consts):
                    spec["shortcut"] = shortcut_of(arg, consts)
                elif name in ("pure", "query", "noundo", "view"):
                    spec["mutates"] = name == "noundo"
                i = bend + 1
            keep(spec, path)
        # cmd!("id", "Label", ["Menu", "Sub"], Some("Cmd+K"), "{params}", enabled, run) and helpers shaped like it
        for m in re.finditer(r"(?<![\w.])([a-z_][a-z0-9_]*!?)\s*\(", src):
            callee = m.group(1)
            if callee.rstrip("!") in NOT_DECLARATIONS or callee not in declaring:
                continue
            head = src[m.end():m.end() + 160]
            if not re.match(r'\s*(?:(?:query|noundo|view|pure)\s+)?"[a-z][A-Za-z0-9_]*\.', head):
                continue
            end = match_close(src, m.end() - 1)
            if end < 0:
                continue
            spec = spec_from_args(callee, split_args(src[m.end():end]), consts)
            if spec:
                keep(spec, path)
    specs = list(found.values())
    helpers = sorted({s["declaredBy"] for s in specs})
    notes.append(f"Commands were read from source ({len(specs)} found, declared with: {', '.join(helpers) or 'nothing recognised'}). "
                 "A macro that fills in params or a menu inside its body is not expanded, so those commands show `{}` and no menu here. "
                 "Run again with --commands and the engine's own `engine.commands` JSON before relying on this.")
    return specs


def load_commands(path: str, notes: list[str]) -> list[dict]:
    """Commands from the engine's own listing: a JSON array, or an object holding one under `result` or `commands`."""
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    if isinstance(data, dict):
        data = data.get("result", data.get("commands", []))
    out = []
    for c in data if isinstance(data, list) else []:
        if not isinstance(c, dict) or not isinstance(c.get("id"), str) or not c["id"]:
            continue
        menu = c.get("menu")
        if not isinstance(menu, list):
            menu = [p for p in re.split(r"\s*›\s*", c.get("location") or "") if p]
        mut = c.get("mutates", c.get("undoable", c.get("journal", True)))
        out.append({"id": c["id"], "label": str(c.get("label") or c["id"]), "menu": [str(x) for x in menu if isinstance(x, str) and x.strip()],
                    "shortcut": str(c.get("shortcut") or ""), "params": str(c.get("params") or "{}"), "mutates": mut is not False,
                    "declaredBy": "engine.commands", "paramsKnown": True, "menuKnown": True, "source": ""})
    notes.append(f"Commands came from the engine's own listing ({os.path.basename(path)}, {len(out)} commands).")
    return out


# --------------------------------------------------------------------------------------------
# The egui front end: menus, panels, tools, dialogs, theme
# --------------------------------------------------------------------------------------------

def menu_order(ui_src: str, commands: list[dict]) -> list[str]:
    """
    The top-level menus (or ribbon tabs) in the front end's order. The engine lists commands in
    its own order, not the menu bar's, so the order is taken from the front end: the list of strings
    in its source that names the most of them. Menus that list leaves out (contextual tabs) follow.
    """
    seen = []
    for c in commands:
        if c["menu"] and c["menu"][0] not in seen:
            seen.append(c["menu"][0])
    best = []
    for path in rust_files(ui_src):
        src = blank_comments(read_text(path))
        for m in re.finditer(r'\[\s*\(?\s*"', src):
            end = match_close(src, m.start())
            if end < 0 or end - m.start() > 20000:
                continue
            names = []
            for a in split_args(src[m.start() + 1:end]):
                # A table of menus is a list of names, or of (name, items) pairs.
                s = string_value(a) if string_value(a) is not None else (first_string(a) if a.lstrip().startswith("(") else None)
                if s is not None:
                    names.append(s)
            known = [n for n in dict.fromkeys(names) if n in seen]
            if len(known) >= 3 and len(known) > len(best) and len(known) * 2 >= len(names):
                best = known
    if best:
        return best + [s for s in seen if s not in best]
    # No table found (the menu bar is built in code): the order every desktop menu bar keeps.
    first = ["File", "Edit"]
    last = ["View", "Window", "Help"]
    return [m for m in first if m in seen] + [m for m in seen if m not in first and m not in last] + [m for m in last if m in seen]


def humanize(name: str) -> str:
    s = re.sub(r"(?<=[a-z0-9])(?=[A-Z])|(?<=[A-Z])(?=[A-Z][a-z])", " ", name).replace("_", " ").strip()
    return s[:1].upper() + s[1:]


def scan_enums(roots: list[str], wanted: tuple) -> "OrderedDict[str, dict]":
    """Enums whose name is one of `wanted`, with their variants and, where a match gives one, each variant's title."""
    out: "OrderedDict[str, dict]" = OrderedDict()
    for root in roots:
        for path in rust_files(root):
            src = blank_comments(read_text(path))
            for m in re.finditer(r"\bpub(?:\([a-z]+\))?\s+enum\s+(\w+)\s*\{", src):
                name = m.group(1)
                if name not in wanted or name in out:
                    continue
                end = match_close(src, m.end() - 1)
                if end < 0:
                    continue
                variants = []
                for part in split_args(src[m.end():end]):
                    part = re.sub(r"#\[[^\]]*\]\s*", "", part).strip()
                    vm = re.match(r"([A-Z]\w*)", part)
                    if vm:
                        variants.append({"name": vm.group(1), "title": humanize(vm.group(1)), "data": bool(re.match(r"[A-Z]\w*\s*[\({]", part))})
                # `Name::Variant => "Title"` anywhere in the file: the title the front end shows.
                for v in variants:
                    tm = re.search(r"\b(?:%s|Self)::%s\b[^=\n]{0,40}=>\s*(?:t\(|tr\(|i18n::t\()?\s*\"((?:[^\"\\]|\\.)*)\"" % (re.escape(name), re.escape(v["name"])), src)
                    if tm and looks_like_label(tm.group(1)):
                        v["title"] = string_value('"' + tm.group(1) + '"') or v["title"]
                out[name] = {"file": path, "variants": variants}
    return out


WIDGETS = [
    # (kind, pattern); the label and the binding are read from the call's arguments
    ("checkbox", re.compile(r"\bcheckbox\s*\(")),
    ("toggle", re.compile(r"\btoggle_value\s*\(")),
    ("radio", re.compile(r"\bradio_value\s*\(")),
    ("option", re.compile(r"\bselectable_value\s*\(")),
    ("slider", re.compile(r"\bSlider::new\s*\(")),
    ("number", re.compile(r"\bDragValue::new\s*\(")),
    ("combo", re.compile(r"\bComboBox::(?:from_label|from_id_salt|from_id_source|new)\s*\(")),
    ("text", re.compile(r"\btext_edit_singleline\s*\(|\bTextEdit::singleline\s*\(")),
    ("textarea", re.compile(r"\btext_edit_multiline\s*\(|\bTextEdit::multiline\s*\(")),
    ("color", re.compile(r"\bcolor_edit_button_\w+\s*\(")),
    ("button", re.compile(r"(?<![\w_])(?:small_)?button\s*\(")),
    ("label", re.compile(r"(?<![\w_])(?:label|heading|strong)\s*\(")),
]


def binding_key(expr: str) -> str:
    """The parameter name a `&mut self.opts.radius` binding most likely stands for: its last field."""
    e = re.sub(r"^&\s*mut\s+", "", expr.strip())
    e = re.sub(r"\[[^\]]*\]|\([^)]*\)|\*", "", e)
    names = re.findall(r"[A-Za-z_]\w*", e)
    names = [n for n in names if n not in ("self", "mut", "app", "state", "s", "d", "dlg", "dialog", "opts", "options", "p", "st")]
    key = names[-1] if names else "value"
    parts = key.split("_")
    return parts[0] + "".join(p[:1].upper() + p[1:] for p in parts[1:])


def scan_function_widgets(body: str, known_ids: set) -> dict:
    """The widgets a function draws, in order, and the commands it names."""
    hits = []
    for kind, pat in WIDGETS:
        for m in pat.finditer(body):
            end = match_close(body, m.end() - 1)
            if end < 0:
                continue
            hits.append((m.start(), kind, split_args(body[m.end():end]), end))
    hits.sort(key=lambda h: h[0])
    widgets, last_label, pending_combo = [], "", None
    for pos, kind, args, end in hits:
        if kind == "label":
            txt = first_string(args[0]) if args else None
            if txt and looks_like_label(txt.rstrip(":")):
                last_label = txt.rstrip(": ")
            continue
        w = {"kind": kind, "label": "", "key": "", "options": []}
        if kind in ("checkbox", "toggle"):
            w["key"] = binding_key(args[0]) if args else ""
            w["label"] = (first_string(args[1]) if len(args) > 1 else None) or last_label
        elif kind in ("radio", "option"):
            text = (first_string(args[2]) if len(args) > 2 else None) or ""
            key = binding_key(args[0]) if args else ""
            value = re.sub(r"^.*::", "", args[1].strip()) if len(args) > 1 else text
            value = string_value(args[1]) if len(args) > 1 and string_value(args[1]) is not None else value
            # Options of one binding are one choice; a combo box just opened owns the options that follow it.
            target = pending_combo if pending_combo is not None and kind == "option" else next((x for x in reversed(widgets[-12:]) if x["kind"] == "choice" and x["key"] == key), None)
            if target is None:
                target = {"kind": "choice", "label": last_label, "key": key, "options": []}
                widgets.append(target)
            if not target["key"]:
                target["key"] = key
            if text and len(target["options"]) < 60:
                target["options"].append({"label": text, "value": str(value)[:60]})
            continue
        elif kind == "slider":
            w["key"] = binding_key(args[0]) if args else ""
            rng = re.match(r"\s*(-?[\d._]+)(?:f32|f64|u8|i32|u32|usize)?\s*\.\.=?\s*(-?[\d._]+)", args[1]) if len(args) > 1 else None
            if rng:
                w["min"], w["max"] = rng.group(1).replace("_", ""), rng.group(2).replace("_", "")
            tm = re.match(r"\s*\.\s*text\s*\(", body[end + 1:end + 40])
            w["label"] = (first_string(body[end + 1:end + 200]) if tm else None) or last_label
        elif kind == "number":
            w["key"] = binding_key(args[0]) if args else ""
            w["label"] = last_label
        elif kind == "combo":
            w["kind"] = "choice"
            w["label"] = (first_string(args[0]) if args and "label" in body[pos:pos + 24] else None) or last_label
            pending_combo = w
        elif kind in ("text", "textarea"):
            w["key"] = binding_key(args[0]) if args else ""
            w["label"] = last_label
        elif kind == "color":
            w["key"] = binding_key(args[0]) if args else ""
            w["label"] = last_label
        elif kind == "button":
            w["label"] = (first_string(args[0]) if args else None) or ""
            if not looks_like_label(w["label"]):
                continue
            # A command id named within the button's `if …clicked() { … }` is what the button runs.
            after = body[end:end + 400]
            cm = re.search(r'"([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', after)
            if cm and cm.group(1) in known_ids:
                w["command"] = cm.group(1)
        if kind != "combo":
            pending_combo = None if kind != "option" else pending_combo
        if kind != "button":
            last_label = "" if w["label"] == last_label else last_label
        widgets.append(w)
    ids = [i for i in dict.fromkeys(re.findall(r'"([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', body)) if i in known_ids]
    return {"widgets": [w for w in widgets if w["kind"] != "choice" or w["options"] or w["label"]], "commands": ids}


def scan_dialogs(ui_src: str, known_ids: set, limit: int) -> list[dict]:
    """Dialogs of the front end: every function under `dialogs/` (or in a file named for a dialog) that draws input widgets."""
    out = []
    for path in rust_files(ui_src):
        rel = os.path.relpath(path, ui_src).replace("\\", "/")
        in_dialogs = rel.startswith("dialogs/") or rel in ("dialogs.rs",) or "dialog" in os.path.basename(rel) or rel.startswith(("prefs", "options/", "new_document"))
        if not in_dialogs:
            continue
        src = drop_test_modules(blank_comments(read_text(path)))
        for m in re.finditer(r"\bfn\s+([a-z_][a-z0-9_]*)\s*(?:<[^>{]*>)?\s*\(", src):
            close = match_close(src, m.end() - 1)
            if close < 0:
                continue
            brace = src.find("{", close)
            semi = src.find(";", close)
            if brace < 0 or (0 <= semi < brace):
                continue
            end = match_close(src, brace)
            if end < 0 or "egui" not in src[m.start():brace] and "Ui" not in src[m.start():brace] and "ctx" not in src[m.start():brace]:
                continue
            body = src[brace:end + 1]
            got = scan_function_widgets(body, known_ids)
            inputs = [w for w in got["widgets"] if w["kind"] != "button"]
            if not inputs:
                continue
            title = None
            tm = re.search(r"(?:Window|Modal)::new\s*\(\s*(?:t\(|tr\(|i18n::t\()?\s*\"((?:[^\"\\]|\\.)*)\"", body)
            if tm:
                title = string_value('"' + tm.group(1) + '"')
            stem = os.path.splitext(os.path.basename(rel))[0]
            name = stem if m.group(1) in ("show", "ui", "draw", "body", "contents", "content", "render", "window") else f"{stem}_{m.group(1)}" if stem not in ("dialogs", "mod") else m.group(1)
            out.append({"name": name, "title": title or humanize(name), "file": rel, "function": m.group(1), "line": src.count("\n", 0, m.start()) + 1,
                        "widgets": got["widgets"], "commands": got["commands"]})
    out.sort(key=lambda d: (-len([w for w in d["widgets"] if w["kind"] != "button"]), d["file"], d["line"]))
    return out[:limit]


def scan_theme(ui_src: str) -> dict:
    """Named colours of the front end's theme, grouped by the function or constant that sets them."""
    tokens = []
    for path in rust_files(ui_src):
        base = os.path.basename(path)
        if not re.search(r"theme|palette|colors|appearance|brand", base):
            continue
        src = blank_comments(read_text(path))
        fn_starts = [(m.start(), m.group(1)) for m in re.finditer(r"\bfn\s+([a-z_0-9]+)\s*\(", src)]
        for m in re.finditer(r"\b([a-z_][a-z0-9_]*|[A-Z][A-Z0-9_]*)\s*:\s*(?:Color32\s*=\s*)?(?:egui::)?Color32::from_(?:rgb|gray)\s*\(([^)]*)\)", src):
            nums = []
            for a in m.group(2).split(","):
                a = re.sub(r"_?(u8|u32|i32)$", "", a.strip())
                try:
                    nums.append(int(a, 0))
                except ValueError:
                    nums = []
                    break
            if len(nums) == 1:
                nums = nums * 3
            if len(nums) != 3 or not all(0 <= x <= 255 for x in nums):
                continue
            owner = next((n for s, n in reversed(fn_starts) if s < m.start()), "")
            tokens.append({"name": m.group(1).lower(), "rgb": nums, "in": owner, "file": base})
    return {"tokens": tokens[:400]}


def luminance(rgb) -> float:
    return (0.2126 * rgb[0] + 0.7152 * rgb[1] + 0.0722 * rgb[2]) / 255.0


def pick_palette(theme: dict) -> dict:
    """Light and dark schemes from the theme's tokens: its accent, and the backgrounds and text of its lightest and darkest sets."""
    toks = theme["tokens"]

    def named(*names, where=None):
        for n in names:
            for t in toks:
                if t["name"] == n and (where is None or where(t)):
                    return t["rgb"]
        return None

    accent = named("accent", "accent_color", "primary", "selection", "highlight", "focus", "brand", "active")
    bgs = [t for t in toks if re.search(r"(^|_)(bg|background|panel|window|surface)(_|$)", t["name"]) and "hover" not in t["name"]]
    texts = [t for t in toks if re.fullmatch(r"text|fg|foreground|text_color|label", t["name"])]
    dark_bg = min((t["rgb"] for t in bgs), key=luminance, default=None)
    light_bg = max((t["rgb"] for t in bgs), key=luminance, default=None)
    dark_text = max((t["rgb"] for t in texts), key=luminance, default=None)
    light_text = min((t["rgb"] for t in texts), key=luminance, default=None)
    out = {"accent": accent, "found": bool(accent or bgs)}
    out["dark"] = {"background": dark_bg if dark_bg and luminance(dark_bg) < 0.35 else [22, 24, 28], "text": dark_text if dark_text and luminance(dark_text) > 0.6 else [225, 228, 232]}
    out["light"] = {"background": light_bg if light_bg and luminance(light_bg) > 0.75 else [244, 246, 249], "text": light_text if light_text and luminance(light_text) < 0.35 else [27, 31, 38]}
    return out


def scan_front_end_methods(ui_src: str) -> list[str]:
    """Control-protocol methods the desktop front end answers itself (`ui.*`, `window.*`): things no engine command does."""
    out = set()
    for path in rust_files(ui_src):
        if "control" not in path.replace("\\", "/").split("crates/ui-egui/src/")[-1]:
            continue
        for m in re.finditer(r'"((?:ui|window|panel|app)\.[A-Za-z0-9_.]+)"\s*(?:\||=>)', blank_comments(read_text(path))):
            out.add(m.group(1))
    return sorted(out)


# --------------------------------------------------------------------------------------------
# Deciding what the profile says
# --------------------------------------------------------------------------------------------

def params_mention_point(params: str) -> bool:
    head = params.split("→")[0]
    keys = set(re.findall(r'"?([A-Za-z_]\w*)"?\s*\??\s*:', head)) | set(re.findall(r"[{,]\s*([A-Za-z_]\w*)\s*\??\s*(?=[,}])", head))
    return ("x" in keys and "y" in keys) or bool(keys & {"toX", "toY", "points"}) and bool(keys & {"x", "page", "points"})


def classify_commands(commands: list[dict], ui_ids: set) -> dict:
    """Which commands are pointer gestures, state the app reads, or typing: the profile's named sets."""
    pointer, readers, typing = [], [], []
    for c in commands:
        cid, label = c["id"], c["label"]
        required_point = params_mention_point(c["params"]) and not re.search(r'"?\bx\b"?\s*\?', c["params"])
        if required_point and not c["menu"]:
            pointer.append(cid)
        elif not c["mutates"] and not c["menu"] and re.search(r"(\.|^)(state|status|get|list|info|inspect|query|describe|current|selection)$", cid):
            readers.append(cid)
        elif re.fullmatch(r"(text|type|edit)\.(insert|type|typeText|insertText)", cid):
            typing.append(cid)
    return {"pointer": pointer, "readers": readers, "typing": typing}


# --------------------------------------------------------------------------------------------
# Writing Kotlin
# --------------------------------------------------------------------------------------------

def kt(s: str) -> str:
    """`s` as a Kotlin string literal."""
    out = ['"']
    for ch in str(s):
        if ch == "\\":
            out.append("\\\\")
        elif ch == '"':
            out.append('\\"')
        elif ch == "$":
            out.append("\\$")
        elif ch == "\n":
            out.append("\\n")
        elif ch == "\r":
            out.append("\\r")
        elif ch == "\t":
            out.append("\\t")
        elif ord(ch) < 32 or ch in "  ":
            out.append("\\u%04x" % ord(ch))
        else:
            out.append(ch)
    out.append('"')
    return "".join(out)


KOTLIN_WORDS = {"as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in", "interface", "is", "null", "object", "package",
                "return", "super", "this", "throw", "true", "try", "typealias", "typeof", "val", "var", "when", "while"}


def ident(s: str, upper: bool = False) -> str:
    parts = [p for p in re.split(r"[^A-Za-z0-9]+", s) if p]
    if not parts:
        return "X" if upper else "x"
    name = "".join(p[:1].upper() + p[1:] for p in parts)
    if not upper:
        name = name[:1].lower() + name[1:]
    if name[0].isdigit():
        name = ("N" if upper else "n") + name
    return name + "_" if name in KOTLIN_WORDS else name


def kt_list(items, indent="    ", per_line=6) -> str:
    items = [kt(i) for i in items]
    if not items:
        return ""
    lines = [", ".join(items[i:i + per_line]) for i in range(0, len(items), per_line)]
    return "\n" + "".join(f"{indent}    {line},\n" for line in lines) + indent


def color_hex(rgb) -> str:
    return "Color(0xFF%02X%02X%02X)" % tuple(rgb)


def mix(a, b, t):
    return [round(a[i] + (b[i] - a[i]) * t) for i in range(3)]


def gen_profile(pkg: str, model: dict) -> str:
    sets = model["sets"]
    return f'''package {pkg}

import app.loki.kit.CommandProfile

// Generated by egui2compose from {model["repoName"]}. Edit freely: this file is the app's to keep.
// The coverage test (jvmTest) checks every id here against the engine's command list.

/** The top-level menus of the desktop front end, in its order: this app's tabs. */
val TABS: List<String> = listOf({kt_list(model["tabs"])})

/**
 * Commands a press or drag on the surface issues. Each starts in the profile's `pointerGaps`
 * (out of reach) and moves to `pointerGestures` when its gesture is built.
 */
val POINTER_GAPS: Set<String> = setOf({kt_list(sets["pointer"])})

/** Commands that only read state the app already shows; not menu entries. */
val STATE_READERS: Set<String> = setOf({kt_list(sets["readers"])})

/** Commands the keyboard issues as part of typing. */
val TYPING_COMMANDS: Set<String> = setOf({kt_list(sets["typing"])})

/** What this app tells Loki Kit about its engine's commands. */
val PROFILE = CommandProfile(
    tabs = TABS,
    pointerGaps = POINTER_GAPS,
    typing = TYPING_COMMANDS,
    stateReaders = STATE_READERS,
)
'''


def gen_theme(pkg: str, model: dict) -> str:
    pal = model["palette"]
    accent = pal["accent"] or [31, 111, 197]
    lb, lt, db, dt = pal["light"]["background"], pal["light"]["text"], pal["dark"]["background"], pal["dark"]["text"]
    # A light surface wants a darker accent and a dark one a lighter, so the accent reads on both.
    acc_light = accent if luminance(accent) < 0.55 else mix(accent, [0, 0, 0], 0.35)
    acc_dark = accent if luminance(accent) > 0.45 else mix(accent, [255, 255, 255], 0.45)
    note = "read from the desktop front end's theme" if pal["found"] else "a neutral default: the desktop theme's colours were not found"
    return f'''package {pkg}

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// Generated by egui2compose: {note}.
// The accent is the desktop app's; the surfaces are derived from its backgrounds. Tune by eye.

val LightColors = lightColorScheme(
    primary = {color_hex(acc_light)},
    onPrimary = Color.White,
    primaryContainer = {color_hex(mix(lb, acc_light, 0.18))},
    onPrimaryContainer = {color_hex(mix(lt, acc_light, 0.3))},
    secondaryContainer = {color_hex(mix(lb, acc_light, 0.14))},
    onSecondaryContainer = {color_hex(lt)},
    background = {color_hex(lb)},
    onBackground = {color_hex(lt)},
    surface = {color_hex(mix(lb, [255, 255, 255], 0.4))},
    onSurface = {color_hex(lt)},
    surfaceVariant = {color_hex(mix(lb, lt, 0.08))},
    onSurfaceVariant = {color_hex(mix(lt, lb, 0.25))},
    surfaceContainerLowest = Color.White,
    surfaceContainer = {color_hex(mix(lb, lt, 0.04))},
    surfaceContainerHigh = {color_hex(mix(lb, lt, 0.07))},
    outline = {color_hex(mix(lt, lb, 0.45))},
    outlineVariant = {color_hex(mix(lb, lt, 0.16))},
)

val DarkColors = darkColorScheme(
    primary = {color_hex(acc_dark)},
    onPrimary = {color_hex(mix(db, [0, 0, 0], 0.2))},
    primaryContainer = {color_hex(mix(db, acc_dark, 0.3))},
    onPrimaryContainer = {color_hex(mix(dt, acc_dark, 0.2))},
    secondaryContainer = {color_hex(mix(db, acc_dark, 0.22))},
    onSecondaryContainer = {color_hex(dt)},
    background = {color_hex(db)},
    onBackground = {color_hex(dt)},
    surface = {color_hex(mix(db, [255, 255, 255], 0.04))},
    onSurface = {color_hex(dt)},
    surfaceVariant = {color_hex(mix(db, dt, 0.12))},
    onSurfaceVariant = {color_hex(mix(dt, db, 0.25))},
    surfaceContainerLowest = {color_hex(mix(db, [0, 0, 0], 0.3))},
    surfaceContainer = {color_hex(mix(db, dt, 0.06))},
    surfaceContainerHigh = {color_hex(mix(db, dt, 0.1))},
    outline = {color_hex(mix(dt, db, 0.45))},
    outlineVariant = {color_hex(mix(db, dt, 0.2))},
)
'''


def gen_workspace(pkg: str, model: dict) -> str:
    panels, tools = model["panels"], model["tools"]
    prow = "".join(f'    Panel({kt(p["name"])}, {kt(p["title"])}, {kt(p.get("source") or "")}),\n' for p in panels)
    trow = "".join(f'    Tool({kt(t["name"])}, {kt(t["title"])}, {kt(t.get("command") or "")}),\n' for t in tools)
    return f'''package {pkg}

// Generated by egui2compose from the desktop front end's panel and tool enums.

/**
 * A panel of the desktop front end. [source] is the engine command whose answer lists what the
 * panel shows, when one was found by name; empty means the panel is drawn by hand on the desktop
 * and has to be built here (see REPORT.md).
 */
class Panel(val id: String, val title: String, val source: String)

/** A tool of the desktop front end. [command] is the engine command that picks it up, when one was found by name. */
class Tool(val id: String, val title: String, val command: String)

val PANELS: List<Panel> = listOf(
{prow})

val TOOLS: List<Tool> = listOf(
{trow})
'''


def field_decl(i: int, w: dict) -> str:
    k = w["kind"]
    if k in ("checkbox", "toggle"):
        return f"    var f{i} by remember {{ mutableStateOf(false) }}\n"
    if k == "slider":
        lo = w.get("min", "0")
        return f"    var f{i} by remember {{ mutableStateOf({float_lit(lo)}) }}\n"
    return f'    var f{i} by remember {{ mutableStateOf("") }}\n'


def float_lit(s: str) -> str:
    try:
        return repr(float(s)) + "f"
    except ValueError:
        return "0f"


def field_ui(i: int, w: dict) -> str:
    k, label = w["kind"], w["label"] or humanize(w["key"] or "Value")
    if k in ("checkbox", "toggle"):
        return f'''            Row(Modifier.fillMaxWidth().clickable {{ f{i} = !f{i} }}, verticalAlignment = Alignment.CenterVertically) {{
                Checkbox(f{i}, {{ f{i} = it }})
                Text({kt(label)})
            }}
'''
    if k == "slider":
        lo, hi = float_lit(w.get("min", "0")), float_lit(w.get("max", "1"))
        return f'''            Text({kt(label)} + ": " + (kotlin.math.round(f{i} * 100f) / 100f), style = MaterialTheme.typography.labelLarge)
            Slider(f{i}, {{ f{i} = it }}, valueRange = minOf({lo}, {hi})..maxOf({lo}, {hi}))
'''
    if k == "choice":
        chips = "".join(f"                FilterChip(f{i} == {kt(o['value'])}, {{ f{i} = {kt(o['value'])} }}, label = {{ Text({kt(o['label'])}) }})\n" for o in w["options"][:40])
        return f'''            Text({kt(label)}, style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {{
{chips}            }}
'''
    lines = "false" if k == "textarea" else "true"
    kb = ", keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)" if k == "number" else ""
    return f"            OutlinedTextField(f{i}, {{ f{i} = it }}, label = {{ Text({kt(label)}) }}, singleLine = {lines}{kb}, modifier = Modifier.fillMaxWidth())\n"


def field_put(i: int, w: dict) -> str:
    k, key = w["kind"], w["key"] or f"field{i}"
    if k in ("checkbox", "toggle", "slider"):
        return f"                put({kt(key)}, f{i})\n"
    if k == "number":
        return f"                f{i}.trim().replace(',', '.').toDoubleOrNull()?.let {{ put({kt(key)}, it) }}\n"
    return f"                if (f{i}.isNotBlank()) put({kt(key)}, f{i})\n"


def gen_dialog(pkg: str, d: dict, by_id: dict) -> str:
    inputs = [w for w in d["widgets"] if w["kind"] != "button"]
    buttons = [w for w in d["widgets"] if w["kind"] == "button"]
    fn = ident(d["name"], upper=True) + "Dialog"
    command = next((b["command"] for b in buttons if b.get("command")), None) or (d["commands"][0] if d["commands"] else "")
    doc = by_id.get(command, {}).get("params", "")
    decls = "".join(field_decl(i, w) for i, w in enumerate(inputs))
    body = "".join(field_ui(i, w) for i, w in enumerate(inputs))
    puts = "".join(field_put(i, w) for i, w in enumerate(inputs))
    extra = "".join(f"            // egui button {kt(b['label'])}" + (f" runs {b['command']}" if b.get("command") else "") + "\n" for b in buttons if b["label"] not in ("OK", "Cancel", "Close"))
    run = f"onRun({kt(command)}, params)" if command else "/* TODO: which command this dialog runs was not found */"
    return f'''package {pkg}.dialogs

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import app.loki.kit.SheetDialog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * {d["title"]}: translated from `{d["file"]}` (fn `{d["function"]}`, line {d["line"]}) by egui2compose.
 *
 * The controls and their order are the desktop dialog's. NOT translated: the starting values (the
 * desktop reads them from its own state), anything it draws by hand, and enabling rules. The
 * parameter names are guessed from the Rust fields each control was bound to: check them against
 * the command's params doc before trusting OK.
 *   command: {command or "(not found)"}
 *   params:  {doc[:300].replace("*/", "* /") if doc else "(unknown)"}
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun {fn}(onRun: (command: String, params: JsonObject) -> Unit, onDismiss: () -> Unit) {{
{decls}    SheetDialog(
        {kt(d["title"])}, "", onDismiss,
        buttons = {{
            TextButton(onClick = onDismiss) {{ Text("Cancel") }}
            TextButton(onClick = {{
                val params = buildJsonObject {{
{puts}                }}
                {run}
                onDismiss()
            }}) {{ Text("OK") }}
        }},
    ) {{
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {{
{body}{extra}        }}
    }}
}}
'''


def gen_dialog_index(pkg: str, dialogs: list[dict]) -> str:
    rows = "".join(f"    TranslatedDialog({kt(d['title'])}, {kt(d['file'])}) {{ onRun, onDismiss -> {ident(d['name'], upper=True)}Dialog(onRun, onDismiss) }},\n" for d in dialogs)
    return f'''package {pkg}.dialogs

import androidx.compose.runtime.Composable
import kotlinx.serialization.json.JsonObject

// Generated by egui2compose: the dialogs translated from the desktop front end, for the app to
// open from wherever each belongs (a group's launcher, a menu entry, an engine request).

class TranslatedDialog(val title: String, val source: String, val show: @Composable (onRun: (String, JsonObject) -> Unit, onDismiss: () -> Unit) -> Unit)

val TRANSLATED_DIALOGS: List<TranslatedDialog> = listOf(
{rows})
'''


APP_KT = r'''package __PKG__

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.loki.kit.AllCommandsDialog
import app.loki.kit.CommandBar
import app.loki.kit.CommandFormDialog
import app.loki.kit.CommandInfo
import app.loki.kit.CraftTheme
import app.loki.kit.EngineClient
import app.loki.kit.EngineException
import app.loki.kit.EngineTransport
import app.loki.kit.PaneContent
import app.loki.kit.PaneList
import app.loki.kit.PaneRow
import app.loki.kit.ResultDialog
import app.loki.kit.RibbonTab
import app.loki.kit.describeResult
import app.loki.kit.parseCommands
import app.loki.kit.parseRibbon
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Menu
import com.composables.icons.lucide.Search
import __PKG__.dialogs.TRANSLATED_DIALOGS
import __PKG__.dialogs.TranslatedDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// Generated by egui2compose as the app's first shell. It is the app's to keep and change.
//
// What works as generated: every engine command is on the command bar or in the all-commands
// list; one that needs values opens a form built from its params doc; one that reports shows its
// answer; panels and tools from the desktop front end are listed; light, dark and system themes.
// What is a placeholder: the SURFACE (see [surface]) and whatever REPORT.md lists as not translated.

const val APP_NAME = __NAME__

/** How much room the window gives: a phone, a tablet, a desktop. From the window's width, not the device. */
enum class SizeClass { Compact, Medium, Expanded }

/** `{"disabled": {id: reason}, "on": [id…]}` from `loki.commandState`; anything else is "nothing disabled, nothing on". */
private fun parseState(e: JsonElement?): Pair<Map<String, String>, Set<String>> {
    val o = e as? JsonObject ?: return emptyMap<String, String>() to emptySet()
    val disabled = (o["disabled"] as? JsonObject).orEmpty().mapNotNull { (id, why) -> (why as? JsonPrimitive)?.contentOrNull?.let { id to it } }.toMap()
    val on = (o["on"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
    return disabled to on
}

/**
 * The app. [transport] is the engine's one JSON call (the platform binding, or [FixtureTransport]
 * to look at the UI with no engine). [surface] draws the document: the page, canvas or timeline the
 * engine renders. It gets a number that changes after every command, to re-render on.
 */
@Composable
fun App(
    transport: EngineTransport,
    pickFile: (save: Boolean) -> String? = { null },
    surface: @Composable (revision: Int, modifier: Modifier) -> Unit = { _, modifier -> SurfacePlaceholder(modifier) },
) = CraftTheme(LightColors, DarkColors, Typography()) { themeChoice, setTheme, _ ->
    val engine = remember(transport) { EngineClient(transport) }
    DisposableEffect(engine) { onDispose { engine.close() } }
    val scope = rememberCoroutineScope()
    // The session is not thread-safe and calls block: every call goes through this lock, off the UI thread.
    val lock = remember { Mutex() }
    var revision by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf("") }
    var catalog by remember { mutableStateOf<List<CommandInfo>>(emptyList()) }
    var ribbon by remember { mutableStateOf<List<RibbonTab>>(emptyList()) }
    var disabled by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var on by remember { mutableStateOf<Set<String>>(emptySet()) }
    var showCommands by remember { mutableStateOf(false) }
    var form by remember { mutableStateOf<CommandInfo?>(null) }
    var result by remember { mutableStateOf<Pair<CommandInfo, JsonElement?>?>(null) }
    var dialog by remember { mutableStateOf<TranslatedDialog?>(null) }
    var openPanel by remember { mutableStateOf<Panel?>(null) }
    var tool by remember { mutableStateOf(TOOLS.firstOrNull()?.id.orEmpty()) }
    val byId = remember(catalog) { catalog.associateBy { it.id } }

    suspend fun <T> onEngine(block: EngineClient.() -> T): Result<T> =
        runCatching { lock.withLock { withContext(Dispatchers.Default) { engine.block() } } }

    // Runs a command and returns why it failed, or null. A request it makes of the window is listed
    // in the status line until the app answers each kind (see the playbook, "Requests from the engine").
    suspend fun execute(c: CommandInfo, params: JsonObject, show: Boolean): String? {
        val r = onEngine { execute(c.id, params) to runCatching { uiRequests() }.getOrDefault(emptyList()) }
        val (answer, requests) = r.getOrElse { return (it as? EngineException)?.message ?: it.message ?: "error" }
        revision++
        status = requests.joinToString("; ") { "the engine asks: $it" }
        if (show && PROFILE.showsResult(c, answer)) result = c to answer
        return null
    }

    fun run(id: String) {
        val c = byId[id] ?: return
        if (c.needsInput) { form = c; return }
        scope.launch { execute(c, JsonObject(emptyMap()), show = true)?.let { status = it } }
    }

    LaunchedEffect(engine) {
        val commands = onEngine { commands() }.getOrElse { status = "engine.commands failed: ${it.message}"; null }
        catalog = parseCommands(commands, PROFILE)
        ribbon = parseRibbon(commands, PROFILE)
    }
    LaunchedEffect(revision) {
        val (d, o) = parseState(onEngine { commandState() }.getOrNull())
        disabled = d
        on = o
    }

    if (showCommands) {
        AllCommandsDialog(catalog, PROFILE, null, disabled, on, icon = {}, onRun = ::run, onOptions = { id -> form = byId[id] }, onDismiss = { showCommands = false })
    }
    form?.let { c ->
        CommandFormDialog(c, pickFile = pickFile, writesFile = { id -> "export" in id.lowercase() || "save" in id.lowercase() }, run = { p -> execute(c, p, show = true) }, onDismiss = { form = null })
    }
    result?.let { (c, answer) -> ResultDialog(c, answer, onChange = if (c.hasParams) ({ result = null; form = c }) else null, onDismiss = { result = null }) }
    dialog?.let { d ->
        d.show({ id, params -> byId[id]?.let { c -> scope.launch { execute(c, params, show = false)?.let { status = it } } } ?: run { status = "no such command: $id" } }, { dialog = null })
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val sizeClass = if (maxWidth < 600.dp) SizeClass.Compact else if (maxWidth < 900.dp) SizeClass.Medium else SizeClass.Expanded
        Column(Modifier.fillMaxSize()) {
            CommandBar(
                ribbon, disabled, on,
                leading = {
                    AppMenu(themeChoice, setTheme, onDialog = { dialog = it })
                    IconButton(onClick = { showCommands = true }, modifier = Modifier.size(36.dp)) { Icon(Lucide.Search, contentDescription = "Search all commands", modifier = Modifier.size(18.dp)) }
                },
                onRun = ::run,
            )
            // A smaller window moves a feature, it never removes one: tools and panels go above the surface.
            if (sizeClass == SizeClass.Compact) {
                ToolStrip(tool, horizontal = true) { t -> tool = t.id; if (t.command.isNotEmpty()) run(t.command) }
                PanelChips(openPanel) { openPanel = if (openPanel == it) null else it }
                openPanel?.let { p -> PanelView(p, revision, { cmd -> onEngine { execute(cmd) }.getOrNull() }, { openPanel = null }, Modifier.fillMaxWidth().height(260.dp)) }
            }
            Row(Modifier.weight(1f).fillMaxWidth()) {
                if (sizeClass != SizeClass.Compact) ToolStrip(tool, horizontal = false) { t -> tool = t.id; if (t.command.isNotEmpty()) run(t.command) }
                surface(revision, Modifier.weight(1f).fillMaxHeight())
                if (sizeClass != SizeClass.Compact && PANELS.isNotEmpty()) {
                    Column(Modifier.width(320.dp).fillMaxHeight()) {
                        PanelChips(openPanel) { openPanel = if (openPanel == it) null else it }
                        openPanel?.let { p -> PanelView(p, revision, { cmd -> onEngine { execute(cmd) }.getOrNull() }, { openPanel = null }, Modifier.fillMaxWidth().weight(1f)) }
                    }
                }
            }
            HorizontalDivider()
            Text(
                status.ifEmpty { "${catalog.size} commands" }, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                color = if (status.isEmpty()) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

/** Where the document goes. Replace it: pass `surface` to [App] with what the engine renders. */
@Composable
fun SurfacePlaceholder(modifier: Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        Text("The document surface goes here.\nIt is drawn by hand on the desktop and is not translated.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The app's own menu: the theme, and the dialogs translated from the desktop front end. */
@Composable
private fun AppMenu(themeChoice: String, setTheme: (String) -> Unit, onDialog: (TranslatedDialog) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }, modifier = Modifier.size(36.dp)) {
        Icon(Lucide.Menu, contentDescription = "Menu", modifier = Modifier.size(18.dp))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (choice in listOf("system", "light", "dark")) {
                DropdownMenuItem(text = { Text((if (choice == themeChoice) "✓ " else "") + "Theme: " + choice.replaceFirstChar { it.uppercase() }) }, onClick = { open = false; setTheme(choice) })
            }
            if (TRANSLATED_DIALOGS.isNotEmpty()) HorizontalDivider()
            for (d in TRANSLATED_DIALOGS) DropdownMenuItem(text = { Text(d.title + "…") }, onClick = { open = false; onDialog(d) })
        }
    }
}

/** The desktop front end's tools: a column beside the surface, or a row above it in a narrow window. */
@Composable
private fun ToolStrip(current: String, horizontal: Boolean, onPick: (Tool) -> Unit) {
    if (TOOLS.isEmpty()) return
    val content: @Composable () -> Unit = {
        for (t in TOOLS) FilterChip(t.id == current, { onPick(t) }, label = { Text(t.title, maxLines = 1, overflow = TextOverflow.Ellipsis) }, modifier = Modifier.padding(horizontal = 2.dp))
    }
    Surface(tonalElevation = 1.dp) {
        if (horizontal) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp)) { content() }
        else Column(Modifier.width(184.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(4.dp)) { content() }
    }
}

@Composable
private fun PanelChips(open: Panel?, onToggle: (Panel) -> Unit) {
    if (PANELS.isEmpty()) return
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (p in PANELS) FilterChip(p == open, { onToggle(p) }, label = { Text(p.title, maxLines = 1) })
    }
}

/** A panel: what its source command answers, as lines; or a note that the desktop draws it by hand. */
@Composable
private fun PanelView(panel: Panel, revision: Int, read: suspend (String) -> JsonElement?, onClose: () -> Unit, modifier: Modifier) {
    var rows by remember(panel) { mutableStateOf<List<PaneRow>>(emptyList()) }
    LaunchedEffect(panel, revision) {
        rows = if (panel.source.isEmpty()) emptyList() else describeResult(read(panel.source)).map { PaneRow(it.name.ifEmpty { it.value }, if (it.name.isEmpty()) "" else it.value) }
    }
    val empty = if (panel.source.isEmpty()) "This panel is drawn by hand on the desktop and has not been built here yet." else "Nothing to show."
    Surface(tonalElevation = 2.dp, modifier = modifier) { PaneList(panel.title, PaneContent(rows, empty), "", {}, onClose) }
}

/**
 * A stand-in engine for looking at the UI before the FFI crate exists: it answers `engine.commands`
 * with a recorded list and refuses everything else, so menus, forms and the command list can be
 * seen and tested with nothing native.
 */
class FixtureTransport(private val commandsJson: String) : EngineTransport {
    override fun callRaw(method: String, paramsJson: String?): String = when (method) {
        "engine.commands" -> """{"ok":true,"result":$commandsJson}"""
        "loki.commandState" -> """{"ok":true,"result":{"disabled":{},"on":[]}}"""
        "loki.uiRequests" -> """{"ok":true,"result":[]}"""
        else -> """{"ok":false,"error":"preview: no engine is attached, so nothing runs"}"""
    }
    override fun close() {}
}
'''

MAIN_KT = r'''package __PKG__

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

// Generated by egui2compose. Until the FFI crate exists this opens the UI on the recorded command
// list; replace FixtureTransport with the platform binding (see the playbook, phase 1).
fun main() = application {
    val commands = object {}.javaClass.getResource("/engine-commands.json")?.readText() ?: "[]"
    Window(onCloseRequest = ::exitApplication, title = APP_NAME, state = rememberWindowState(size = DpSize(1200.dp, 800.dp))) {
        App(FixtureTransport(commands))
    }
}
'''

TEST_KT = r'''package __PKG__

import app.loki.kit.buildParams
import app.loki.kit.coverageProblems
import app.loki.kit.parseCommands
import app.loki.kit.parseParamForm
import app.loki.kit.parseRibbon
import app.loki.kit.runShotScript
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// Generated by egui2compose. The command list is `src/jvmTest/resources/engine-commands.json`:
// replace it with the engine's own `engine.commands` output and keep it in step (the playbook, phase 2).
class CoverageTest {
    private val raw = Json.parseToJsonElement(File("src/jvmTest/resources/engine-commands.json").readText())

    @Test
    fun everyCommandHasAWayToIt() {
        val named = PANELS.map { it.source }.filter { it.isNotEmpty() } + TOOLS.map { it.command }.filter { it.isNotEmpty() }
        // Lower maxGaps as gestures are built; it may never rise.
        val problems = coverageProblems(raw, PROFILE, parseRibbon(raw, PROFILE), { _, _ -> false }, named, emptySet(), maxGaps = __GAPS__)
            // Until an engine params doc is fixed, list here the ids whose form has no fields.
            .filterNot { line -> KNOWN_BARE_FORMS.any { line.startsWith("$it needs input") } }
        assertEquals(emptyList(), problems, problems.joinToString("\n"))
    }

    @Test
    fun everyFormCanBeBuiltWithNothingFilledIn() {
        val commands = parseCommands(raw, PROFILE)
        assertTrue(commands.size >= __MIN__, "only ${commands.size} commands: is the fixture cut short?")
        for (c in commands) buildParams(parseParamForm(c.params), emptyMap())
    }

    /** `LOKI_SHOTS=/tmp/shots gradle :app:jvmTest --tests '*CoverageTest*' --rerun` draws the app off screen at three widths. */
    @Test
    fun screenshots() {
        val dir = System.getenv("LOKI_SHOTS")?.takeIf { it.isNotBlank() } ?: return
        val script = System.getenv("LOKI_SHOT_SCRIPT") ?: "size=390x780@2; wait=1500; shot=phone.png; size=800x700@1; wait=1500; shot=tablet.png; size=1280x800@1; wait=1500; shot=desktop.png"
        runShotScript(File(dir), script) { App(FixtureTransport(raw.toString())) }
    }
}

/** Commands that need input but whose params doc gives the form nothing to draw: fix the doc in the engine, then remove the id. */
val KNOWN_BARE_FORMS: Set<String> = setOf(__BARE__)
'''

BUILD_KTS = r'''// Generated by egui2compose. Desktop (JVM) only as generated: add androidTarget() and the iOS
// targets with the platform bindings when the FFI crate exists (the playbook, phases 1 and 9).
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    jvm()
    sourceSets {
        commonMain.dependencies {
            implementation("app.loki:kit")
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(libs.coroutines.core)
            implementation(libs.serialization.json)
            implementation(libs.lucide)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.coroutines.swing)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

compose.desktop {
    application { mainClass = "__PKG__.MainKt" }
}
'''

SETTINGS_KTS = r'''// Generated by egui2compose.
rootProject.name = __SLUG__

pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
    versionCatalogs {
        // One set of versions for the suite: the kit's.
        create("libs") { from(files(__CATALOG__)) }
    }
}

// Loki Kit, until it has a home of its own: the build that holds it.
includeBuild(__KIT_BUILD__)

include(":app")
'''

ROOT_BUILD_KTS = r'''plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
}
'''


# --------------------------------------------------------------------------------------------
# The report
# --------------------------------------------------------------------------------------------

def gen_report(model: dict, notes: list[str]) -> str:
    cmds, dialogs = model["commands"], model["dialogs"]
    on_menu = [c for c in cmds if c["menu"]]
    with_params = [c for c in cmds if c["params"].strip() not in ("", "{}")]
    odd_docs = [c for c in cmds if c["params"].strip() and not c["params"].strip().startswith("{")]
    widgets = [w for d in dialogs for w in d["widgets"] if w["kind"] != "button"]
    with_cmd = [d for d in dialogs if d["commands"]]
    by_id = {c["id"]: c for c in cmds}
    matched = 0
    for d in dialogs:
        cmd = next((b.get("command") for b in d["widgets"] if b.get("command")), None) or (d["commands"][0] if d["commands"] else None)
        doc = by_id.get(cmd, {}).get("params", "")
        matched += sum(1 for w in d["widgets"] if w["kind"] != "button" and w["key"] and re.search(r'["{,\s]%s"?\s*\??\s*[:,}?]' % re.escape(w["key"]), doc))
    L = []
    L.append(f"# egui → Compose: {model['name']}\n")
    L.append(f"Generated by `egui2compose.py` from `{model['repoName']}`. This is a start to finish by hand, not a port.\n")
    L.append("## What was read\n")
    for n in notes:
        L.append(f"* {n}")
    L.append("")
    L.append("| | Count |")
    L.append("|---|---|")
    L.append(f"| Commands | {len(cmds)} |")
    L.append(f"| …on a menu or ribbon tab | {len(on_menu)} |")
    L.append(f"| …on no menu (reached from the all-commands list, a panel or a gesture) | {len(cmds) - len(on_menu)} |")
    L.append(f"| …that take parameters | {len(with_params)} |")
    L.append(f"| Top-level menus / tabs | {len(model['tabs'])} |")
    L.append(f"| Panels | {len(model['panels'])} ({sum(1 for p in model['panels'] if p.get('source'))} with a source command found) |")
    L.append(f"| Tools | {len(model['tools'])} ({sum(1 for t in model['tools'] if t.get('command'))} with a command found) |")
    L.append(f"| Dialog functions with input widgets | {model['dialogsFound']} ({len(dialogs)} translated) |")
    L.append(f"| Input widgets translated | {len(widgets)} |")
    L.append(f"| …whose guessed parameter name is in the command's params doc | {matched} |")
    L.append(f"| Dialogs whose command was found | {len(with_cmd)} of {len(dialogs)} |")
    L.append(f"| Theme colours found | {len(model['theme']['tokens'])} |")
    L.append(f"| Front-end-only methods of the desktop app | {len(model['frontEndMethods'])} |")
    L.append("")
    L.append("## Is the engine ready? (the playbook, §1)\n")
    L.append(f"* Command registry with id, label, menu, shortcut, params: **{'yes' if cmds else 'not found'}**.")
    L.append(f"* Params docs that are prose rather than `{{…}}`: {len(odd_docs)}" + (" (" + ", ".join(c['id'] for c in odd_docs[:8]) + ("…" if len(odd_docs) > 8 else "") + ")" if odd_docs else "") + ". These get a form with only a JSON box.")
    L.append(f"* Headless backend: {'found (`' + model['headless'] + '`)' if model['headless'] else '**not found by name**; the FFI crate needs one'}.")
    L.append(f"* Requests to the front end (a queue commands push to): {'found' if model['uiRequests'] else '**not found**; commands that need a picker or dialog will do nothing until the engine has one'}.")
    L.append("")
    L.append("## What was translated\n")
    L.append("* `Profile.kt`: the tabs in the desktop's order, and first guesses at the pointer, state-reader and typing sets.")
    L.append("* `Theme.kt`: light and dark schemes from the desktop theme's accent and backgrounds.")
    L.append("* `Workspace.kt`: the panels and tools the desktop declares.")
    L.append(f"* `dialogs/`: {len(dialogs)} dialogs, each with the desktop dialog's controls in its order.")
    L.append("* `App.kt`: a responsive shell on Loki Kit: command bar, all-commands list, generated forms, results, panels, tools, themes.")
    L.append("* `CoverageTest.kt`: the test that every command has a way to it, and off-screen screenshots.\n")
    L.append("## What was NOT translated (the work that is left)\n")
    L.append("* **The surface.** The canvas, page or timeline is painted by hand in egui. `App(surface = …)` takes its replacement; the engine's renderer supplies the pixels (the playbook, phase 5).")
    L.append("* **The FFI crate and platform bindings.** The app runs on `FixtureTransport` (a recorded command list, nothing executes) until they exist (phase 1).")
    L.append("* **Dialog behaviour.** Starting values, enabling rules, live previews and anything a dialog draws itself. Parameter names are guesses: see the table below.")
    L.append("* **Hand-drawn panels.** A panel with no source command shows a note. Each needs its own composable.")
    L.append("* **Custom widgets, context menus, drag and drop, docking layouts, keyboard handling, icons, translations.**")
    if model["frontEndMethods"]:
        L.append("* **Front-end-only behaviour** the desktop exposes on its control channel, each with no engine command behind it: " + ", ".join(f"`{m}`" for m in model["frontEndMethods"][:40]) + ("…" if len(model["frontEndMethods"]) > 40 else "") + ".")
    L.append("")
    if model["panels"]:
        L.append("## Panels\n")
        L.append("| Panel | Source command |")
        L.append("|---|---|")
        for p in model["panels"]:
            L.append(f"| {p['title']} | {'`' + p['source'] + '`' if p.get('source') else '— (hand-drawn: build it)'} |")
        L.append("")
    if dialogs:
        L.append("## Dialogs\n")
        L.append("| Dialog | From | Inputs | Command | Names matched |")
        L.append("|---|---|---|---|---|")
        for d in dialogs:
            cmd = next((b.get("command") for b in d["widgets"] if b.get("command")), None) or (d["commands"][0] if d["commands"] else None)
            doc = by_id.get(cmd, {}).get("params", "")
            ins = [w for w in d["widgets"] if w["kind"] != "button"]
            ok = sum(1 for w in ins if w["key"] and re.search(r'["{,\s]%s"?\s*\??\s*[:,}?]' % re.escape(w["key"]), doc))
            L.append(f"| {d['title']} | `{d['file']}:{d['line']}` | {len(ins)} | {'`' + cmd + '`' if cmd else '—'} | {ok} of {len(ins)} |")
        L.append("")
        L.append("\"Names matched\" counts the controls whose guessed parameter name appears in the command's params doc. A low count means the dialog's OK button will send keys the command does not know: fix the names in the generated file.\n")
    L.append("## Next\n")
    L.append("1. Replace `app/src/jvmTest/resources/engine-commands.json` with the engine's own `engine.commands` output and run `gradle :app:jvmTest`. The coverage test's lines are the to-do list.")
    L.append("2. Follow `docs/kmp-responsive-ui-playbook.md` in Loki Text from phase 1 (the FFI crate). Phases 3 and 4 are what this script did.")
    L.append("3. Look at it: `LOKI_SHOTS=/tmp/shots gradle :app:jvmTest --tests '*CoverageTest*' --rerun`.")
    return "\n".join(L) + "\n"


# --------------------------------------------------------------------------------------------
# Putting it together
# --------------------------------------------------------------------------------------------

def find_first(root: str, pattern: str, exts=(".rs",)) -> str:
    rx = re.compile(pattern)
    for d, dirs, files in os.walk(root):
        dirs[:] = [x for x in dirs if x not in ("target", ".git")]
        for f in sorted(files):
            if f.endswith(exts):
                p = os.path.join(d, f)
                if not is_test_path(p) and rx.search(read_text(p)):
                    return os.path.relpath(p, root)
    return ""


def guess_command(name: str, title: str, ids: set, kinds: tuple) -> str:
    """An engine command for a panel or tool, by name: `layers.list` for the Layers panel, `tool.pen` for the Pen tool."""
    stems = {name[:1].lower() + name[1:], name.lower(), re.sub(r"\W", "", title).lower(), title.lower().replace(" ", "_")}
    stems |= {s[:-1] for s in list(stems) if s.endswith("s")} | {s + "s" for s in list(stems)}
    low = {i.lower(): i for i in ids}
    for stem in stems:
        for k in kinds:
            for cand in (k.replace("*", stem),):
                if cand.lower() in low:
                    return low[cand.lower()]
    return ""


def build_model(args, notes: list[str]) -> dict:
    repo = os.path.abspath(args.repo)
    engine_src = os.path.join(repo, args.engine)
    ui_src = os.path.join(repo, args.ui)
    for label, p in (("engine crate", engine_src), ("egui crate", ui_src)):
        if not os.path.isdir(p):
            raise SystemExit(f"egui2compose: no {label} at {p} (use --engine / --ui to say where it is)")
    commands = load_commands(args.commands, notes) if args.commands else scan_commands(engine_src, notes)
    if not commands:
        raise SystemExit("egui2compose: found no commands. Pass --commands with the engine's own `engine.commands` JSON.")
    ids = {c["id"] for c in commands}
    tabs = menu_order(ui_src, commands)

    ui_ids = set()
    for path in rust_files(ui_src):
        ui_ids |= {i for i in re.findall(r'"([a-z][A-Za-z0-9_]*(?:\.[A-Za-z0-9_]+)+)"', read_text(path)) if i in ids}
    sets = classify_commands(commands, ui_ids)

    enums = scan_enums([ui_src, engine_src], ("Panel", "PanelKind", "PanelId", "Pane", "PaneKind", "Tool", "ToolKind", "ToolId", "Dialog", "DialogKind"))
    panels, tools = [], []
    for name in ("PanelKind", "Panel", "PanelId", "PaneKind", "Pane"):
        if name in enums and not panels:
            for v in enums[name]["variants"]:
                panels.append({"name": v["name"], "title": v["title"], "source": guess_command(v["name"], v["title"], ids, ("*.list", "*.state", "*.get", "*.info", "*s.list", "*.inspect"))})
    for name in ("Tool", "ToolKind", "ToolId"):
        if name in enums and not tools:
            for v in enums[name]["variants"]:
                tools.append({"name": v["name"], "title": v["title"], "command": guess_command(v["name"], v["title"], ids, ("tool.*", "tools.*", "tool.select.*", "tools.select.*", "*.tool", "draw.*"))})
    declared_dialogs = [v["name"] for n in ("Dialog", "DialogKind") if n in enums for v in enums[n]["variants"]]

    all_dialogs = scan_dialogs(ui_src, ids, 100000)
    dialogs = all_dialogs[:args.max_dialogs]
    # Two dialogs must not share a Kotlin name.
    seen = {}
    for d in dialogs:
        base = ident(d["name"], upper=True)
        seen[base] = seen.get(base, 0) + 1
        if seen[base] > 1:
            d["name"] = f"{d['name']}_{seen[base]}"
    theme = scan_theme(ui_src)
    name = args.name or humanize(os.path.basename(repo.rstrip("/")))
    if dialogs or declared_dialogs:
        notes.append(f"The desktop front end declares {len(declared_dialogs)} dialogs in an enum and has {len(all_dialogs)} functions that draw input widgets; the {len(dialogs)} with the most inputs were translated (--max-dialogs).")
    return {
        "name": name, "repoName": os.path.basename(repo.rstrip("/")), "tabs": tabs, "commands": commands, "sets": sets,
        "panels": panels, "tools": tools, "declaredDialogs": declared_dialogs, "dialogs": dialogs, "dialogsFound": len(all_dialogs),
        "theme": theme, "palette": pick_palette(theme), "frontEndMethods": scan_front_end_methods(ui_src),
        "headless": find_first(os.path.join(repo, "crates"), r"pub struct Headless\b"),
        "uiRequests": bool(find_first(engine_src, r"ui_requests|push_ui_request|UiRequest\b")),
    }


def write(path: str, text: str):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8") as f:
        f.write(text)


def emit(model: dict, args, notes: list[str]):
    out = os.path.abspath(args.out)
    pkg = args.package or "app.loki." + re.sub(r"[^a-z0-9]", "", model["repoName"].lower())
    src = os.path.join(out, "app", "src", "commonMain", "kotlin", *pkg.split("."))
    kit_build = os.path.abspath(args.kit)
    if not os.path.isfile(os.path.join(kit_build, "kit", "build.gradle.kts")):
        raise SystemExit(f"egui2compose: no Loki Kit at {kit_build}/kit (use --kit to name the folder that holds it)")
    by_id = {c["id"]: c for c in model["commands"]}
    fixture = [{"id": c["id"], "label": c["label"], "menu": c["menu"], "shortcut": c["shortcut"], "params": c["params"], "undoable": c["mutates"]} for c in model["commands"]]
    fixture_text = "[\n" + ",\n".join(json.dumps(c, ensure_ascii=False) for c in fixture) + "\n]\n"
    bare = [c["id"] for c in model["commands"] if needs_input_without_fields(c["params"])]

    write(os.path.join(src, "Profile.kt"), gen_profile(pkg, model))
    write(os.path.join(src, "Theme.kt"), gen_theme(pkg, model))
    write(os.path.join(src, "Workspace.kt"), gen_workspace(pkg, model))
    write(os.path.join(src, "App.kt"), APP_KT.replace("__PKG__", pkg).replace("__NAME__", kt(model["name"])))
    for d in model["dialogs"]:
        write(os.path.join(src, "dialogs", ident(d["name"], upper=True) + "Dialog.kt"), gen_dialog(pkg, d, by_id))
    write(os.path.join(src, "dialogs", "TranslatedDialogs.kt"), gen_dialog_index(pkg, model["dialogs"]))
    write(os.path.join(out, "app", "src", "jvmMain", "kotlin", *pkg.split("."), "Main.kt"), MAIN_KT.replace("__PKG__", pkg))
    write(os.path.join(out, "app", "src", "jvmMain", "resources", "engine-commands.json"), fixture_text)
    write(os.path.join(out, "app", "src", "jvmTest", "resources", "engine-commands.json"), fixture_text)
    write(os.path.join(out, "app", "src", "jvmTest", "kotlin", *pkg.split("."), "CoverageTest.kt"),
          TEST_KT.replace("__PKG__", pkg).replace("__GAPS__", str(len(model["sets"]["pointer"]))).replace("__MIN__", str(max(1, len(model["commands"]) * 9 // 10)))
          .replace("__BARE__", kt_list(bare)))
    write(os.path.join(out, "app", "build.gradle.kts"), BUILD_KTS.replace("__PKG__", pkg))
    write(os.path.join(out, "settings.gradle.kts"), SETTINGS_KTS.replace("__SLUG__", kt(re.sub(r"[^a-z0-9-]", "-", model["repoName"].lower()) + "-compose"))
          .replace("__CATALOG__", kt(os.path.join(kit_build, "gradle", "libs.versions.toml"))).replace("__KIT_BUILD__", kt(kit_build)))
    write(os.path.join(out, "build.gradle.kts"), ROOT_BUILD_KTS)
    props = os.path.join(kit_build, "gradle.properties")
    if os.path.isfile(props):
        shutil.copyfile(props, os.path.join(out, "gradle.properties"))
    write(os.path.join(out, ".gitignore"), "build/\n.gradle/\n.kotlin/\nlocal.properties\n")
    write(os.path.join(out, "ui-model.json"), json.dumps(model, ensure_ascii=False, indent=1))
    write(os.path.join(out, "REPORT.md"), gen_report(model, notes))


def needs_input_without_fields(params: str) -> bool:
    """Does this doc name a required key in a way only the JSON box can take: alternatives of whole shapes with no readable key?"""
    p = params.strip()
    if not p.startswith("{"):
        return False
    close = match_close(p, 0)
    if close < 0:
        return False
    body = p[1:close].strip()
    return bool(re.match(r"^\|\s*\{", p[close + 1:].strip())) and not re.search(r'"?[A-Za-z_]\w*"?\s*\??\s*[:,}]|^"?[A-Za-z_]\w*"?\??$', body)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="Start a Compose Multiplatform front end for a Craft app from its egui one.", epilog="See the module docstring and docs/egui2compose.md.")
    ap.add_argument("--repo", required=True, help="the Craft repository to read")
    ap.add_argument("--out", required=True, help="where to write the Gradle project (created; existing generated files are overwritten)")
    ap.add_argument("--commands", help="the engine's own `engine.commands` JSON; without it commands are read from source")
    ap.add_argument("--name", help="the app's name (default: from the repository's folder)")
    ap.add_argument("--package", help="the Kotlin package (default: app.loki.<repo>)")
    ap.add_argument("--engine", default="crates/engine/src", help="the engine crate's source, relative to --repo")
    ap.add_argument("--ui", default="crates/ui-egui/src", help="the egui crate's source, relative to --repo")
    ap.add_argument("--kit", default=os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."), help="the folder that holds Loki Kit (its `kit/` and `gradle/`)")
    ap.add_argument("--max-dialogs", type=int, default=40, help="translate at most this many dialogs, those with the most inputs first")
    ap.add_argument("--model-only", action="store_true", help="write only ui-model.json and REPORT.md")
    args = ap.parse_args(argv)
    args.max_dialogs = max(0, min(args.max_dialogs, 500))
    notes: list[str] = []
    model = build_model(args, notes)
    if args.model_only:
        out = os.path.abspath(args.out)
        write(os.path.join(out, "ui-model.json"), json.dumps(model, ensure_ascii=False, indent=1))
        write(os.path.join(out, "REPORT.md"), gen_report(model, notes))
    else:
        emit(model, args, notes)
    c = model["commands"]
    print(f"{model['name']}: {len(c)} commands, {len(model['tabs'])} tabs, {len(model['panels'])} panels, {len(model['tools'])} tools, "
          f"{len(model['dialogs'])}/{model['dialogsFound']} dialogs, {len(model['theme']['tokens'])} theme colours -> {os.path.abspath(args.out)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
