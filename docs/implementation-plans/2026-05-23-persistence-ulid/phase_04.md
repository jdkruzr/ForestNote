# Off-Main-Thread Persistence + ULID Identity — Phase 4: Wire DrawView + MainActivity to NotebookStore

**Goal:** Route all UI persistence through `NotebookStore`, removing every main-thread DB call and the per-view `eraseExecutor`; make startup load non-blocking with a safe merge; drain the store on destroy.

**Architecture:** `DrawView` holds a `NotebookStore` instead of a `NotebookRepository`. Pen-up calls `store.save(...)`; erase snapshots inputs on the main thread and calls `store.reconcileErase(...)`, applying the posted diff. The startup merge is a pure companion function (`mergeStrokes`) so it is unit-testable; `mergeLoadedStrokes` applies it and redraws. `MainActivity` builds the store, kicks off a non-blocking `load`, routes clear through the store, and calls `store.shutdown()` in `onDestroy`.

**Tech Stack:** Kotlin, Android Views, JUnit 4, `kotlin.test`.

**Scope:** Phase 4 of 4.

**Codebase verified:** 2026-05-23.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### persistence-ulid.AC7: Non-blocking startup load with safe merge
- **persistence-ulid.AC7.1 Success:** previously-saved strokes appear after the async load completes.
- **persistence-ulid.AC7.2 Success:** a stroke drawn during the load gap is preserved (not clobbered) and ordered after loaded strokes.
- **persistence-ulid.AC7.3 Edge:** the merge dedups by id (no duplicate if a stroke appears in both sets).

### Verified on-device (manual checklist), not unit-testable here
`app:notes` has no Robolectric; the following require the Activity/View/threading integration and are verified on-device per `docs/manual-test-checklist.md` (see Phase 4 verification): **AC1.2** (onCreate returns without sync open/load), **AC1.3** (no ANR on large pixel erase), **AC2.4** (erase-then-relaunch doesn't resurrect), **AC3.1–AC3.4** (draw/erase/clear survive relaunch in order), **AC7.4** (load failure leaves canvas usable), **AC8.1/AC8.2** (save/erase failures don't crash). The code paths that satisfy these are implemented in Tasks 1 & 3; their automated coverage at the storage layer is in Phase 2, and `NotebookStore`'s off-thread/drain guarantees are tested in Phase 3.

---

## Context for the engineer

- Depends on Phases 1–3 (`NotebookStore` exists with `create(context)`, `load`, `save`, `reconcileErase`, `clear`, `shutdown`).
- `DrawView` (`app/notes/.../DrawView.kt`):
  - `repository`/`setRepository` (lines 65, 132–134) — to be replaced by `store`/`setStore`.
  - `eraseExecutor` field (line 104) and `onDetachedFromWindow` shutdown (lines 123–126) — to be removed.
  - Pen-up save block in `handleDraw` ACTION_UP (~lines 367–379, already simplified in Phase 2 Task 5).
  - `reconcileAfterErase` (lines 475–513) — currently snapshots inputs, runs `eraseExecutor.execute { StrokeGeometry.reconcileErase(...) ; repo.applyErase(...) ; post { diff } }`. To be rewritten to delegate to `store.reconcileErase(...)`.
  - `restoreStrokes(strokes)` (lines 144–153) — replays strokes onto the bitmap by clearing then drawing; `completedStrokes` (line 60); `redrawBitmap()` (519–537); `ensureBitmap()` (196–208); `shouldAcceptToolType` companion (51–58) is the existing pure-function pattern to mirror.
- `MainActivity` (`app/notes/.../MainActivity.kt`):
  - `repository` field (line 31), `NotebookRepository.open(this)` (line 45), `repository.loadStrokes()` + `drawView.restoreStrokes(strokes)` (lines 64–66), `setRepository(repository)` (line 57), `repository.clearPage()` (line 100), `repository.close()` (line 131).
  - Manual wiring in `onCreate` (no DI). `onPause`/`onResume`/`onDestroy` manage `backend`.
- Run app tests: `./gradlew :app:notes:test`. Build: `./gradlew :app:notes:assembleDebug`. All: `./gradlew test`.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: DrawView → NotebookStore (save, erase, merge); remove eraseExecutor

