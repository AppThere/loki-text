#!/usr/bin/env python3
"""Wire the dialogs egui2compose wrote to the commands they run.

egui2compose names a dialog's parameters after the Rust fields its controls were bound to, and takes
a choice's values from the Rust expressions it found, so an OK button often sends keys the command
does not know. This reads each dialog (`app/src/commonMain/kotlin/**/dialogs/*Dialog.kt`), the
command's params doc from the engine's own command list, and for every control:

  * keeps its key when the doc names it;
  * otherwise matches it to the doc's key by name, label and type, and renames it when the match is
    clear (one candidate, well ahead of the next, of a kind the control can supply);
  * puts it inside the doc's nested list or object when that is where the key lives
    (`keys: [{column, order, by}]`);
  * cleans a choice's values (`"values".into()` is `values`) and sends a choice of true and false as a
    boolean;
  * says what it could not settle, and which required keys no control supplies.

    wire_dialogs.py --app <app dir> --commands engine-commands.json            # report
    wire_dialogs.py --app <app dir> --commands engine-commands.json --write    # fix what is clear
    wire_dialogs.py ... --check                                                # exit 1 if any key is unknown

An app owns its dialogs once they are written, so this edits them in place and never regenerates.
"""
import argparse
import glob
import json
import os
import re
import sys
from dataclasses import dataclass, field

# ---------------------------------------------------------------- the params doc


@dataclass
class Param:
    name: str
    optional: bool = False
    kind: str = "any"                    # bool | number | enum | text | array | object | any
    options: list = field(default_factory=list)
    children: list = field(default_factory=list)   # the keys of an object, or of the objects in an array


def match_close(s: str, i: int) -> int:
    """The index of the bracket that closes the one at s[i], skipping strings; -1 if it never does."""
    pairs = {"{": "}", "[": "]", "(": ")"}
    stack = []
    q = None
    j = i
    while j < len(s):
        c = s[j]
        if q:
            if c == "\\":
                j += 1
            elif c == q:
                q = None
        elif c in "\"'" and (c == '"' or not (j > 0 and s[j - 1].isalnum())):
            q = c
        elif c in pairs:
            stack.append(pairs[c])
        elif c in ")]}":
            if not stack or stack.pop() != c:
                return -1
            if not stack:
                return j
        j += 1
    return -1


def split_top(s: str, sep: str = ",") -> list:
    out, depth, q, cur = [], 0, None, []
    i = 0
    while i < len(s):
        c = s[i]
        if q:
            cur.append(c)
            if c == "\\" and i + 1 < len(s):
                i += 1
                cur.append(s[i])
            elif c == q:
                q = None
        elif c == '"':
            q = c
            cur.append(c)
        elif c in "{[(":
            depth += 1
            cur.append(c)
        elif c in "}])":
            depth -= 1
            cur.append(c)
        elif c == sep and depth == 0:
            out.append("".join(cur))
            cur = []
        else:
            cur.append(c)
        i += 1
    if "".join(cur).strip():
        out.append("".join(cur))
    return out


NUMBER_WORDS = {"pt", "px", "dp", "deg", "percent", "number", "num", "int", "float", "f64", "f32", "u8", "u16", "u32", "u64", "i32", "i64",
                "index", "usize", "twips", "emu", "seconds", "ms", "ratio", "angle", "degrees", "radians", "points", "pixels", "n"}


def classify(typ: str) -> tuple:
    """(kind, options, children) of the text after a key's colon."""
    t = typ.strip()
    if not t:
        return "any", [], []
    if t[0] == "[":
        end = match_close(t, 0)
        inner = t[1:end].strip() if end > 0 else t[1:]
        if inner.startswith("{"):
            e2 = match_close(inner, 0)
            return "array", [], parse_keys(inner[1:e2]) if e2 > 0 else []
        return "array", [], []
    if t[0] == "{":
        end = match_close(t, 0)
        return "object", [], parse_keys(t[1:end]) if end > 0 else []
    head = re.split(r"[\s(]", t, maxsplit=1)[0].strip(",")
    if head in ("bool", "boolean"):
        return "bool", [], []
    words = [w.strip().strip('"') for w in re.split(r"\s*\|\s*", re.split(r"\s*[(—→]", t, maxsplit=1)[0].strip())]
    if len(words) > 1 and all(re.fullmatch(r"[A-Za-z0-9_+\-./]+", w) for w in words):
        return "enum", words, []
    if head.lower() in NUMBER_WORDS or re.match(r"^-?\d", head):
        return "number", [], []
    return "text", [], []


