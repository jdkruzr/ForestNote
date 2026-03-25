# ForestNote v1 Design

## Summary

ForestNote v1 is a stylus-first note-taking app built for the Viwoods AiPaper e-ink tablet, with a fallback path for standard Android devices. The app presents a full-screen drawing canvas where a stylus produces pressure-sensitive ink strokes, and a minimal toolbar provides pen, erase, and clear tools. Each notebook is stored as a self-contained SQLite file (`.forestnote`) that auto-saves on every pen-up event and restores the full canvas on relaunch.

The codebase is a multi-module Kotlin Android project. A `:core:ink` module abstracts the rendering backend behind a single `InkBackend` interface, allowing the Viwoods-specific fast-ink path (which bypasses normal Android rendering to write directly to the e-ink panel at ~81Hz) to be swapped out for a standard Canvas fallback without any changes to the application layer. A `:core:format` module handles all storage concerns using SQLDelight, with strokes encoded as compact integer BLOBs and coordinates stored in a resolution-independent virtual space so that notes render correctly across different screen sizes. The `:app:notes` module wires these together into the user-facing application. All Viwoods-specific API access is done through reflection at runtime, making the app safe to run on devices where those APIs are absent.

## Definition of Done

1. **A multi-module Kotlin Android app** (`:core:ink`, `:core:format`, `:app:notes`) with a polished Material 3 e-ink UI that draws pressure-sensitive strokes with fast ink on the Viwoods AiPaper, falls back to standard Canvas on other devices, and provides stroke erase and pixel erase tools.

2. **A SQLite-per-notebook file format** (`.forestnote`) designed to support vector strokes, shapes, text boxes, rasters, links, tags, and recognized text — but v1 only persists strokes (auto-saved to a single implicit document).

3. **Proper lifecycle management** — clean WritingBufferQueue handoff between apps, no poisoned state.

## Acceptance Criteria

### forestnote-v1.AC1: Drawing & Tools
- **forestnote-v1.AC1.1 Success:** Stylus input produces visible pressure-sensitive strokes on the canvas
- **forestnote-v1.AC1.2 Success:** Stroke width varies with pressure using the logarithmic curve, matching Viwoods first-party app appearance
- **forestnote-v1.AC1.3 Success:** Finger touches are ignored on the canvas (no accidental marks)
- **forestnote-v1.AC1.4 Success:** Stroke eraser deletes an entire stroke when any part of it is touched
- **forestnote-v1.AC1.5 Success:** Pixel eraser removes only the erased region, splitting the stroke into valid sub-strokes
- **forestnote-v1.AC1.6 Edge:** Pixel eraser at the end of a stroke removes the end segment without creating empty sub-strokes
- **forestnote-v1.AC1.7 Success:** Hardware eraser end (TOOL_TYPE_ERASER) triggers the active eraser tool
- **forestnote-v1.AC1.8 Success:** Toolbar allows switching between Pen, Stroke Erase, Pixel Erase, and Clear
- **forestnote-v1.AC1.9 Success:** Clear deletes all strokes on the page after confirmation

### forestnote-v1.AC2: Storage & Persistence
- **forestnote-v1.AC2.1 Success:** Strokes auto-save to a .forestnote SQLite file on pen-up
- **forestnote-v1.AC2.2 Success:** All strokes are restored exactly when the app is killed and relaunched
- **forestnote-v1.AC2.3 Success:** StrokePoint data (x, y, pressure, timestamp) survives a serialize/deserialize round-trip without data loss
- **forestnote-v1.AC2.4 Failure:** Corrupted or missing .forestnote file results in a new empty document, not a crash
- **forestnote-v1.AC2.5 Success:** Strokes created on a 1440x1920 device render at correct proportions on a different screen resolution

