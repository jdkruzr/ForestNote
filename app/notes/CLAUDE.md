# Notes App (app:notes)

Last verified: 2026-05-26

## Purpose
Main application module that wires together ink rendering, storage, and user interaction into a single-activity note-taking experience optimized for e-ink. Includes a nav bar for moving between pages, plus a full-screen Library overlay for browsing folders and notebooks as a card grid.

## Contracts
- **Exposes**: `MainActivity` (entry point), `DrawView` (drawing surface), `ToolBar` (tool selection), `PageNavigationLogic` (pure page index/bounds/label math), `ToolSelectionLogic` (pure tool/variant state), `LassoSelectionLogic` (pure selection geometry), `Clipboard`/`InProcessClipboard`, `SelectionMenuView`, `SettingsView` (full-screen Settings overlay), `SettingsFormLogic` (pure pitch-radio rules), `LibraryView` (full-screen Library overlay), `RecycleBinView` (full-screen Recycle Bin overlay), `LibraryAdapter`/`LibraryItem` (sealed `Folder`/`Notebook`), `BreadcrumbView`, `LaunchLogic`/`BreadcrumbLogic`/`NotebookNameParser`/`RelativeTime`/`ThumbnailCacheLogic` (pure helpers), `ThumbnailRenderer`/`ThumbnailCache`/`ThumbnailLoader`
- **Guarantees**: Strokes auto-save on pen-up. Strokes restore on app launch (non-blocking async load), reopening the last-active notebook+page; if there is no resumable notebook (blank/empty active id or empty library, per `LaunchLogic`) the app opens into the Library instead. Backend lifecycle managed across pause/resume. Finger input rejected (stylus/eraser only). Page/notebook switching commits the current stroke (via clearAll) and refreshes the canvas to avoid e-ink ghosting. Lasso selects strokes by centroid; Cut/Copy/Delete via a floating pill + in-process clipboard; Paste is tap-to-place (arm, then tap the canvas); a closed selection can be dragged to move it in place. The Library lists folders-then-notebooks inside the current folder (null = root) as a 4-column card grid; tap a notebook to open it, tap a folder to descend, breadcrumb/back to walk up.
- **Expects**: `core:ink` for backend and stroke types, `core:format` for persistence.

## Dependencies
- **Uses**: `core:ink` (InkBackend, BackendDetector, PageTransform, Stroke, Tool, StrokeGeometry, PressureCurve), `core:format` (NotebookRepository + NotebookMeta/PageMeta/FolderMeta/NotebookCard/FolderCard/FolderPathLogic, owned exclusively by `NotebookStore`), `androidx.recyclerview` (Library card grid only)
- **Used by**: Nothing (top-level app module)
- **Boundary**: Should not contain domain logic; delegates to core modules

