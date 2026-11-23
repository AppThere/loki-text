# Loki Text: parity with the WordCraft desktop front end

Status: November 2026 (pointer gestures added). Measured against the engine's own command list (678 commands) and the egui
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
| Commands with a way to them | roughly 500 of 678 by a source scan, and fewer on a phone (value controls were desktop-only) | 678 of 678, at every size |
| Commands outside the ribbon tabs (File › Info/Export/Options, Navigation, Reading…) | 5 (the File menu) | all, in the all-commands list |
| Commands that take a value | only those with a hand-built control | every one: its control, or a form built from its params doc |
| Not reachable | not counted | none: `POINTER_GAPS` is empty (below) |

## What gives every command a way in

| Piece | Where | What it does |
|---|---|---|
| Command form | the kit: `CommandParams.kt`, `Dialogs.kt` | Reads a params doc (`{"kind": "box\|none", "width"?: pt, "color"?: "RRGGBB"}`) into fields: choice chips, number and text boxes, a colour box with swatches, a file box with Browse, yes/no chips, and a raw JSON box as the last resort. Errors from the engine stay in the form. |
| Result dialog | the kit: `Commands.kt` (`describeResult`) | A command that reports instead of changing (Word Count, Info, Check Compatibility, Theme List…) shows its answer as named lines; **Change…** opens the same command's form. |
| All-commands list | the kit: `AllCommandsDialog.kt` | Every command by place (ribbon tabs, then File, Quick Access Toolbar, Editing, Navigation, Reading…) with search over names, places and ids. The magnifier beside Undo/Redo opens it at every size; so does `tools.search` (Alt+Q). |
| Ribbon | the kit's `parseRibbon`, drawn by `Ribbon.kt` | Lists every command of its tabs. One that needs a value is in the group's menu with an ellipsis. Groups the app draws itself have a `…` at the left of their name that lists all the group's commands. |
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

## Pointer gestures

The commands whose parameters are page positions are driven by the page itself (`POINTER_GESTURES`).
What is under the pointer is the engine's to say: `loki.hit` (text position, line, selection-bar
line, text box) and `loki.tableSpot` (border, move handle, insert button, selection area), in
`ffi/src/pointer.rs`. `PageMouse.kt` turns a mouse or pen press, its moves and its release into
commands; a finger's drag still scrolls.

| Gesture | Command |
|---|---|
| Drag a table border or the corner resize handle (Shift: only the column on the left; Ctrl: share by proportion) | `table.resizeDrag` |
| Double-click a column border | `table.fitColumn` |
| Press above a column, left of a row, along a cell's edge; drag to go on selecting; Ctrl or triple-click beside a row selects the document | `table.selectAt` |
| Press an insert button (the "+" at a border) | `table.insertAt` |
| Drag the move handle past a table's top-left corner (a click selects the table) | `table.moveDrag` |
| Double-click or triple-click, then drag: grow by words, paragraphs; Ctrl+click: sentences; the selection bar (left margin): lines, paragraphs, document | `select.dragUnit` |
| Alt+drag | `select.column` |
| Ctrl+drag a picture, shape or text box (an object in the text lands at the text position under the drop) | `arrange.copyTo` |
| Drag either end of a selected connector | `shape.connect` |
| Create Link, then tap the empty text box to continue in | `shape.link` |

The Android build compiles with these gestures, but they have not been tried on a device with a mouse: they are covered by
`PageMouseTest` (a fake host records the commands) and `pointer_hit_tests_report_positions_and_table_spots`
(the hit-testing against a real table). A dashed line follows the pointer while a border or handle is
dragged; the egui front end's live preview of the new widths is not drawn. The dashed move outline of
the table, alignment guides while an object moves, and crop handles are not either.

## Known gaps (do not assume these are solved)

Things the egui front end does itself (`ui.*` commands and its own widgets) that this app does not:

* **Bespoke dialogs.** The desktop has about sixty; this app has its own Font, Paragraph, Page
  Setup, Styles, Lists, Table, Find/Replace/Go To, Insert, Print, Info and Recipient List dialogs, and
  the generated form for everything else. The form is complete but plain. It starts with the document's
  current values for the commands in `FORM_DEFAULTS` (`FormDefaults.kt`: File › Options, tracking
  options, privacy options, margins, page layout, paper size, orientation, columns, indents, paragraph
  spacing, font size and font; `FormDefaultsFixtureTest` checks every entry against the engine's own
  parameter docs), and those commands open their form directly. Every other command's form starts blank,
  and list or object parameters are still typed as JSON (Chart data, Customize Keyboard, Customize
  Ribbon, filter and sort rules). Defaults come only from read-only commands: calling a setter with no
  values to read it back changes the document.
* **Backstage pages**: File › Options is the generic form with the current values, not the desktop's panes. Done: the start screen (`StartScreen.kt`: New, Open, templates, recent documents, unsaved
  documents), File › New from Template (`TemplateGallery.kt`: five built-in templates with first-page
  pictures, personal templates from `file.listTemplates`, Browse), File › Print (`PrintDialog.kt`: page
  preview, range, odd/even, pages to a sheet, paper, copies, what it comes to; the options go to
  `file.printPdf`), File › Info and Properties (`InfoDialog.kt`: the facts, and the properties to edit),
  and recovery of unsaved documents (`Recovery.kt`).
  Recovery: `loki-ffi` turns the engine's recovery on in the app's folder (`loki.enableRecovery`) and
  the app ticks it every two seconds (`loki.recoveryTick`); a copy of unsaved work is kept at most once
  in ten seconds, after a crash or a kill the start screen and File › Recover Unsaved Documents list it,
  and Restore opens it as an unsaved document. Don't save discards the copy. Tried on a device: type, wait,
  kill the app, relaunch, restore. Not in the gallery: pictures for personal templates. The print preview
  shows the pages the window has drawn, so a far page may show only its number. On Android a recent
  document keeps the system's permission to read (and write) where it was picked
  (`takePersistableUriPermission`), and opening it from the list copies it afresh; a provider that
  refuses a persistable grant falls back to the staged copy.
* **Window management**: Split, Arrange All, View Side by Side, Switch Windows (the desktop opens
  new windows for New and Open; phones have one). Full Screen and Focus toggle engine state that
  this app does not act on.
* **Customize Ribbon / Customize Keyboard / Quick Access Toolbar**: the engine commands run, but this
  ribbon and key map do not read the saved customisation.
* **Ruler dragging, crop handles, alignment guides, keytips, interface size, interface language**
  (the UI is English only), **Format Shape and SmartArt text panes, Zotero, Read Aloud's audio,
  updates**.
* **Saved settings** are the theme, the screen paper colour and the recent documents (`Settings.kt`;
  `settings.json` in the platform's config folder, app storage on Android, user defaults on iOS).
  Zoom, view mode and the other view state are still per run.
* **System fonts** (`font.loadSystem`) are not enumerated; documents use the bundled open fonts.

## Checking it

```sh
cd loki-text
cargo test -p loki-ffi                 # FFI round trip, loki.uiRequests, the command fixture
gradle :kit:jvmTest :app:jvmTest       # the shared kit; then panes, view state, engine coverage
cargo build -p loki-ffi                # then look at it:
LOKI_LIB=$PWD/target/debug LOKI_SHOTS=/tmp/shots \
LOKI_SHOT_SCRIPT='size=390x780@2; wait=3000; shot=phone.png; click=116,66; wait; shot=commands.png' \
gradle :app:jvmTest --tests '*ScreenshotHarness*' --rerun
```