### forestnote-v1.AC3: Lifecycle & Backend
- **forestnote-v1.AC3.1 Success:** BackendDetector returns ViwoodsBackend on AiPaper and GenericBackend on other devices
- **forestnote-v1.AC3.2 Success:** App releases WritingBufferQueue on pause, re-acquires on resume
- **forestnote-v1.AC3.3 Success:** Switching between ForestNote and WiNote preserves fast ink in both apps (no poisoned state)
- **forestnote-v1.AC3.4 Success:** GenericBackend renders strokes on a non-e-ink device via standard Canvas
- **forestnote-v1.AC3.5 Failure:** Backend init failure (e.g., reflection fails) falls back to GenericBackend, not crash

## Glossary

- **AiPaper**: The Viwoods AiPaper Mini, the primary target device — an Android-based e-ink tablet with a Wacom-compatible stylus and proprietary fast-ink rendering APIs.
- **BackendDetector**: Runtime component that inspects the device at startup and returns the most capable available `InkBackend` — `ViwoodsBackend` on AiPaper, `GenericBackend` everywhere else.
- **BLOB**: Binary Large Object — a raw byte sequence stored in a SQLite column. Used here to pack a stroke's list of points into a compact `IntArray`.
- **Convention plugin**: A Gradle plugin defined in `build-logic/` that centralises shared build configuration (SDK versions, compiler flags, dependencies) so individual modules do not repeat it.
- **dirty rect**: The minimal bounding rectangle of pixels that changed in a single render step. Passed to `InkBackend.renderSegment()` so only the affected screen region is refreshed, reducing flicker and latency on e-ink.
- **DisplayMode**: Enum controlling the e-ink panel refresh strategy — `FAST` for per-segment stroke rendering, `NORMAL` for moderate refresh, `FULL_REFRESH` for a clean full-panel redraw.
- **ENoteSetting / `android.os.enote`**: A hidden Android system API present on Viwoods devices that controls e-ink rendering behaviour. Accessed through Java reflection because it is not part of the public Android SDK.
- **fast ink**: A Viwoods-specific rendering path that pushes bitmap data directly to the e-ink panel's `WritingSurface`, achieving ~12 ms per segment and ~81 Hz apparent frame rate — far faster than standard Android view invalidation on e-ink.
- **GenericBackend**: The fallback `InkBackend` implementation that renders strokes using standard Android `View.invalidate()` and `Canvas`, usable on any Android device.
- **InkBackend**: Interface in `:core:ink` defining the contract for all rendering backends — `init`, `startStroke`, `renderSegment`, `endStroke`, `release`, `isAvailable`.
- **Jetpack Ink API**: An Android Jetpack library (`androidx.ink`) providing geometry primitives and algorithms for stylus input — used here specifically for stroke intersection and splitting during pixel erase.
- **logarithmic pressure curve**: The formula `ln(3p+1)/ln(4)` that maps raw stylus pressure (0–1) to stroke width, matching the visual feel of Viwoods first-party apps.
- **Material 3 (MD3)**: Google's third-generation Material Design system, used for the toolbar and theme. ForestNote uses a monochrome `lightColorScheme` with animations disabled for e-ink.
- **millipressure**: The unit used to store stylus pressure in `StrokePoint` — integer range 0–1000, avoiding floating-point values in storage.
- **NotebookRepository**: The storage facade in `:core:format` — opens, creates, and closes `.forestnote` database files and provides CRUD operations for strokes.
- **offscreen bitmap**: A `Bitmap` held in memory that accumulates all drawn strokes. On each render cycle the new segment is drawn to this bitmap; on restore, all strokes are replayed onto it before display.
- **PageTransform**: The single class responsible for converting between virtual coordinate space and screen pixels. Everything above it operates in virtual units; everything below it operates in screen pixels.
- **pixel erase**: An erase mode that removes only the region covered by the eraser path, splitting the intersected stroke into valid sub-strokes on either side of the erased gap — as opposed to stroke erase, which deletes an entire stroke at once.
- **reflection**: Java/Kotlin mechanism for accessing classes, methods, and fields by name at runtime without compile-time knowledge of their API. Used to call Viwoods hidden system APIs.
- **SQLDelight**: A Kotlin library that generates type-safe Kotlin APIs from `.sq` SQL schema files, used in `:core:format` to manage the `.forestnote` SQLite database.
- **StrokeGeometry**: Adapter class in `:core:ink` that converts between ForestNote's `Stroke`/`StrokePoint` types and the Jetpack Ink API's geometry types for erase intersection and stroke splitting.
- **StrokeSerializer**: Component in `:core:format` that encodes a `List<StrokePoint>` as a compact `IntArray` BLOB for storage, and decodes it back.
- **Strategy pattern**: Design pattern where behaviour (here, rendering) is encapsulated behind an interface (`InkBackend`) and the concrete implementation is selected at runtime.
- **SurfaceFlinger**: Android's system compositor. The Viwoods fast-ink path bypasses normal view compositing and writes closer to SurfaceFlinger directly.
- **version catalog (`libs.versions.toml`)**: A Gradle file that centralises all dependency versions for the project, referenced by all modules.
- **virtual coordinate space**: A resolution-independent coordinate system (short axis: 10,000 units) in which all strokes are stored, ensuring they render at correct proportions on any screen size.
- **ViwoodsBackend**: The `InkBackend` implementation that wraps `ENoteBridge` to use Viwoods fast ink via reflection.
- **WiNote**: A first-party note-taking app shipped on the Viwoods AiPaper. Correct `WritingBufferQueue` lifecycle management ensures fast ink works in both apps when switching.
- **WritingBufferQueue**: A system-level resource on Viwoods devices — only one app can hold it at a time. Must be released on pause and re-acquired on resume.
- **WritingSurface**: The Viwoods system surface that receives bitmap data for direct e-ink panel rendering, accessed via `ENoteSetting` through reflection.

