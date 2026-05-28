# ForestNote

Last verified: 2026-05-28

E-ink note-taking app for the Viwoods AiPaper Mini tablet. Uses reverse-engineered fast ink APIs for low-latency stylus rendering with a fallback path for generic Android devices.

## Tech Stack
- Language: Kotlin (Android)
- Min SDK: 30, Target SDK: 30, Compile SDK: 35
- Build: Gradle with convention plugins in `build-logic/`
- Storage: SQLDelight 2.0.2 (SQLite)
- Geometry: Jetpack Ink API 1.0.0 (brush/geometry/strokes)
- UI: Android Views (no Compose), Material 3
- Handwriting recognition: Google ML Kit Digital Ink 18.1.0 (stroke-native; bundled-only artifact requires GMS + one-time per-language ~20 MB model download via `RemoteModelManager` — host tablet has GMS + Google account)

## Commands
- `./gradlew :app:notes:assembleDebug` - Build debug APK
- `./gradlew :core:ink:test` - Run ink module unit tests
- `./gradlew :core:format:test` - Run format module unit tests
- `./gradlew :app:notes:test` - Run app module unit tests
- `./gradlew test` - Run all unit tests

## Project Structure
- `app/notes/` - Main application module (Activity, DrawView, ToolBar, NotebookStore persistence owner)
- `core/ink/` - Ink rendering domain (backends, stroke model, coordinate transform)
- `core/format/` - Storage domain (SQLDelight database, serialization)
- `build-logic/` - Gradle convention plugins (shared Android config)
- `docs/` - Design plans and implementation phases
- `android-icon/` - Tracked archive of launcher icon sources (`*.svg`) and generated raster output (`res/`, `res-inverted/`, `play-store-512*.png`). The live app icon at `app/notes/src/main/res/` is the adaptive XML icon + vector drawables only; no raster `mipmap-*dpi/` directories are checked in (dead at minSdk 30). When regenerating icons, write into `android-icon/` and copy only what the adaptive icon references into the live `res/`.

## Conventions
- Virtual coordinate space everywhere above PageTransform; screen pixels below it
- Defensive coding: catch-and-log, never crash on I/O or reflection failures
- All strokes stored in virtual units (short axis = 10,000)
- Pressure stored as millipressure (0-1000 integer)
- Notebook/page/stroke identity is a client-minted ULID (String), assigned at construction — no "unsaved" id state
- All DB access is off the main thread, serialized through `NotebookStore` (single background thread); UI never touches `NotebookRepository` directly
- Scope: multiple notebooks (optionally nested in folders), each with multiple pages. One SQLite library file holds `folder → notebook → page → stroke`; the active notebook+page are restored on launch from an `app_state` row (when there is none to resume, the app opens into the Library)
- Test mocking: `:core:ink` pins Mockito to the **subclass** mock maker via `core/ink/src/test/resources/mockito-extensions/org.mockito.plugins.MockMaker` (the default inline maker breaks on JDK 25-ea against `android.jar` stubs). Consequence: tests in this module **cannot mock final classes, private methods, or static methods** — mark target Kotlin classes `open`, or refactor to inject an interface instead of reaching for PowerMock-style escapes.

## Boundaries
- Safe to edit: `app/`, `core/`
- Never touch: `build-logic/` conventions without updating all modules
- `AIPAPER_INK_API_DOC.md`, `VIWOODS_APP_DEV.md` - Reference docs, do not modify
