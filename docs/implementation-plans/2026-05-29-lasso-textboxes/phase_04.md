# Phase 4: Batch persistence for text boxes

**Goal:** Add a single-transaction batch path for text-box move/paste/delete (`NotebookRepository.applyTextBoxBatch` + `NotebookStore.replaceTextBoxes`) so the mixed-content commits in Phase 5 and Phase 6 have an atomic, off-thread API to call — mirroring the existing `applyErase` / `replaceStrokes` pattern for strokes.

**Architecture:** Kotlin-side `db.transaction { … }` wraps N `softDeleteTextBox` calls + M `upsertTextBox` calls. No new `.sq` statements — the existing per-row queries are sufficient and SQLDelight 2.0.2's transaction model gives the same atomicity guarantee as a single multi-statement SQL. `NotebookStore.replaceTextBoxes` enqueues onto the single-threaded executor and posts the callback to the main thread, again mirroring `replaceStrokes`. Sync ops are enqueued inside the transaction gated by `TEXT_BOX_SYNC_ENABLED` (already `true` on `main`).

**Tech Stack:** SQLDelight 2.0.2, in-memory JDBC SQLite driver for tests (`JdbcSqliteDriver.IN_MEMORY`), JUnit 4 + `kotlin.test`. Commands: `./gradlew :core:format:test`, `:app:notes:test`, `:app:notes:assembleDebug`.

**Scope:** Phase 4 of 6.

**Codebase verified:** 2026-05-29 via codebase-investigator. Key facts:
- `notebook.sq` at `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq` already exposes `softDeleteTextBox` (line 417-419): `UPDATE text_box SET deleted_at = ? WHERE id = ?;` — and `upsertTextBox` (lines 398-415): an `INSERT … ON CONFLICT(id) DO UPDATE SET …` covering all 14 columns (the upsert sets `deleted_at = NULL` to revive a tombstoned id on re-insert).
- `text_box` schema: `id, page_id, x, y, width, height, text, font_name, font_size, color, weight, border_width, z, created_at, deleted_at`.
- `NotebookRepository.applyErase` (lines 749-779) is the exact template. It uses `db.transaction { … }`, iterates `removedIds` skipping anything in `added`'s id set (a re-added id is a move-in-place, not a delete-and-re-insert), `softDeleteStroke`s the rest, then iterates `added` and `upsertStroke`s each — with per-row `nextZForPage` for stroke z-ordering. Within the same transaction it calls `enqueueOp("stroke", id, now)` for every changed id, `markPageOcrStale(currentPageId, now)`, and `touchCurrentNotebook()`.
- Existing `NotebookRepository.saveTextBox` (lines 805-828) does the per-row upsert path; `NotebookRepository.deleteTextBox` (lines 852-861) does the per-row soft-delete. **Both call `markPageOcrStale` and `touchCurrentNotebook`** and both gate `enqueueOp("text_box", id, now)` on `TEXT_BOX_SYNC_ENABLED` (constant at line 111: `internal const val TEXT_BOX_SYNC_ENABLED = true`). The batch function must do the same.
- `NotebookStore.replaceStrokes` (lines 109-115) is the executor-wrapper template: `executor.execute { runCatching { repo?.applyErase(removedIds, added) }.onFailure { Log.e(...) }; poster { onDone() } }`.
- Tests: `ApplyEraseTest` in `core/format/src/test/kotlin/com/forestnote/core/format/`. JUnit 4 + `kotlin.test`, in-memory JDBC driver via `JdbcSqliteDriver.IN_MEMORY`, `NotebookRepository.forTesting(driver)`. The pattern is **austere** — uses only public APIs (`saveStroke`, `loadStrokes`, `applyErase`), no `*ForTesting` inspection helpers, and does NOT verify side effects like `markPageOcrStale` or sync-op enqueue counts. Mirror this discipline for `ApplyTextBoxBatchTest` (use only `saveTextBox` / `loadTextBoxes` / `applyTextBoxBatch`).