def parse_keys(body: str) -> list:
    keys = []
    for item in split_top(body):
        m = re.match(r'\s*((?:"?[A-Za-z_][\w.]*"?)(?:\s*\|\s*"?[A-Za-z_][\w.]*"?)*)(\?)?\s*(?::\s*(.*))?$', item, re.S)
        if not m:
            continue
        names = [n.strip().strip('"') for n in m.group(1).split("|")]
        kind, opts, kids = classify(m.group(3) or "")
        for n in names:
            keys.append(Param(n, optional=bool(m.group(2)), kind=kind, options=opts, children=kids))
    return keys


def parse_doc(doc: str) -> list:
    """The keys of the first `{…}` in a params doc; empty when it has none."""
    i = (doc or "").find("{")
    if i < 0:
        return []
    j = match_close(doc, i)
    return parse_keys(doc[i + 1:j]) if j > 0 else []


# ---------------------------------------------------------------- a dialog


@dataclass
class Field:
    index: int
    kind: str                 # bool | slider | text | number | choice
    label: str = ""
    options: list = field(default_factory=list)    # [(value text as written, label)]
    key: str = ""
    put_line: int = -1
    style: str = ""           # flat | blank | number
    path: tuple = ()


@dataclass
class Dialog:
    path: str
    lines: list
    title: str = ""
    command: str = ""
    fields: list = field(default_factory=list)
    puts_start: int = -1
    puts_end: int = -1


KSTR = r'"((?:[^"\\]|\\.)*)"'


def kunescape(s: str) -> str:
    try:
        return json.loads('"' + s.replace("\\$", "$") + '"')
    except ValueError:
        return s


def read_dialog(path: str) -> Dialog:
    text = open(path, encoding="utf-8").read()
    d = Dialog(path, text.split("\n"))
    m = re.search(r"SheetDialog\(\s*" + KSTR, text)
    d.title = kunescape(m.group(1)) if m else os.path.basename(path)
    m = re.search(r'onRun\("([^"]+)",\s*params\)', text)
    d.command = m.group(1) if m else ""
    kinds = {}
    for m in re.finditer(r'var f(\d+) by remember \{ mutableStateOf\((false|"[^"]*"|[-\d.]+f)\) \}', text):
        i = int(m.group(1))
        v = m.group(2)
        kinds[i] = "bool" if v == "false" else "slider" if v.endswith("f") else "text"
    fields = {i: Field(i, k) for i, k in kinds.items()}
    # The puts, between `buildJsonObject {` and the call that runs the command.
    for n, line in enumerate(d.lines):
        if "val params = buildJsonObject {" in line:
            d.puts_start = n + 1
        elif d.puts_start >= 0 and d.puts_end < 0 and re.match(r"^\s*(onRun\(|/\* TODO)", line):
            d.puts_end = n - 1
    container = None
    depth = 0
    for n in range(max(d.puts_start, 0), max(d.puts_end, 0)):
        line = d.lines[n]
        open_m = re.match(r'^\s*put\("([^"]+)",\s*build(?:JsonArray|JsonObject) \{\s*$', line)
        if open_m and container is None:
            container, depth = open_m.group(1), 1
            continue
        if container is not None:
            depth += line.count("{") - line.count("}")
            if depth <= 0:
                container = None
                continue
        for pat, style in ((r'^\s*put\("([^"]+)",\s*f(\d+)\)\s*$', "flat"),
                           (r'^\s*if \(f(\d+)\.isNotBlank\(\)\) put\("([^"]+)",\s*f\1\)\s*$', "blank"),
                           (r"^\s*f(\d+)\.trim\(\)\.replace\(',', '\.'\)\.toDoubleOrNull\(\)\?\.let \{ put\(\"([^\"]+)\", it\) \}\s*$", "number"),
                           # What this script writes: a flag for one value of an enum, a true/false choice as a boolean.
                           (r'^\s*(?:if \(f(\d+)(?:\.isNotBlank\(\)| == "true")?\) )?put\("([^"]+)",.*\bf\1\b.*\)\s*$', "other")):
            m = re.match(pat, line)
            if not m:
                continue
            a_, b_ = m.group(1), m.group(2)
            i, key = (int(b_), a_) if style == "flat" else (int(a_), b_)
            if i in fields:
                fields[i].key, fields[i].put_line, fields[i].style = key, n, style
                fields[i].path = (container,) if container else ()
                if style == "number":
                    fields[i].kind = "number"
            break
    # The controls, for their labels and a choice's values.
    ui = d.lines[max(d.puts_end, 0):]
    last_text = ""
    for n, line in enumerate(ui):
        t = re.search(r"\bText\(" + KSTR, line)
        if t and "label = { Text(" not in line and "FilterChip" not in line:
            last_text = kunescape(t.group(1))
        m = re.search(r"OutlinedTextField\(f(\d+),.*?label = \{ Text\(" + KSTR, line)
        if m and int(m.group(1)) in fields:
            fields[int(m.group(1))].label = kunescape(m.group(2))
            if "KeyboardType.Decimal" in line:
                fields[int(m.group(1))].kind = "number"
        m = re.search(r"Checkbox\(f(\d+),", line)
        if m and int(m.group(1)) in fields:
            nxt = re.search(r"Text\(" + KSTR, ui[n + 1]) if n + 1 < len(ui) else None
            fields[int(m.group(1))].label = kunescape(nxt.group(1)) if nxt else ""
        m = re.search(r"Slider\(f(\d+),", line)
        if m and int(m.group(1)) in fields:
            fields[int(m.group(1))].label = last_text
        m = re.search(r"FilterChip\(f(\d+) == " + KSTR + r",.*?label = \{ Text\(" + KSTR, line)
        if m and int(m.group(1)) in fields:
            f = fields[int(m.group(1))]
            f.kind = "choice"
            f.label = f.label or last_text
            f.options.append((kunescape(m.group(2)), kunescape(m.group(3))))
    d.fields = [fields[i] for i in sorted(fields)]
    return d


