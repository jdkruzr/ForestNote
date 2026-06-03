# ForestNote

Last verified: 2026-06-02 (phase8-rhizome-cutover)

E-ink note-taking app for low-latency stylus handwriting. Two firmware-latency ink paths behind one `InkBackend` seam: the **Viwoods AiPaper Mini** via reverse-engineered fast-ink APIs (display accelerator — MotionEvents → fast overlay), and **Boox/Onyx** devices via the published Onyx Pen SDK (`TouchHelper`/`RawInputCallback` — the backend OWNS input, firmware renders live ink). A generic `View.invalidate()` fallback covers any other Android device. Backend is auto-detected at launch; stored stroke DATA is identical across platforms (only the on-panel raster differs).

## Tech Stack
- Language: Kotlin (Android)
- Min SDK: 30, Target SDK: 30, Compile SDK: 35
- Build: Gradle with convention plugins in `build-logic/`
- Storage: SQLDelight 2.0.2 (SQLite)
- Device↔server sync: the **RhizomeSync library** (`io.rhizome:rhizome-core/-sqlite/-http:0.8.0`, published to mavenLocal from `~/rhizome`) — the schema-driven, row-level-LWW, HLC-stamped sync core extracted from FN+UB so the correctness-critical algorithm lives in one place. `core:format` binds it via `SqliteStorageAdapter` (capture/apply/outbox in `rhizome_*` tables) + `ForestNoteRegistry` (the wire schema, hash v3 `724411eb…`); `app:notes` `SyncController` drives `io.rhizome.core.SyncEngine`. `core:sync` is now just the FN-specific `SyncBackoff`/`SyncJoinPlan` policy. (Phase 8 cutover; see `core/sync/CLAUDE.md` + memory `project_rhizome`.)
- Geometry: Jetpack Ink API 1.0.0 (brush/geometry/strokes)
- Boox/Onyx ink: Onyx Pen SDK (`onyxsdk-pen:1.5.4` + `-device:1.3.5` + `-base:1.8.5`) + `hiddenapibypass:6.1`, used only by `BooxInkBackend` in `core:ink`, runtime-gated to Onyx devices (inert elsewhere); cleartext-HTTP Maven repo. See `core/ink/CLAUDE.md` for the firmware raw-drawing model (two independent firmware switches, canvas-only surface, freeze-toggle reconcile)
- UI: Android Views (no Compose), Material 3
- Handwriting recognition: Google ML Kit Digital Ink 18.1.0 (stroke-native; bundled-only artifact requires GMS + one-time per-language ~20 MB model download via `RemoteModelManager` — host tablet has GMS + Google account)
- CalDAV transport: OkHttp 4.12.0 (scoped to `app/notes/caldav/` only — the device-sync engine rides `HttpURLConnection` via RhizomeSync's `io.rhizome.http.HttpUrlTransport`, dependency-light per the original `core:sync` rationale, now upstreamed into the library). UI never PUTs synchronously: the lasso → task path enqueues into a local SQLite outbox (`caldav_outbox`, LOCAL-ONLY) and a `CalDavOutboxDrainer` (lifecycle peer of `SyncController`) does the PUTs, with a `NetworkAvailabilityMonitor` kicking `drainNow()` when WiFi comes back. No WorkManager — the AiPaper is foreground-driven, and SQLite + lifecycle hooks + `NetworkCallback` cover the actual failure mode
- Secret storage: `androidx.security:security-crypto` 1.1.0-alpha06 (EncryptedSharedPreferences, AES-256 key in Android Keystore) — owns sync + CalDAV credentials; `Settings` JSON blob is non-secrets only

## Commands
- `./gradlew :app:notes:assembleDebug` - Build debug APK
- `./gradlew :core:ink:test` - Run ink module unit tests
- `./gradlew :core:format:test` - Run format module unit tests
- `./gradlew :app:notes:test` - Run app module unit tests
- `./gradlew test` - Run all unit tests

## Project Structure
- `app/notes/` - Main application module (Activity, DrawView, ToolBar, NotebookStore persistence owner)
- `core/ink/` - Ink rendering domain (backends incl. Viwoods/Boox/Generic, the `StrokeSink` ingest seam, stroke model, coordinate transform)
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
