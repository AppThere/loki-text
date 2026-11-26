# Loki Text: parity with the WordCraft desktop front end

Status: 26 November 2026 (after the Tabs, Borders and Shading, and Zoom dialogs). Measured against the engine's own command list (678
commands) and the egui front end (`crates/ui-egui`). Checked by `:app:jvmTest` (`EngineCoverageTest`)
and looked at with the off-screen screenshot harness at phone (390 dp), tablet (760–800 dp) and
desktop (1280 dp) widths, light and dark. Android release builds are built and tried on devices (a
Chromebook and a Fire HD 8). **iOS is scaffolded and has never been compiled**, and the desktop app has
not been tried as a whole; see [What is left](#what-is-left).

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
* **Ruler**: margins, inch marks from the left margin, the caret paragraph's indents and tab stops;
  all of them can be dragged (see Direct manipulation below).
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

They are covered by `PageMouseTest` (a fake host records the commands) and
`pointer_hit_tests_report_positions_and_table_spots` (the hit-testing against a real table). Column
border and move-handle drags show the real result as they go (`loki.preview`, below). Most gestures have
not been tried on a device with a mouse or pen; a live column drag has.

## What is done beyond the command list

What the egui front end does itself (`ui.*` commands and its own widgets), and how far this app has
come with each. What is still missing is gathered in [What is left](#what-is-left).

* **Bespoke dialogs.** The desktop has about sixty; this app has its own Font, Paragraph, Page
  Setup, Styles, Lists, Table, Find/Replace/Go To, Insert, Print, Info, Recipient List, Customize
  Keyboard, Symbol, Date and Time, Columns, Drop Cap, Tabs, Borders and Shading, and Zoom dialogs,
  and the generated form for everything else.
  * Symbol (Insert › Symbol › More Symbols…, `SymbolDialog.kt`): sixteen Unicode subsets in a grid,
    a character code box (beyond the Basic Multilingual Plane too), the symbols used lately in this
    window, and the font to write it in; Insert keeps the dialog open. The grid is drawn in the
    interface's font, so a symbol font's own pictures show only on the page.
  * Date and Time (`DateTimeDialog.kt`): today in each format, English (United States) or Portuguese
    (Brazil), from `loki.dateFormats`, and Update automatically; it opens on the last choice. There is
    no Set as Default yet.
  * Columns (Layout › Columns › More Columns…, `ColumnsDialog.kt`): presets, the number, each column's
    width and spacing (a change is taken from the next column, so they still fill the text), equal
    widths, a line between, Apply to, and a picture of the page.
  * Drop Cap (Insert › Drop Cap, now a menu: None, Dropped, In margin, Drop Cap Options…;
    `DropCapDialog.kt`): position, font, lines and distance, starting from the paragraph's drop cap
    (`loki.dropCap`), with a picture.
  * Tabs (Paragraph › Tabs, `TabsDialog.kt`): the stops in force to pick from, a position with an
    alignment and a leader to set or clear, Clear All and the default interval. Only what changed is
    sent.
  * Borders and Shading (Home › Borders › Borders and Shading…, `BordersDialog.kt`): the sides, one
    line (style, width, colour) and the paragraph's fill, with a picture; OK is one undo step
    (`loki.runSteps`). Sides that differ keep their own lines until another line is chosen. Both
    start from `loki.paraLines` (the caret paragraph's). The desktop's Page Border tab and Apply to:
    Text are not in it.
  * Zoom (View › Zoom, `ZoomDialog.kt`): preset percentages, Page width, Whole page, Many pages, or
    a typed percentage within what the page area draws (25% up to 200%, less on a dense screen).
  * Object and list parameters are fields, not JSON (the kit's `StructuredParams.kt`): an object is a
    nested form, a list is rows to add, remove and reorder, `where X = {…}` types are followed, fixed
    pairs (`[[width pt, space after pt], …]`) are side-by-side fields, and chart data is a grid. A map
    from names (the TOC's style levels) is rows of a name and a level, rows of any length (spreadsheet
    values) are a grid, and table-style parts are rows with the part to pick and its settings (a
    part's `borders` is still a JSON box). Each
    keeps "Edit as JSON". `StructuredFieldsTest` fails when a written-out object or list in the engine's
    docs has no fields; left as JSON on purpose: engine positions (set by pressing on the page), types a
    doc names without spelling out (`CharProps`, `ParaProps`, `SectionProps`, `Source`) and the recipient
    list's rows (it has its own editor).
  * Forms start with the document's current values for the 39 commands in `FORM_DEFAULTS`: Options,
    tracking and privacy options, AutoCorrect Options, user name; margins, page layout, paper size,
    orientation, columns, vertical alignment, line numbers, page number format, document grid, text
    direction and section direction; indents, spacing, alignment, the break options and paragraph
    direction; font, size, colour, underline and its colour, character spacing, emphasis mark, paragraph
    style and proofing language; zoom, numerals, the Styles pane's dock, reading speed and skipping
    citations. Each is read with a command that only reports (`format.state`, `para.state`,
    `layout.section`, `view.state`, `readAloud.status`, `file.options`, …), and
    `FormDefaultsReadersTest` checks against a real engine that reading changes nothing. Others start
    blank: their values have no reader (character scale and position, line spacing, shading, borders,
    Reading Mode's layout, theme and design choices) or are a choice to make each time (break kind,
    symbol, table size). Chart › Edit Data starts with the chart's categories and series, read with
    the engine's `chart.data`.
  * Customize Keyboard edits the engine's key changes; the app's own key map does not read them yet
    (see Customize Ribbon below). The engine answers `{custom: [{command, keys}]}`, not the
    `{assigned, removed}` its params doc describes; the dialog reads both.
* **Backstage pages**: File › Options has pages of its own (`OptionsDialog.kt`: General, Display, Proofing, Save, Language, Advanced; a list then a page on phones, side by side on wider windows). The engine's Accessibility and Agents panes have no settings in this app yet and open General. Done: the start screen (`StartScreen.kt`: New, Open, templates, recent documents, unsaved
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
* **Window management** (`WindowHost.kt`, `WindowChrome.kt`; desktop `DesktopWindows.kt`, Android in
  `MainActivity.kt`). Split shows the page area twice, each half scrolled on its own (the lower one
  follows the caret). Focus (the page on black) and Full Screen view (the pages alone, with Search) hide
  the title bar, ribbon, panes, ruler and status bar behind a slim bar, and Esc leaves them; they and
  View › Full Screen put the window in full screen (Android hides the system bars). Switch Windows lists
  the app's other windows and brings one to the front (Android: the other document tasks). On the
  desktop, Arrange All tiles the windows, View Side by Side puts two windows on the two halves of the
  screen, and Synchronous Scrolling makes them scroll together; Android places windows itself, so those
  say so. Tried on an Android device: Focus, Esc, Split, a second window and Switch Windows. The
  desktop's tiling, side by side and synchronous scrolling are covered by `DesktopWindowsTest`, not
  tried on a desktop. Untitled windows are all listed as "Untitled".
* **Customize Ribbon / Customize Keyboard / Quick Access Toolbar** now take effect: the app reads the
  engine's layout and key changes after start-up and after each change (`Customization.kt`), shows the
  ribbon in the person's order with hidden tabs left out and their tabs and groups added, takes the
  toolbar's commands from the layout, and puts their keys over the built-in ones. They are kept in the
  settings file and put back into each fresh engine at start-up (the layout through
  `tools.customizeRibbon`'s `layout`, the keys replayed as `tools.customizeKeyboard` removals and
  assignments). Customize Ribbon is still the generated form, not a drag-and-drop dialog. Menus and
  tooltips still show the built-in shortcut, and a change made in one desktop window reaches the
  others only at the next launch.
* **Direct manipulation** (item 6). The ruler drags the indent markers, the margins' edges (its middle
  band) and the tab stops; a click in the text area sets a left tab and a tab dragged off it goes
  (`Ruler.kt`; the paragraph's tabs come from `loki-ffi`'s layout state). Picture Format › Crop puts
  crop handles on the picture, which trim it in one `picture.crop` (Crop Values keeps the dialog). A
  floating object being moved snaps to the page's edges, margins and middle, with the guide drawn (Alt
  moves freely); guides to other objects are not drawn. Dragging a table's column border or move handle
  shows the real result as it goes: `loki.preview` runs the command and takes it back
  (`revert_since`), so only the drag's own command is an undo step. Tried on an Android device: tab
  stops, the margin, a live column drag undone in one step, and a crop.
* **Item 7.** Done: system fonts on Android (the font database scans `/system/fonts` and the font
  APEX), Read Aloud speech on Android (Android's text-to-speech; `loki.speechVoice` holds the engine's
  player on each sentence while the app reads it; the desktop keeps the engine's own voice), interface
  size (File menu; the pages keep the screen's density), the SmartArt Text Pane (`ShapePanes.kt`) and a
  Format Shape pane (fill, line colour and width, size and position), and interface language
  (`I18n.kt`: craft-i18n's catalogs copied in from `CRAFT_I18N_DIR` at build time and never committed;
  command labels, tabs, groups, the File menu, the kit's dialog words and the page counter; File ›
  Interface Language or the system's), and keytips (`Keytips.kt`: Alt alone or F10 shows the tabs'
  letters, as the desktop's, and the Quick Access Toolbar's numbers; a tab's letter shows its
  controls' letters, taken from their names in drawing order; Esc goes back a step). The desktop's
  legacy Alt sequences, the Search box's Q, keytips inside menus and on the File menu, and the
  smaller ribbons used on phones are not done. Not done: Zotero, update checks, counted strings
  (plurals) and most of the app's own dialog text, which still show in English. The Format Shape pane
  has not been tried on a device.
* **Saved settings** (`Settings.kt`; `settings.json` in the platform's config folder, app storage on
  Android, user defaults on iOS): the theme, the screen paper colour, the recent documents, the
  person's ribbon layout and key changes, the interface size and the interface language. Zoom, view
  mode and the other view state are still per run.
* **System fonts**: the engine's font database scans the platform's font folders (on Android
  `/system/fonts`, `/product/fonts` and the font APEX), and `font.loadSystem` loads them into the
  font list.

## What is left

Roughly largest first. Do not assume any of these are solved without checking.

1. **Bespoke dialogs.** The desktop has about sixty; this app has twenty of its own and the
   generated form for the rest. Forms start with the document's values for the 39 commands in
   `FORM_DEFAULTS`; the rest start blank, mostly because nothing reports their current value. A few parameter types stay JSON on purpose (listed above). The Tabs, Borders and
   Shading and Zoom dialogs and the new map and grid fields have not been tried in a running app.
2. **Backstage and customization.** File › Options pages are not tried on a device yet; the folders are text boxes, not pickers.
   Customize Ribbon is the generic form, not a drag-and-drop dialog. Menus and tooltips show the
   built-in shortcut after a key change, and a customization made in one desktop window reaches the
   others only at the next launch. Personal templates have no first-page pictures; the print preview
   may show a far page as only its number.
3. **Item 7 remainders.**
   * Keytips: the desktop's hidden legacy Alt sequences, the Search box's Q, keytips inside menus and
     on the File menu, and keytips on the smaller (phone and tablet) ribbons.
   * Zotero: the desktop talks to a running Zotero; nothing here yet (on the desktop it would need the
     `zotero` crate behind `loki-ffi`; Android has no Zotero to talk to).
   * Update checks: none. Android gets updates from its store or by sideloading; desktop builds are
     not packaged for an updater.
   * Translation: counted strings (plurals, "5 words") and most of the app's own dialog text still
     show in English.
4. **Smaller gaps.** Zoom, view mode and the other view state are not saved. Alignment guides snap
   to the page, not to other objects. Untitled windows are all listed as "Untitled".
5. **Platforms and testing.**
   * iOS: scaffolded, never compiled.
   * Desktop (JVM): the app has not been tried as a whole; tiling, side by side and synchronous
     scrolling are covered only by `DesktopWindowsTest`.
   * Not tried on a device: the Format Shape pane, interface size, most mouse and pen gestures, and
     the Quick Access Toolbar's keytip numbers.

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