# ---------------------------------------------------------------- matching


def norm(s: str) -> str:
    return re.sub(r"[^a-z0-9]", "", s.lower())


def tokens(s: str) -> set:
    s = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", s)
    return {w for w in re.split(r"[^A-Za-z0-9]+", s.lower()) if w}


SYNONYMS = {"wb": {"workbook", "within"}, "w": {"width"}, "h": {"height"}, "desc": {"order", "descending"}, "asc": {"order"},
            "col": {"column", "columns"}, "cols": {"columns", "column"}, "pct": {"percent"}, "rot": {"rotation", "rot", "angle"},
            "num": {"number", "count"}, "val": {"value"}, "case": {"matchcase"}, "fmt": {"format", "numfmt"}}


def clean_value(v: str) -> str:
    """A choice's value as the engine reads it: a Rust `"x".into()` or `String::from("x")` is x."""
    for pat in (r'^"(.*)"\.(?:into|to_string|to_owned|to_lowercase)\(\)$', r'^String::from\("(.*)"\)$', r'^"(.*)"$'):
        m = re.match(pat, v.strip())
        if m:
            return m.group(1)
    return v.strip()


def is_flag_choice(f: Field) -> bool:
    return f.kind == "choice" and {clean_value(v) for v, _ in f.options} <= {"true", "false"}


def enum_pick(f: Field, p: Param):
    """For a checkbox or a true/false choice that stands for one value of an enum key: that value, and the one for the other state."""
    if p.kind != "enum" or not (f.kind == "bool" or is_flag_choice(f)):
        return None
    words = tokens(f.key) | tokens(f.label)
    hit = [o for o in p.options if norm(o) in {norm(w) for w in words} or norm(o) == norm(f.key)]
    if len(hit) != 1:
        return None
    others = [o for o in p.options if o != hit[0]]
    return hit[0], (others[0] if len(others) == 1 else None)


