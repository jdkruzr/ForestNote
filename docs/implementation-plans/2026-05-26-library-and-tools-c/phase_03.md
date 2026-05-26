# Library & Tools — Area C Implementation Plan (Phase 3 / C3a)

**Goal:** The Library — a full-screen overlay that lists every notebook as a 4-column card grid with placeholder tiles, opens a notebook on tap, shows Properties on long-press, and has a header bar (Settings, +Notebook active; Recycle Bin / Select / +Folder greyed for now). Real ink thumbnails come in C3b.

**Architecture:** The Library is a **full-screen overlay `View`** (`LibraryView`), mirroring the existing `SettingsView` — **not** a second Activity. This is a hard project invariant: a 2nd Activity opens a 2nd DB connection and breaks the single-writer `NotebookStore` serialization (see `app/notes/CLAUDE.md`). `LibraryView` attaches to `android.R.id.content`, reuses `MainActivity`'s single `NotebookStore`, and on card-tap calls `MainActivity.goToNotebook(id)` in-place then hides. The Library is reached by repointing the existing notebook-label tap; the old `showNotebookPicker` (list/switch + New Notebook + Settings entry) is fully superseded by the Library header and is removed here.

**Tech Stack:** Kotlin, Android Views (no Compose), Material 3, `androidx.recyclerview` (added this phase — the app's first RecyclerView), SQLDelight, JUnit4 + `kotlin.test`.

**Scope:** Phase 3 of 7 (area C). Depends on Phase 1 (folder_id column exists; harmless here) and the existing A9 Properties dialog + `SettingsView` overlay.

**Codebase verified:** 2026-05-26 — single-Activity app (`MainActivity` is the LAUNCHER; notebooks switch in-place via `goToNotebook`/`store.switchNotebook`). `SettingsView` overlay pattern: `show(host, store, onClose)` / `hide()` / `isShowing`, attached to `android.R.id.content`, dismissed via `onBackPressed`. `openNotebookProperties(NotebookMeta, canDelete)` and `promptNewNotebook()` are standalone and reusable. `openSettings()` shows `SettingsView`. No RecyclerView, no Tokens class, no relative-time/datestamp helper today. Default notebook name is "Untitled" (no auto-datestamp). navbar/toolbar are `30dp`. Theme `Theme.Material3.Light.NoActionBar`; colors black/white/gray_light/gray_dark. `NotebookStore` is async/callback (`listNotebooks`, `createNotebook`, `switchNotebook`, `countPages`), single background thread, posts to main via `poster`.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC4: Library
- **library-and-tools.AC4.2 Success:** Library shows a grid of cards (4 across at Mini width). Folder cards have a folder glyph + child count. Notebook cards have a thumbnail rendering of the first page (no template, just ink).
- **library-and-tools.AC4.3 Success:** Notebook card footer shows: monospaced datestamp prefix (if name matches `YYYYMMDD_HHMMSS …` pattern), the rest of the name in bold, and "Np · 2h ago" meta on a third line.
- **library-and-tools.AC4.4 Success:** Tapping a folder card enters that folder. Tapping a notebook card opens it in the editor.
- **library-and-tools.AC4.5 Success:** Long-pressing (~500 ms) a notebook or folder card opens its Properties dialog (rename, delete, metadata).
- **library-and-tools.AC4.6 Success:** Header shows: Settings cell, Recycle Bin cell (with numeric badge when non-empty), back chevron (when not at root), breadcrumb, item count, and right-side actions (Select / +Folder / +Notebook). Header height matches the editor nav bar height (~30 dp).

> Scope notes for C3a: **folders do not exist in the UI yet** (C4) — AC4.2's folder-card clause and AC4.4's folder-tap clause are out of scope here; only notebook cards + notebook-tap are built. AC4.6's Recycle Bin badge, back chevron, breadcrumb navigation, Select, and +Folder are rendered as **greyed/static placeholders** this phase (wired in C4/C5/D/E). The notebook thumbnail in AC4.2 is a **placeholder tile** (C3b swaps in real ink). **AC4.1 (launch into Library) is deferred to C6.** Automated tests cover only AC4.3's pure formatting; the rest is verified operationally on-device.

---

## Decisions (confirmed with the human, diverge from the design's literal wording)

1. **`LibraryView` overlay, not `LibraryActivity`** — single-writer DB invariant (`app/notes/CLAUDE.md`). Mirrors `SettingsView`.
2. **C3a repoints the notebook label to the Library and removes `showNotebookPicker`** — the picker's three jobs all move into the Library header. This pulls the design's "delete picker" forward from C6; C6 is reduced to cold-launch-into-Library logic.
3. **Plain `dp` chrome, no `Tokens` object** — matches the existing `30dp` navbar; the design's `mm()` equals plain dp on the Mini. A unified token system is deferred to the AC9 work (outside area C).

---

<!-- START_SUBCOMPONENT_A (tasks 1-3) -->

<!-- START_TASK_1 -->
### Task 1: Add the RecyclerView dependency (infrastructure)

**Verifies:** None (infrastructure — operational verification only).

**Files:**
- Modify: `gradle/libs.versions.toml`
- Modify: `app/notes/build.gradle.kts`

**Step 1: Add the version + library to `libs.versions.toml`**

Under `[versions]` add:
```toml
androidx-recyclerview = "1.3.2"
```
Under `[libraries]` (near the other AndroidX entries) add:
```toml
androidx-recyclerview = { module = "androidx.recyclerview:recyclerview", version.ref = "androidx-recyclerview" }
```

**Step 2: Add it to the app module**

In `app/notes/build.gradle.kts`, inside `dependencies { }`:
```kotlin
implementation(libs.findLibrary("androidx-recyclerview").get())
```
(Also add `implementation(libs.findLibrary("material").get())` if Material components are not already on the app classpath and the header uses any — check first; the theme is Material3 but the app currently lists only ink/format deps. Plain `ImageView`/`TextView`/`RecyclerView` need only recyclerview + core, so Material is optional. Prefer NOT adding Material unless a component requires it.)

**Step 3: Verify operationally**
```bash
./gradlew :app:notes:assembleDebug
```
Expected: resolves the new dependency and builds.

**Commit:**
```bash
git add gradle/libs.versions.toml app/notes/build.gradle.kts
git commit -m "build(app): add androidx.recyclerview for the Library grid (C3a)"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `listNotebookCards` query + `NotebookCard` type + repository method

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.3 (supplies the per-card data: identity, name, timestamps, page count)

**Files:**
- Modify: `core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq`
- Modify: `core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt`
- Modify: `core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt`

**Implementation:**

Add a single query that returns each notebook with its page count (avoids an N+1 of `countPages` per card, which would flicker during RecyclerView recycling). In `notebook.sq`, in the notebook section:

```sql
listNotebookCards:
SELECT
    n.id,
    n.name,
    n.created_at,
    n.modified_at,
    (SELECT count(*) FROM page WHERE page.notebook_id = n.id) AS page_count
FROM notebook n
ORDER BY n.sort_order ASC, n.created_at ASC;
```

In `NotebookRepository.kt`, add the domain type (next to `NotebookMeta`):
```kotlin
data class NotebookCard(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val pageCount: Long
)
```
and the method (the generated query exposes a row type with `id, name, created_at, modified_at, page_count`):
```kotlin
fun listNotebookCards(): List<NotebookCard> =
    db.notebookQueries.listNotebookCards().executeAsList()
        .map { NotebookCard(it.id, it.name, it.created_at, it.modified_at, it.page_count) }
```

**Testing:** Add a `NotebookRepositoryTest` case: create two notebooks, add pages to one via `createPage`, assert `listNotebookCards()` returns both with the correct `pageCount` and ordering (AC4.2/4.3 data). Use `forTesting(driver)`.

**Verification:**
```bash
./gradlew :core:format:test
```
Expected: new test passes; existing pass.

**Commit:**
```bash
git add core/format/src/main/sqldelight/com/forestnote/core/format/notebook.sq \
        core/format/src/main/kotlin/com/forestnote/core/format/NotebookRepository.kt \
        core/format/src/test/kotlin/com/forestnote/core/format/NotebookRepositoryTest.kt
git commit -m "feat(format): listNotebookCards query + NotebookCard type (C3a)"
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: `NotebookStore.listNotebookCards` off-thread wrapper

**Verifies:** library-and-tools.AC4.2 (the off-main-thread load path the Library uses)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt`
- Modify: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt`

**Implementation:** mirror `listNotebooks`:
```kotlin
fun listNotebookCards(onResult: (List<NotebookCard>) -> Unit) {
    executor.execute {
        val cards = runCatching { repo?.listNotebookCards() ?: emptyList() }
            .onFailure { android.util.Log.e(TAG, "failed to list notebook cards", it) }
            .getOrDefault(emptyList())
        poster { onResult(cards) }
    }
}
```
Import `com.forestnote.core.format.NotebookCard`.

**Testing:** Add a `NotebookStoreTest` case (existing harness: real single-thread executor + draining): create a notebook through the store, then `listNotebookCards` returns it with `pageCount >= 1`.

**Verification:**
```bash
./gradlew :app:notes:test
```
Expected: new test passes; existing pass.

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookStore.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookStoreTest.kt
git commit -m "feat(app): NotebookStore.listNotebookCards wrapper (C3a)"
```
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 4-5) -->

<!-- START_TASK_4 -->
### Task 4: `NotebookNameParser` (pure) + test

**Verifies:** library-and-tools.AC4.3

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookNameParser.kt`
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookNameParserTest.kt`

**Implementation:** a pure `object` (view-presentation logic, lives in `app:notes` beside `PageNavigationLogic`):

```kotlin
package com.forestnote.app.notes

/**
 * Splits a notebook name into an optional leading datestamp (YYYYMMDD_HHMMSS) and
 * the remaining display name, for the Library card footer (AC4.3). Pure; no Android.
 */
object NotebookNameParser {
    private val DATESTAMP = Regex("""^(\d{8}_\d{6})(?:\s+(.*))?$""")

    data class Split(val datestamp: String?, val rest: String)

    /**
     * If [name] begins with a `YYYYMMDD_HHMMSS` token, returns that token as [Split.datestamp]
     * and the trailing text (trimmed) as [Split.rest]. Otherwise datestamp is null and rest is
     * the whole name.
     */
    fun split(name: String): Split {
        val m = DATESTAMP.matchEntire(name.trim()) ?: return Split(null, name.trim())
        val stamp = m.groupValues[1]
        val rest = m.groupValues.getOrElse(2) { "" }.trim()
        return Split(stamp, rest)
    }
}
```

**Testing:** name `"20260524_091500 Meeting notes"` → datestamp `"20260524_091500"`, rest `"Meeting notes"`; name `"Untitled"` → datestamp null, rest `"Untitled"`; name exactly `"20260524_091500"` → datestamp set, rest `""`; a near-miss like `"2026_05"` → datestamp null.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookNameParser.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/NotebookNameParserTest.kt
git commit -m "feat(app): NotebookNameParser for card datestamp split (C3a)"
```
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: `RelativeTime` (pure) + test

**Verifies:** library-and-tools.AC4.3

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/RelativeTime.kt`
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/RelativeTimeTest.kt`

**Implementation:** pure `object` with an injectable `now` for deterministic tests:

```kotlin
package com.forestnote.app.notes

/** Compact relative-time formatting for the Library card meta line (AC4.3). Pure. */
object RelativeTime {
    private const val MINUTE = 60_000L
    private const val HOUR = 60 * MINUTE
    private const val DAY = 24 * HOUR

    /** "just now" / "5 min ago" / "2h ago" / "3d ago" / "5w ago". Future/0 → "just now". */
    fun format(epochMs: Long, now: Long): String {
        val d = now - epochMs
        return when {
            d < MINUTE -> "just now"
            d < HOUR -> "${d / MINUTE} min ago"
            d < DAY -> "${d / HOUR}h ago"
            d < 7 * DAY -> "${d / DAY}d ago"
            else -> "${d / (7 * DAY)}w ago"
        }
    }
}
```

**Testing:** with a fixed `now`: 30 s ago → "just now"; 5 min ago → "5 min ago"; 2 h ago → "2h ago"; 3 d ago → "3d ago"; 2 w ago → "2w ago"; a future timestamp → "just now". (Pick exact deltas so boundaries are unambiguous.)

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/RelativeTime.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/RelativeTimeTest.kt
git commit -m "feat(app): RelativeTime formatter for card meta (C3a)"
```
<!-- END_TASK_5 -->

<!-- END_SUBCOMPONENT_B -->

<!-- START_SUBCOMPONENT_C (tasks 6-9) -->

<!-- START_TASK_6 -->
### Task 6: Library layouts + placeholder tile

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.6 (operational)

**Files:**
- Create: `app/notes/src/main/res/layout/view_library.xml`
- Create: `app/notes/src/main/res/layout/item_notebook_card.xml`
- Modify: `app/notes/src/main/res/values/colors.xml` (add a `card_placeholder` color)

**Implementation:**

`colors.xml` — add:
```xml
<color name="card_placeholder">#FFF2F2F2</color>
```

`view_library.xml` — opaque full-screen overlay: a `30dp` header row (matching navbar) over a RecyclerView. Header cells, left→right: Settings gear (`@id/btn_library_settings`), back chevron (`@id/btn_library_back`, `visibility="gone"` for now), breadcrumb TextView (`@id/text_breadcrumb`, text "Library"), item-count TextView (`@id/text_item_count`), then right-aligned actions: Select (`@id/btn_library_select`, disabled), +Folder (`@id/btn_library_add_folder`, disabled), +Notebook (`@id/btn_library_add_notebook`, enabled), Recycle Bin (`@id/btn_library_recycle_bin`, disabled). Use plain `ImageButton`/`TextView` with `android:background="@color/white"`, black tint/text, `30dp` header height, `1dp` black divider below (match `activity_main.xml`). Use existing drawables where available (`ic_arrow_left` for the back chevron); for Settings/+Notebook/+Folder/Recycle-Bin/Select either reuse an existing icon or add simple vector drawables (create minimal `ic_*` vectors in `res/drawable/` as needed — list each created drawable in the commit). Disabled cells: `android:enabled="false"` + `android:alpha="0.3"`.

```xml
<?xml version="1.0" encoding="utf-8"?>
<LinearLayout xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:orientation="vertical"
    android:background="@color/white"
    android:clickable="true"
    android:focusable="true">

    <LinearLayout
        android:id="@+id/library_header"
        android:layout_width="match_parent"
        android:layout_height="30dp"
        android:orientation="horizontal"
        android:gravity="center_vertical"
        android:background="@color/white">
        <!-- Settings, back chevron(gone), breadcrumb, count, spacer, Select(disabled),
             +Folder(disabled), +Notebook, Recycle Bin(disabled) per the description above -->
    </LinearLayout>

    <View
        android:layout_width="match_parent"
        android:layout_height="1dp"
        android:background="@color/black" />

    <androidx.recyclerview.widget.RecyclerView
        android:id="@+id/library_grid"
        android:layout_width="match_parent"
        android:layout_height="0dp"
        android:layout_weight="1"
        android:padding="8dp"
        android:clipToPadding="false" />
</LinearLayout>
```

`item_notebook_card.xml` — a 3:4 placeholder tile + footer (datestamp / bold name / meta). Use a fixed-aspect tile: an `ImageView` (`@id/card_thumb`, `background="@color/card_placeholder"`) whose height is set to 4/3 of its width in the adapter (or use a simple fixed dp tile sized by the grid). Footer: monospaced datestamp TextView (`@id/card_datestamp`, `typeface="monospace"`, `gone` when no datestamp), bold name TextView (`@id/card_name`, `textStyle="bold"`, single line, ellipsize end), meta TextView (`@id/card_meta`, e.g. "3p · 2h ago"). Card text sizes ~12sp; keep it e-ink legible.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```
Expected: layouts compile (resource references resolve).

**Commit:**
```bash
git add app/notes/src/main/res/layout/view_library.xml \
        app/notes/src/main/res/layout/item_notebook_card.xml \
        app/notes/src/main/res/values/colors.xml \
        app/notes/src/main/res/drawable/   # any new ic_* vectors
git commit -m "feat(app): Library overlay + notebook card layouts (C3a)"
```
<!-- END_TASK_6 -->

<!-- START_TASK_7 -->
### Task 7: `NotebookCardAdapter`

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.3, library-and-tools.AC4.4, library-and-tools.AC4.5 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookCardAdapter.kt`

**Implementation:** a `RecyclerView.Adapter<NotebookCardAdapter.VH>` over `List<NotebookCard>` with tap + long-press callbacks. Bind using `NotebookNameParser.split(card.name)` and `RelativeTime.format(card.modifiedAt, System.currentTimeMillis())`; meta line is `"${card.pageCount}p · ${relative}"`. Show/hide the datestamp TextView based on the split. Thumbnail stays the placeholder background (C3b replaces it).

```kotlin
package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.NotebookCard

class NotebookCardAdapter(
    private val onOpen: (NotebookCard) -> Unit,
    private val onLongPress: (NotebookCard) -> Unit
) : RecyclerView.Adapter<NotebookCardAdapter.VH>() {

    private val items = mutableListOf<NotebookCard>()

    fun submit(cards: List<NotebookCard>) {
        items.clear()
        items.addAll(cards)
        notifyDataSetChanged()
    }

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val datestamp: TextView = view.findViewById(R.id.card_datestamp)
        val name: TextView = view.findViewById(R.id.card_name)
        val meta: TextView = view.findViewById(R.id.card_meta)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_notebook_card, parent, false)
        return VH(v)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val card = items[position]
        val split = NotebookNameParser.split(card.name)
        if (split.datestamp != null) {
            holder.datestamp.visibility = View.VISIBLE
            holder.datestamp.text = split.datestamp
            holder.name.text = split.rest.ifEmpty { " " }
        } else {
            holder.datestamp.visibility = View.GONE
            holder.name.text = card.name
        }
        holder.meta.text = "${card.pageCount}p · ${RelativeTime.format(card.modifiedAt, System.currentTimeMillis())}"
        holder.itemView.setOnClickListener { onOpen(card) }
        holder.itemView.setOnLongClickListener { onLongPress(card); true }
    }

    override fun getItemCount(): Int = items.size
}
```

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/NotebookCardAdapter.kt
git commit -m "feat(app): NotebookCardAdapter for the Library grid (C3a)"
```
<!-- END_TASK_7 -->