**Verifies:** persistence-ulid.AC7.1, persistence-ulid.AC7.2, persistence-ulid.AC7.3 (via the `mergeStrokes` pure function added here; tested in Task 2)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/DrawView.kt`

**Implementation:**
1. **Swap dependency:** replace `private var repository: NotebookRepository? = null` with `private var store: NotebookStore? = null`, and `setRepository(...)` with `fun setStore(store: NotebookStore) { this.store = store }`. Remove the `NotebookRepository` import if now unused; keep the `Stroke`/`StrokeGeometry` imports.
2. **Remove the executor:** delete the `eraseExecutor` field (line 104) and the entire `onDetachedFromWindow` override (lines 123–126) — the store owns the thread now, and `MainActivity.onDestroy` drains it. Remove the now-unused `ExecutorService`/`Executors` imports.
3. **Pen-up save:** in `handleDraw` ACTION_UP, replace the `repository`-based block with:
   ```kotlin
   if (!stroke.isEmpty()) {
       store?.save(completed)
       onStrokeSaved?.invoke(completed)
   }
   ```
   (The stroke already carries its ULID; no return value, no `copy`.)
4. **Erase:** rewrite `reconcileAfterErase(tool, eraserWidthScreen)` to delegate to the store. Keep the main-thread snapshot of inputs (strokes, path, radius, wholeStrokes) and `eraserPathVirtual.clear()`, then:
   ```kotlin
   store?.reconcileErase(
       strokes = strokesSnapshot,
       eraserPath = pathSnapshot,
       radius = virtualRadius,
       eraseWholeStrokes = wholeStrokes
   ) { removed, fragments ->
       // Runs on the main thread (store posts it). Apply as a diff so strokes drawn
       // while we worked aren't clobbered, then redraw from the reconciled model.
       val removedSet = removed.toHashSet()
       completedStrokes.removeAll { it.id in removedSet }
       completedStrokes.addAll(fragments)
       redrawBitmap()
   }
   ```
   Delete the old `eraseExecutor.execute { … }` body and its inner `post { … }`.
5. **Merge (pure + apply):** add a pure companion function next to `shouldAcceptToolType`:
   ```kotlin
   companion object {
       // ... shouldAcceptToolType ...

       /**
        * Merge DB-loaded strokes (already z-ordered) with strokes drawn during the
        * async load gap. Loaded come first, then session strokes not already present;
        * dedup by stable id so nothing is duplicated or clobbered.
        */
       fun mergeStrokes(loaded: List<Stroke>, session: List<Stroke>): List<Stroke> {
           val seen = HashSet<String>(loaded.size + session.size)
           val out = ArrayList<Stroke>(loaded.size + session.size)
           for (s in loaded) if (seen.add(s.id)) out.add(s)
           for (s in session) if (seen.add(s.id)) out.add(s)
           return out
       }
   }
   ```
   Add `fun mergeLoadedStrokes(strokes: List<Stroke>)` that replaces `restoreStrokes` usage from `MainActivity`:
   ```kotlin
   fun mergeLoadedStrokes(strokes: List<Stroke>) {
       val merged = mergeStrokes(strokes, completedStrokes)
       completedStrokes.clear()
       completedStrokes.addAll(merged)
       ensureBitmap()
       writingBitmap?.eraseColor(Color.TRANSPARENT)
       for (stroke in completedStrokes) drawStrokeToBitmap(stroke)
       invalidate()
   }
   ```
   You may keep `restoreStrokes` if still referenced elsewhere; otherwise remove it (its replay logic is reused above). Confirm via search that `MainActivity` is the only caller.

**Verification:** `./gradlew :app:notes:compileDebugKotlin` — compiles.

**Commit:** (defer to end of Subcomponent A)
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `mergeStrokes` tests

**Verifies:** persistence-ulid.AC7.1, persistence-ulid.AC7.2, persistence-ulid.AC7.3

**Files:**
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/DrawViewLogicTest.kt` (add cases) — or Create `MergeStrokesTest.kt` if cleaner.

**Testing (pure function, no Android instantiation — mirrors `shouldAcceptToolType` tests):**
- **AC7.1:** `mergeStrokes(loaded = [a, b], session = [])` returns `[a, b]` (loaded strokes appear).
- **AC7.2:** `mergeStrokes(loaded = [a, b], session = [c])` returns `[a, b, c]` — the gap-drawn stroke `c` is preserved and ordered after the loaded ones.
- **AC7.3:** when a stroke appears in both lists (same `id`), it appears once: `mergeStrokes(loaded = [a, b], session = [b, c])` returns `[a, b, c]` (dedup by id; loaded position wins). Build `Stroke`s with explicit ids (e.g. `Stroke(id = "a", points = …)`).
- Edge: both empty → empty list.

