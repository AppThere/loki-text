# Loki Kit

What every Craft front end of this kind shares, as one Kotlin Multiplatform module
(`app.loki:kit`, package `app.loki.kit`). It knows nothing about any one engine. An app gives it
two things:

* an `EngineTransport`: the one JSON call of the engine's C ABI (`loki_call`), from the app's own
  platform binding;
* a `CommandProfile`: the app's ribbon tabs, the places outside the ribbon, and a few named sets
  of command ids.

From `engine.commands` and those, the kit builds the parts that were the same work in every app.
Loki Text (`../app`) is the first user and the worked example; how to port an app is in
[`../docs/kmp-responsive-ui-playbook.md`](../docs/kmp-responsive-ui-playbook.md), and
[`../scripts/egui2compose.py`](../docs/egui2compose.md) writes a new app's starting project on the
kit from its egui front end.

## What is in it

| File | Gives an app |
|---|---|
| `EngineClient.kt` | `EngineClient`: `call`, `execute`, `commands`, `commandState`, `uiRequests`; `EngineException`. An app extends it with what its surface needs (rendering, selection geometry). |
| `Commands.kt` | `CommandInfo`, `CommandProfile`, `parseCommands`; the ribbon model (`RibbonItem`, `RibbonGroup`, `RibbonTab`, `parseRibbon`); `catalogSections`, `matchesQuery`; results (`isInformative`, `describeResult`); `fitZoom`; `displayShortcut`; and **`coverageProblems`**, the check that every command has a way to it. |
| `CommandParams.kt` | `parseParamForm`: a params doc into form fields; `buildParams`: the filled form into JSON. The grammar is in the playbook, §5.3. |
| `CommandBar.kt` | `CommandBar`: tabs, then one scrolling row of each group's buttons and menu. Works for a ribbon's tabs and a menu bar's menus, at every width. The floor an app starts from. |
| `CanvasWorkspace.kt` | The art apps' window: `MenuBar` (text menus from the engine's), `ToolRail` (icon tools with alternates on a long press or right click; its footer, an art app's colours, stays put while the tools scroll), `PanelDock` (icon strip, tabbed panels), `DocumentTab`, `StatusBar`, and `CanvasWorkspace`, which lays them out and moves them on a phone. |
| `ArtCanvas.kt` | The art apps' canvas: the page on a pasteboard (`CanvasView`: fit, pan, zoom about a point, a zoom pill), and every press turned into document-space `CanvasInput` for the app's tools. Pens are first-class: pressure, tilt and rotation on every point, the eraser end, the high-rate samples between frames, and palm rejection (once a pen has touched the canvas, fingers only pan and pinch). `penPose` reads tilt and the side button where only the platform has them (Android: `MotionEvent` axes; desktop and iOS: none yet). `WorkspaceContext` is what the generated workspace hands its canvas. `renderScaleFor` picks a render scale that does not change at every frame of a pinch. |
| `MenuSheet.kt`, `Platform.kt` | On a phone or tablet (`isTouchPlatform`) the art workspace's menus open from one button as a sheet (`MenuSheet`: from the bottom on a phone, from the side on a tablet), with finger-sized rows and the reason a command can't run written out; `TouchTopBar` holds it, the document title (which opens File) and the app's buttons. |
| `CanvasText.kt`, `SvgPath.kt` | `CanvasTextEditor`: a text field floating on the canvas where a Type tool was tapped. `svgPath`: an engine's SVG path data as a Compose path, for drawing its overlays. `FileIo` and `addToFileMenu` (in `ArtCanvas.kt`) let an app add its own File actions. |
| `InkLayer.kt` | Low-latency ink: `InkLayer` and `InkSurface`. On Android 10+ a stroke's segments go straight into the display's front buffer as the pen moves (androidx.graphics `CanvasFrontBufferedRenderer`, Apache-2.0, a dependency), are committed when it lifts, and cleared once the engine's picture shows the stroke. Elsewhere the layer is inactive and the canvas overlay draws the wet ink. |
| `ColorPicker.kt`, `NumberChip.kt` | An art app's controls. `ColorPickerDialog`: a saturation and brightness square, a hue strip, a hex field, a palette and the colours picked lately (`allowNone` for a vector fill or stroke); `ColorWell` is the swatch that opens it. `NumberChip` (a slider, steps and a typed value; a logarithmic scale for sizes), `ChoiceChip` and `ToggleChip` are the options bar's controls: each opens under itself, and the engine hears a value when the slider is let go, not at every pixel. |
| `Shortcuts.kt` | `parseShortcuts` reads every command's shortcut from `engine.commands` (`Mod+` or the art engines' `Cmd+`); `Modifier.shortcutKeys` runs them for a whole window from the keys nothing inside used, so a text field keeps its typing. The art workspace adds each tool's letter. |
| `TransformBox.kt` | Handles to scale and turn a selection, sized for a finger: `TransformBox` (eight handles and a turning knob on a box that may itself be turned; the knob stands where the canvas has room), which handle a press is on, and the math of a drag (`scaled`: the opposite side or the centre stays put, Shift keeps the shape; `rotated`: about the centre, Shift in steps of 15°). `ArtCanvas(claimsTouch = …)` lets a finger grab a handle even after a pen has been used, when fingers otherwise only pan. `TouchModifiers` and `ModifierChips`: Shift and Alt as chips for the options bar, since a touch screen has neither key; they feed every input's `mods`. |
| `Dialogs.kt` | `SheetDialog` (the responsive dialog frame), `CommandFormDialog`, `ResultDialog`. |
| `AllCommandsDialog.kt` | Every command by place, with search. |
| `Panes.kt` | `PaneContent`, `PaneRow`, `PaneAction`, `PaneStep` and `PaneList`: a task pane that lists a command's answer with actions on each row. |
| `Shortcuts.kt` | `Chord`, `parseShortcuts`: the engine's own key bindings from `engine.commands`. |
| `Theme.kt` | `CraftTheme` (light, dark or the system's, with the content colour set), `LocalBaseColors`. |
| `jvmMain/ShotScript.kt` | `runShotScript`: draws an app off screen at any window size and saves PNGs. |

## What is not in it yet

Still in Loki Text, to be moved when a second app shows where the seam is:

* **The ribbon's composables** (`Ribbon`, `SimpleRibbon`, `GroupPanelButton`, `RibbonTabRow`,
  `ScrollRow`, `CommandMenu`) and `RibbonControls.kt`. They are generic in shape but still take
  Loki Text's `RibbonEnv`, `CommandState` and Styles gallery.