<!-- START_TASK_8 -->
### Task 8: `LibraryView` overlay

**Verifies:** library-and-tools.AC4.2, library-and-tools.AC4.4, library-and-tools.AC4.5, library-and-tools.AC4.6 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt`

**Implementation:** mirror `SettingsView`'s lifecycle (`show`/`hide`/`isShowing`, attach to a host `ViewGroup`). Take a `Callbacks` bundle so `MainActivity` keeps owning navigation:

```kotlin
package com.forestnote.app.notes

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.forestnote.core.format.NotebookCard

/**
 * Full-screen Library overlay (library-and-tools C3a). Like [SettingsView], an overlay
 * View (not an Activity) so it reuses MainActivity's single NotebookStore. Lists every
 * notebook as a 4-column card grid; tap opens, long-press shows Properties. Folders,
 * thumbnails, Select, Recycle Bin, breadcrumb nav arrive in later phases.
 */
class LibraryView {

    data class Callbacks(
        val onOpenNotebook: (NotebookCard) -> Unit,
        val onNotebookProperties: (NotebookCard) -> Unit,
        val onNewNotebook: () -> Unit,
        val onOpenSettings: () -> Unit
    )

    private var root: View? = null
    private var host: ViewGroup? = null
    val isShowing: Boolean get() = root != null