**Z-ordering subtlety:** `applyErase` calls `nextZForPage(currentPageId)` *per stroke* inside the loop, which monotonically increments. For text boxes, the existing `saveTextBox` writes `z = box.zBand.value.toLong()` — i.e. the band int, not a per-row z-monotonic counter. So `applyTextBoxBatch` writes `z = box.zBand.value.toLong()` per box (same as `saveTextBox`). It does **not** call any `nextZ`-style helper. Each band only encodes "BOTTOM" or "TOP"; intra-band order comes from insertion order / created_at, which is also how `saveTextBox` behaves today.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### Foundation for later phases — no end-to-end ACs verified in Phase 4 alone

The functional ACs `AC2.2`, `AC3.2`, `AC3.3`, `AC3.4`, `AC3.5` are verified in Phase 5 / Phase 6 (the editor wiring). This phase's verification is:

- **applyTextBoxBatch tombstone + upsert atomicity:** The function runs in one SQLite transaction; partial-failure leaves no observable mid-state.
- **Re-added-id treatment:** An id present in both `removedIds` and `added` is treated as a move-in-place (the upsert runs, the soft-delete does NOT) — mirroring the stroke pattern's `addedIds = added.mapTo(HashSet()) { it.id }; if (id !in addedIds) softDelete…`. The investigator confirmed this is the correct pattern.
- **Sync op enqueue parity:** Every removed-and-not-re-added id and every added/upserted id results in one `enqueueOp("text_box", id, now)` call (gated by `TEXT_BOX_SYNC_ENABLED`).
- **Side effects parity:** `markPageOcrStale(currentPageId, now)` and `touchCurrentNotebook()` run exactly once per batch.

(Phase 4 has no `lasso-textboxes.AC*.*` items it owns end-to-end. It is purely the persistence foundation. Phase 5/6 verify the user-visible ACs by calling this batch path.)

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: `NotebookRepository.applyTextBoxBatch(removedIds, added)`

**Verifies:** Batch atomicity, re-added-id treatment, sync-op parity, side-effect parity (foundation for AC2.2, AC3.2-AC3.5).

**Files:**
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt` — add the function near the existing `applyErase` (lines 749-779) for locality.

**Implementation:**

Insert this function (place it right after `applyErase` for code-locality with the pattern it mirrors):

```kotlin
/**
 * Apply a batch of text-box moves/pastes/deletes in a single SQLite transaction
 * (lasso-textboxes Phase 4). Mirror of [applyErase] for strokes.
 *
 * - Ids in [removedIds] that are NOT also in [added] are soft-deleted (tombstoned).
 *   Ids in BOTH are treated as a move/upsert in place — the upsert revives the
 *   tombstone via the `deleted_at = NULL` clause in `upsertTextBox`. This avoids
 *   churning two sync ops for one logical move.
 * - Ids in [added] are upserted (insert-or-update-by-id).
 * - Each changed id emits one `enqueueOp("text_box", id, now)` so the sync engine
 *   picks them up (gated by [TEXT_BOX_SYNC_ENABLED], live on main as of 2026-05-27).
 * - `markPageOcrStale` and `touchCurrentNotebook` run exactly once per batch.
 */
fun applyTextBoxBatch(removedIds: List<String>, added: List<TextBox>) {
    db.transaction {
        val now = clock()
        val addedIds = added.mapTo(HashSet()) { it.id }
        removedIds.forEach { id ->
            if (id !in addedIds) {
                db.notebookQueries.softDeleteTextBox(now, id)
                if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", id, now)
            }
        }
        added.forEach { box ->
            db.notebookQueries.upsertTextBox(
                id = box.id,
                page_id = currentPageId,
                x = box.x.toLong(),
                y = box.y.toLong(),
                width = box.width.toLong(),
                height = box.height.toLong(),
                text = box.text,
                font_name = box.fontName,
                font_size = box.fontSize.toLong(),
                color = box.color.toLong(),
                weight = box.weight.toLong(),
                border_width = box.borderWidth.toLong(),
                z = box.zBand.value.toLong(),
                created_at = now,
            )
            if (TEXT_BOX_SYNC_ENABLED) enqueueOp("text_box", box.id, now)
        }
        markPageOcrStale(currentPageId, now)
        touchCurrentNotebook()
    }
}
```

(`TextBox` is already imported by `saveTextBox`/`deleteTextBox`/`loadTextBoxes` in the same file — no new import.)

**Note on the `created_at` column for upserts:** Per the existing `upsertTextBox` ON CONFLICT clause, `created_at = excluded.created_at` overwrites the column on conflict. This matches `saveTextBox`'s behavior today (which also passes `now`), and is what lets the sync engine LWW-order moves correctly. If we ever decide to preserve original `created_at` across moves, that's a separate decision that should be made consistently with `saveTextBox` — out of scope here.

**Verification:**

Compile:
```
./gradlew :core:format:compileDebugKotlin
```
Expected: BUILD SUCCESSFUL.

**No commit yet** — pair with the test in Task 2 to ensure the commit boundary always has green tests.
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `ApplyTextBoxBatchTest` — atomicity, re-added-id, sync-op count, side-effect parity

**Verifies:** Same foundation guarantees as Task 1.

**Files:**
- Create: `core/format/src/test/kotlin/com/forestnote/core/format/ApplyTextBoxBatchTest.kt`

**Implementation:**

Mirror the existing `ApplyEraseTest` structure. The investigator quoted the setup pattern:

```kotlin
val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
val repo = NotebookRepository.forTesting(driver)
```

(`NotebookRepository.forTesting` already exists per the investigator's quote — use it.)

The test file:

```kotlin
package com.forestnote.core.format

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test

