# Playbook: a Kotlin Multiplatform, responsive front end for a Craft engine

Status: revised November 2026 from Loki Text (the WordCraft front end), after the pass that made
every engine command reachable at every window size ([`parity.md`](parity.md)).

Audience: an agent or a person applying the same approach to another Craft application (PhotoCraft,
VectorCraft, FilmCraft, LightCraft, PDFCraft, EffectCraft, DesignCraft), to build one suite for
Android and, later, iOS and iPadOS. Read §0 and §1 first: §0 says how far this document can be
trusted, §1 says whether the target app's engine is ready.

## 0. What has been verified, and what has not

| Claim | How it was checked | State |
|---|---|---|
| The FFI crate builds, its tests pass | `cargo test -p loki-ffi` (24 tests), Linux x86_64 | verified, November 2026 |
| The Kotlin common code compiles, its tests pass | `gradle :kit:jvmTest :app:jvmTest` (18 + 148 tests), Gradle 8.14.3, JDK 21, no Android SDK installed | verified, November 2026 |
| The shared kit serves the app unchanged | the app's tests, and off-screen renders before and after the extraction | verified on the JVM. The kit's Android and iOS targets have **not been compiled** |
| The UI lays out at phone, tablet and desktop widths, light and dark | off-screen renders of the real app (`ScreenshotHarness`), read as images | verified, November 2026 |
| The desktop app runs in a window | `scripts/build-desktop.sh run` | verified October 2026, not re-run since |
| The Android app builds and runs | `scripts/build-android.sh`, a Chromebook's Android runtime | verified October 2026. **Not rebuilt after the November changes**: the picker change in `androidMain/MainActivity.kt` has not been compiled |
| iOS / iPadOS | none | **never built**. `iosMain` is a scaffold; there is no Xcode host project |
| Another Craft app | none | **never tried**. §1 and §11 are written from WordCraft's engine; the other engines were not available to check against |
| Typing into the generated dialogs | none (the harness sends pointer input only) | not verified |

Treat every "should" about Android after November, iOS, and other apps as a plan, not a report.

## 1. What the engine must offer (check this before anything else)

The whole approach rests on the engine, not on Kotlin. "The same type of API" means all of the
following. Each line names where WordCraft has it, so the equivalent can be found in another app.

| # | The engine has | WordCraft | Needed for |
|---|---|---|---|
| 1 | A **command registry**: every user-visible action is a command with `id`, `label`, `location` (`Tab › Group[ › …]`), `params` (a doc string, §5.3), `shortcut`, `mutates` | `Session::registry`, `registry.describe()` → `engine.commands` | the ribbon, the forms, the all-commands list, shortcuts, the coverage test |
| 2 | **Run by id** with JSON params, returning JSON or an error string; never panics on junk | `Session::run(id, &params)`; `hostile_params_never_panic` | everything |
| 3 | An **enabled check with a reason** per command | `Session::why_disabled(id)` | greying buttons, tooltips |
| 4 | **State queries as commands** | `format.state`, `view.state`, `para.state`, `file.info` | toggles drawn pressed, the status bar, the title |
| 5 | A **headless backend**: a session with no window that answers the control protocol | `wordcraft_mcp::Headless` (`Backend::call`) | the FFI crate wraps exactly this |
| 6 | **Rendering to pixels** from the session, at a scale, with no window | `wordcraft_render::render_page` | the surface |
| 7 | **Geometry from the session**: where the selection is, and hit testing by position | `Session::layout()`, `caret.set {page,x,y}`, `select.objectAt` | drawing the caret, pointer input |
| 8 | **Requests to the front end**: a queue a command pushes to when it wants a dialog or a picker | `Session::ui_requests` (`{"open": "exportPdf"}`) | bare commands (Export, Save As, Options) doing something |
| 9 | Commands take **paths**, and offer a bytes form (`data`: base64) where a path is impossible | `file.open {path}`, `font.add {data}` | Android's content URIs, bundled fonts |

How to check an engine in ten minutes, before writing any Kotlin: build its headless backend, call
`engine.commands`, and look at the answer.

* Every entry has the six fields of row 1. A command with no `location` cannot be placed; one with
  no `params` doc gets a form with only a JSON box.
* Count the params docs that do not start with `{`. They should be rare and mean "no parameters".
* Run each parameterless, non-mutating command and look at what comes back (the probe in
  [`parity.md`](parity.md) "How parity is measured" did this for WordCraft). Toggles should answer
  small (`{"value": true}`); reports should answer with an object worth reading.
* Run a command that needs a window (`file.exportPdf` with no path). If nothing is queued for the
  front end, row 8 is missing: add it to the engine first.

