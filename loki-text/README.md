# Loki Text

A Kotlin Multiplatform (Compose) front end for the WordCraft engine, for Android, iOS and JVM
desktop. It is separate from the egui front end (`crates/ui-egui`) and shares only the Rust engine.

```
Compose UI (app/, Kotlin)  ──  Engine.kt (commands as JSON)  ──  C ABI  ──  loki-ffi (ffi/, Rust)  ──  wordcraft-mcp::Headless / wordcraft-engine
```

## Why a C ABI with JSON

Everything in WordCraft is a command (`CLAUDE.md`), so the UI needs only: run a command or
control-protocol method by id (`engine.execute`, `engine.commands`, `document.inspect`), and draw a
page. `ffi/` exposes exactly that (`ffi/include/loki.h`): `loki_session_new/free`, `loki_call`,
`loki_render_page`. One ABI serves JVM and Android (JNA, `app/src/jnaMain`) and iOS (Kotlin/Native
cinterop, `app/src/nativeInterop`). New engine features reach the UI with no binding changes.

## Layout

- `Cargo.toml`, `ffi/`: own Cargo workspace (the engine workspace forbids `unsafe`; the FFI crate
  needs it). Depends on `../crates/*` by path. Panics are caught at the boundary.
- `app/`: the Gradle KMP module. `commonMain` has the UI and `Engine`; `jvmMain`, `androidMain`,
  `iosMain` have the entry points, image conversion and (iOS) the native binding.
- `scripts/`: build the Rust library per target.

## Run

Desktop: `scripts/build-desktop.sh && gradle :app:run` (add a Gradle wrapper with
`gradle wrapper`). Android: `scripts/build-android.sh`, then run `:app:installDebug`. iOS:
`scripts/build-ios.sh`, then a thin Xcode host app calls `MainViewControllerKt.MainViewController()`
(the Xcode project is not generated yet).

## Rules that still apply

The repository rules in `CLAUDE.md` hold here: never crash (the FFI layer returns errors, and
Kotlin code surfaces `EngineException` rather than throwing into the UI), and no Microsoft/Adobe
assets or iconography; add a row to `ATTRIBUTION.md` for every asset file.

## Status

Walking skeleton: insert text, Bold and a rendered page 1. Not yet built on a machine with the
Android/iOS toolchains, and Gradle plugin versions in `gradle/libs.versions.toml` should be bumped
to current releases. Next: caret and selection from `document.inspect`/hit testing, pointer input
(`ui.*` methods need an attached UI; add engine hit-test calls), ribbon from `engine.commands`,
file open/save through platform pickers, and IME.
