# Human Test Plan — Multiple Notebooks with Multiple Pages

Generated from test-analyst coverage validation (PASS) against
`docs/implementation-plans/2026-05-23-multi-notebook-multi-page/test-requirements.md`.

**Automated coverage:** 20/20 automated-coverage ACs covered (0 missing). Suites green:
`./gradlew :core:format:test :app:notes:test`. The criteria below are the ones that
require on-device verification (no Robolectric in `app:notes`); their pure/storage
substrate is already unit-tested.

## Prerequisites
- Build and deploy the debug APK to the Viwoods AiPaper Mini: `./gradlew :app:notes:assembleDebug`, then push/install via the Termux/SSH loop (device `192.168.8.78:8022`; adb is broken on this device).
- Confirm automated suites are green first: `./gradlew :core:format:test :app:notes:test`.
- Start from a clean install (or a known notebook) so page/notebook counts are predictable.
- Have the stylus and the hardware eraser ready (finger input is rejected by design).

## Phase 2: Multi-page Navigation

| Step | Action | Expected |
|------|--------|----------|
| 2.1 | Open the app on a single-page notebook. Tap the "N / M" indicator, choose "New Page" twice (now 3 pages). | Each "New Page" opens a blank page; indicator reads "3 / 3" after the second. |
| 2.2 | Draw a distinct mark on page 3 (e.g. a large "3"). Tap Prev. | Moves to page 2; page 3's "3" is not visible; no ghost of it remains on the e-ink panel. |
| 2.3 | Draw "2" on page 2, tap Prev again to page 1, draw "1". Tap Next, Next. | Indicator walks 1/3 → 2/3 → 3/3; each page shows only its own mark, cleanly rendered (AC6.1). |
| 2.4 | On page 1, confirm the Prev control is disabled/inert; on page 3 confirm Next is disabled/inert. | Bounds correct: no wrap-around past first/last (AC6.1 bounds). |
| 2.5 | Begin a stroke and, mid-stroke pen still down then lift, immediately tap Next. | The just-drawn stroke is committed and survives on its page; the new page renders without ghosting from the previous page (AC6.4). |
| 2.6 | Tap the "N / M" indicator to open the page picker. | Picker lists all pages; tapping a row switches to it and loads that page's ink (AC6.3). |
| 2.7 | In the picker, use "Delete Current Page" on a page with strokes (with ≥2 pages present). | Page is removed; app falls back to a surviving page; strokes of deleted page gone. |
| 2.8 | Reduce to a single page, reopen the picker. | "Delete Current Page" is hidden/unavailable at one page (AC6.3 / AC6.4 guard). |

### Phase 2: Relaunch restore

| Step | Action | Expected |
|------|--------|----------|
| 2.9 | With a 3-page notebook, navigate to page 2 and draw on it. Force-kill the app, relaunch. | App reopens on page 2 (not page 1) with its ink intact; indicator shows "2 / 3" (AC5.1 UI relaunch). |

## Phase 3: Notebook Picker

| Step | Action | Expected |
|------|--------|----------|
| 3.1 | Tap the notebook label to open the notebook picker. | Picker lists notebooks; the current one is identifiable. |
| 3.2 | Choose "New Notebook", enter a name (e.g. "Field Notes"), confirm. | A fresh notebook with exactly one blank page is created and opened; the label shows "Field Notes" (AC7.2). |
| 3.3 | Draw a mark in "Field Notes". Open the picker, select the original notebook. | Switches to the original notebook, loads its active/first page and ink; label and "N / M" indicator update accordingly (AC7.1 / AC4.2 UI). |
| 3.4 | Open the picker, "Edit Current" → "Rename", change the name, confirm. | Label updates immediately; reopening the picker shows the renamed entry (AC7.2). |
| 3.5 | Open the picker, "Edit Current" → "Delete" on a notebook (with ≥2 notebooks present). | A confirmation dialog appears first; on confirm, the notebook and all its pages are removed and the app switches to a remaining notebook (AC7.2). |
| 3.6 | Reduce to a single notebook, open "Edit Current". | "Delete" is unavailable at one notebook (never zero notebooks) (AC7.2 / AC2.4 guard). |

## End-to-End: Cross-context isolation and persistence

Purpose: validate that the automated per-page/per-notebook scoping (AC3.4/AC4.1/AC4.2) and app_state restore (AC4.3/AC5.1) hold together through the real UI and a relaunch.

1. Create Notebook A with pages A1, A2; draw "A1" and "A2" respectively.
2. Create Notebook B with pages B1, B2; draw "B1" and "B2".
3. Switch A → B → A via the picker, verifying each page shows only its own mark and never B's ink (and vice versa).
4. Leave the app sitting on Notebook B, page B2. Force-kill and relaunch.
5. Expect: reopens on Notebook B / page B2 with "B2" ink; switching to A still shows A1/A2 correctly. No ghosting on any switch.