## Key Decisions
- Activity (not Fragment/Compose): minimal overhead for single-screen e-ink app. Secondary screens (Settings, Library) are full-screen overlay Views added to `android.R.id.content`, not new Activities — keeps the single `NotebookStore` (single-writer DB invariant) and avoids fragments. The Library is reached by tapping the fixed-width Library grid icon in the nav bar (a fixed icon, NOT the variable-width notebook name, so different name lengths never reflow the bar; the old `showNotebookPicker` dialog is REMOVED); its own folder/notebook/create dialogs are driven by `MainActivity` via `LibraryView.Callbacks`
- **Toolbar caption convention (both the editor toolbar and the Library header):** every action is a vertical icon-over-caption cell where the cell is the clickable hitbox and the inner `ImageButton` is non-clickable (taps fall through). A trailing **`▾`** on the caption means "opens a chooser/dialog" (editor: `Fountain ▾`/`Stroke ▾`/`Template ▾`; Library: `Folder ▾`/`Notebook ▾`); a plain caption is a direct action (`Lasso`/`Clear`/`Refresh`/`Settings`/`Up`). Captions are static strings (fixed widths → no reflow). The editor toolbar is 30dp; the Library header is 44dp (icon+caption + breadcrumb needs the extra room)
- RecyclerView is used only by the Library card grid (`androidx.recyclerview` — the app's first and only RecyclerView). Thumbnails render off-thread (`ThumbnailLoader`) into an LRU `ThumbnailCache`; eviction/sizing math is pure in `ThumbnailCacheLogic`
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
- `MainActivity.kt` - Lifecycle management, backend + NotebookStore wiring, nav-bar wiring (page picker; notebook label opens the Library), Library callback wiring + folder/notebook dialogs, launch-into-Library decision (`LaunchLogic`), crash handler
- `NotebookStore.kt` - Single background-thread owner of NotebookRepository; async load/save/erase/clear, notebook/page list-switch-CRUD, folder CRUD + Library card reads (`listNotebookCardsInFolder`/`listFolderCardsForParent`/`folderPath`) + thumbnail source wrappers, settings load/update + per-page setPageTemplate, drain-on-shutdown
- `DrawView.kt` - Touch handling, bitmap rendering, stroke/erase logic
- `ToolBar.kt` - UI toolbar with tool button state management (incl. Paste enable/armed state, Template action cell → per-page template picker). The Fountain cell opens a **pen-settings popup** (A10): variant rows + a 5-chip width strip (chips drawn as thickness samples); tapping a variant/width updates the popup in place (no dismiss), tap-outside dismisses. Exposes `setOnPenWidthSelected`/`loadPenWidths`/`activePenWidthLevel`/`currentPenWidthLevels`
- `ToolSelectionLogic.kt` - Pure tool selection state machine (pen/erase variants + Lasso + **per-variant pen width level**, default M: `selectPenWidth`/`activePenWidth`/`penWidthFor`/`setPenWidthForVariant`/`allPenWidthLevels`)
- `PenWidthSettings.kt` - Pure bridge: `Settings.penWidthLevels` (`Map<String,String>`) ↔ `Map<PenVariant,PenWidthLevel>` (decode drops unknown names; consumer defaults to M)
- `LassoSelectionLogic.kt` - Pure selection geometry (ray-cast pointInPolygon, integer centroid, selectedIds, bounds, translate)
- `Clipboard.kt` - `Clipboard` interface + `InProcessClipboard` (listener-based; B1 re-backs it with `app_state.clipboard_json`)
- `SelectionMenuView.kt` - Floating action pill (PopupWindow) for a lasso selection; non-focusable so drag-to-move touches reach the canvas
- `SettingsView.kt` - Full-screen Settings overlay (B2): inflated over the editor via `addContentView` onto `android.R.id.content`, reusing MainActivity's single `NotebookStore` (a 2nd Activity would open a 2nd DB connection and break single-writer serialization). Editor template+pitch radios persist on change; Sync/AI/CalDAV URL fields commit on blur or IME Done. Reached via a Settings action in the Library; system back dismisses it (`MainActivity.onBackPressed`)
- `SettingsFormLogic.kt` - Pure pitch-radio rules (presets 5/7/10mm, visible iff template != BLANK, nearest-preset snap, index clamp)
- `TemplateGeometry.kt` - Pure template geometry (interior `lineOffsets(extent,pitch)`) + effective-config resolution (`page override ?: global default`, AC8.4)
- `res/layout/view_settings.xml` + `res/values/styles.xml` - Settings layout (header Back/title + scrolling sections) and its field styles (black-on-white, e-ink friendly)
- `PageNavigationLogic.kt` - Pure page navigation (index, prev/next bounds, "N / M" label, can-delete)
- `res/layout/toolbar.xml` - The seven tool cells: Fountain / Lasso / Erase / Paste / Clear / Refresh / Template (each whole cell is the hitbox)
- `res/layout/dialog_page_template.xml` - Per-page template picker (B4): "Use default" (snapshots current default) + Blank/Dot/Ruled/Grid + pitch sub-radio
- `res/layout/navbar.xml` - Unified top bar: notebook label / prev (◀) / "N / M" indicator / next (▶) / `<include>` of `toolbar.xml`; a 1dp divider in `activity_main.xml` separates it from the canvas. `toolbar.xml` (root id `@id/toolbar`) is included here rather than placed at the bottom.
- `res/layout/dialog_notebook_properties.xml` - Long-press → Notebook Properties (name + Created/Modified/Pages)
- `RecycleBinView.kt` + `res/layout/view_recycle_bin.xml` - Full-screen Recycle Bin overlay (E3): mirrors `SettingsView` (`show/hide/isShowing`, reuses the single `NotebookStore`), opened from the Library header's now-enabled Recycle cell (which shows a "Recycle (N)" count badge), system-back closes it (`MainActivity.onBackPressed`, before the Settings/Library checks). Rows are built programmatically into a ScrollView (not a RecyclerView) — each bin top (standalone notebook or folder batch) with Restore / Delete-forever; header has Empty Bin. Folder soft-delete is reachable via the Folder Properties **Delete** button (`MainActivity.confirmDeleteFolder` → `store.deleteFolder`). E4 retention: a "Auto-empty after N days" field in Settings (`Settings.recycleBinRetentionDays`), purged at launch in the repo's `bootstrap()`
- `LibraryView.kt` - Full-screen Library overlay (C3a/C4/C5): a 4-column RecyclerView grid of folders-then-notebooks in the current folder (`currentFolderId`, null = root). Like `SettingsView`, an overlay View (not an Activity) reusing the single `NotebookStore`. Tap a notebook → open; tap a folder → descend; breadcrumb/back → walk up. Data via `NotebookStore`; folder/notebook dialogs + Settings via `LibraryView.Callbacks` driven by `MainActivity`
- `LibraryAdapter.kt` + `LibraryItem.kt` - RecyclerView adapter over `sealed LibraryItem (Folder(FolderCard) | Notebook(NotebookCard))`; binds thumbnails via `ThumbnailLoader`/`ThumbnailCache`
- `LaunchLogic.kt` - Pure: `shouldOpenLibraryOnLaunch(activeNotebookId?, notebookCount)` — open the Library at launch only when there is no notebook to resume into (C6)
- `BreadcrumbLogic.kt` + `BreadcrumbView.kt` - Pure breadcrumb trail math + its top-bar View for the current folder path (C5)
- `NotebookNameParser.kt` - Pure parse of a notebook display name
- `RelativeTime.kt` - Pure "modified N ago" formatting for cards
- `ThumbnailRenderer.kt` / `ThumbnailCache.kt` / `ThumbnailCacheLogic.kt` / `ThumbnailLoader.kt` - Off-thread notebook-thumbnail render (first page's strokes) + LRU cache; `ThumbnailCacheLogic` holds the pure sizing/eviction math
- `res/layout/view_library.xml` + `res/layout/item_folder_card.xml` + `res/layout/item_notebook_card.xml` - Library grid + its folder/notebook card cells

## Gotchas
- ViwoodsBackend cast in onResume: only safe because isEInk guards it
- Crash handler writes to /sdcard/Download/ first, falls back to app filesDir
- E-ink mode disables animations and ripple effects to prevent ghosting