def compatible(f: Field, p: Param) -> bool:
    if p.kind in ("array", "object"):
        return False
    if enum_pick(f, p):
        return True
    if f.kind == "bool":
        return p.kind in ("bool", "any")
    if f.kind in ("slider", "number"):
        return p.kind in ("number", "any")
    if f.kind == "choice":
        vals = [clean_value(v) for v, _ in f.options]
        if set(vals) <= {"true", "false"}:
            return p.kind in ("bool", "any")
        if p.kind == "enum":
            return True
        return p.kind in ("text", "any")
    return p.kind in ("text", "any", "enum", "number")


def score(f: Field, p: Param) -> int:
    k, n = norm(f.key), norm(p.name)
    if not compatible(f, p):
        return 0
    s = 0
    if f.key == p.name:
        s = 100
    elif k and k == n:
        s = 95
    else:
        # One inside the other counts only when it is a whole word of the other (case in matchCase), not letters (rot in protection).
        short, long_, words = (k, n, tokens(p.name)) if len(k) <= len(n) else (n, k, tokens(f.key))
        if len(short) >= 3 and (short in words or (long_.startswith(short) and len(short) >= 5)):
            s = max(s, 70 + min(15, int(15 * len(short) / len(long_))))
        if n in {norm(x) for x in SYNONYMS.get(f.key.lower(), ())}:
            s = max(s, 82)
        pt = tokens(p.name)
        lt = tokens(f.label) | tokens(f.key)
        if pt and lt:
            # A word counts when the control's word is it or an abbreviation of it (doc for document, desc for descending).
            got = sum(1 for w in pt if w in lt or any(len(x) >= 3 and w.startswith(x) for x in lt))
            overlap = got / len(pt)
            s = max(s, int(65 * overlap) if overlap >= 0.5 else 0)
        if norm(f.label) == n:
            s = max(s, 90)
    if enum_pick(f, p):
        s = max(s, 78)
    if f.kind == "choice" and p.kind == "enum" and not enum_pick(f, p):
        vals = {norm(clean_value(v)) for v, _ in f.options}
        have = {norm(o) for o in p.options}
        if vals and vals <= have:
            s += 15
        elif vals and not (vals & have):
            s = max(0, s - 25)
    return min(s, 100)


CLEAR = 60     # a match at least this good ...
MARGIN = 10    # ... and this far ahead of the runner-up is taken


@dataclass
class Verdict:
    field: Field
    state: str                      # ok | rename | nest | unknown | no-doc
    to: str = ""
    container: str = ""
    container_kind: str = ""
    candidates: list = field(default_factory=list)
    notes: list = field(default_factory=list)
    pick: tuple = None            # (value for true, value for false) when a flag stands for a value of an enum key


def resolve(d: Dialog, keys: list) -> list:
    flat = [p for p in keys]
    by_name = {p.name: p for p in flat}
    nested = [(c, ch) for c in flat if c.kind in ("array", "object") for ch in c.children]
    out = []
    claimed = set()
    for f in d.fields:
        if not f.key:
            out.append(Verdict(f, "unknown", notes=["its value is not put anywhere (hand-edited, or not translated)"]))
            continue
        if f.path:
            c = by_name.get(f.path[0])
            child = next((ch for ch in (c.children if c else []) if ch.name == f.key), None)
            v = Verdict(f, "ok" if child else "unknown", to=f.key)
            if not child:
                v.notes.append(f"{f.path[0]} has no key {f.key}")
            claimed.add(f.key)
            out.append(v)
            continue
        if f.key in by_name and by_name[f.key].kind not in ("array", "object"):
            v = Verdict(f, "ok", to=f.key)
            if not compatible(f, by_name[f.key]):
                v.notes.append(f"the control gives {f.kind}, the doc says {by_name[f.key].kind}")
            claimed.add(f.key)
            out.append(v)
            continue
    done = {v.field.index for v in out}
    pending = [f for f in d.fields if f.index not in done and f.key]
    scored = []
    for f in pending:
        flat_s = sorted(((score(f, p), p.name, None) for p in flat if p.name not in claimed), key=lambda t: (t[0], t[1]), reverse=True)
        nest_s = sorted(((score(f, ch), ch.name, c) for c, ch in nested), key=lambda t: (t[0], t[1]), reverse=True)
        scored.append((f, flat_s, nest_s))
    # Take the most certain matches first, so one control does not take another's key.
    order = sorted(scored, key=lambda t: -max([s for s, _, _ in t[1][:1]] + [s for s, _, _ in t[2][:1]] + [0]))
    verdicts = {}
    for f, flat_s, nest_s in order:
        flat_s = [(s, n, c) for s, n, c in flat_s if n not in claimed]
        best = flat_s[0] if flat_s else (0, "", None)
        nbest = nest_s[0] if nest_s else (0, "", None)
        use_nested = nbest[0] > best[0] and nbest[0] >= CLEAR
        top, rest = (nbest, [x for x in nest_s[1:]]) if use_nested else (best, flat_s[1:])
        runner = rest[0][0] if rest else 0
        cands = [f"{n} ({s})" for s, n, _ in (flat_s[:3] if not use_nested else nest_s[:3]) if s > 0]
        if top[0] >= CLEAR and top[0] - runner >= MARGIN:
            if use_nested:
                c = top[2]
                param = next((ch for ch in c.children if ch.name == top[1]), None)
                verdicts[f.index] = Verdict(f, "nest", to=top[1], container=c.name, container_kind=c.kind, candidates=cands, pick=enum_pick(f, param) if param else None)
            else:
                claimed.add(top[1])
                verdicts[f.index] = Verdict(f, "rename", to=top[1], candidates=cands, pick=enum_pick(f, by_name[top[1]]))
        else:
            verdicts[f.index] = Verdict(f, "unknown", candidates=cands, notes=["no clear match"] if cands else ["nothing in the doc fits"])
    final = []
    seen = {v.field.index: v for v in out}
    seen.update(verdicts)
    for f in d.fields:
        final.append(seen[f.index])
    return final


