# egui2compose: starting a Compose front end from an egui one

`scripts/egui2compose.py` reads a Craft repository and writes a Gradle project on
[Loki Kit](../kit/README.md) that compiles, passes its own coverage test and can be looked at with
no engine attached. It does phases 3 and 4 of the [playbook](kmp-responsive-ui-playbook.md), and
gives phase 8 a head start. **It is not a transpiler**: it carries over what the egui front end
*declares*, and reports what it *paints by hand*, which a person still has to build.

```sh
# From the engine's own command list (always prefer this):
gridcraft-cli commands --json > commands.json      # where the CLI has it; else `engine.commands` over the control channel or MCP
scripts/egui2compose.py --repo ../../vecthere --commands commands.json --out ~/vecthere-compose

# With no command list, commands are read from the engine's source:
scripts/egui2compose.py --repo ../../vecthere --out ~/vecthere-compose

cd ~/vecthere-compose
gradle :app:jvmTest                                              # the coverage test
LOKI_SHOTS=/tmp/shots gradle :app:jvmTest --tests '*CoverageTest*' --rerun   # phone, tablet, desktop PNGs
gradle :app:run                                                  # the UI on the recorded command list
```

Python 3.9+, standard library only. It reads source as text and never runs anything from the
repository it reads. Tests: `python3 scripts/test_egui2compose.py`.

## Status

Checked in November 2026 against WordCraft and the ten other Craft repositories (the AppThere
forks, read from source).

| Claim | How it was checked | State |
|---|---|---|
| The source reader finds the commands | against WordCraft's own `engine.commands`: 678 of 678 ids, labels, locations, shortcuts and `mutates`; 673 of 678 params docs; nothing extra | verified, and held by a test |
| It runs on every Craft repository | run on all eleven; 1–5 s each | verified |
| What it writes compiles and passes its tests | `gradle :app:jvmTest` on all eleven generated projects, JVM | verified |
| What it writes renders at phone, tablet and desktop widths | off-screen renders of three (FilmCraft, GridCraft, VectorCraft), read as images | verified for those three |
| The command lists it reads from the *other* engines are right | **not checked**: their engines were not run here, so there was no true list to compare with | unverified |
| The generated apps do anything | **no**: they run on a recorded command list (`FixtureTransport`); nothing executes until the FFI crate exists | by design |
| Android, iOS | the generated project is JVM-only | not attempted |

## How the other Craft front ends work (what the script was built from)

Read from each repository's `crates/engine` and `crates/ui-egui`. All eleven share the idea
(everything is a command; the UI dispatches by id) and differ in details the script and the kit now
absorb.

| | WordCraft | The other ten |
|---|---|---|
| Where a command lives | `location: "Tab › Group"` | `menu: &["Edit", "Transform"]`, empty for a command on no menu |
| Changes the document? | `mutates` | `undoable` and/or `journal` |
| Declared with | `CommandSpec::new(id, label, location, run).params(…).key(…)` | mostly a `cmd!(id, label, [menu], shortcut, params, enabled, run)` macro; also struct literals, `query!`/`toggle!`-style macros that expand to it, small helper functions and closures. CadCraft uses the builder, with ids that have no dot |
| Params doc | `{"key"?: "a\|b"}`: quoted keys, quoted values | `{key?: a\|b, id}`: bare keys and values (eight apps); `{"key":"a\|b"?,"n":f64=5}`: optional and default after the type (FilmCraft, PhotoCraft) |
| Main chrome | a ribbon | a ribbon (GridCraft, DeckCraft); a menu bar with dockable panels and a tool palette (the rest) |
| Menu order | the engine's order is not the menu bar's | same: a table in the front end (`TABS`, `MENUS`) or code that builds the bar |
| Panels and tools | — | enums in the front end (`Panel`, `PanelKind`, `Tool`), with titles in a `match` |
| Dialogs | a `Dialog` enum; each dialog a function that calls egui widgets | same |
| Introspection | `engine.commands`, `ui.inspect` | the same two everywhere; `ui.menu.list` in eight |

Consequences, all handled in the kit so every app gets them:

* `parseCommands` reads either listing shape (`location` or `menu`; `mutates`, `undoable` or `journal`).
* `parseParamForm` reads all three params-doc styles (see the playbook, §5.3).
* A command on no menu has no tab: the all-commands list files it under **Other**, and the coverage
  check counts it as reachable there. In the canvas and timeline apps this is half the commands or
  more (they are run by panels, tools and gestures on the desktop), so the generated app's panels and
  gestures are where most of the remaining work is.
* `CommandBar` is a generic bar (tabs, then one scrolling row of group menus) that works for a
  ribbon's tabs and a menu bar's menus alike.