## Architecture

### Module Structure

Multi-module Gradle project with convention plugins:

```
ForestNote/
├── build-logic/conventions/          # Shared build configuration
│   ├── android-library.gradle.kts    # Common config for library modules
│   └── android-app.gradle.kts        # Common config for app modules
├── core/
│   ├── ink/                          # :core:ink — drawing engine abstraction
│   └── format/                       # :core:format — file format & storage
└── app/
    └── notes/                        # :app:notes — the notes application
```

Dependencies flow downward: `:app:notes` → `:core:ink` + `:core:format`. Core modules do not depend on each other.

### Core Ink Module (`:core:ink`)

Device-agnostic drawing engine using the Strategy pattern.

**Components:**
- `InkBackend` — interface defining the fast rendering contract: `init`, `startStroke`, `renderSegment`, `endStroke`, `release`, `isAvailable`
- `BackendDetector` — runtime device detection, returns the first available backend
- `ViwoodsBackend` — WritingSurface implementation extracted from the working PoC (reflection-based bridge to `android.os.enote.ENoteSetting`)
- `GenericBackend` — fallback using standard Android `View.invalidate()` on dirty regions
- `StrokeModel` — `Stroke` and `StrokePoint` data classes in virtual coordinate space
- `PageTransform` — coordinate mapping between virtual page space and screen pixels
- `PressureCurve` — logarithmic pressure-to-width calculation in virtual units
- `StrokeGeometry` — bridges to Jetpack Ink API geometry module for stroke intersection and splitting (pixel erase)

**InkBackend interface contract:**

```kotlin
interface InkBackend {
    fun isAvailable(): Boolean
    fun init(context: Context)
    fun setDisplayMode(mode: DisplayMode)
    fun startStroke(bitmap: Bitmap, viewLocation: IntArray)
    fun renderSegment(dirtyRect: Rect)
    fun endStroke()
    fun release()
}

enum class DisplayMode { FAST, NORMAL, FULL_REFRESH }
```

The backend operates exclusively in screen-pixel coordinates. Virtual-to-screen translation is handled by `PageTransform` in `DrawView` before any backend call.

### Core Format Module (`:core:format`)

SQLDelight-based per-notebook file format.

**Components:**
- `notebook.sq` — SQLDelight schema defining tables for pages, strokes, and future object types (text, images, links, tags, FTS)
- `NotebookRepository` — open/create/close `.forestnote` files, CRUD operations for strokes
- `StrokeSerializer` — encode/decode `StrokePoint` lists to/from compact `IntArray` BLOBs