def missing_required(keys: list, verdicts: list) -> list:
    given = {v.to for v in verdicts if v.state in ("ok", "rename")} | {v.container for v in verdicts if v.state == "nest"}
    return [p.name for p in keys if not p.optional and p.name not in given]


# ---------------------------------------------------------------- the edit


def put_line_for(v: Verdict, key: str, indent: str) -> str:
    f = v.field
    expr = f"f{f.index}"
    if v.pick:
        yes, no = v.pick
        if f.kind == "bool":
            return f'{indent}if (f{f.index}) put("{key}", "{yes}")'
        if no is None:
            return f'{indent}if (f{f.index} == "true") put("{key}", "{yes}")'
        return f'{indent}if (f{f.index}.isNotBlank()) put("{key}", if (f{f.index} == "true") "{yes}" else "{no}")'
    if f.kind == "choice" and {clean_value(o) for o, _ in f.options} <= {"true", "false"}:
        if f.style == "blank":
            return f'{indent}if (f{f.index}.isNotBlank()) put("{key}", f{f.index} == "true")'
        return f'{indent}put("{key}", f{f.index} == "true")'
    if f.style == "flat":
        return f'{indent}put("{key}", {expr})'
    if f.style == "number":
        return f"{indent}f{f.index}.trim().replace(',', '.').toDoubleOrNull()?.let {{ put(\"{key}\", it) }}"
    return f'{indent}if (f{f.index}.isNotBlank()) put("{key}", {expr})'


def apply(d: Dialog, verdicts: list) -> tuple:
    """The dialog's new lines and how many controls were changed."""
    lines = list(d.lines)
    changed = 0
    nests = {}
    for v in verdicts:
        f = v.field
        if f.put_line < 0 or (f.path and v.state == "ok"):
            continue
        indent = re.match(r"\s*", lines[f.put_line]).group(0)
        if v.state == "rename" or (v.state == "ok" and f.kind == "choice" and f.style in ("flat", "blank") and not f.path):
            key = v.to
            new = put_line_for(v, key, indent)
            if new != lines[f.put_line]:
                lines[f.put_line] = new
                changed += 1
        elif v.state == "nest":
            nests.setdefault((v.container, v.container_kind), []).append(v)
            lines[f.put_line] = None
            changed += 1
    # A choice's values: the engine's, not the Rust expression that was found.
    for v in verdicts:
        f = v.field
        if f.kind != "choice":
            continue
        for raw, _ in f.options:
            clean = clean_value(raw)
            if clean != raw:
                esc_raw = json.dumps(raw)[1:-1].replace("$", "\\$")
                for n, line in enumerate(lines):
                    if line is not None and f"f{f.index} == \"{esc_raw}\"" in line:
                        lines[n] = line.replace(f"\"{esc_raw}\"", json.dumps(clean))
    # Fields that belong inside a doc's list or object go into one.
    insert_at = d.puts_end + 1
    extra = []
    for (cname, ckind), vs in nests.items():
        inner = "\n".join(put_line_for(v, v.to, "                        ") for v in vs)
        if ckind == "array":
            extra.append(f'                put("{cname}", buildJsonArray {{\n                    add(buildJsonObject {{\n{inner}\n                    }})\n                }})')
        else:
            extra.append(f'                put("{cname}", buildJsonObject {{\n{inner}\n                }})')
    out = []
    for n, line in enumerate(lines):
        if n == insert_at and extra:
            out.extend(extra)
        if line is not None:
            out.append(line)
    text = "\n".join(out)
    if any(nests) and "import kotlinx.serialization.json.buildJsonArray" not in text and any(k[1] == "array" for k in nests):
        text = text.replace("import kotlinx.serialization.json.buildJsonObject", "import kotlinx.serialization.json.buildJsonArray\nimport kotlinx.serialization.json.buildJsonObject", 1)
    return text, changed


