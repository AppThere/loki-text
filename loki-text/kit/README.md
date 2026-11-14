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
[`../docs/kmp-responsive-ui-playbook.md`](../docs/kmp-responsive-ui-playbook.md).

## What is in it

| File | Gives an app |
|---|---|
| `EngineClient.kt` | `EngineClient`: `call`, `execute`, `commands`, `commandState`, `uiRequests`; `EngineException`. An app extends it with what its surface needs (rendering, selection geometry). |
| `Commands.kt` | `CommandInfo`, `CommandProfile`, `parseCommands`; the ribbon model (`RibbonItem`, `RibbonGroup`, `RibbonTab`, `parseRibbon`); `catalogSections`, `matchesQuery`; results (`isInformative`, `describeResult`); `fitZoom`; `displayShortcut`; and **`coverageProblems`**, the check that every command has a way to it. |
| `CommandParams.kt` | `parseParamForm`: a params doc into form fields; `buildParams`: the filled form into JSON. The grammar is in the playbook, §5.3. |
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
* **A change here changes every app.** Run `gradle :kit:jvmTest :app:jvmTest`, and look at the app
  (`ScreenshotHarness`) when a composable changed.

## State

Compiled and tested on the JVM (`gradle :kit:jvmTest`, 18 tests; Loki Text's 148 tests run on top
of it, and the app was re-rendered through it at phone and desktop sizes). The Android and iOS
targets are declared but **have not been compiled**: no Android SDK or macOS was available. The
composite-build recipe above is the standard Gradle mechanism but has not been tried from a second
repository.