### App Notes Module (`:app:notes`)

The notes application.

**Components:**
- `MainActivity` — entry point, lifecycle management (pause/resume backend), toolbar
- `DrawView` — canvas view, manages offscreen bitmap, delegates rendering to `InkBackend`, handles touch input with tool-type filtering
- `ToolBar` — minimal Material 3 toolbar: Pen, Stroke Erase, Pixel Erase, Clear
- `ForestNoteTheme` — Material Design 3 monochrome theme with e-ink adaptations

### Coordinate System

All strokes are stored in a fixed virtual coordinate system to ensure resolution independence across devices.

- Short axis: 10,000 units (always)
- Long axis: scales proportionally to page aspect ratio (13,333 for 3:4 portrait)
- `StrokePoint` uses `Int` for x, y, and pressure (0–1000 millipressure). No floats in storage.
- Pen width presets are defined in virtual units (e.g., M preset: min 7, max 35)

`PageTransform` is the single point where virtual meets screen. Everything above it (storage, geometry, erase logic) works in virtual units. Everything below it (backends, dirty rects, bitmap rendering) works in screen pixels.

### Input Filtering

`DrawView` filters touch events by tool type:
- `TOOL_TYPE_STYLUS` → draw/erase based on active tool
- `TOOL_TYPE_ERASER` → erase using active eraser mode (hardware eraser end)
- `TOOL_TYPE_FINGER` → returns false, bubbles to UI (toolbar buttons)

### Data Flow

```
Touch input (screen px)
  → DrawView.screenToPage() → virtual coords
    → StrokePoint stored in memory
    → StrokeSerializer → BLOB → SQLite on ACTION_UP

Rendering (per ACTION_MOVE):
  → StrokePoint (virtual) → DrawView.pageToScreen() → screen px
    → draw line segment to offscreen Bitmap
    → InkBackend.renderSegment(dirtyRect in screen px)
      → WritingSurface → SurfaceFlinger → e-ink panel (~12ms)

App restore:
  → NotebookRepository.getStrokesForPage()
    → StrokeSerializer decodes BLOBs → StrokePoints
      → pageToScreen → draw all to Bitmap → invalidate()
```

## Existing Patterns

Investigation found the working Kotlin PoC at `~/KotlinViwoodsPort` with:
- `ENoteBridge.kt` — reflection-based access to Viwoods hidden API. This becomes the core of `ViwoodsBackend`.
- `MainActivity.kt` with `DrawView` — pressure-sensitive drawing with WritingSurface lifecycle management. This informs `DrawView` and `MainActivity` in `:app:notes`.
- Defensive crash logging to `/sdcard/Download/` — preserved for e-ink device debugging where logcat is inaccessible.
- Logarithmic pressure curve (`ln(3p+1)/ln(4)`) matching Viwoods first-party apps — preserved in `PressureCurve`.

No multi-module patterns, no abstraction interfaces, no storage layer exist yet. All abstractions in this design are new.

The working Java PoC at `~/ForestNote` (tagged `v0.1-java-poc`) contains additional reference code including the full AutoDraw binder path (not used in this design) and NativeProbe diagnostics.

## Implementation Phases

<!-- START_PHASE_1 -->
### Phase 1: Project Scaffolding

**Goal:** Multi-module Gradle project that compiles and runs an empty Activity.

**Components:**
- `build-logic/conventions/` — convention plugins for Android library and app modules
- `gradle/libs.versions.toml` — version catalog (Kotlin, Android, SQLDelight, Ink API)
- `:core:ink` module — empty, compiles
- `:core:format` module — empty, compiles
- `:app:notes` module — `MainActivity` with blank white screen, `AndroidManifest.xml`

**Dependencies:** None (first phase)

**Done when:** `./gradlew assembleDebug` succeeds, APK installs and shows a white screen on device
<!-- END_PHASE_1 -->

<!-- START_PHASE_2 -->
### Phase 2: Ink Backend Abstraction & Viwoods Implementation

