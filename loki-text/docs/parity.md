# Loki Text: parity with the WordCraft desktop front end

Status: November 2026. Measured against the engine's own command list (678 commands) and the egui
front end (`crates/ui-egui`). Checked by `:app:jvmTest` (`EngineCoverageTest`) and looked at with
the off-screen screenshot harness at phone (390 dp), tablet (760–800 dp) and desktop (1280 dp)
widths, light and dark. **Not built for Android or iOS in this round** (no SDK on the build
machine): the Android picker change in `MainActivity.kt` compiles only in CI or on a machine with
the SDK, and iOS remains scaffolded.

## How parity is measured

Everything in WordCraft is a command, so parity here is a question with a number for an answer:
*of the commands the engine has, how many can a person reach from this app, at every window size?*

* `ffi/src/tests.rs::kotlin_command_fixture_follows_the_engine` writes `engine.commands` to
  `app/src/jvmTest/resources/engine-commands.json` and fails when the engine and the copy differ
  (`LOKI_UPDATE_FIXTURES=1 cargo test -p loki-ffi kotlin_command_fixture` refreshes it).
* `EngineCoverageTest` reads that copy and fails when a command is neither listed (ribbon and
  all-commands list) nor a named pointer gesture, when a command that needs values has a form with
  no fields, or when the app names a command the engine does not have.

| | Before | Now |
|---|---|---|
| Commands with a way to them | roughly 500 of 678 by a source scan, and fewer on a phone (value controls were desktop-only) | 668 of 678, at every size |
| Commands outside the ribbon tabs (File › Info/Export/Options, Navigation, Reading…) | 5 (the File menu) | all, in the all-commands list |
| Commands that take a value | only those with a hand-built control | every one: its control, or a form built from its params doc |
| Not reachable | not counted | 10 pointer gestures, named in `POINTER_GAPS` (below) |

## What gives every command a way in

| Piece | Where | What it does |
|---|---|---|
| Command form | `CommandParams.kt`, `CommandFormDialog.kt` | Reads a params doc (`{"kind": "box\|none", "width"?: pt, "color"?: "RRGGBB"}`) into fields: choice chips, number and text boxes, a colour box with swatches, a file box with Browse, yes/no chips, and a raw JSON box as the last resort. Errors from the engine stay in the form. |
| Result dialog | `CommandCatalog.kt` (`describeResult`) | A command that reports instead of changing (Word Count, Info, Check Compatibility, Theme List…) shows its answer as named lines; **Change…** opens the same command's form. |
| All-commands list | `Ribbon.kt` (`AllCommandsDialog`) | Every command by place (ribbon tabs, then File, Quick Access Toolbar, Editing, Navigation, Reading…) with search over names, places and ids. The magnifier beside Undo/Redo opens it at every size; so does `tools.search` (Alt+Q). |
| Ribbon | `Ribbon.kt` (`parseRibbon`) | Lists every command of its tabs. One that needs a value is in the group's menu with an ellipsis. Groups the app draws itself have a `…` at the left of their name that lists all the group's commands. |
| Engine requests | `ffi/src/lib.rs` (`loki.uiRequests`), `App.kt` (`handleUiRequest`) | When a command asks the front end for a dialog or a picker (`{"open": "exportPdf"}`), the app opens its own dialog, the platform picker, or that command's form. |

## Responsive layout

One size class from the window width (`Compact` < 600 dp, `Medium` < 900 dp, `Expanded`), as before.
What changed is that nothing is desktop-only any more:

| Feature | Compact (phone) | Medium (tablet) | Expanded (desktop) |
|---|---|---|---|
| Ribbon groups with value controls (font boxes, colours, spacing, table sizes, picture and shape controls) | a button named after the group opens the same controls in a panel that scrolls sideways | same | laid out in the group |
| Commands without an icon | a menu under the group's name in the scrolling row | same | the group's **More** menu |
| All commands / search | magnifier in the row | magnifier in the row | magnifier beside Undo/Redo |
| Zoom | the percentage in the status bar opens Zoom in/out, 100%, Page width, One page; pinch | −, %, + ; pinch | −, %, + ; Ctrl and the View tab |
| Task panes | above the page, 260 dp, one at a time with chips to switch | beside the page, 320 dp | beside the page, 320 dp |
| Dialogs (form, result, all-commands) | full width less a margin; title and buttons fixed, body scrolls | capped at 560 dp (720 dp for the list) | same |
| Ruler | not shown (the text reflows; there is no page to measure) | shown in Print Layout | shown in Print Layout |

