# Library & Tools — Area C Implementation Plan (Phase 4 / C3b)

**Goal:** Replace the Library's placeholder tiles with real first-page ink thumbnails, rendered off-thread to a small `Bitmap` and cached on disk, without blocking scroll.

**Architecture:** A standalone `ThumbnailRenderer` (app:notes) draws a page's strokes onto a fixed **300×400** `Bitmap` using the same `PageTransform` + `PressureCurve` math as the editor — re-implementing the ~10-line draw loop rather than refactoring `DrawView`'s hot path (defensive: don't perturb the live editor on e-ink). A `ThumbnailCache` stores PNGs in `cacheDir/thumbnails/`, keyed by `(pageId, strokeCount, notebook.modifiedAt)`, with prefix-purge of stale keys and a ~50 MB LRU cap. A `ThumbnailLoader` orchestrates: read the cheap cache key inputs via `NotebookStore`, check the disk cache, and only load strokes + render on a miss — all on a dedicated 2-thread render executor (the single DB-writer thread is never blocked by rendering). RecyclerView recycling is guarded by a view tag. The pure key/eviction logic (`ThumbnailCacheLogic`) is unit-tested; the `Bitmap` render + file IO are verified on-device (no Robolectric in the project).

**Tech Stack:** Kotlin, Android `Bitmap`/`Canvas`/`Paint`, `java.util.concurrent` + `Handler` (no coroutines, no image libs — matches house style), SQLDelight, JUnit4 + `kotlin.test`.

**Scope:** Phase 4 of 7 (area C). Depends on C3a (`LibraryView` + `NotebookCardAdapter` + the `card_thumb` ImageView) and C1 (schema). Per the design, C3b "only swaps the card image source" and can land any time after C3a.

**Codebase verified:** 2026-05-26 — `DrawView.drawStrokeToBitmap` (DrawView.kt ~976) renders `Stroke` line-segments with `PressureCurve.width(...)` and `PageTransform.toScreen*`; no View state required. `PageTransform.update(wPx,hPx)` derives `scale = shortPx/10000`; constructible off-thread. `Stroke(id, points, color, penWidthMin, penWidthMax)`, `StrokePoint(x,y,pressure,timestampMs)` in `core/ink`. `NotebookRepository.loadStrokes()` is scoped to the current page — an arbitrary-page read must be added; `getStrokesForPage` query already exists. `modifiedAtOf(notebookId)` exists (`selectNotebookModifiedAt`); no stroke-count query exists. App uses `Executors.newSingleThreadExecutor()` + `Handler(mainLooper)`; no kotlinx-coroutines; no Glide/Coil. Crash handler writes to `filesDir`; `cacheDir` is the right home for regenerable thumbnails. `Bitmap`/`Canvas` are safe off the main thread.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC4: Library
- **library-and-tools.AC4.2 Success:** Library shows a grid of cards (4 across at Mini width). Folder cards have a folder glyph + child count. Notebook cards have a thumbnail rendering of the first page (no template, just ink).

> C3b completes the **notebook-card thumbnail** clause of AC4.2 (folder cards are C4). Automated tests cover the pure `ThumbnailCacheLogic` (cache-key format, stale-key purge, LRU eviction selection). The rendered thumbnails, async loading, edit-invalidation, restart-survival, and 50 MB eviction are verified on-device.

---

## Decisions (confirmed with the human)

1. **Standalone `ThumbnailRenderer`** (re-implement the small draw loop) — do not refactor `DrawView.drawStrokeToBitmap`. Reuse `PressureCurve` + `PageTransform`.
2. **`cacheDir/thumbnails/`** (ephemeral/regenerable), not `filesDir`.
3. **Fixed 300×400** render via `PageTransform.update(300,400)`; **no template** drawn; `ppi` irrelevant for ink.
4. **Cache-hit avoids loading strokes**: read `firstPageId` + `countStrokesForPage` + `modifiedAtOf` (cheap), build key, only `loadStrokesForPage` + render on miss.
5. **Separate 2-thread render executor**; **only `ThumbnailCacheLogic` is unit-tested**, render + IO operational.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: Arbitrary-page read queries + repository methods

**Verifies:** library-and-tools.AC4.2 (supplies the off-thread data the renderer/cache need)

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`

**Implementation:**

`notebook.sq` — add (pages section / strokes section):
```sql
firstPageIdForNotebook:
SELECT id FROM page WHERE notebook_id = ? ORDER BY sort_order ASC, created_at ASC LIMIT 1;

countStrokesForPage:
SELECT count(*) FROM stroke WHERE page_id = ?;
```

`NotebookRepository.kt` — add (the stroke row → `Stroke` mapping mirrors `loadStrokes()`; reuse its existing mapper if one is factored out, else replicate the same `StrokeSerializer.decode` + field mapping):
```kotlin
fun firstPageIdForNotebook(notebookId: String): String? =
    db.notebookQueries.firstPageIdForNotebook(notebookId).executeAsOneOrNull()

fun countStrokesForPage(pageId: String): Long =
    db.notebookQueries.countStrokesForPage(pageId).executeAsOne()

/** Load strokes for an arbitrary page without changing the active page context. */
fun loadStrokesForPage(pageId: String): List<Stroke> =
    db.notebookQueries.getStrokesForPage(pageId).executeAsList().map { row ->
        Stroke(
            id = row.id,
            points = StrokeSerializer.decode(row.points),
            color = row.color.toInt(),
            penWidthMin = row.pen_width_min.toInt(),
            penWidthMax = row.pen_width_max.toInt()
        )
    }
```
(Check `loadStrokes()` for the exact existing mapping and match it — including any `.toInt()` conversions on the generated Long/Int columns.)

**Testing:** add `NotebookRepositoryTest` cases — create a notebook, add N strokes to its first page via the existing save path, assert `firstPageIdForNotebook` returns that page, `countStrokesForPage` == N, and `loadStrokesForPage(firstPage)` round-trips the strokes (ids + points). `firstPageIdForNotebook("nope")` → null.

**Verification:**
```bash
./gradlew :core:format:test
```

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq \
        core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt \
        core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt
git commit -m "feat(format): arbitrary-page reads for thumbnails (C3b)"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `NotebookStore` thumbnail-source + arbitrary-page wrappers

**Verifies:** library-and-tools.AC4.2 (off-main-thread access path)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`

**Implementation:** add a composite read (one background hop for the cheap cache-key inputs) + an arbitrary-page strokes read:

```kotlin
/** The cheap inputs to a notebook's first-page thumbnail cache key. */
data class ThumbnailSource(val pageId: String, val strokeCount: Long, val modifiedAt: Long)

fun thumbnailSource(notebookId: String, onResult: (ThumbnailSource?) -> Unit) {
    executor.execute {
        val src = runCatching {
            val r = repo ?: return@runCatching null
            val pageId = r.firstPageIdForNotebook(notebookId) ?: return@runCatching null
            ThumbnailSource(pageId, r.countStrokesForPage(pageId), r.modifiedAtOf(notebookId))
        }.onFailure { android.util.Log.e(TAG, "failed to read thumbnail source", it) }
            .getOrNull()
        poster { onResult(src) }
    }
}

fun loadStrokesForPage(pageId: String, onResult: (List<com.forestnote.core.ink.Stroke>) -> Unit) {
    executor.execute {
        val strokes = runCatching { repo?.loadStrokesForPage(pageId) ?: emptyList() }
            .onFailure { android.util.Log.e(TAG, "failed to load strokes for page", it) }
            .getOrDefault(emptyList())
        poster { onResult(strokes) }
    }
}
```

**Testing:** `NotebookStoreTest` — create a notebook + save a stroke through the store, assert `thumbnailSource` returns a non-null source with `strokeCount >= 1` and the page id, and `loadStrokesForPage(pageId)` returns the stroke.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt
git commit -m "feat(app): NotebookStore thumbnailSource + loadStrokesForPage (C3b)"
```
<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 3) -->

<!-- START_TASK_3 -->
### Task 3: `ThumbnailCacheLogic` (pure) + tests

**Verifies:** library-and-tools.AC4.2 (cache identity + eviction correctness)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailCacheLogic.kt`
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/ThumbnailCacheLogicTest.kt`

**Implementation:** pure functions, no Android, no IO:

```kotlin
package com.forestnote.app.notes

/** Pure cache-key + eviction logic for the Library thumbnail disk cache (AC4.2). */
object ThumbnailCacheLogic {
    /** "{pageId}_{strokeCount}_{modifiedAt}" — pageId is a ULID (no underscores). */
    fun key(pageId: String, strokeCount: Long, modifiedAt: Long): String =
        "${pageId}_${strokeCount}_${modifiedAt}"

    /** True if [fileBaseName] (no extension) belongs to [pageId] (same prefix). */
    fun belongsTo(pageId: String, fileBaseName: String): Boolean =
        fileBaseName.startsWith("${pageId}_")

    /**
     * Of [allBaseNames], the ones for [pageId] that are NOT [currentKey] — stale renders
     * to delete when a fresh thumbnail is written.
     */
    fun staleKeysFor(pageId: String, currentKey: String, allBaseNames: List<String>): List<String> =
        allBaseNames.filter { belongsTo(pageId, it) && it != currentKey }

    data class Entry(val name: String, val sizeBytes: Long, val lastModified: Long)

    /**
     * Given cache [entries] and a [capBytes] limit, the base names to delete (oldest
     * lastModified first) so the total falls at/under the cap. Empty if already under.
     */
    fun evictionList(entries: List<Entry>, capBytes: Long): List<String> {
        var total = entries.sumOf { it.sizeBytes }
        if (total <= capBytes) return emptyList()
        val victims = ArrayList<String>()
        for (e in entries.sortedBy { it.lastModified }) {
            if (total <= capBytes) break
            victims.add(e.name)
            total -= e.sizeBytes
        }
        return victims
    }
}
```

**Testing:**
- `key(...)` formats as expected; round-trips a known triple.
- `staleKeysFor` returns sibling renders of the same page but not the current key, and ignores other pages' files.
- `evictionList`: under cap → empty; over cap → drops oldest-first until at/under; ties handled deterministically.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailCacheLogic.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/ThumbnailCacheLogicTest.kt
git commit -m "feat(app): pure ThumbnailCacheLogic (key/purge/eviction) (C3b)"
```
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 4-7) -->