    fun show(host: ViewGroup, store: NotebookStore, callbacks: Callbacks) {
        if (isShowing) return
        this.host = host
        val view = LayoutInflater.from(host.context).inflate(R.layout.view_library, host, false)
        host.addView(view)
        root = view

        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        grid.layoutManager = GridLayoutManager(host.context, COLUMNS)
        val adapter = NotebookCardAdapter(
            onOpen = callbacks.onOpenNotebook,
            onLongPress = callbacks.onNotebookProperties
        )
        grid.adapter = adapter

        view.findViewById<View>(R.id.btn_library_settings).setOnClickListener { callbacks.onOpenSettings() }
        view.findViewById<View>(R.id.btn_library_add_notebook).setOnClickListener { callbacks.onNewNotebook() }

        store.listNotebookCards { cards ->
            adapter.submit(cards)
            view.findViewById<TextView>(R.id.text_item_count).text = "${cards.size}"
        }
    }

    /** Re-query and rebind (call after create/rename/delete to reflect changes). */
    fun refresh(store: NotebookStore) {
        val view = root ?: return
        val grid = view.findViewById<RecyclerView>(R.id.library_grid)
        val adapter = grid.adapter as? NotebookCardAdapter ?: return
        store.listNotebookCards { cards ->
            adapter.submit(cards)
            view.findViewById<TextView>(R.id.text_item_count).text = "${cards.size}"
        }
    }