If rows 1–5 are missing, build them in the engine before starting. Rows 6–9 can be added while
porting, but they are engine work, not front-end work.

## 2. The idea

```
Compose UI (app/, Kotlin)  ──  Engine.kt (commands as JSON)  ──  C ABI  ──  ffi/ (Rust)  ──  engine
```

The Kotlin app is **thin**. It never re-implements editing logic. It does three things: run a
command by id, read state back as JSON, draw what the engine renders. Because the ribbon, the
forms and the command list are *generated from the engine's command list*, a new engine command
appears in the UI with no Kotlin change, and a test fails if one ever has no way to it.

One C ABI serves every target: JNA on JVM and Android, Kotlin/Native cinterop on iOS.

## 3. Repository layout (copy this)

```
<app>/                      # a folder inside the Craft repo, e.g. loki-text/
  Cargo.toml                # its OWN Cargo workspace, members = ["ffi"]
  ffi/                      # the C-ABI crate (needs `unsafe`, so it can't live in the engine workspace)
    include/<name>.h        # hand-written header, used by iOS cinterop; keep in step with lib.rs
    src/lib.rs, state.rs, tests.rs
  app/                      # Gradle KMP module
    build.gradle.kts
    src/commonMain          # ALL UI + Engine.kt (this is where the work is)
    src/commonTest          # pure-Kotlin tests (parsers), run with :app:jvmTest
    src/jvmTest             # tests that read files: the engine coverage test, the screenshot harness
    src/jvmTest/resources/engine-commands.json   # the engine's command list, written by a Rust test
    src/jnaMain             # NativeEngine for JVM + Android (JNA)
    src/jvmMain             # desktop entry point, file picker, clipboard
    src/androidMain         # MainActivity, manifest, jniLibs/<abi>/lib<name>_ffi.so (built, gitignored)
    src/iosMain             # MainViewController, NativeEngine via cinterop
    src/nativeInterop/cinterop/<name>.def
  gradle/libs.versions.toml
  scripts/build-desktop.sh, build-android.sh, build-ios.sh
  docs/parity.md            # the app's own measured parity and gaps
```

Why a separate Cargo workspace: the engine workspace sets `unsafe_code = "forbid"` (never-crash
rules). The FFI crate is the one place that needs raw pointers; keep it small (Loki Text: about 500
lines) and keep the `#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, ...)]` header.
Release profile: `panic = "unwind"` so the boundary can catch panics. Mirror any `[patch]` the
engine workspace has.

## 4. The C ABI (keep it this small)

Six functions. Do not add more; add JSON *methods* instead.

```c
LokiSession *loki_session_new(void);
void         loki_session_free(LokiSession *s);
char        *loki_call(LokiSession *s, const char *method, const char *params_json);
void         loki_string_free(char *p);
uint8_t     *loki_render_page(LokiSession *s, uint32_t page, float scale,
                              uint32_t *width, uint32_t *height, uint32_t *pages);
void         loki_buffer_free(uint8_t *p, size_t len);
```

* `loki_call` returns `{"ok":true,"result":…}` or `{"ok":false,"error":"…"}`. Methods are the
  engine's control-protocol names (`engine.execute`, `engine.commands`, `document.inspect`) plus a
  few front-end-only ones prefixed `loki.`:

  | Method | Answers | Why it is not an engine command |
  |---|---|---|
  | `loki.selection` | caret and selection boxes in surface units, the selected object's frame | the engine's replies carry positions, not geometry |
  | `loki.commandState` | `{"disabled": {id: reason}, "on": [id…]}` plus what the contextual tabs show | one call instead of one per command |
  | `loki.viewport` | nothing; sets the size reflowing views wrap to | transient, per window |
  | `loki.uiRequests` | what commands asked the front end to do since the last call; reading clears | the queue is the session's, drained by whoever owns the window |

* Rendering returns straight-alpha RGBA8. Cap the bitmap (Loki Text: 8192² pixels).
* **Never crash**: every entry point wraps its body in `catch_unwind`; null and non-UTF-8 arguments
  are errors, not UB. Test it (`hostile_arguments_are_errors_not_crashes`).
* For an app whose surface is a canvas, timeline or artboard, keep the same shape:
  `render(region, scale)` returning RGBA. Add `loki.*` methods for geometry the UI needs.

## 5. Kotlin side

### 5.1 Engine.kt (commonMain)

* `expect class NativeEngine : AutoCloseable { callRaw(method, paramsJson): String; renderPage(...) }`
  with `actual`s in `jnaMain` (JNA `Library` interface; `Native.load("<name>_ffi", …)`) and `iosMain`.
* `class Engine(native)`: `call(method, params)` parses the reply and throws `EngineException` on
  `ok:false`; `execute(command, params)`, `commands()`, `commandState()`, `selection()`, `uiRequests()`.