<!-- START_TASK_4 -->
### Task 4: `ThumbnailRenderer`

**Verifies:** library-and-tools.AC4.2 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailRenderer.kt`

**Implementation:** render strokes to a fixed 300×400 bitmap (white background, ink only, no template). Reuse `PageTransform` + `PressureCurve`:

```kotlin
package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.forestnote.core.ink.PageTransform
import com.forestnote.core.ink.PressureCurve
import com.forestnote.core.ink.Stroke

/**
 * Renders a page's ink to a small bitmap for Library cards (AC4.2). Pure draw loop
 * mirroring DrawView (kept separate so the editor's hot path is untouched). No template.
 */
object ThumbnailRenderer {
    const val WIDTH_PX = 300
    const val HEIGHT_PX = 400  // 3:4, matches the card tile

    fun render(strokes: List<Stroke>): Bitmap {
        val bmp = Bitmap.createBitmap(WIDTH_PX, HEIGHT_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val transform = PageTransform().apply { update(WIDTH_PX, HEIGHT_PX) }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            paint.color = stroke.color
            for (i in 1 until pts.size) {
                val prev = pts[i - 1]
                val curr = pts[i]
                val w = PressureCurve.width(curr.pressure, stroke.penWidthMin, stroke.penWidthMax)
                paint.strokeWidth = transform.toScreenSize(w)
                canvas.drawLine(
                    transform.toScreenX(prev.x), transform.toScreenY(prev.y),
                    transform.toScreenX(curr.x), transform.toScreenY(curr.y),
                    paint
                )
            }
        }
        return bmp
    }
}
```
(Thumbnails draw every stroke with normal compositing — highlighter `DST_OVER` subtlety from the editor isn't needed at card scale.)

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailRenderer.kt
git commit -m "feat(app): ThumbnailRenderer (strokes -> 300x400 bitmap) (C3b)"
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `ThumbnailCache` (disk)

**Verifies:** library-and-tools.AC4.2 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailCache.kt`

