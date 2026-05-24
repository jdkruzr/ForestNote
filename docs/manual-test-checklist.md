# ForestNote v1 Manual Test Checklist

This document provides the manual testing procedure for on-device verification of ForestNote v1. These tests cannot be automated and require physical device interaction.

## AiPaper Device Tests

Test on a Viwoods AiPaper Mini e-ink tablet running the ForestNote APK.

### Drawing & Tools (AC1.x)

- [ ] **AC1.1: Stylus Input & Pressure Sensitivity** - Draw with stylus → visible pressure-sensitive strokes appear on the canvas
- [ ] **AC1.2: Pressure Curve Matching** - Light pressure → thin lines, heavy pressure → thick lines, appearance matches WiNote first-party app
- [ ] **AC1.3: Finger Touch Rejection** - Touch canvas with finger → no marks created, stylus input only
- [ ] **AC1.4: Stroke Eraser Tool** - Select stroke eraser tool, touch any part of a stroke → entire stroke is deleted
- [ ] **AC1.5: Pixel Eraser Tool** - Select pixel eraser tool, drag through middle of a stroke → stroke splits into two sub-strokes, both preserved
- [ ] **AC1.6: Pixel Eraser at Stroke End** - Use pixel eraser to erase end of a stroke → end segment is removed cleanly, no empty ghost strokes created
- [ ] **AC1.7: Hardware Eraser End** - Flip stylus to eraser end (activate TOOL_TYPE_ERASER) → eraser tool activates automatically in app
- [ ] **AC1.8: Toolbar Tool Switching** - Tap each toolbar button (Pen, Stroke Erase, Pixel Erase, Clear) → tool switches, correct icon is highlighted
- [ ] **AC1.9: Clear All with Confirmation** - Tap Clear button → confirmation dialog appears → confirm deletion → all strokes removed from page

### Storage & Persistence (AC2.x)

- [ ] **AC2.1: Auto-Save to File** - Draw several strokes, check device file system → .forestnote SQLite file exists in app data directory
- [ ] **AC2.2: Restore After Kill** - Draw strokes, kill app from recents (force stop), relaunch ForestNote → all strokes restored exactly in same positions and appearance
- [ ] **AC2.5: Cross-Device Resolution** - (If second AiPaper available) Copy .forestnote file from 1440x1920 device to different resolution device → strokes render at correct proportions on new screen

### Backend & Fast Ink (AC3.x)

- [ ] **AC3.1: Fast Ink Active** - On AiPaper, draw strokes → strokes appear on screen during pen movement (not just on pen-up), confirming fast ink mode is active
- [ ] **AC3.2: Lifecycle Pause/Resume** - Press home button to pause app, return to ForestNote from recents → fast ink still works correctly, no degradation
- [ ] **AC3.3: WiNote Context Switching** - Switch to WiNote app, draw strokes → fast ink works in WiNote. Switch back to ForestNote, draw → fast ink still works in ForestNote. Verify no poisoned/locked state between apps.

### Performance

- [ ] **Performance: Rendering Latency** - Draw strokes at normal speed → strokes appear within ~12ms of pen movement (~81Hz refresh), no multi-second redraw delays or stuttering

## Emulator / Non-E-Ink Device Tests

Test on Android emulator or standard Android device without e-ink support.

### Backend Fallback (AC3.x)

- [ ] **AC3.4: GenericBackend Rendering** - App launches without crash, draw strokes → strokes render correctly via standard Canvas (non-e-ink rendering)
- [ ] **AC3.5: Missing Viwoods API Fallback** - App detects non-AiPaper device, gracefully falls back to GenericBackend, no crash from missing Viwoods API

### Data Integrity (AC2.x)

- [ ] **AC2.4: Corrupted Database Recovery** - Delete .forestnote file from app data directory while app is not running, relaunch ForestNote → app displays empty canvas without crash, can draw and save new strokes

## Off-Main-Thread Persistence + ULID Identity (persistence-ulid)

On-device checks for the acceptance criteria that can't be unit-tested here (no
Robolectric). The storage-layer behavior is covered by `core:format` tests and the
off-thread/drain guarantees by `NotebookStore` tests; these verify the full
Activity/View/threading integration.

