# Off-Main-Thread Persistence + ULID Identity — Phase 3: NotebookStore

**Goal:** Introduce `NotebookStore` in `app:notes` — the single owner of one background thread and the only holder of `NotebookRepository` — exposing an async API (load/save/reconcileErase/clear) and a draining shutdown.

**Architecture:** A single-thread `ExecutorService` is the serialization point for all DB access. `NotebookStore` takes its dependencies injected — `repoProvider: () -> NotebookRepository`, the `ExecutorService`, and a `poster: (Runnable) -> Unit` for main-thread callbacks — so it is fully unit-testable on the JVM without Android. The repository is opened as the first task on the executor; every later task queues behind it. Every task body is catch-and-log. `shutdown()` enqueues `close()` last, then `shutdown()` + `awaitTermination`, falling back to `shutdownNow()`.

**Tech Stack:** Kotlin, `java.util.concurrent`, Android `Handler`/`Looper` (production poster only), JUnit 4, `kotlin.test`, `JdbcSqliteDriver` (test).

**Scope:** Phase 3 of 4.

**Codebase verified:** 2026-05-23.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### persistence-ulid.AC1: All persistence runs off the main thread
- **persistence-ulid.AC1.1 Success:** open/load/save/erase/clear each execute on the store's background thread, not the caller's thread. *(reconcileErase runs the geometry + transaction off-thread too, which is the mechanism behind AC1.3's no-ANR guarantee.)*

### persistence-ulid.AC6: Graceful shutdown drains writes
- **persistence-ulid.AC6.1 Success:** a save enqueued immediately before `shutdown()` completes before the driver closes.
- **persistence-ulid.AC6.2 Edge:** if draining exceeds the timeout, `shutdownNow()` is called and the event is logged (no hang).

---

## Context for the engineer

- Depends on Phases 1–2 (`Ulid`, ULID-based `Stroke`, `NotebookRepository` with `Unit`-returning `saveStroke`/`applyErase` and `String` ids).
- Package: `com.forestnote.app.notes`. Create at `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`.
- `NotebookRepository` (`core:format`) factories: `open(context)` (production), `forTesting(driver)` / `openExisting(driver)` (tests). It is synchronous and must only ever be touched on the executor thread once owned by the store.
- `StrokeGeometry.reconcileErase(strokes, eraserPath, radius, eraseWholeStrokes, newId = Ulid::generate)` is pure and lives in `core:ink`; the store calls it on the executor thread.
- `app:notes` has **no Robolectric**; tests are pure JVM (`DrawViewLogicTest`, `ToolBarLogicTest`). To test `NotebookStore` with a real repository, add the SQLDelight JDBC driver as a `testImplementation` (same one `core:format` uses).
- Existing background-persistence reference being generalized: `DrawView.eraseExecutor` (`Executors.newSingleThreadExecutor()`), `reconcileAfterErase`'s `eraseExecutor.execute { … post { … } }`.
- Run app tests: `./gradlew :app:notes:test`.

---

<!-- START_TASK_1 -->
### Task 1: Add SQLDelight JDBC driver as a test dependency for `app:notes`

**Files:**
- Modify: `app/notes/build.gradle.kts` (dependencies block)
- Reference: `gradle/libs.versions.toml` (use the existing `sqldelight-sqlite-driver` alias that `core:format` uses)

**Implementation:**
This project binds the version catalog as a `VersionCatalog` object (`val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")`), NOT the generated type-safe `libs` extension — so dependencies use `libs.findLibrary("...").get()`. Add, matching `core/format/build.gradle.kts:25` exactly:
```kotlin
testImplementation(libs.findLibrary("sqldelight-sqlite-driver").get())
```
This gives tests `app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver` so they can build a real `NotebookRepository` (in-memory for load/erase/clear tests; **file-backed** for the AC6.1 drain test — see Task 3, since an in-memory DB does not survive `close()`). Confirm `app:notes` already depends on `core:format` and `core:ink` (it does, for the main source set).

**Note on test dependencies:** `app:notes` uses the `forestnote.android.application` convention plugin, whose test classpath has only `junit` + `kotlin-test` (NO Mockito — Mockito is added by the `forestnote.android.library` plugin used by `core:*`). Do not write `app:notes` tests that import `org.mockito.*`; use hand-written fakes instead (Task 3 AC6.2 does this).

**Verification:**
Run: `./gradlew :app:notes:dependencies --configuration testRuntimeClasspath` (or simply build the test task in Task 3)
Expected: `sqlite-driver` present on the test classpath.

**Commit:** `chore(notes): add sqldelight jdbc driver as test dependency`
<!-- END_TASK_1 -->

<!-- START_SUBCOMPONENT_A (tasks 2-3) -->
<!-- START_TASK_2 -->
### Task 2: `NotebookStore`

**Verifies:** persistence-ulid.AC1.1, persistence-ulid.AC6.1, persistence-ulid.AC6.2

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`

**Implementation:**
```kotlin
package com.forestnote.app.notes

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.forestnote.core.format.NotebookRepository
import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.StrokeGeometry
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Single owner of all persistence. Runs every database operation on one background
 * thread (the serialization point — single writer, no lock contention), and posts
 * UI-facing results back to the main thread. The repository is opened as the first
 * task and is never touched off this thread.
 */
class NotebookStore(
    private val repoProvider: () -> NotebookRepository,
    private val executor: ExecutorService,
    private val poster: (Runnable) -> Unit
) {
    // Written and read only on the executor thread, so no synchronization is needed.
    private var repo: NotebookRepository? = null

    init {
        // Open as the first enqueued task; every later task queues behind it.
        executor.execute {
            repo = runCatching { repoProvider() }
                .onFailure { android.util.Log.e(TAG, "failed to open repository", it) }
                .getOrNull()
        }
    }

    /** Load all strokes (z-ordered) off-thread; result posted to the main thread. */
    fun load(onLoaded: (List<Stroke>) -> Unit) {
        executor.execute {
            val strokes = runCatching { repo?.loadStrokes() ?: emptyList() }
                .onFailure { android.util.Log.e(TAG, "failed to load strokes", it) }
                .getOrDefault(emptyList())
            poster { onLoaded(strokes) }
        }
    }

    /** Persist a completed stroke. Fire-and-forget (the stroke already has its ULID). */
    fun save(stroke: Stroke) {
        executor.execute {
            runCatching { repo?.saveStroke(stroke) }
                .onFailure { android.util.Log.e(TAG, "failed to save stroke", it) }
        }
    }

    /**
     * Reconcile an erase gesture (geometry + transaction) off-thread, then post the
     * resulting diff (removed ids + surviving fragments) to the main thread.
     */
    fun reconcileErase(
        strokes: List<Stroke>,
        eraserPath: List<Pair<Int, Int>>,
        radius: Int,
        eraseWholeStrokes: Boolean,
        onResult: (removed: List<String>, fragments: List<Stroke>) -> Unit
    ) {
        executor.execute {
            val result = StrokeGeometry.reconcileErase(strokes, eraserPath, radius, eraseWholeStrokes)
            if (result.removedStrokeIds.isEmpty() && result.addedStrokes.isEmpty()) return@execute
            runCatching { repo?.applyErase(result.removedStrokeIds, result.addedStrokes) }
                .onFailure {
                    android.util.Log.e(TAG, "failed to apply erase", it)
                    return@execute // post no diff on failure (in-memory ink stays visible)
                }
            poster { onResult(result.removedStrokeIds, result.addedStrokes) }
        }
    }

    /** Clear the page off-thread; callback posted when done. */
    fun clear(onCleared: () -> Unit) {
        executor.execute {
            runCatching { repo?.clearPage() }
                .onFailure { android.util.Log.e(TAG, "failed to clear page", it) }
            poster { onCleared() }
        }
    }

    /** Drain pending writes, then close the driver as the last task. */
    fun shutdown() {
        executor.execute { runCatching { repo?.close() } }
        executor.shutdown()
        try {
            if (!executor.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                android.util.Log.w(TAG, "persistence executor did not drain in time; forcing shutdown")
                executor.shutdownNow()
            }
        } catch (e: InterruptedException) {
            android.util.Log.w(TAG, "interrupted while draining persistence executor", e)
            executor.shutdownNow()
            Thread.currentThread().interrupt()
        }
    }

    companion object {
        private const val TAG = "NotebookStore"
        private const val SHUTDOWN_TIMEOUT_SECONDS = 5L

        /** Production factory: real single-thread executor, main-thread Handler poster. */
        fun create(context: Context): NotebookStore {
            val appContext = context.applicationContext
            val mainHandler = Handler(Looper.getMainLooper())
            return NotebookStore(
                repoProvider = { NotebookRepository.open(appContext) },
                executor = Executors.newSingleThreadExecutor(),
                poster = { runnable -> mainHandler.post(runnable) }
            )
        }
    }
}
```

Notes:
- `onResult`/`onCleared` and `onLoaded` are invoked via `poster`, i.e. on the main thread in production.
- The `return@execute` inside `reconcileErase`'s `onFailure` skips posting a diff so in-memory ink remains (AC8.2 — exercised more fully in Phase 4).
- `repo` is confined to the executor thread; do not expose it.

**Verification:** `./gradlew :app:notes:compileDebugKotlin` — compiles.

**Commit:** (defer to end of Subcomponent A)
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: `NotebookStore` tests

**Verifies:** persistence-ulid.AC1.1, persistence-ulid.AC6.1, persistence-ulid.AC6.2

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt` (unit)

**Testing:**
Use JUnit 4 / `kotlin.test`, condition-based waiting (`CountDownLatch`, not sleeps). Build a real repository with `NotebookRepository.forTesting(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY))` (or a temp file where the test needs data to survive `close()` — see AC6.1).

- **AC1.1 (work runs off the caller thread):** Construct a store with a **real** `Executors.newSingleThreadExecutor()` and an **inline poster** (`{ it.run() }`) so callbacks run on the executor thread. Record the test thread. Call `load { capturedThread = Thread.currentThread(); latch.countDown() }`; await the latch; assert `capturedThread != testThread`. Repeat the assertion for `reconcileErase(...)` (provide a stroke list + an eraser path that intersects, so a diff is produced and `onResult` fires) and for `clear { … }`. This proves load/erase/clear bodies execute on the background thread, not the caller's.

- **AC6.1 (save drains before close):** Use a **file-backed** driver so data survives `close()`: `JdbcSqliteDriver("jdbc:sqlite:" + tmpFile.absolutePath)` with `NotebookRepository.forTesting(driver)` via the `repoProvider`. `save(stroke)` then immediately `shutdown()`. After `shutdown()` returns, open a fresh `JdbcSqliteDriver` on the same file via `NotebookRepository.openExisting(driver2)` and assert `loadStrokes()` contains the saved stroke (its `id` matches). This proves the enqueued save completed before the driver closed (drain works). Delete the temp file in teardown.

- **AC6.2 (timeout → shutdownNow + log):** `app:notes` has no Mockito on its test classpath, so use a **hand-written fake `ExecutorService`** (implement the interface, or subclass `java.util.concurrent.AbstractExecutorService`) whose `awaitTermination(timeout, unit)` returns `false` and which records boolean flags `shutdownCalled` / `shutdownNowCalled` when those methods are invoked (its `execute(Runnable)` can run inline or no-op — the open/init task must not throw). Construct the store with this fake, call `shutdown()`, then assert `shutdownCalled == true` and `shutdownNowCalled == true` (the store falls back to `shutdownNow()` when draining times out). The fake returns immediately, so the test cannot hang.

Match existing app-test style. No Robolectric, no Activity/View instantiation.

**Verification:** `./gradlew :app:notes:test` — all pass.

**Commit:** `feat(notes): NotebookStore single-thread persistence boundary with drain-on-shutdown + tests`
<!-- END_TASK_3 -->
<!-- END_SUBCOMPONENT_A -->