* **Defensive parsing everywhere.** Replies are untrusted: use `as? JsonObject`,
  `(x as? JsonPrimitive)?.…OrNull`, default values. A malformed reply must degrade (empty state),
  never throw into the UI. Every parser has a test that feeds it junk.

### 5.2 Threading and the snapshot (important, easy to get wrong)

The native session is **not thread-safe** and calls block. Use one `Mutex` for *every* engine call
(edits, geometry, rendering) and run them on `Dispatchers.Default`.

A `revision` counter is bumped after each successful command; a `LaunchedEffect(revision, …)` takes
ONE **snapshot** under the lock (selection geometry, view state, command state, rendered surface)
and publishes it in a single batch of assignments. Publishing caret and surface together avoids a
frame where the caret is for the new document and the picture for the old one.

State the engine owns stays in the engine. **Zoom is the example**: the status bar, a pinch and the
View tab all run `view.zoom`, and the snapshot reads it back and renders at it. When the app kept
its own zoom, the View tab's buttons did nothing visible.

`perform` also drains `loki.uiRequests` after every command, inside the same lock, and hands them
to one coroutine that acts on them in order (§5.6). A caller that deals with the request itself
(Save, which asks for a place to save) passes `requests = false`.

> **Pitfall that cost an hour:** the Kotlin fallback for a failed call was
> `runCatching{…}.getOrDefault(EMPTY)`. When the installed native library was stale, the call
> failed silently and the ribbon simply looked unstyled. Fallbacks must log (`warnOnce`).

> **Kotlin pitfall:** the app's actions are local functions inside the root composable, and local
> functions cannot refer to one declared later. Order them so each calls only earlier ones
> (`runCommand` → `executeFromUi` → `runFromUi` → `exportTo` → `runFileMenu` → `handleUiRequest` →
> `runAny`), and give call sites in the UI the last one.

### 5.3 The generated UI: ribbon, form, result, command list

Four pieces turn `engine.commands` into a complete UI. All four are engine-agnostic.

**Ribbon** (the kit's `parseRibbon`; Loki Text's `Ribbon.kt` draws it). `location` is `Tab › Group[ › …]`. Tabs come in a fixed
`TAB_ORDER`, then the contextual tabs (`CONTEXTUAL_ORDER`) the selection brings up. Every listed
command of a tab is in its group. A command with a known icon and no required value is an icon
button; the rest are a menu under the group. A command that needs a value is marked
`RibbonItem.needsInput` and named with an ellipsis.