- [x] **AC1.2: Non-blocking cold start** - Launch the app → the canvas accepts strokes immediately, with no blocking spinner or jank while previously-saved ink loads. _(verified on-device 2026-05-23)_
- [x] **AC1.3: No ANR on large erase** - With many strokes on the page, do a large, fast pixel-erase across them → no ANR / freeze; erase completes smoothly. _(verified on-device 2026-05-23)_
- [x] **AC2.4 / AC3.2: Whole-stroke erase survives relaunch** - Draw strokes, stroke-erase some, relaunch → erased ink stays gone (does not resurrect). _(verified on-device 2026-05-23)_
- [x] **AC3.3: Pixel-erase split survives relaunch** - Pixel-erase through the middle of a stroke, relaunch → the surviving fragments are present and correct, the gap remains. _(verified on-device 2026-05-23)_
- [x] **AC3.1: Draw order survives relaunch** - Draw several overlapping strokes, relaunch → they reload in the original draw order. _(verified on-device 2026-05-23)_
- [x] **AC3.4: Clear survives relaunch** - Clear the page, relaunch → the page is empty. _(verified on-device 2026-05-23)_
- [x] **AC7.x: Gap-drawn ink preserved** - Immediately on launch (before/while older ink loads), draw new strokes → they are preserved alongside the loaded ink, ordered after it, with no duplicates. _(verified on-device 2026-05-23)_
- [x] **AC7.4 / AC8.1 / AC8.2: Failures don't crash** - If a load/save/erase fails, the canvas stays usable and the app does not crash. _(covered by automated `NotebookStoreTest` failure-injection tests; not practically reproducible on-device, so accepted as automated-only)_

## Multiple Notebooks with Multiple Pages — Phase 2: Multi-page Navigation (multi-notebook-multi-page)

On-device checks for the navigation UI that can't be unit-tested here (no Robolectric).
The page index math is covered by `PageNavigationLogic` tests and the switch/CRUD
storage behavior by `core:format` / `NotebookStore` tests; these verify the full
Activity/View integration.

- [x] **AC6.1: Prev/next navigation** - Create several pages, then use the prev/next buttons → they move between pages of the current notebook in order, and the "N / M" indicator updates to the correct position/count. _(verified on-device 2026-05-24)_
- [x] **AC6.3: Page picker** - Tap the "N / M" indicator → the picker lists the pages; tapping one switches to it and loads the right ink. "New Page" adds and opens a blank page. "Delete Current Page" is hidden when only one page exists and deletes (falling back to a surviving page) otherwise. _(verified on-device 2026-05-24)_
- [x] **AC6.4: Switch commits stroke + no ghosting** - Draw a stroke, then tap next/prev → the just-drawn stroke is kept (not lost), and the new page renders cleanly without e-ink ghosting from the previous page. _(verified on-device 2026-05-24)_
- [x] **AC5.1: Reopen on last-viewed page** - With 3 pages, navigate to page 2 and draw, then kill + relaunch the app → it reopens on page 2 showing that page's ink (not page 1). _(verified on-device 2026-05-24)_

## Multiple Notebooks with Multiple Pages — Phase 3: Notebook Picker (multi-notebook-multi-page)

On-device checks for the notebook picker (UI-only, no Robolectric). The underlying
list/switch/CRUD storage is covered by `core:format` / `NotebookStore` tests.

- [x] **AC7.1: Notebook switch** - Tap the notebook label → the picker lists notebooks; selecting one switches to it and loads its active/first page (correct ink, indicator updates, label shows the chosen notebook's name). _(verified on-device 2026-05-24)_
- [x] **AC7.2: New Notebook** - "New Notebook" → enter a name → a fresh notebook is created and opened with one blank page; the label shows the new name. _(verified on-device 2026-05-24)_
- [x] **AC7.2: Rename** - "Edit Current" → "Rename" → change the name → the label updates to the new name; reopening the picker shows the renamed entry. _(verified on-device 2026-05-24)_
- [x] **AC7.2: Delete (confirms first)** - "Edit Current" → "Delete" → confirm → the notebook and all its pages are removed, the app switches to a remaining notebook; "Delete" is unavailable when only one notebook exists (never zero notebooks). _(verified on-device 2026-05-24)_
- [x] **End-to-end: reopen last-active notebook+page** - Create 2 notebooks with distinct pages/ink, switch between them landing on a non-default notebook+page, kill + relaunch → the app reopens the last-active notebook and page with the right ink. _(verified on-device 2026-05-24)_

## Test Sign-Off

- Date tested: 2026-05-23 (persistence-ulid: all on-device ACs verified — AC1.2, AC1.3, AC2.4/AC3.2, AC3.1, AC3.3, AC3.4, AC7.x; failure paths AC7.4/AC8.1/AC8.2 covered by automated tests)
- Date tested: 2026-05-24 (multi-notebook-multi-page: all on-device ACs verified — AC6.1, AC6.3, AC6.4, AC5.1 (page nav); AC7.1, AC7.2 ×3, last-active notebook+page relaunch (notebook picker). Backend logs clean: ENoteBridge init OK, no crash log.)
- Tester name: ________________
- Device model: Viwoods AiPaper Mini
- OS version: ________________
- Notes/Issues: persistence-ulid fully signed off. multi-notebook-multi-page fully signed off — every device-testable AC passed on-device; storage/switch/CRUD substrate covered by automated `core:format`/`NotebookStore` tests; on-device backend logs showed clean init and zero crashes across the session.

All tests completed and passing: [ ] Yes [ ] No
