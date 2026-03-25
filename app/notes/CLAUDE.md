# Notes App (app:notes)

Last verified: 2026-03-25

## Purpose
Main application module that wires together ink rendering, storage, and user interaction into a single-activity note-taking experience optimized for e-ink.

## Contracts
- **Exposes**: `MainActivity` (entry point), `DrawView` (drawing surface), `ToolBar` (tool selection)
- **Guarantees**: Strokes auto-save on pen-up. Strokes restore on app launch. Backend lifecycle managed across pause/resume. Finger input rejected (stylus/eraser only).
- **Expects**: `core:ink` for backend and stroke types, `core:format` for persistence.

## Dependencies
- **Uses**: `core:ink` (InkBackend, BackendDetector, PageTransform, Stroke, Tool, StrokeGeometry, PressureCurve), `core:format` (NotebookRepository)
- **Used by**: Nothing (top-level app module)
- **Boundary**: Should not contain domain logic; delegates to core modules

## Key Decisions
- Activity (not Fragment/Compose): minimal overhead for single-screen e-ink app
- DrawView owns offscreen bitmap: all strokes rendered to bitmap, blitted to screen in onDraw
- Hardware eraser (TOOL_TYPE_ERASER) always erases regardless of toolbar selection
- 900ms postDelayed invalidate after pen-up: triggers quality e-ink refresh without excessive redraws

## Invariants
- All coordinates in DrawView: virtual for storage, screen for bitmap rendering
- WritingBufferQueue released in onPause, reacquired in onResume (shared device resource)
- clearAll() only clears in-memory state; database clearing is caller's responsibility
- Tool selection is pure logic in ToolSelectionLogic (testable without Android)

## Key Files
- `MainActivity.kt` - Lifecycle management, backend/repository wiring, crash handler
- `DrawView.kt` - Touch handling, bitmap rendering, stroke/erase logic
- `ToolBar.kt` - UI toolbar with tool button state management
- `ToolSelectionLogic.kt` - Pure tool selection state machine

## Gotchas
- ViwoodsBackend cast in onResume: only safe because isEInk guards it
- Crash handler writes to /sdcard/Download/ first, falls back to app filesDir
- E-ink mode disables animations and ripple effects to prevent ghosting
