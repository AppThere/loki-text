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
- `kit/`: Loki Kit, the engine-agnostic part (command model, generated forms, all-commands list,
  coverage check, screenshot runner) that other Craft front ends depend on. See `kit/README.md`.
- `scripts/`: build the Rust library per target.

## Run

Desktop: `scripts/build-desktop.sh run` (add a Gradle wrapper with
`gradle wrapper`). Android: `scripts/build-android.sh`, then run `:app:installDebug`. iOS:
`scripts/build-ios.sh`, then a thin Xcode host app calls `MainViewControllerKt.MainViewController()`
(the Xcode project is not generated yet).

## Credits

Ribbon icons are [Lucide](https://lucide.dev) (ISC licence; some derived from Feather, MIT),
through the Compose Multiplatform package `com.composables:icons-lucide-cmp`. They are a
dependency, not files in this repository.

## Rules that still apply

The repository rules in `CLAUDE.md` hold here: never crash (the FFI layer returns errors, and
Kotlin code surfaces `EngineException` rather than throwing into the UI), and no Microsoft/Adobe
assets or iconography; add a row to `ATTRIBUTION.md` for every asset file.

## Status

Every engine command is reachable at every window size: see [`docs/parity.md`](docs/parity.md) for
how that is measured, what the app draws itself (zoom, dark pages, ruler, panes, ink) and the gaps
that remain. Built and tested on JVM desktop; Android builds with `scripts/build-android.sh` on a
machine with the SDK; iOS is scaffolded and has never been built.