class ApplyTextBoxBatchTest {

    private fun makeRepo(): NotebookRepository {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookRepository.forTesting(driver)
    }

    private fun box(
        id: String,
        x: Int = 0, y: Int = 0,
        w: Int = 100, h: Int = 50,
        text: String = "t",
        zBand: ZBand = ZBand.BOTTOM,
    ) = TextBox(
        id = id, x = x, y = y, width = w, height = h, text = text,
        fontName = "Roboto-Regular.ttf", fontSize = 32, zBand = zBand,
    )

    @Test
    fun emptyBatchIsNoOp() {
        val repo = makeRepo()
        repo.saveTextBox(box("keep"))
        repo.applyTextBoxBatch(emptyList(), emptyList())
        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals("keep", live[0].id)
    }

    @Test
    fun removesIdsNotInAdded() {
        val repo = makeRepo()
        repo.saveTextBox(box("keep"))
        repo.saveTextBox(box("erase"))

        repo.applyTextBoxBatch(removedIds = listOf("erase"), added = emptyList())

        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals("keep", live[0].id)
    }

    @Test
    fun upsertsAddedBoxes() {
        val repo = makeRepo()
        repo.saveTextBox(box("a"))
        val newBox = box("b", x = 500, y = 700)

        repo.applyTextBoxBatch(removedIds = emptyList(), added = listOf(newBox))

        val live = repo.loadTextBoxes()
        assertEquals(2, live.size)
        assertNotNull(live.firstOrNull { it.id == "b" && it.x == 500 && it.y == 700 })
    }

    @Test
    fun reAddedIdIsTreatedAsMoveNotDeleteAndReinsert() {
        // A id appearing in BOTH removedIds and added means "move in place" — the
        // upsert runs, the soft-delete does NOT (mirrors applyErase's behavior).
        val repo = makeRepo()
        repo.saveTextBox(box("m", x = 0, y = 0))

        val moved = box("m", x = 200, y = 300)
        repo.applyTextBoxBatch(removedIds = listOf("m"), added = listOf(moved))

        val live = repo.loadTextBoxes()
        assertEquals(1, live.size)
        assertEquals(200, live[0].x)
        assertEquals(300, live[0].y)
        assertEquals("m", live[0].id)
    }

    @Test
    fun multipleRemovesAndAddsAtomically() {
        val repo = makeRepo()
        repo.saveTextBox(box("a"))
        repo.saveTextBox(box("b"))
        repo.saveTextBox(box("c"))

        val newD = box("d", x = 1, y = 1)
        val newE = box("e", x = 2, y = 2)
        repo.applyTextBoxBatch(
            removedIds = listOf("a", "b"),
            added = listOf(newD, newE),
        )

        val live = repo.loadTextBoxes()
        val ids = live.map { it.id }.toSet()
        assertEquals(setOf("c", "d", "e"), ids, "a and b tombstoned; c kept; d, e added")
    }

