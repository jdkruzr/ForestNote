# Human Test Plan — Off-Main-Thread Persistence + ULID Identity

Generated from the test-analyst coverage validation (PASS: 19/19 automated criteria,
136 tests green across `core:ink`, `core:format`, `app:notes`). This document covers the
on-device acceptance criteria that cannot be unit-tested here (no Robolectric in
`app:notes`); the underlying mechanisms are all automated.

## Prerequisites
- Build + install on the Viwoods AiPaper Mini: `./gradlew :app:notes:assembleDebug`, then deploy via the Termux/SSH loop (192.168.8.78:8022; adb is unavailable on this device).
- `./gradlew :app:notes:test :core:ink:test :core:format:test` passing (136 tests, 0 failures — confirmed).
- Start each relaunch scenario from a fully killed app (swipe from recents / force-stop), not just backgrounded, so `onCreate` runs cold and `onDestroy`→`store.shutdown()` has drained.
- Use the stylus and the hardware eraser; finger input is intentionally rejected.

## Phase 4: On-device persistence + lifecycle

| Step | Action | Expected |
|------|--------|----------|
| 1 | Cold-launch the app on an empty notebook (AC1.2) | Canvas is interactive immediately — stylus draws with no blocking spinner or input jank before any saved ink would appear |
| 2 | Draw 5 distinct strokes, lift pen after each | Each stroke renders; e-ink quality refresh settles ~900 ms after pen-up |
| 3 | Force-kill, then relaunch (AC3.1) | All 5 strokes reappear in the same draw order (earliest-drawn under later ones) |
| 4 | Erase one whole stroke with the hardware eraser; relaunch (AC2.4, AC3.2) | The erased stroke is gone and stays gone after relaunch; the other 4 remain |
| 5 | Pixel-erase a gap through the middle of one remaining stroke; relaunch (AC3.3) | The two surviving fragments are present, the erased gap stays empty after relaunch |
| 6 | Perform a large, fast pixel-erase sweeping across many strokes (AC1.3) | No ANR dialog; UI stays responsive throughout and after |
| 7 | Clear the page; relaunch (AC3.4) | Page is empty immediately and remains empty after relaunch |

## End-to-End: Draw-during-load survives the async load gap (AC7.1, AC7.2)
Purpose: confirm the non-blocking startup load merges previously-saved ink with ink drawn during the load window without clobbering either.

1. With several strokes already saved, force-kill the app.
2. Cold-launch and immediately begin drawing a new stroke (before the older ink finishes loading in).
3. Expected: the previously-saved strokes appear once the load returns, ordered first; the stroke drawn during the gap is preserved and ordered after the loaded ink; no saved stroke is dropped and no stroke is duplicated.
4. Force-kill and relaunch: both the loaded ink and the gap-drawn stroke are present.

## Human Verification Required
| Criterion | Why Manual | Steps |
|-----------|------------|-------|
| AC1.2 | No Robolectric — cannot observe real `onCreate` timing on JVM | Step 1: cold-launch, confirm canvas interactive with no sync-load block |
| AC1.3 | ANR is an OS main-thread-stall condition, not reproducible in JVM | Step 6: large fast pixel-erase, confirm no ANR, UI responsive |
| AC2.4 (UI) | Requires a real app relaunch | Step 4: erase whole stroke → relaunch → stays gone |
| AC3.1 (UI) | Requires a real relaunch | Step 3: draw → relaunch → confirm draw order |
| AC3.2 (UI) | Requires a real relaunch | Step 4: relaunch → erased stroke absent |
| AC3.3 (UI) | Requires a real relaunch | Step 5: relaunch → fragments present, gap empty |
| AC3.4 (UI) | Requires a real relaunch | Step 7: clear → relaunch → empty |
| AC7.1/7.2 (UI) | Requires real async load against a live Looper | E2E scenario above |
| AC7.4 (UX) | Real load failure needs a live device | Simulate/observe a load failure (e.g. transient I/O); confirm canvas remains usable, no crash |
| AC8.1 (UI) | Real save failure needs a live device | Force a save failure; confirm on-screen stroke unaffected during the session (cost is absence only after relaunch) |
| AC8.2 (UI) | Real erase failure needs a live device | Force an erase failure; confirm in-memory ink stays visible (stale stroke beats a crash) |

## Traceability
| Acceptance Criterion | Automated Test | Manual Step |
|----------------------|----------------|-------------|
| AC1.1 | NotebookStoreTest `workRunsOffTheCallerThread` | — |
| AC1.2 | — | Step 1 |
| AC1.3 | — | Step 6 |
| AC2.1 | StrokeGeometryReconcileTest + UlidTest | — |
| AC2.2 / AC2.3 | NotebookRepositoryTest | — |
| AC2.4 | ApplyEraseTest (storage) | Step 4 |
| AC3.1 | StorageIntegrationTest | Step 3 |
| AC3.2 | ApplyEraseTest / StorageIntegrationTest | Step 4 |
| AC3.3 | ApplyEraseTest / StorageIntegrationTest | Step 5 |
| AC3.4 | StorageIntegrationTest | Step 7 |
| AC4.1-4.4 | UlidTest | — |
| AC5.1-5.3 | NotebookRepositoryTest | — |
| AC6.1 | NotebookStoreTest `saveDrainsBeforeShutdownCloses` | — |
| AC6.2 | NotebookStoreTest `drainTimeoutFallsBackToShutdownNow` | — |
| AC7.1 | MergeStrokesTest `loadedStrokesAppear` | E2E |
| AC7.2 | MergeStrokesTest `gapDrawnStrokeIsPreservedAfterLoaded` | E2E |
| AC7.3 | MergeStrokesTest `duplicateIdAppearsOnce` | — |
| AC7.4 | NotebookStoreTest `loadFailurePostsEmptyList` | AC7.4 manual row |
| AC8.1 | NotebookStoreTest `saveFailureIsSwallowedAndThreadSurvives` | AC8.1 manual row |
| AC8.2 | NotebookStoreTest `eraseFailurePostsNoDiff` | AC8.2 manual row |
| AC8.3 | NotebookRepositoryTest (self-heal) | — |