**Goal:** Pluggable ink backend with working Viwoods fast ink, extracted from PoC

**Components:**
- `InkBackend` interface in `:core:ink`
- `DisplayMode` enum in `:core:ink`
- `BackendDetector` in `:core:ink`
- `ViwoodsBackend` in `:core:ink` — reflection bridge extracted from `ENoteBridge.kt`
- `GenericBackend` in `:core:ink` — `View.invalidate()` fallback
- Defensive crash logging utility in `:core:ink`

**Dependencies:** Phase 1

**Done when:** `BackendDetector` returns `ViwoodsBackend` on AiPaper and `GenericBackend` on other devices. `ViwoodsBackend.init()` + `release()` succeeds without crashing. Unit tests verify `BackendDetector` logic and `GenericBackend` behavior. Tests cover `forestnote-v1.AC3.1`, `forestnote-v1.AC3.2`.
<!-- END_PHASE_2 -->

<!-- START_PHASE_3 -->
### Phase 3: Stroke Model & Coordinate System

**Goal:** Resolution-independent stroke data model with coordinate transforms

**Components:**
- `StrokePoint` data class in `:core:ink` — Int-based virtual coordinates
- `Stroke` data class in `:core:ink` — list of points with pen width params and color
- `StrokeData` (mutable, in-progress) vs `Stroke` (immutable, completed) distinction
- `PageTransform` in `:core:ink` — virtual ↔ screen mapping based on view dimensions
- `PressureCurve` in `:core:ink` — logarithmic pressure-to-width in virtual units
- `Tool` sealed class in `:core:ink` — Pen, StrokeEraser, PixelEraser

**Dependencies:** Phase 1

**Done when:** `PageTransform` correctly maps between virtual (10,000-unit) space and arbitrary screen dimensions. `PressureCurve` produces expected widths. Unit tests verify coordinate round-tripping and pressure calculations. Tests cover `forestnote-v1.AC1.2`.
<!-- END_PHASE_3 -->

<!-- START_PHASE_4 -->
### Phase 4: SQLDelight Storage

**Goal:** Per-notebook `.forestnote` files with stroke persistence

**Components:**
- `notebook.sq` in `:core:format` — full schema (pages, strokes, future tables as empty definitions)
- `StrokeSerializer` in `:core:format` — `List<StrokePoint>` ↔ `IntArray` BLOB encoding
- `NotebookRepository` in `:core:format` — open/create/close database files, stroke CRUD, auto-save

**Dependencies:** Phase 3 (stroke model)

**Done when:** Can create a `.forestnote` file, insert strokes, read them back with identical data. `StrokeSerializer` round-trips without data loss. Unit tests verify serialization, CRUD operations, and schema creation. Tests cover `forestnote-v1.AC2.1`, `forestnote-v1.AC2.2`, `forestnote-v1.AC2.3`.
<!-- END_PHASE_4 -->

<!-- START_PHASE_5 -->
### Phase 5: Drawing Canvas with Fast Ink

**Goal:** Full-screen drawing with pressure-sensitive strokes and fast ink rendering

**Components:**
- `DrawView` in `:app:notes` — offscreen bitmap, touch handling with tool-type filtering, `PageTransform` integration, delegates to `InkBackend`
- `MainActivity` updated — integrates `BackendDetector`, holds backend reference, manages lifecycle (pause/resume)
- Auto-save on pen-up via `NotebookRepository`
- Stroke restoration on app launch

**Dependencies:** Phase 2 (backend), Phase 3 (stroke model), Phase 4 (storage)

**Done when:** Drawing with stylus produces pressure-sensitive strokes on AiPaper with fast ink (~81Hz render rate). Strokes persist across app kill/relaunch. Finger touches are ignored on the canvas. Backend releases on pause, re-acquires on resume. Tests cover `forestnote-v1.AC1.1`, `forestnote-v1.AC1.2`, `forestnote-v1.AC1.3`, `forestnote-v1.AC3.1`, `forestnote-v1.AC3.2`, `forestnote-v1.AC3.3`.
<!-- END_PHASE_5 -->