    fun hide() {
        root?.let { host?.removeView(it) }
        root = null
        host = null
    }

    private companion object { const val COLUMNS = 4 }
}
```

> **On the fixed `COLUMNS = 4`:** AC4.2 says "4 across *at Mini width*." A fixed span of 4 is intentional for area C — the design's AC9.2 (true cross-device physical sizing) is explicitly deferred outside area C, and this plan's "plain dp, no Tokens" decision means we don't derive span from width here. 4 columns is correct on the Mini (the only device under test); revisit a width-derived span count if/when the Full device sizing work (AC9) lands. Do not treat this as a TODO inside C3a.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt
git commit -m "feat(app): LibraryView overlay with notebook grid (C3a)"
```
<!-- END_TASK_8 -->

<!-- START_TASK_9 -->
### Task 9: Wire `LibraryView` into `MainActivity`; remove `showNotebookPicker`

**Verifies:** library-and-tools.AC4.4, library-and-tools.AC4.5, library-and-tools.AC4.6 (operational)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**

1. Add a field `private val libraryView = LibraryView()` (alongside `settingsView`).
2. Repoint the notebook label (currently `btnNotebooks.setOnClickListener { showNotebookPicker() }`, MainActivity ~line 120) to `{ openLibrary() }`.
3. Add `openLibrary()` / `closeLibrary()` mirroring `openSettings()`/`closeSettings()`:
```kotlin
private fun openLibrary() {
    if (libraryView.isShowing) return
    val content = findViewById<android.view.ViewGroup>(android.R.id.content)
    libraryView.show(content, store, LibraryView.Callbacks(
        onOpenNotebook = { card -> libraryView.hide(); goToNotebook(card.id) },
        onNotebookProperties = { card ->
            // Build NotebookMeta from the card to reuse the A9 dialog (AC4.5).
            openNotebookProperties(
                NotebookMeta(card.id, card.name, card.createdAt, card.modifiedAt),
                canDelete = true
            )
        },
        onNewNotebook = { promptNewNotebook() },   // on create, goToNotebook closes editor-side; see below
        onOpenSettings = { openSettings() }
    ))
}

private fun closeLibrary() { libraryView.hide() }
```
4. `onBackPressed`: add a branch so a showing Library is dismissed first (before Settings/super), e.g.:
```kotlin
if (libraryView.isShowing) { closeLibrary(); return }
```
5. `promptNewNotebook()` currently calls `goToNotebook(newId)` on create. When invoked from the Library, the new notebook should open in the editor — so also hide the Library if showing. Change the create callback to `{ newId -> libraryView.hide(); goToNotebook(newId) }` (safe no-op hide when not showing). After a rename/delete from the Properties dialog while the Library is open, call `libraryView.refresh(store)` in those callbacks so the grid updates (extend `openNotebookProperties` rename/delete `onDone` to also refresh the Library when `libraryView.isShowing`).
6. **Remove `showNotebookPicker()`** entirely (the function and any remaining references). Its three roles are now in the Library header (list/switch = the grid; New Notebook = +Notebook; Settings = the gear). Remove now-unused imports (`ListView`, `ArrayAdapter`) if nothing else uses them.

**Note on the canDelete rule:** the picker passed `canDelete = notebooks.size > 1`. The repository already refuses to leave zero notebooks (deleting the last bootstraps a fresh one), so `canDelete = true` is safe here; the Library grid doesn't pre-count for this gate. (If you prefer to preserve the old guard, pass `canDelete` based on the current card count held by the adapter.)

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
./gradlew test
```
Expected: builds; all unit tests pass. **Then on-device (manual, per project convention):** launch app → tap the notebook label → Library overlay shows a 4-col grid of notebook cards with datestamp/name/meta + placeholder tiles (AC4.2/4.3); tap a card → opens that notebook in the editor and dismisses the Library (AC4.4); long-press a card → Properties dialog (AC4.5); +Notebook → New Notebook dialog → creates + opens; Settings gear → Settings overlay (AC4.6); Recycle Bin / Select / +Folder appear greyed; system Back from the Library returns to the editor. No editor regression.

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt
git commit -m "feat(app): open Library overlay from notebook label; remove picker (C3a)"
```
<!-- END_TASK_9 -->

<!-- END_SUBCOMPONENT_C -->