## End-to-End: Delete-the-active-context recovery

Purpose: validate AC2.4 (delete active notebook → switch; delete last → never zero) end-to-end with confirmation UI.

1. With two notebooks, make Notebook A active, then delete A from the picker (confirm).
2. Expect: app switches to Notebook B, loading B's active page and ink.
3. Delete B as well. Expect: deletion of the last notebook is prevented (Delete unavailable at one notebook), so the app always retains ≥1 notebook with ≥1 page.

## Human Verification Required

| Criterion | Why Manual | Steps |
|-----------|------------|-------|
| AC6.4 | E-ink ghosting + in-progress-stroke commit on switch need a real DrawView/Activity and e-ink panel | Step 2.5 |
| AC7.1 | Notebook picker AlertDialog switch+load flow has no JVM-testable surface (no Robolectric); store half automated under AC4.2 | Steps 3.1, 3.3 |
| AC7.2 | EditText name prompts, nested Edit dialog, and delete-confirmation dialog are UI flows; repo ops automated under AC2.1–AC2.4 | Steps 3.2, 3.4, 3.5, 3.6 |
| AC4.1 (UI) | Real page switch rendering the correct page's ink | Steps 2.2, 2.3 |
| AC4.2 (UI) | Notebook switch loading active/first page in the live app | Step 3.3 |
| AC5.1 (UI) | A real Activity cold-start/relaunch is not unit-testable in app:notes | Step 2.9 |
| AC6.1 (UI) | Inflated nav-bar XML + on-screen indicator are JVM-untestable without Robolectric | Steps 2.3, 2.4 |
| AC6.3 (UI) | AlertDialog page picker list/tap/New/Delete flow | Steps 2.6, 2.7, 2.8 |

## Traceability

| Acceptance Criterion | Automated Test | Manual Step |
|----------------------|----------------|-------------|
| AC1.1 | `MigrationTest.v2ToV3...`, `fullMigrateV1ToV3...` | — |
| AC1.2 | `MigrationTest.v2ToV3AddsNotebookAppStateAndPageNotebookIdAndUsable` | — |
| AC1.3 | `NotebookCrudTest.freshRepoBootstrapsOneNotebookOnePage` | — |
| AC2.1 | `NotebookCrudTest.createNotebookAppendsInOrderEachWithAPage` | (3.2 incidentally) |
| AC2.2 | `NotebookCrudTest.renameNotebookReflectedInList` | 3.4 |
| AC2.3 | `NotebookCrudTest.deleteNotebookCascadesPagesAndStrokes` | 3.5 |
| AC2.4 | `NotebookCrudTest.deleteActiveSwitchesAndDeleteLastBootstraps` | 3.5, 3.6, E2E delete-recovery |
| AC3.1 | `NotebookCrudTest.createPageAppendsInOrder` | 2.1 |
| AC3.2 | `NotebookCrudTest.deletePageRemovesPageAndStrokes` | 2.7 |
| AC3.3 | `NotebookCrudTest.deleteOnlyPageRefused`, `deletePageFromOtherNotebookRefused` | 2.8 |
| AC3.4 | `NotebookCrudTest.pagesScopedPerNotebook` | E2E cross-context |
| AC4.1 | `NotebookCrudTest.switchPageScopesStrokeOps` + `NotebookStoreTest.switchPageLoadsTargetPageStrokesInIsolation` | 2.2, 2.3 |
| AC4.2 | `NotebookCrudTest.switchNotebookSetsActiveAndFirstPage` + `NotebookStoreTest.switchNotebookActivatesNewNotebookAndPage` | 3.3 |
| AC4.3 | `NotebookCrudTest.switchesPersistAndReopenRestores` | 2.9 (implied) |
| AC4.4 | `NotebookStoreTest.saveBeforeSwitchAppliesToOriginalPage` | — |
| AC5.1 | `NotebookCrudTest.switchesPersistAndReopenRestores` | 2.9 |
| AC5.2 | `NotebookCrudTest.staleAppStateFallsBackToFirst` | — |
| AC6.1 | `PageNavigationLogicTest` (prevId/nextId/label) | 2.3, 2.4 |
| AC6.2 | `PageNavigationLogicTest` (bounds + absent-active edge) | — |
| AC6.3 | `PageNavigationLogicTest.canDelete...` | 2.6, 2.7, 2.8 |
| AC6.4 | — (human-only) | 2.5 |
| AC7.1 | (store half under AC4.2) | 3.1, 3.3 |
| AC7.2 | (repo ops under AC2.1–AC2.4) | 3.2, 3.4, 3.5, 3.6 |
