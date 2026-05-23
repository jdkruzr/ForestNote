# Notes App (app:notes)

Last verified: 2026-05-23

## Purpose
Main application module that wires together ink rendering, storage, and user interaction into a single-activity note-taking experience optimized for e-ink.

## Contracts
- **Exposes**: `MainActivity` (entry point), `DrawView` (drawing surface), `ToolBar` (tool selection)
- **Guarantees**: Strokes auto-save on pen-up. Strokes restore on app launch (non-blocking async load). Backend lifecycle managed across pause/resume. Finger input rejected (stylus/eraser only).
- **Expects**: `core:ink` for backend and stroke types, `core:format` for persistence.

## Dependencies
- **Uses**: `core:ink` (InkBackend, BackendDetector, PageTransform, Stroke, Tool, StrokeGeometry, PressureCurve), `core:format` (NotebookRepository, owned exclusively by `NotebookStore`)
- **Used by**: Nothing (top-level app module)
- **Boundary**: Should not contain domain logic; delegates to core modules

## Key Decisions
- Activity (not Fragment/Compose): minimal overhead for single-screen e-ink app
- DrawView owns offscreen bitmap: all strokes rendered to bitmap, blitted to screen in onDraw
- Hardware eraser (TOOL_TYPE_ERASER) always erases regardless of toolbar selection
- 900ms postDelayed invalidate after pen-up: triggers quality e-ink refresh without excessive redraws
- All persistence flows through `NotebookStore` (single background thread); DrawView/MainActivity never touch `NotebookRepository` or the DB directly. Save is fire-and-forget; erase reconcile + load run off-thread and post results back to the main thread

## Invariants
- All coordinates in DrawView: virtual for storage, screen for bitmap rendering
- WritingBufferQueue released in onPause, reacquired in onResume (shared device resource)
- clearAll() only clears in-memory state; database clearing is caller's responsibility (via `store.clear`)
- Tool selection is pure logic in ToolSelectionLogic (testable without Android)
- `DrawView.mergeStrokes` is a pure companion function: loaded ink first, then session ink, deduped by ULID — so the async load never clobbers strokes drawn during the load gap
- `MainActivity.onDestroy` calls `store.shutdown()`, which drains pending saves before closing the driver

## Key Files
- `MainActivity.kt` - Lifecycle management, backend + NotebookStore wiring, crash handler
- `NotebookStore.kt` - Single background-thread owner of NotebookRepository; async load/save/erase/clear + drain-on-shutdown
- `DrawView.kt` - Touch handling, bitmap rendering, stroke/erase logic
- `ToolBar.kt` - UI toolbar with tool button state management
- `ToolSelectionLogic.kt` - Pure tool selection state machine

## Gotchas
- ViwoodsBackend cast in onResume: only safe because isEInk guards it
- Crash handler writes to /sdcard/Download/ first, falls back to app filesDir
- E-ink mode disables animations and ripple effects to prevent ghosting