    @Test
    fun upsertPreservesZBand() {
        val repo = makeRepo()
        val top = box("top", zBand = ZBand.TOP)
        val bottom = box("bot", zBand = ZBand.BOTTOM)
        repo.applyTextBoxBatch(removedIds = emptyList(), added = listOf(top, bottom))

        val live = repo.loadTextBoxes().associateBy { it.id }
        assertEquals(ZBand.TOP, live["top"]?.zBand)
        assertEquals(ZBand.BOTTOM, live["bot"]?.zBand)
    }

}
```

**Test scope — matches the austere `ApplyEraseTest` pattern.** The existing `ApplyEraseTest` (verified by reading it: `core/format/src/test/kotlin/com/forestnote/core/format/ApplyEraseTest.kt`) deliberately uses only the public `saveStroke` / `loadStrokes` / `applyErase` API. It does NOT verify `markPageOcrStale` was called, does NOT count sync-op-outbox rows, and does NOT introspect notebook `updated_at`. Those side effects are trusted to fire because `applyErase` calls them inside the same transaction.

`ApplyTextBoxBatchTest` mirrors that discipline: only public APIs (`saveTextBox`, `loadTextBoxes`, `applyTextBoxBatch`), no `*ForTesting` inspection helpers. The OCR-stale + notebook-touch side effects are covered by code review (they appear in the function body alongside the existing patterns) rather than by tests. If a future test wants to assert sync-op count or OCR staleness, that's a separate `@VisibleForTesting`-helper-introducing change out of scope here.

**Verification:**

```
./gradlew :core:format:test --tests com.forestnote.core.format.ApplyTextBoxBatchTest
```
Expected: All tests pass. If the OCR/notebook touch assertions fail because the clock didn't advance, increase the `Thread.sleep` or wire a fake clock via `forTesting` (mirror whatever pattern `ApplyEraseTest` uses).

**Commit:** `feat(format): applyTextBoxBatch + ApplyTextBoxBatchTest`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: `NotebookStore.replaceTextBoxes(removedIds, added, onDone)`

**Verifies:** Off-thread execution + main-thread callback parity with `replaceStrokes` (foundation for Phase 5/6).

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt` — add the function near `replaceStrokes` (lines 109-115) for locality.

**Implementation:**

```kotlin
/**
 * Apply a batch of text-box moves/pastes/deletes off the main thread, then post
 * [onDone] back to the main thread (lasso-textboxes Phase 4). Mirrors
 * [replaceStrokes] for strokes; drag-commit / cut / delete / paste in Phase 5/6
 * call this and [replaceStrokes] back-to-back. The single-threaded executor
 * serializes the two — strokes and boxes are NOT atomic with each other across
 * tables, which matches the existing failure model (see design plan's
 * "Failure-mode characterization for the parallel batch commits").
 */
fun replaceTextBoxes(removedIds: List<String>, added: List<TextBox>, onDone: () -> Unit = {}) {
    executor.execute {
        runCatching { repo?.applyTextBoxBatch(removedIds, added) }
            .onFailure { android.util.Log.e(TAG, "failed to replace text boxes", it) }
        poster { onDone() }
    }
}
```

(Add `import com.forestnote.core.ink.TextBox` at the top if not already present — investigator confirms `NotebookStore` already imports `TextBox` for `saveTextBox`/`loadTextBoxes`/`deleteTextBox`, so no new import.)

**Verification:**

```
./gradlew :app:notes:compileDebugKotlin
./gradlew :app:notes:test
```
Expected: BUILD SUCCESSFUL on both; existing tests still pass.

**Commit:** `feat(notes): NotebookStore.replaceTextBoxes — off-thread batch wrapper`
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: Final phase sweep

**Verifies:** Phase-level: batch path lands without breaking strokes-side persistence.

**Files:** (no edits)

**Verification:**

```
./gradlew :core:format:test
./gradlew :app:notes:test
./gradlew :app:notes:assembleDebug
```
Expected: BUILD SUCCESSFUL on all three. `ApplyTextBoxBatchTest` and existing `ApplyEraseTest` are both green.

**Done when:** `NotebookRepository.applyTextBoxBatch(removedIds, added)` exists and is transaction-atomic; `NotebookStore.replaceTextBoxes(removedIds, added, onDone)` enqueues onto the existing single-threaded executor; sync-op rows are enqueued exactly once per logical change; the OCR-stale marker and notebook-updated-at are bumped exactly once per batch; existing `saveTextBox` / `deleteTextBox` continue to work unchanged.
<!-- END_TASK_4 -->
