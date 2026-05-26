# Notes App (app:notes)

Last verified: 2026-05-25

## Purpose
Main application module that wires together ink rendering, storage, and user interaction into a single-activity note-taking experience optimized for e-ink. Includes a nav bar for moving between pages and notebooks.

## Contracts
- **Exposes**: `MainActivity` (entry point), `DrawView` (drawing surface), `ToolBar` (tool selection), `PageNavigationLogic` (pure page index/bounds/label math), `ToolSelectionLogic` (pure tool/variant state), `LassoSelectionLogic` (pure selection geometry), `Clipboard`/`InProcessClipboard`, `SelectionMenuView`, `SettingsView` (full-screen Settings overlay), `SettingsFormLogic` (pure pitch-radio rules)
- **Guarantees**: Strokes auto-save on pen-up. Strokes restore on app launch (non-blocking async load), reopening the last-active notebook+page. Backend lifecycle managed across pause/resume. Finger input rejected (stylus/eraser only). Page/notebook switching commits the current stroke (via clearAll) and refreshes the canvas to avoid e-ink ghosting. Lasso selects strokes by centroid; Cut/Copy/Delete via a floating pill + in-process clipboard; Paste is tap-to-place (arm, then tap the canvas); a closed selection can be dragged to move it in place.
- **Expects**: `core:ink` for backend and stroke types, `core:format` for persistence.

## Dependencies
- **Uses**: `core:ink` (InkBackend, BackendDetector, PageTransform, Stroke, Tool, StrokeGeometry, PressureCurve), `core:format` (NotebookRepository + NotebookMeta/PageMeta, owned exclusively by `NotebookStore`)
- **Used by**: Nothing (top-level app module)
- **Boundary**: Should not contain domain logic; delegates to core modules

## Key Decisions
- Activity (not Fragment/Compose): minimal overhead for single-screen e-ink app. Secondary screens (Settings) are full-screen overlay Views added to `android.R.id.content`, not new Activities — keeps the single `NotebookStore` (single-writer DB invariant) and avoids fragments
- DrawView owns offscreen bitmap: all strokes rendered to bitmap, blitted to screen in onDraw. The page template (B3) is drawn onto the bitmap UNDER the ink in every rebuild path (onSizeChanged/mergeLoadedStrokes/clearAll/redrawBitmap); `setTemplate(effectiveTemplate, pitchMm)` repaints on change. Template pitch uses true panel PPI (293 on the Mini, set in MainActivity by isEInk), NOT densityDpi/xdpi
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
- Page/notebook index math is pure in `PageNavigationLogic` (testable without Android); `MainActivity` drives all switching/CRUD through `NotebookStore` and never touches `NotebookRepository` directly
- `goToPage`/`goToNotebook`/`reloadCurrentPage` share one idiom: `clearAll()` → `store.switch*`/`load` → `mergeLoadedStrokes` + `fullRefresh` + refresh indicator/label

## Key Files
- `MainActivity.kt` - Lifecycle management, backend + NotebookStore wiring, nav-bar wiring (page + notebook pickers), crash handler
- `NotebookStore.kt` - Single background-thread owner of NotebookRepository; async load/save/erase/clear, notebook/page list-switch-CRUD, settings load/update + per-page setPageTemplate, drain-on-shutdown
- `DrawView.kt` - Touch handling, bitmap rendering, stroke/erase logic
- `ToolBar.kt` - UI toolbar with tool button state management (incl. Paste enable/armed state, Template action cell → per-page template picker)
- `ToolSelectionLogic.kt` - Pure tool selection state machine (pen/erase variants + Lasso)
- `LassoSelectionLogic.kt` - Pure selection geometry (ray-cast pointInPolygon, integer centroid, selectedIds, bounds, translate)
- `Clipboard.kt` - `Clipboard` interface + `InProcessClipboard` (listener-based; B1 re-backs it with `app_state.clipboard_json`)
- `SelectionMenuView.kt` - Floating action pill (PopupWindow) for a lasso selection; non-focusable so drag-to-move touches reach the canvas
- `SettingsView.kt` - Full-screen Settings overlay (B2): inflated over the editor via `addContentView` onto `android.R.id.content`, reusing MainActivity's single `NotebookStore` (a 2nd Activity would open a 2nd DB connection and break single-writer serialization). Editor template+pitch radios persist on change; Sync/AI/CalDAV URL fields commit on blur or IME Done. Reached via a "Settings" neutral button on the notebook picker; system back dismisses it (`MainActivity.onBackPressed`)
- `SettingsFormLogic.kt` - Pure pitch-radio rules (presets 5/7/10mm, visible iff template != BLANK, nearest-preset snap, index clamp)
- `TemplateGeometry.kt` - Pure template geometry (interior `lineOffsets(extent,pitch)`) + effective-config resolution (`page override ?: global default`, AC8.4)
- `res/layout/view_settings.xml` + `res/values/styles.xml` - Settings layout (header Back/title + scrolling sections) and its field styles (black-on-white, e-ink friendly)
- `PageNavigationLogic.kt` - Pure page navigation (index, prev/next bounds, "N / M" label, can-delete)
- `res/layout/toolbar.xml` - The seven tool cells: Fountain / Lasso / Erase / Paste / Clear / Refresh / Template (each whole cell is the hitbox)
- `res/layout/dialog_page_template.xml` - Per-page template picker (B4): "Use default" (snapshots current default) + Blank/Dot/Ruled/Grid + pitch sub-radio
- `res/layout/navbar.xml` - Unified top bar: notebook label / prev (◀) / "N / M" indicator / next (▶) / `<include>` of `toolbar.xml`; a 1dp divider in `activity_main.xml` separates it from the canvas. `toolbar.xml` (root id `@id/toolbar`) is included here rather than placed at the bottom.
- `res/layout/dialog_notebook_properties.xml` - Long-press → Notebook Properties (name + Created/Modified/Pages)

## Gotchas
- ViwoodsBackend cast in onResume: only safe because isEInk guards it
- Crash handler writes to /sdcard/Download/ first, falls back to app filesDir
- E-ink mode disables animations and ripple effects to prevent ghosting
