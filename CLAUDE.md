# ForestNote

Last verified: 2026-03-25

E-ink note-taking app for the Viwoods AiPaper Mini tablet. Uses reverse-engineered fast ink APIs for low-latency stylus rendering with a fallback path for generic Android devices.

## Tech Stack
- Language: Kotlin (Android)
- Min SDK: 30, Target SDK: 30, Compile SDK: 35
- Build: Gradle with convention plugins in `build-logic/`
- Storage: SQLDelight 2.0.2 (SQLite)
- Geometry: Jetpack Ink API 1.0.0 (brush/geometry/strokes)
- UI: Android Views (no Compose), Material 3

## Commands
- `./gradlew :app:notes:assembleDebug` - Build debug APK
- `./gradlew :core:ink:test` - Run ink module unit tests
- `./gradlew :core:format:test` - Run format module unit tests
- `./gradlew :app:notes:test` - Run app module unit tests
- `./gradlew test` - Run all unit tests

## Project Structure
- `app/notes/` - Main application module (Activity, DrawView, ToolBar)
- `core/ink/` - Ink rendering domain (backends, stroke model, coordinate transform)
- `core/format/` - Storage domain (SQLDelight database, serialization)
- `build-logic/` - Gradle convention plugins (shared Android config)
- `docs/` - Design plans and implementation phases

## Conventions
- Virtual coordinate space everywhere above PageTransform; screen pixels below it
- Defensive coding: catch-and-log, never crash on I/O or reflection failures
- All strokes stored in virtual units (short axis = 10,000)
- Pressure stored as millipressure (0-1000 integer)
- V1 scope: single notebook, single page

## Boundaries
- Safe to edit: `app/`, `core/`
- Never touch: `build-logic/` conventions without updating all modules
- `AIPAPER_INK_API_DOC.md`, `VIWOODS_APP_DEV.md` - Reference docs, do not modify