Use minimal valid `Stroke`s (a couple of `StrokePoint`s). JUnit 4 / `kotlin.test` with assertion messages.

**Verification:** `./gradlew :app:notes:test` — all pass.

**Commit:** `feat(notes): DrawView uses NotebookStore; non-blocking merge of loaded strokes (+tests)`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: MainActivity → NotebookStore lifecycle

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**
- Replace `private lateinit var repository: NotebookRepository` with `private lateinit var store: NotebookStore`.
- In `onCreate`, replace `repository = NotebookRepository.open(this)` (line 45) with `store = NotebookStore.create(this)`. **Move it after `setContentView`/view lookup is fine**, but it must be created before wiring DrawView. Remove the synchronous `val strokes = repository.loadStrokes(); drawView.restoreStrokes(strokes)` (lines 64–66) and instead, after wiring, kick off a non-blocking load:
  ```kotlin
  drawView.apply {
      setBackend(backend)
      setStore(store)
      setTransform(PageTransform())
      onStrokeSaved = { /* notification-only */ }
  }
  // Non-blocking restore: canvas is interactive immediately; ink appears when load returns.
  store.load { strokes -> drawView.mergeLoadedStrokes(strokes) }
  ```
  (Remove `setRepository`; `NotebookRepository.open` is no longer called here — it happens inside the store on its thread, satisfying AC1.2.)
- `showClearConfirmation`: keep `drawView.clearAll()`, replace the `try { repository.clearPage() } catch …` block with `store.clear { }` (the store handles its own errors).
- `onDestroy`: replace `repository.close()` with `store.shutdown()` (keep `backend.release()` in the same try/catch). `store.shutdown()` drains pending saves then closes the driver as its last task.
- Remove the now-unused `NotebookRepository` import.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug` and `./gradlew test`
Expected: APK builds; all unit tests across modules pass.

**Commit:** `feat(notes): MainActivity drives NotebookStore (non-blocking load, drain on destroy)`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Update on-device manual checklist + stale module docs

**Files:**
- Modify: `docs/manual-test-checklist.md`
- Modify: `core/format/CLAUDE.md`
- Inspect/Modify if needed: `core/ink/CLAUDE.md`, `app/notes/CLAUDE.md`

**Implementation:**
1. **Manual checklist** — add a "persistence-ulid" section listing the on-device checks for the ACs that can't be unit-tested here, so they're tracked for sign-off after deploy:
   - AC1.2: app launches and the canvas accepts strokes immediately (no blocking spinner / jank on cold start).
   - AC1.3: a large, fast pixel-erase over many strokes does not ANR.
   - AC2.4 / AC3.2 / AC3.3: draw → erase (whole + pixel) → relaunch; erased ink stays gone, fragments correct.
   - AC3.1 / AC3.4: draw several strokes → relaunch shows them in order; clear → relaunch shows empty page.
   - AC7.x: strokes drawn immediately on launch (before/while older ink loads) are preserved alongside loaded ink.
2. **Stale doc invariants** — `core/format/CLAUDE.md` currently asserts "Stroke IDs from saveStroke() are always > 0" and (in Gotchas) "Stroke.id=0 means unsaved; only saved strokes have positive IDs." Both are false after this work. Update them to describe the new reality: stroke/page ids are client-minted ULID strings minted at creation; there is no unsaved/id=0 state; `saveStroke` returns `Unit`; an explicit `z` column orders strokes (`loadStrokes` returns `ORDER BY z`); persistence is funneled through `NotebookStore`'s single background thread. Also refresh the `core/format/CLAUDE.md` Contracts line for `NotebookRepository` (saveStroke/applyErase now return `Unit`, deleteStroke takes a `String`). Check `core/ink/CLAUDE.md` (the "Sub-strokes from splitStroke() always have 2+ points" invariant is still true; update any id-as-Long language) and `app/notes/CLAUDE.md` (note DrawView now persists via `NotebookStore`, not a direct repository). Bump each touched file's "Last verified" date.

**Verification:** N/A (documentation).

**Commit:** `docs: persistence-ulid checklist + refresh module CLAUDE.md invariants for ULID/store`
<!-- END_TASK_4 -->