# ---------------------------------------------------------------- the report


def suggest_commands(title: str, commands: list) -> list:
    t = norm(re.sub(r"\b(\w+)\s+\1\b", r"\1", title.lower()))
    out = []
    for c in commands:
        label = norm(re.sub(r"[….]", "", c.get("label", "")))
        tail = norm(c["id"].split(".")[-1])
        s = 100 if label == t else 85 if t and (t in label or label in t) and len(label) >= 4 else 70 if tail and tail == t else 0
        if s:
            out.append((s, c["id"]))
    return sorted(out, reverse=True)[:3]


GENERIC = {"dlg", "dialog", "ui", "body", "options", "window", "panel", "the", "a", "of", "and", "new"}


def infer_command(d: Dialog, commands: list, parsed: dict) -> tuple:
    """The command whose doc these controls fit: (best, runner-up) as (score, id), best None if no command is clear.

    A command qualifies when most of the controls match one of its keys and its id or label shares a
    word with the dialog's title; a dialog wired to the wrong command does something, so a doubtful
    answer is no answer.
    """
    fields = [f for f in d.fields if f.key]
    title = tokens(re.sub(r"\b(\w+)\s+\1\b", r"\1", d.title.lower())) - GENERIC
    if not fields or not title:
        return None, []
    ranked = []
    for c in commands:
        keys = parsed.get(c["id"])
        if not keys:
            continue
        params = list(keys) + [ch for k in keys if k.kind in ("array", "object") for ch in k.children]
        scores = [max((score(f, p) for p in params), default=0) for f in fields]
        hit = sum(1 for x in scores if x >= CLEAR)
        words = tokens(c["id"]) | tokens(c.get("label", ""))
        shared = len(title & words)
        if hit * 10 < len(fields) * 6 or not shared:
            continue
        ranked.append((int(60 * hit / len(fields)) + 20 * min(shared, 2) + (10 if len(params) <= 2 * len(fields) else 0), c["id"]))
    ranked.sort(key=lambda t: (-t[0], t[1]))
    best = ranked[0] if ranked and ranked[0][0] >= 80 and (len(ranked) == 1 or ranked[0][0] - ranked[1][0] >= 15) else None
    return best, ranked[:3]


def load_commands(path: str) -> list:
    data = json.load(open(path, encoding="utf-8"))
    return data if isinstance(data, list) else data.get("commands", [])


def dialog_files(app: str) -> list:
    pat = os.path.join(app, "app", "src", "commonMain", "kotlin", "**", "dialogs", "*Dialog.kt")
    return sorted(p for p in glob.glob(pat, recursive=True) if not p.endswith("TranslatedDialogs.kt"))


