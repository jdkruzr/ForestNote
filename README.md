# ForestNote

A stylus note-taking app for the [Viwoods AiPaper Mini](https://www.viwoods.com/) e-ink tablet,
built around the device's fast-ink rendering path for low-latency handwriting — with a fallback
that runs on generic Android devices.

> **Status:** early and actively developed. The core writing experience (low-latency ink,
> pressure-sensitive pens, durable storage, multi-notebook/multi-page) works and is validated
> on-device. Library home, settings, and sync are in progress.

## Features

- **Low-latency ink** via the reverse-engineered Viwoods WritingSurface API, with a standard
  Android canvas fallback on other devices
- **Pens** — Fountain / Fineliner / Highlighter, pressure-sensitive (the highlighter composites
  behind ink and never darkens on overlap)
- **Erasers** — stroke eraser and pixel eraser
- **Lasso selection** — select by enclosing strokes, then Cut / Copy / Delete via a floating menu,
  **drag the selection to move it**, and **tap-to-place paste** (tap once, then tap where it lands)
- **Notebooks & pages** — multiple notebooks, each with multiple pages; the active notebook + page
  are restored on launch
- **Notebook properties** — long-press a notebook for its created/modified dates, page count,
  rename, and delete

## Tech stack

- Kotlin, Android Views (no Compose), Material 3
- Min/Target SDK 30, Compile SDK 35
- [SQLDelight](https://sqldelight.github.io/sqldelight/) for storage; [Jetpack Ink](https://developer.android.com/jetpack/androidx/releases/ink) geometry
- Multi-module Gradle with convention plugins in `build-logic/`

## Modules

| Module | Responsibility |
|--------|----------------|
| `app/notes` | The app: activity, drawing surface, toolbar, persistence wiring |
| `core/ink` | Ink rendering backends, stroke model, coordinate transform, geometry |
| `core/format` | SQLite storage (notebook → page → stroke), serialization |

All strokes are stored in a resolution-independent virtual coordinate space, so notes render
identically across screen sizes and orientations.

## Building

```sh
./gradlew :app:notes:assembleDebug   # build the debug APK
./gradlew test                       # run all unit tests
```

The debug APK lands at `app/notes/build/outputs/apk/debug/notes-debug.apk`.

## License

[MIT](LICENSE) © 2026 jdkruzr