<!-- START_PHASE_6 -->
### Phase 6: Erase Tools

**Goal:** Stroke erase and pixel erase with Jetpack Ink geometry

**Components:**
- Stroke eraser logic in `DrawView` — hit-test strokes by distance to touch point, delete on hit
- Pixel eraser logic in `DrawView` — Jetpack Ink geometry intersection test, stroke splitting at erase boundaries
- `StrokeGeometry` in `:core:ink` — bridge to Ink API geometry, converts between `Stroke`/`StrokePoint` and Ink types
- Bitmap full-redraw after erase operations
- SQLite updates on erase (delete original, insert sub-strokes for pixel erase)
- Hardware eraser support (`TOOL_TYPE_ERASER` maps to active eraser tool)

**Dependencies:** Phase 5 (drawing canvas)

**Done when:** Stroke eraser deletes entire strokes on touch. Pixel eraser splits strokes at erased regions, creating valid sub-strokes. Erased state persists to SQLite. Hardware eraser end triggers erase. Tests cover `forestnote-v1.AC1.4`, `forestnote-v1.AC1.5`, `forestnote-v1.AC1.6`, `forestnote-v1.AC1.7`.
<!-- END_PHASE_6 -->

<!-- START_PHASE_7 -->
### Phase 7: Toolbar & Theme

**Goal:** Polished Material 3 UI with tool selection and e-ink optimizations

**Components:**
- `ForestNoteTheme` in `:app:notes` — MD3 monochrome `lightColorScheme`, zero animations on e-ink, high-contrast borders
- `ToolBar` in `:app:notes` — horizontal bar with 4 tool icons (Pen, Stroke Erase, Pixel Erase, Clear), active tool highlighted
- Clear with confirmation dialog
- E-ink detection flag in `BackendDetector` — disables animations, ripple effects when on e-ink device
- Vector drawable icons for tools (crisp on e-ink)

**Dependencies:** Phase 6 (erase tools)

**Done when:** Toolbar renders correctly on both e-ink and standard displays. Tool selection switches active tool. Clear erases all strokes with confirmation. No animations on e-ink. Tests cover `forestnote-v1.AC1.8`, `forestnote-v1.AC1.9`.
<!-- END_PHASE_7 -->

<!-- START_PHASE_8 -->
### Phase 8: Integration Testing & Polish

**Goal:** End-to-end verified, polished, ship-ready build

**Components:**
- Integration tests: full draw → save → kill → restore cycle
- Integration tests: draw → erase → save → restore
- Integration tests: app switch to WiNote and back (WritingBufferQueue handoff)
- Generic backend verification on non-e-ink device/emulator
- Edge cases: rapid pen-up/pen-down, very long strokes, empty page save
- Performance: confirm ~81Hz render rate on AiPaper, no multi-second redraw delays

**Dependencies:** Phase 7 (all features complete)

**Done when:** All acceptance criteria pass. App runs without crashes through full usage scenarios on both AiPaper and a standard Android device/emulator. Tests cover `forestnote-v1.AC3.3` and any remaining ACs.
<!-- END_PHASE_8 -->

## Additional Considerations

**No undo/redo in v1.** The auto-save-on-pen-up model means erased strokes are immediately deleted from SQLite. Adding undo later will require either a command history table or soft-delete with garbage collection. This is a deliberate v1 simplification.

**Bitmap redraw on erase is O(n) in stroke count.** Acceptable for v1 where a single page won't have thousands of strokes. If performance becomes an issue, a spatial index (R-tree or grid) can limit redraw to affected regions.

**Future multi-page support.** The schema includes a `page` table with `sort_order`. Adding page navigation is additive — no schema migration needed, just UI work in a future version.

**WritingSurface bitmap offset.** The bitmap provided to `setWritingJavaBitmap` must be offset by the view's screen position (`getLocationOnScreen`). This offset changes if the toolbar height changes or the system bar state changes. `DrawView` recalculates this on each `startStroke` call.