What the script found in each (commands read from source, except WordCraft's):

| Repository | Commands | On a menu | Tabs | Panels | Tools | Dialog functions | Inputs translated | …with a matching param name | Params style | Headless backend | Request queue | Params not read |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| loki-grid | 380 | 287 | 18 | 0 | 0 | 16 | 49 | 5 | bare | yes | yes | 0 |
| loki-decks | 331 | 242 | 31 | 0 | 4 | 30 | 50 | 5 | bare | yes | yes | 0 |
| vecthere | 800 | 367 | 9 | 0 | 0 | 37 | 47 | 0 | bare | yes | yes | 0 |
| photothere | 780 | 382 | 9 | 0 | 68 | 71 | 83 | 1 | quoted | yes | no | 126 |
| designthere | 542 | 210 | 9 | 0 | 0 | 41 | 65 | 0 | bare | yes | yes | 0 |
| lightthere | 373 | 72 | 4 | 4 | 0 | 50 | 78 | 2 | bare | yes | no | 1 |
| cadthere | 416 | 279 | 10 | 6 | 0 | 13 | 57 | 0 | bare | yes | no | 33 |
| filmthere | 668 | 216 | 7 | 28 | 17 | 16 | 38 | 10 | quoted | no | no | 61 |
| fxthere | 735 | 267 | 9 | 36 | 31 | 10 | 41 | 19 | bare | no | no | 19 |
| soundthere | 482 | 325 | 9 | 6 | 0 | 14 | 58 | 5 | bare | yes | no | 94 |
| WordCraft (true list) | 678 | 678 | 28 | 11 | 0 | 95 | 228 | 92 | quoted | yes | yes | 0 |

How to read it:

* **Panels 0** (VectorCraft, PhotoCraft, DesignCraft, the two ribbon apps) means the panels are not
  an enum the script recognises, not that there are none. They have to be listed by hand.
* **Headless backend "no"** and **Request queue "no"** mean "not found under the names WordCraft
  uses" (`pub struct Headless`, `ui_requests`). FilmCraft and EffectCraft have an `automation` crate
  that may serve; check it against the playbook's §1 before writing the FFI crate.
* **Params not read** are commands declared through a macro that fills the params in inside its own
  body (`toggle!`, `flag!`, `section!`). They show `{}` here. The engine's own listing has them.
* **…with a matching param name** is the honest measure of the dialog translation: of the input
  controls carried over, how many were bound to a Rust field whose name is also a key of the
  command's params doc. It is low everywhere but WordCraft. See "Dialogs" below.

## What it carries over, and how

| From the egui front end | Becomes | How it is read |
|---|---|---|
| The commands | `engine-commands.json` (the fixture), and through the kit: the command bar, the forms, the all-commands list | `--commands`, or the source: struct literals, builder calls, and calls to anything whose own definition builds a `CommandSpec` (so a look-alike helper that builds a preferences row is not taken for a command) |
| The menu or tab order | `TABS` in `Profile.kt` | the list of strings in the front end that names the most menus; else File, Edit … View, Window, Help |
| Pointer, state-reading and typing commands | first guesses at the profile's sets | by params (`x` and `y` required, on no menu), by name (`*.state`, `*.list` and not mutating), by id |
| Panels and tools | `Workspace.kt`, shown as chips and a tool strip | `Panel`/`PanelKind`/`Tool` enums and the `Variant => "Title"` arms beside them; a source command when one matches by name (`layers.list`) |
| Dialogs | one composable each in `dialogs/` | functions under `dialogs/` (and prefs, options) that call input widgets |
| A dialog's widgets | the same controls in the same order | `checkbox`, `Slider::new`, `DragValue::new`, `ComboBox` with its `selectable_value`s, `radio_value`, `text_edit_*`, `button`; the label from the call or the `label(…)` before it |
| The theme | `Theme.kt`: a light and a dark scheme | `name: Color32::from_rgb(…)` in theme files: the accent, and the lightest and darkest backgrounds |
| Front-end-only methods | a list in `REPORT.md` | `ui.*` / `window.*` names the desktop's control channel answers |

Every run writes `REPORT.md` (counts, an engine-readiness check, a table per panel and per dialog,
and the list of what was not translated) and `ui-model.json` (everything it read, for other tools).

## What it does not carry over

* **The surface.** The canvas, page or timeline is painted by hand in egui. The generated `App`
  takes a `surface` composable; the engine's renderer supplies the pixels once the FFI crate exists.
* **Behaviour.** Starting values, enabling rules, live previews, validation, drag and drop, docking
  layouts, context menus, keyboard handling.
* **Hand-drawn panels and custom widgets.** A panel with no source command is a titled placeholder.
* **Icons and translations.** Commands without an icon are named menu entries, so nothing is lost,
  but the bar is text until icons are mapped.
* **Platform code.** The generated project is JVM-only and has no FFI crate.

### Dialogs: a start, not a result

A translated dialog has the right controls in the right order with the right labels. Its OK button
sends each control's value under a key guessed from the Rust field the control was bound to
(`&mut self.opts.blur_radius` → `blurRadius`). That guess matches a key of the command's params doc
for 92 of WordCraft's 228 controls and for almost none in the other apps, because their dialogs
keep state in structs whose field names are not the command's parameter names, and often run their
command from another function. So:

* use the generated dialog for its **layout**;
* fix each key against the params doc in the file's header comment;
* and prefer the kit's generated form (`CommandFormDialog`) wherever the dialog is just a command's
  parameters. It is built from the params doc, so its keys are right by construction. The
  hand-shaped dialog is worth finishing only where its layout or preview matters.

## After running it

1. Replace `app/src/jvmTest/resources/engine-commands.json` with the engine's own list if the
   commands were read from source; run `gradle :app:jvmTest`. Each line the coverage test prints is
   a thing to fix: a params doc, a set in `Profile.kt`.
2. Read `REPORT.md`. The "Is the engine ready?" section is the playbook's §1 for this app.
3. Continue with the playbook from phase 1 (the FFI crate), then phase 5 (the surface).
4. Move what the generated `App.kt` gets right for every app into the kit as it settles; it is the
   second column of the playbook's §8.

## Changing the script

* A new declaring form, widget or enum name goes in `scan_commands`, `WIDGETS` or `scan_enums`,
  with a case in `scripts/test_egui2compose.py`.
* `AgainstWordCraft` holds the floors (every command found, nothing extra, 99% of params docs).
  They may rise, not fall.
* It must keep working on a repository it has never seen: every reader tolerates source it cannot
  parse (`test_hostile_source_never_raises`) and says in the report what it could not find.