def run(app: str, commands_path: str, write: bool = False, out=sys.stdout) -> dict:
    commands = load_commands(commands_path)
    by_id = {c["id"]: c for c in commands}
    parsed = {c["id"]: parse_doc(c.get("params", "")) for c in commands}
    stats = dict(dialogs=0, fields=0, ok=0, renamed=0, nested=0, unknown=0, no_command=0, changed_files=0)
    for path in dialog_files(app):
        d = read_dialog(path)
        if not d.fields and not d.command:
            continue
        stats["dialogs"] += 1
        rel = os.path.relpath(path, app)
        sug = suggest_commands(d.title, commands) if not d.command else []
        how = "the title is its label"
        if not d.command and not (sug and sug[0][0] >= 100 and (len(sug) == 1 or sug[1][0] < 100)):
            best, near = infer_command(d, commands, parsed)
            if best:
                sug, how = [best], "its controls fit its doc"
            elif near:
                sug = near
        if sug and sug[0][0] >= (100 if how.startswith("the title") else 80) and (len(sug) == 1 or sug[0][0] - sug[1][0] >= 15 or how.startswith("the title")):
            # The title is the command's own label: that is the command this dialog runs.
            if write:
                text = "\n".join(d.lines)
                text = re.sub(r"/\* TODO: which command this dialog runs was not found \*/", f'onRun("{sug[0][1]}", params)', text, count=1)
                open(path, "w", encoding="utf-8").write(text)
                stats["changed_files"] += 1
                d = read_dialog(path)
            else:
                d.command = sug[0][1]
            out.write(f"- {rel}: {d.title}: command {sug[0][1]} ({how})\n")
        if not d.command or d.command not in by_id:
            stats["no_command"] += 1
            stats["fields"] += len(d.fields)
            stats["unknown"] += len(d.fields)
            sug = ", ".join(f"{c} ({s})" for s, c in suggest_commands(d.title, commands)) or "none"
            out.write(f"- {rel}: {d.title}: no command ({'unknown id ' + d.command if d.command else 'none named'}); candidates: {sug}\n")
            continue
        keys = parsed[d.command]
        verdicts = resolve(d, keys)
        stats["fields"] += len(verdicts)
        for v in verdicts:
            stats["ok" if v.state == "ok" else "renamed" if v.state == "rename" else "nested" if v.state == "nest" else "unknown"] += 1
        miss = missing_required(keys, verdicts)
        bad = [v for v in verdicts if v.state == "unknown" or v.notes]
        if write:
            text, changed = apply(d, verdicts)
            if changed or text != "\n".join(d.lines):
                open(path, "w", encoding="utf-8").write(text)
                stats["changed_files"] += 1
        interesting = [v for v in verdicts if v.state in ("rename", "nest")] + bad
        if len(verdicts) >= 2 and all(v.state == "unknown" for v in verdicts):
            out.write(f"- {rel}: {d.title} -> {d.command}: none of its {len(verdicts)} controls match a key in this command's doc: the wrong command, or keys its doc does not list\n")
            continue
        if interesting or miss:
            out.write(f"- {rel}: {d.title} -> {d.command}\n")
            for v in interesting:
                f = v.field
                what = {"rename": f"{f.key} -> {v.to}", "nest": f"{f.key} -> {v.container}[].{v.to}", "unknown": f"{f.key or '(not put)'}: UNRESOLVED",
                        "ok": f"{f.key}: ok"}[v.state]
                extra = ("; " + "; ".join(v.notes)) if v.notes else ""
                cand = f" (candidates: {', '.join(v.candidates)})" if v.state == "unknown" and v.candidates else ""
                out.write(f"    * {what} [{f.kind} \"{f.label}\"]{extra}{cand}\n")
            if miss:
                out.write(f"    * required, no control: {', '.join(miss)}\n")
    return stats


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--app", required=True, help="the app directory (the one with app/ and settings.gradle.kts)")
    ap.add_argument("--commands", required=True, help="engine-commands.json")
    ap.add_argument("--write", action="store_true", help="edit the dialogs")
    ap.add_argument("--check", action="store_true", help="exit 1 if a control's key is not one the command knows")
    a = ap.parse_args(argv)
    st = run(a.app, a.commands, write=a.write)
    sys.stdout.write(
        f"\n{st['dialogs']} dialogs, {st['fields']} controls: {st['ok']} already right, {st['renamed']} renamed, {st['nested']} nested, "
        f"{st['unknown']} unresolved; {st['no_command']} dialogs without a command"
        + (f"; {st['changed_files']} files written" if a.write else "") + "\n")
    if a.check and (st["unknown"] or (st["renamed"] + st["nested"] and not a.write)):
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