* **The app shell**: the engine lock, the snapshot, `perform`, `handleUiRequest`, the size classes
  and the status bar (`App.kt`).
* **The platform edge**: `NativeEngine` (JNA and cinterop), file pickers, clipboard, printing.
* **The FFI crate's shape** (`../ffi`): the six functions and their hostile-argument tests.

## Using it from an app

Inside this build it is `implementation(project(":kit"))`. From another Craft repository, until
the kit has a home of its own, include this build and depend on the coordinates:

```kotlin
// settings.gradle.kts of the other app
includeBuild("../loki-text/loki-text")          // wherever this folder is checked out

// build.gradle.kts, commonMain
implementation("app.loki:kit")
```

Then, in the app:

```kotlin
val PROFILE = CommandProfile(
    tabs = listOf("Home", "Edit", "View"),            // from the engine's `location`s
    contextualTabs = listOf("Layer"),
    otherPlaces = listOf("File"),
    pointerGaps = setOf(/* every pointer command, until its gesture is built */),
    stateReaders = setOf("view.state"),
)

class Engine(native: NativeEngine) : EngineClient(object : EngineTransport {
    override fun callRaw(method: String, paramsJson: String?) = native.callRaw(method, paramsJson)
    override fun close() = native.close()
})

val commands = parseCommands(engine.commands(), PROFILE)
val ribbon = parseRibbon(engine.commands(), PROFILE)
```

and the test that measures the port (the app's `jvmTest`):

```kotlin
val problems = coverageProblems(fixtureJson, PROFILE, ribbon, ::drawsGroup, namedIds, ownDialogs, maxGaps = PROFILE.pointerGaps.size)
assertEquals(emptyList(), problems, problems.joinToString("\n"))
```

## Rules for changing it

* **Nothing app-specific.** No command id, tab name, colour or string that belongs to one engine.
  If a function needs one, it takes it as a parameter or from the `CommandProfile`.
* **Parsers never throw** on what an engine sends, and each has a test that feeds it junk.
* **A change here changes every app.** Run `./gradlew :kit:jvmTest :app:jvmTest`, and look at the app
  (`ScreenshotHarness`) when a composable changed.

## State

Compiled and tested on the JVM (`./gradlew :kit:jvmTest`, 21 tests; Loki Text's 148 tests run on top
of it, and the app was re-rendered through it at phone and desktop sizes). Eleven projects
generated by [`scripts/egui2compose.py`](../docs/egui2compose.md), one per Craft repository,
compile against it and pass their coverage tests. The Android and iOS
targets are declared but **have not been compiled**: no Android SDK or macOS was available. The
composite-build recipe above is the standard Gradle mechanism but has not been tried from a second
repository.