**Implementation:** a thin disk layer over `ThumbnailCacheLogic`. All methods are called on the render executor (off main):

```kotlin
package com.forestnote.app.notes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

/** PNG disk cache for Library thumbnails in cacheDir/thumbnails/. Off-main-thread only. */
class ThumbnailCache(cacheDir: File, private val capBytes: Long = 50L * 1024 * 1024) {
    private val dir = File(cacheDir, "thumbnails").apply { mkdirs() }

    /** Decode the bitmap for [key] if present (and touch it for LRU). Null on miss/error. */
    fun read(key: String): Bitmap? {
        val f = File(dir, "$key.png")
        if (!f.exists()) return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { BitmapFactory.decodeFile(f.absolutePath) }.getOrNull()
    }

    /** Write [bmp] under [key] for [pageId]; purge that page's stale renders; enforce the cap. */
    fun write(pageId: String, key: String, bmp: Bitmap) {
        runCatching {
            val baseNames = dir.listFiles()?.map { it.nameWithoutExtension }.orEmpty()
            ThumbnailCacheLogic.staleKeysFor(pageId, key, baseNames).forEach {
                File(dir, "$it.png").delete()
            }
            FileOutputStream(File(dir, "$key.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            enforceCap()
        }.onFailure { android.util.Log.e("ThumbnailCache", "write failed", it) }
    }

    private fun enforceCap() {
        val entries = dir.listFiles()?.map {
            ThumbnailCacheLogic.Entry(it.nameWithoutExtension, it.length(), it.lastModified())
        }.orEmpty()
        ThumbnailCacheLogic.evictionList(entries, capBytes).forEach { File(dir, "$it.png").delete() }
    }
}
```

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailCache.kt
git commit -m "feat(app): ThumbnailCache disk layer (purge + LRU) (C3b)"
```
<!-- END_TASK_5 -->

<!-- START_TASK_6 -->
### Task 6: `ThumbnailLoader` (orchestration + recycling guard)

**Verifies:** library-and-tools.AC4.2 (operational)

**Files:**
- Create: `app/notes/src/main/res/values/ids.xml` (a stable view-tag id)
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailLoader.kt`