## View features the app now draws

The engine's renderer draws plain pages; the egui front end adds the rest on its own canvas. Loki
Text now does the same from `view.state` (`ViewState.kt`):

* **Zoom** lives in the engine: View › Zoom, Page Width, One Page, the status bar and a pinch all
  set `view.zoom`, and each render reads it back. Fits are worked out from the window (`fitZoom`).
* **Dark pages** (View › Switch Modes), **Screen Paper Color** and Read Mode's page colour: a colour
  filter over the page image.
* **Gridlines**: a grid every eighth of an inch over the page.
* **Ruler**: margins, inch marks from the left margin and the caret paragraph's indents. It shows
  the measures; its markers cannot be dragged yet.
* **Interface theme**: light, dark, or the system's (File menu, and View › Interface Theme).
* **Task panes** (`SidePanes.kt`): Styles, Clipboard, Reviewing (accept and reject each change),
  Accessibility, Alt Text, Style Inspector and Selection join Comments in one pane host; Navigation
  keeps its own place on the left.
* **Draw tab**: with a pen, pencil or highlighter in hand a drag on the page is ink (`draw.stroke`);
  the eraser removes the stroke under a press; the lasso selects the ink inside a loop. Esc puts the
  tool down.
* **File menu**: Save as Template, Export PDF, Export Page as PNG (each asks where to write, with
  the right name and type on Android), Info, Properties, Options, the theme, All Commands.

## Known gaps (do not assume these are solved)

Pointer gestures of the desktop front end with no counterpart here (`POINTER_GAPS`; the test lets
this list shrink, not grow). The ribbon reaches the same results for the table ones (column width,
row height, Insert, Select, AutoFit):

`table.resizeDrag`, `table.selectAt`, `table.insertAt`, `table.moveDrag`, `table.fitColumn`,
`select.dragUnit` (double-click-drag by words), `select.column` (Alt+drag), `arrange.copyTo`
(Ctrl+drag an object), `shape.link`, `shape.connect`.

Things the egui front end does itself (`ui.*` commands and its own widgets) that this app does not:

* **Bespoke dialogs.** The desktop has about sixty; this app has its own Font, Paragraph, Page
  Setup, Styles, Lists, Table, Find/Replace/Go To and Insert dialogs, and the generated form for
  everything else. The form is complete but plain: it does not show the current values, and list or
  object parameters are typed as JSON (Edit Recipient List, Chart data, Customize Keyboard).
* **Start screen and Backstage pages**: recent documents, templates gallery, the Print page with a
  preview, recovery of unsaved documents. File › Info/Options are the result dialog and the form.
* **Window management**: Split, Arrange All, View Side by Side, Switch Windows (the desktop opens
  new windows for New and Open; phones have one). Full Screen and Focus toggle engine state that
  this app does not act on.
* **Customize Ribbon / Customize Keyboard / Quick Access Toolbar**: the engine commands run, but this
  ribbon and key map do not read the saved customisation.
* **Ruler dragging, crop handles, alignment guides, keytips, interface size, interface language**
  (the UI is English only), **Format Shape and SmartArt text panes, Zotero, Read Aloud's audio,
  updates**.
* **Settings are per run**: the theme choice and paper colour are not saved between launches.
* **System fonts** (`font.loadSystem`) are not enumerated; documents use the bundled open fonts.

## Checking it

```sh
cd loki-text
cargo test -p loki-ffi                 # FFI round trip, loki.uiRequests, the command fixture
gradle :app:jvmTest                    # parsers, panes, view state, engine coverage
cargo build -p loki-ffi                # then look at it:
LOKI_LIB=$PWD/target/debug LOKI_SHOTS=/tmp/shots \
LOKI_SHOT_SCRIPT='size=390x780@2; wait=3000; shot=phone.png; click=116,66; wait; shot=commands.png' \
gradle :app:jvmTest --tests '*ScreenshotHarness*' --rerun
```