**Command form** (the kit's `CommandParams.kt` and `Dialogs.kt`). `parseParamForm` reads a params doc
into fields and never fails:

| In the doc | Field |
|---|---|
| `"name": string`, `"name"?: string` (`?` is optional) | text box |
| `bool` | yes/no chips |
| `pt`, `n`, `number`, `percent`, `deg`, `0-8` | number box |
| `"left\|center\|right"` or `"a" \| "b" \| 1.5` | choice chips, each sent as what it is |
| `percent \| "pageWidth" \| "onePage"` | number box with the names as suggestions |
| `"RRGGBB"` (with `\| null`, `\| "auto"`) | colour box with swatches |
| a key named `path`, `dir`, `…Path`, `…Folder` | text box with Browse |
| `[…]`, `{…}`, a capitalised type (`Pos`) | JSON box |
| `(text in parentheses)` after a type | the hint under the field |
| `{…} \| {…}`, or "exactly one" after the object | nothing is required; the raw JSON box takes other shapes |
| text after the object (`→ {…}`, `— …`) | the form's notes; **not** parameters |

A new engine should write its params docs in this grammar. Where a doc is wrong or missing, patch
it in `PARAM_DOCS` (two entries for WordCraft) and fix the engine later. Commands whose optional
keys carry a rule in prose ("give `tag` or `source`") go in `NEEDS_INPUT`.

**Result dialog** (`showsResult`, `describeResult`). A non-mutating command whose answer is more
than a toggle's new value shows it as named lines; **Change…** opens the same command's form, which
is how settings that are read and written by one command (Properties, Options) get edited.

**All-commands list** (`AllCommandsDialog`, `catalogSections`). Every command by place: the tabs,
then places outside the ribbon (`OTHER_PLACES`: File, Navigation…), then any place the app has
never heard of. Search matches names, places and ids. A button beside Undo/Redo opens it at every
size, so it is the guarantee that nothing is out of reach.

What is deliberately **not** listed (`CommandProfile.isListed`), each a named set of the profile
that the coverage check verifies (Loki Text's are in `CommandCatalog.kt`):

| Set | Meaning | For a new app |
|---|---|---|
| `POINTER_GESTURES` | commands a press or drag on the surface issues | list them as you build each gesture |
| `POINTER_GAPS` | gestures the desktop has and this front end does not | the honest to-do list; the test lets it shrink, not grow |
| `TYPING_COMMANDS` | issued by the keyboard | text insertion and the like |
| `STATE_READERS` | read by the app itself to draw toggles and panes | the `*.state` commands |

### 5.4 Controls the app draws itself

Generated buttons are the floor, not the ceiling. Where a group deserves real controls (font and
size boxes, colour pickers, table sizes), `hasCustomGroup(tab, group)` hands the group to a
composable. Rules that keep this from breaking the guarantee:

* A custom group still gets a `…` at the left of its name listing every command of the group.
* Commands the app answers with its own dialog or pane when run bare are in `APP_DIALOGS`.
* `GROUP_LAUNCHERS` maps a group's corner control to the command that opens its dialog.
* Build these last and only where the generated form is clumsy. Loki Text has about fifteen
  bespoke dialogs against the desktop's sixty; the form covers the rest.

### 5.5 Command state (disabled + toggles)

`loki.commandState` (Rust, `ffi/src/state.rs`) returns `{"disabled": {id: reason}, "on": [id,…]}`
from the engine's own enabled checks and state queries. The UI greys a button with
`enabled = disabledReason == null`, shows the reason in its tooltip, and draws a toggle that is on
with a tinted container. It is fetched in the same snapshot as the render. `state.rs` is the most
app-specific Rust: its tables map state keys to the commands that flip them.

It is computed for every command on every snapshot; fine at 700 commands, measure before copying it
to an app with thousands.

### 5.6 Requests from the engine, and what the app must draw

Two things a headless engine cannot do, which the desktop front end does silently and a new front
end therefore forgets:

**Answer requests.** A command run bare from a menu may queue `{"open": "exportPdf"}`,
`{"close": true}` or `{"interfaceTheme": "dark"}`. `handleUiRequest` maps each to a platform
picker, a dialog of the app's, or (through `REQUEST_DIALOGS`) the form of the command that takes
what the dialog would have collected. List the engine's requests with
`grep -rn "push_ui_request" crates/engine/src` and give each a line.

**Draw the view state.** The headless renderer draws plain content. Whatever the desktop front end
paints on its own canvas has to be drawn here from `view.state` (`ViewState.kt`). For WordCraft
that was: zoom and its fits, dark pages and paper colour (a colour filter over the image),
gridlines, the ruler, which panes are open, and the Draw tab's tool in hand. To find the list for
another app, run every toggle on its View tab against the headless renderer and compare pixels
before and after: a toggle that changes state but not pixels is the front end's to draw.

**Panes** (`SidePanes.kt`). A pane is a command that lists something plus actions on a row:
`paneSource(kind)` names the command, `parsePane` turns its answer into rows. One `PaneHost` shows
all open panes in one place with chips to switch, so a phone never gives its screen to panes.

### 5.7 Responsive layout

One `BoxWithConstraints` at the root computes a size class from the **window width** (not the
device type, so it also handles split-screen, rotation, foldables and resizable windows):

| Class | Width | Ribbon | Chrome |
|---|---|---|---|
| Compact | < 600 dp | `SimpleRibbon`: tab row + ONE scrolling row: icons, a button per group that opens its value controls, menus for the rest (quick-access buttons and the all-commands magnifier lead the row) | page count as `1/3`; zoom is a menu under the percentage; panes above the surface |
| Medium | 600–900 dp | same `SimpleRibbon` | full counts, zoom controls; panes beside the surface |
| Expanded | ≥ 900 dp | full grouped ribbon: tab bar, scrolling row of groups, group names, separators, galleries | adds detail to the status bar |

The rule that matters: **a smaller window may move a feature, never remove it.** Each row of this
table is a place a feature goes, and `parity.md` has the per-feature table to copy.

Rules that made it work:

* Every group's content box has the same fixed height (`GROUP_CONTENT_HEIGHT`) so group names align,
  and so a group's controls fit unchanged in the panel a small window opens (`GroupPanelButton`).
* Wide content scrolls (`ScrollRow`) instead of wrapping or being clipped. A mouse cannot drag, so
  arrows appear at an end that has more.
* Dialogs use `SheetDialog`: full width less a margin, capped at a reading width, title and buttons
  fixed, body scrolling, so the buttons are in reach with the keyboard up or in landscape.
* Touch targets: 32 dp icon buttons in a row that scrolls are acceptable (a miss costs a second
  tap); list rows are at least 44 dp.
* A window narrower than the content (`NARROW_WINDOW` = 640 dp) switches a document to a
  *reflowing* view and restores the page view when widened (`autoWeb` remembers that it was
  automatic). The engine wraps to a viewport pushed with `loki.viewport`, **debounced 150 ms**. An
  app with a canvas instead fits the canvas to the width.
* Zoom re-renders at a new scale, with the page count capped (`pageRenderCap`) because bitmap
  memory grows with the square of zoom. During a pinch the picture is only scaled on screen; the
  zoom is sent when the fingers lift, and the scaled picture stays until the sharp one arrives.
* Don't recreate the activity on resize or rotation: the open document lives in the engine
  session. Manifest: `android:configChanges="orientation|screenSize|smallestScreenSize|screenLayout|density|keyboard|keyboardHidden|navigation|uiMode|layoutDirection|fontScale"`
  and `windowSoftInputMode="adjustResize"`.
* Pad the root with `WindowInsets.safeDrawing` (system bars, cutout, keyboard).

### 5.8 Theme

`Theme.kt` defines a light and a dark Material 3 scheme (`LokiColors`, `LokiDarkColors`), a bar
token (`BarBlue`) for the tab bar and status bar, and `AppTheme`, which holds the choice (system,
light, dark). **Pick one accent token per app and reference it; don't scatter hex values.** Use
each app's own palette.

* The root is a plain background, not a `Surface`, so `AppTheme` provides `LocalContentColor`.
  Without it, icons were black on the dark theme.
* Content on the bars is wrapped in `MaterialTheme(colorScheme = OnBarColors)`. A menu opened from
  a bar must go back to `LocalBaseColors`, or it is white on white.
* A dark *interface* and dark *content* are two settings. The engine has one for each.

Icons: the [Lucide](https://lucide.dev) Compose package (`com.composables:icons-lucide-cmp`, ISC).
It is a *dependency*, not files in the repo, so it needs no `ATTRIBUTION.md` rows. **Never copy
another vendor's iconography** (Craft rule). A map `commandId → ImageVector` lives in
`RibbonIcons.kt`; a command without an icon is a named menu entry, so icons are never a blocker.

### 5.9 Input and the platform edge

* Keys map to commands twice: the engine's own shortcuts (`parseShortcuts` reads them from
  `engine.commands`), then typing and caret keys (`Keys.kt`). Ctrl or Meta both act as Mod.
* The surface is a bitmap, so a hidden text input is what the on-screen keyboard and IME talk to
  (`ImeInput.kt`). An app with no text entry on its surface does not need it.
* Pointer: taps and drags convert pixel offsets to surface units and call hit-testing commands; a
  finger's drag scrolls, a mouse's drag selects. Tools that draw (Draw Table, pens) take every press
  while they are in hand.
* Everything the platform does is an `expect fun`, six in all:

  | `expect fun` | JVM | Android | iOS |
  |---|---|---|---|
  | `rgbaToImageBitmap` | Skia | Skia | Skia |
  | `pickFile(save, suggestedName)` | AWT `FileDialog` | Storage Access Framework; copies to and from app storage because the engine takes paths | **TODO** (`UIDocumentPickerViewController`) |
  | `publishFile(path)` | nothing | copies what the engine wrote to the URI the person picked | **TODO** |
  | `printJobPath`, `printPdf` | `Desktop.print` | `PrintManager` | **TODO** (`UIPrintInteractionController`) |
  | `clipboardText`, `setClipboardText` | AWT | `ClipboardManager` | **TODO** (`UIPasteboard`) |
  | `previewFontFamily` | Skia | Android font | Skia |

## 6. Testing

Three layers, cheapest first. A port is not done until all three exist.

1. **Rust** (`cd <app> && cargo test -p <name>-ffi`): FFI round trip, hostile arguments, command
   state, `loki.uiRequests`, and `kotlin_command_fixture_follows_the_engine`, which writes
   `engine.commands` to `app/src/jvmTest/resources/engine-commands.json` under
   `LOKI_UPDATE_FIXTURES=1` and otherwise fails when the two differ. Expect to refresh it after
   every engine merge; that is the point.
2. **Kotlin** (`gradle :app:jvmTest`): pure parsers in `commonTest` (keep parsing out of
   composables), and in `jvmTest` the **coverage test** (`EngineCoverageTest`, a few lines over the
   kit's `coverageProblems`), which reads the fixture and fails when
   * a command is neither listed nor in one of the four named sets of §5.3;
   * a command on a ribbon tab is not on the ribbon, or a group is empty;
   * a command that needs values has a form with no fields;
   * the app names a command the engine does not have (every id in a Kotlin table is checked).

   This test is the measure of parity. Write it on day one and let it drive the work: its first
   failure list is the to-do list.
3. **Look at it.** `ScreenshotHarness` draws the real app off screen with the real engine library
   and saves PNGs; no display, device or emulator is needed:

   ```sh
   cargo build -p <name>-ffi
   LOKI_LIB=$PWD/target/debug LOKI_SHOTS=/tmp/shots LOKI_SHOT_DOC=/path/to/sample \
   LOKI_SHOT_SCRIPT='size=390x780@2; wait=3000; shot=phone.png; click=116,66; wait; shot=commands.png' \
   gradle :app:jvmTest --tests '*ScreenshotHarness*' --rerun
   ```

   Run it at 390, 760 and 1280 dp, light and dark, and read the images. It sends clicks and drags
   in dp; **it cannot type**, so make a sample document with the engine's CLI or a short script and
   open it with `LOKI_SHOT_DOC`. Four bugs in the November pass were found only this way.

   It is a desktop render of the common code: it proves layout, not the Android or iOS edge.

## 7. Build and run

```sh
# Desktop
scripts/build-desktop.sh                      # cargo build --release -p <name>-ffi
gradle :app:run                               # build.gradle.kts passes -Dloki.lib=<rootDir>/target/release to the JVM

# Android (needs SDK + NDK; ANDROID_HOME defaults to ~/Android/Sdk)
scripts/build-android.sh                      # builds the .so into app/src/androidMain/jniLibs/<abi>/, then :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n <applicationId>/.MainActivity

# iOS (macOS only; never run)
scripts/build-ios.sh                          # libloki_ffi.a for aarch64-apple-ios and -sim
```

On an aarch64 Linux host (an ARM Chromebook) the NDK's own compiler is x86_64-only, so
`build-android.sh` uses the host's `clang` + `lld` against the NDK sysroot, via a per-target linker
wrapper. On x86_64 it uses `cargo-ndk`.

`:app:jvmTest` and `:app:compileKotlinJvm` configure and run **without** an Android SDK, so the
common code can be developed and tested on any machine with a JDK and Rust.

### Pitfalls (each one bit us)

1. **Stale native library.** Gradle does not rebuild Rust. If you only run `gradle :app:assembleDebug`,
   the APK ships whatever `.so` is in `jniLibs`. After any change under `ffi/` or the engine, run
   `build-android.sh`. Check `ls -l --time-style=full-iso` on the `.so` against your source mtimes.
   `strings` on the `.so` is NOT a reliable check.
2. **Gradle version vs AGP.** AGP 8.11.x fails on Gradle ≥ 9.6. Gradle 8.14.3 and 9.1.0 both work.
   No wrapper is committed; generate one (`gradle wrapper --gradle-version 9.1.0`) so nobody depends
   on a machine-local install.
3. **`SDK location not found`** on an Android task when calling `gradle` directly: export
   `ANDROID_HOME` (the build script does) or write `local.properties` (gitignored).
4. **Network.** The build needs Google's Maven repository (`dl.google.com`) as well as Maven
   Central and the Gradle plugin portal; a sandbox or CI that allows only the usual package
   registries fails on `com.android.application`. A cold cache pulls hundreds of files from Maven
   Central and can be rate-limited (HTTP 429): retry, Gradle keeps what it has. Don't pass
   `--offline` unless the cache is known complete.
5. **A script's `timeout`** kills the first cold Rust build (about 8 minutes for a release build on a small ARM
   machine; 2 for a debug build on a 4-core x86). Run long builds in the background with no timeout and log to a file.
6. **ChromeOS Android runtime:** `adb devices` is empty until `adb connect 100.115.92.2:5555`. Use
   `adb shell am start -n <pkg>/.MainActivity`. Screenshots come back **black** when another
   activity holds the foreground: start the app again before `adb exec-out screencap -p`.
7. **Low-memory machines** (6 GB, no swap): put overrides in `~/.gradle/gradle.properties`
   (`org.gradle.jvmargs=-Xmx2g`, `kotlin.compiler.execution.strategy=in-process`,
   `org.gradle.workers.max=2`). On aarch64, Google ships `aapt2` for x86_64 only:
   `android.aapt2FromMavenOverride` pointing at an emulated binary.
8. **Version drift:** the versions in `libs.versions.toml` are the ones that built (Kotlin 2.2.20,
   Compose Multiplatform 1.9.0, AGP 8.11.1, JNA 5.17.0, coroutines 1.10.2, serialization 1.9.0,
   Lucide 2.2.1). Material 3 tab-indicator APIs move between releases; if `tabIndicatorOffset` or
   `SecondaryIndicator` changes, adapt `RibbonTabRow` only.
9. **Release builds:** R8 is off until JNA's reflection has keep rules, and the release variant is
   signed with the debug key. Both must be settled before anything is distributed.

## 8. What to depend on, copy, adapt and rewrite

The engine-agnostic parts are a module, **Loki Kit** ([`../kit`](../kit/README.md), `app.loki:kit`):
depend on it, do not copy it. Loki Text is about 9,200 lines of common Kotlin on top of 1,300 in
the kit and 500 of Rust.

| Depend on (the kit) | Copy from Loki Text, then generalise into the kit | Adapt (same shape, the app's names) | Rewrite (the app's own surface) |
|---|---|---|---|
| `EngineClient`, `EngineTransport` | `Ribbon.kt`: `Ribbon`, `SimpleRibbon`, `GroupPanelButton`, `ScrollRow`, `RibbonTabRow`, `CommandMenu` | `ffi/src/lib.rs`: the crate name, `loki.selection`, render | the surface part of `App.kt`: what is drawn, pointer gestures, selection overlay |
| `CommandProfile`, `parseCommands`, `parseRibbon` | `RibbonControls.kt` (buttons, fields, menus) | `ffi/src/state.rs`: the toggle tables, contextual tabs | `ViewState.kt`: what `view.state` holds and what the app must draw |
| `parseParamForm`, `CommandFormDialog`, `ResultDialog`, `SheetDialog` | the shell in `App.kt`: lock, snapshot, `perform`, `handleUiRequest`, size classes | `CommandCatalog.kt`: the profile and its named sets, `APP_DIALOGS`, `REQUEST_DIALOGS` | the bespoke groups and dialogs (`HomeRibbon.kt`, `TableRibbon.kt`, `FontDialog.kt`…) |
| `AllCommandsDialog`, `PaneList` | `FileMenu.kt` (the mechanism) | `RibbonIcons.kt`: the id → icon map | `Ruler.kt`, `ImeInput.kt`, `SelectionHandles.kt` (documents only) |
| `parseShortcuts`, `CraftTheme` | platform files: pickers, clipboard, print, `NativeEngine` | `SidePanes.kt`: `PaneKind`, `paneSource`, `parsePane` | the status bar's counts |
| `coverageProblems`, `runShotScript` | the fixture test in `ffi/src/tests.rs` | `Theme.kt`: the palette and bar token; `build.gradle.kts`: `namespace`, `applicationId` | |

The second column is the work still to share. Whoever ports the second app should move each piece
into the kit as they find what has to become a parameter, rather than keep two copies in step.

## 9. Porting procedure

Each phase ends with something that runs. Do not start a phase before the one above passes.

| Phase | Do | Done when |
|---|---|---|
| 0. Engine | Read the app's `AGENTS.md`; its rules (never crash, no third-party assets, `cargo xtask ci`) apply to the new folder. Check the engine against §1. | `engine.commands` from its headless backend has all six fields for every command |
| 1. FFI | Copy §3's layout. Rename the crate (`<app>-ffi`), library, header. Implement the six functions against the app's backend. Port the hostile-arguments and round-trip tests. | `cargo test -p <app>-ffi` passes; `clippy` clean |
| 2. Fixture | Add `kotlin_command_fixture_follows_the_engine`; generate the fixture. | `engine-commands.json` is in the tree |
| 3. Skeleton | Depend on the kit; copy §8's second column. Write the app's `CommandProfile` with the fixture's tabs and empty sets. Show one rendered surface. | `gradle :app:jvmTest` compiles; the harness saves a picture of the app with a ribbon |
| 4. Coverage | Add a test that asserts the kit's `coverageProblems` is empty. Work its lines: fill the profile's `typing`, `stateReaders`, `paramDocs`, `needsInput`; put every pointer command in `pointerGaps` for now. | the test passes; `POINTER_GAPS` is an honest list |
| 5. Surface | Selection geometry (`loki.selection`), pointer input, zoom from the engine, the snapshot. Move commands from `POINTER_GAPS` to `POINTER_GESTURES` as each gesture is built. | the app can be used for the app's basic task with a mouse and with a finger |
| 6. State | `state.rs` toggles; `loki.uiRequests` and `handleUiRequest`; the view state the front end must draw (§5.6). | every toggle on the View tab changes what is on screen; Export and Save As open a picker |
| 7. Responsive | Check each feature at the three widths; fill the per-feature table in the app's `parity.md`. | harness pictures at 390, 760 and 1280 dp, light and dark, read and filed |
| 8. Polish | Bespoke groups and dialogs where the form is clumsy; icons; panes; the app's palette. | by judgement; the coverage test stays green throughout |
| 9. Android | `scripts/build-android.sh`; install; use it with a finger; rotate, split-screen, keyboard up. | it runs on a phone and a tablet (or a Chromebook) |
| 10. Record | Write `docs/parity.md` (measure, what the app draws, gaps). Update the app's roadmap and `ATTRIBUTION.md` for any asset *file* added (bundled fonts need rows; the Lucide dependency does not). | the doc's numbers come from the test |

Phases 0–4 are mechanical. Phases 5–6 are where the app's own nature shows; expect most of the
time to go there. No timings are given: only one app has been done, and not in this order.

## 10. Building the suite

Not done; recommendations for whoever starts the second app.

* **The kit exists** ([`../kit`](../kit/README.md)); it lives inside Loki Text for now. Its own
  repository, and whether it is published or included as a build, is the owner's decision. Until
  then another app includes this build (`includeBuild`) and depends on `app.loki:kit`.
* **Finish the kit** with the second app: the ribbon's composables and the app shell (§8, second
  column) are the largest pieces still copied.
* **Share the FFI shape too.** The six functions, `catch_unwind`, the reply format and the hostile
  tests are the same for every engine; only `loki.selection`, the render call and `state.rs` differ.
* **One symbol prefix per app** (`loki_`, and so on) if two engines could ever be linked into one
  binary; otherwise the same header serves all.
* **Give the engines one params-doc grammar** (§5.3's table) and lint it in each engine's CI, so
  forms are good without per-app patches.
* **Settings and recent files** are per-app state no engine holds. Loki Text keeps none between
  runs (theme, paper colour). The suite needs one small shared store (Android `DataStore`, iOS
  `NSUserDefaults`) behind an `expect` interface.
* **CI:** the Rust and JVM tests need only a JDK, Rust and network access to Maven Central, the
  plugin portal and `dl.google.com`. The Android build needs the SDK and NDK. Craft CI changes go
  through `storytold/craft-common-actions`, not a feature pull request.

## 11. What differs per app (a plan, not a report)

Written from WordCraft alone; none of this has been tried.

| | Document apps (WordCraft, DesignCraft, PDFCraft) | Canvas apps (PhotoCraft, VectorCraft, LightCraft) | Timeline apps (FilmCraft, EffectCraft) |
|---|---|---|---|
| Surface | pages in a scrolling column | one canvas, panned and zoomed | a viewer plus a timeline: two surfaces |
| Render call | `render(page, scale)` | `render(region, scale)`; tiles when zoomed in | a frame at a time; a strip for the timeline |
| Narrow window | reflow the text | fit the canvas to the width | stack viewer over timeline |
| Pointer | caret and selection | tools: every press belongs to the tool in hand (the Draw Table and ink tools here are the pattern) | scrub, trim, drag clips |
| Text input | the IME bridge | only in text tools and dialogs | only in dialogs |
| Extra panes | Navigation, Comments | Layers, History, Properties: the pane host and `parsePane` fit | Bins, Effects |
| Hard part | typing and IME | brush latency through a JSON call per stroke; send strokes whole, as `draw.stroke` does | playback: frames cannot come one JSON call at a time. Needs a streaming path the ABI does not have |

The last row is the main open design question for the suite. Stroke latency and video playback are
the two places where "one JSON call per action" may not be enough, and the ABI would have to grow
(a shared frame buffer, a callback). Decide that with the first canvas or timeline app, with
measurements, before copying this ABI to all of them.

## 12. iOS and iPadOS: what is left

Nothing here has been built. In order:

1. An Xcode host project that embeds the `LokiApp` framework and calls `MainViewController()`.
2. Build `libloki_ffi.a` (`scripts/build-ios.sh`) and link it; confirm the cinterop binding in
   `iosMain/engine/NativeEngine.kt` against `ffi/include/loki.h`.
3. The four `TODO` rows of §5.9's table: document picker (with security-scoped URLs copied to and
   from the app container, as Android does), `UIPasteboard`, `UIPrintInteractionController`.
4. The software keyboard and IME through Compose's iOS text input; hardware keyboard shortcuts on
   iPadOS; pointer (trackpad) support, which should behave as the mouse path does.
5. iPadOS multitasking (Split View, Slide Over, Stage Manager): the size class already follows the
   window width, so this is a matter of testing, not design.
6. Fonts: bundled fonts load through `font.add` with bytes on every platform; system fonts are not
   enumerated anywhere yet.
7. App Store matters the code does not touch: signing, the licences of bundled fonts, and that the
   engine's Rust dependencies are all on the Craft licence allowlist.

## 13. Known gaps in Loki Text (don't assume they're solved)

See [`parity.md`](parity.md) for the measured coverage, the pointer gestures not built, and what
the desktop front end does that this one does not. In short: most dialogs are the generated form;
there is no start screen, recent files or print preview; settings are not saved between runs; the
interface is English only; Android has not been rebuilt since the November changes; iOS has never
been built.