**Implementation:**

`ids.xml`:
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <item name="tag_notebook_id" type="id" />
</resources>
```

`ThumbnailLoader` ties `NotebookStore` + cache + renderer + a dedicated executor. Recycling guard: tag the target `ImageView` with the notebook id; before applying a bitmap, confirm the tag still matches.

```kotlin
package com.forestnote.app.notes

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.widget.ImageView
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Loads Library notebook thumbnails: cheap cache-key read via the store, disk-cache hit,
 * else load strokes + render — all off the main thread. Recycling-safe via a view tag.
 */
class ThumbnailLoader(
    private val store: NotebookStore,
    cacheDir: File,
    private val placeholderRes: Int
) {
    private val cache = ThumbnailCache(cacheDir)
    private val renderExecutor: ExecutorService = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    fun load(notebookId: String, target: ImageView) {
        target.setTag(R.id.tag_notebook_id, notebookId)
        target.setImageResource(placeholderRes)
        store.thumbnailSource(notebookId) { src ->
            if (stale(target, notebookId)) return@thumbnailSource
            if (src == null) return@thumbnailSource  // empty notebook keeps the placeholder
            val key = ThumbnailCacheLogic.key(src.pageId, src.strokeCount, src.modifiedAt)
            renderExecutor.execute {
                val cached = cache.read(key)
                if (cached != null) {
                    apply(target, notebookId, cached)
                } else {
                    store.loadStrokesForPage(src.pageId) { strokes ->
                        renderExecutor.execute {
                            val bmp = ThumbnailRenderer.render(strokes)
                            cache.write(src.pageId, key, bmp)
                            apply(target, notebookId, bmp)
                        }
                    }
                }
            }
        }
    }

    fun shutdown() { renderExecutor.shutdown() }

    private fun apply(target: ImageView, notebookId: String, bmp: Bitmap) {
        main.post { if (!stale(target, notebookId)) target.setImageBitmap(bmp) }
    }

    private fun stale(target: ImageView, notebookId: String): Boolean =
        target.getTag(R.id.tag_notebook_id) != notebookId
}
```

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/res/values/ids.xml \
        app/notes/src/main/kotlin/com/forestnote/app/notes/ThumbnailLoader.kt
git commit -m "feat(app): ThumbnailLoader with cache + recycling guard (C3b)"
```
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: Bind thumbnails in the Library

**Verifies:** library-and-tools.AC4.2 (operational)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookCardAdapter.kt`
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt`

**Implementation:**
- Give `NotebookCardAdapter` a `ThumbnailLoader` (constructor param). In `onBindViewHolder`, call `loader.load(card.id, holder.thumb)` where `holder.thumb` is the `card_thumb` ImageView. The loader sets the placeholder first, then swaps in the rendered bitmap when ready (recycling-safe).
- In `LibraryView.show`, construct the `ThumbnailLoader(store, host.context.cacheDir, R.color.card_placeholder)` (or a placeholder drawable) and pass it to the adapter. Call `loader.shutdown()` from `LibraryView.hide()`.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
./gradlew test
```
Expected: builds; all unit tests pass. **On-device (manual):** open the Library → cards render real first-page ink thumbnails that appear asynchronously without blocking scroll; an empty notebook shows the placeholder; edit a notebook then return to the Library → its thumbnail updates (key changes via `modifiedAt`/`strokeCount`); relaunch the app → cached thumbnails load without re-render; the `thumbnails/` dir stays under ~50 MB (older entries evicted).

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookCardAdapter.kt \
        app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt
git commit -m "feat(app): bind real ink thumbnails in the Library (C3b)"
```
<!-- END_TASK_7 -->

<!-- END_SUBCOMPONENT_C -->
