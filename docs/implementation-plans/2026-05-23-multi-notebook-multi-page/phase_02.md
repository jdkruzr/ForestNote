# Multiple Notebooks with Multiple Pages — Phase 2: Multi-page navigation UI

**Goal:** Let the user move between pages of the current notebook — prev/next buttons + a tappable "N / M" indicator + a page picker (New/Delete Page) — and restore the last-viewed page on launch.

**Architecture:** A thin navigation bar above the canvas holds prev / "N / M" / next. The index math, bounds, label, and can-delete rule live in a pure `PageNavigationLogic` (mirrors `ToolSelectionLogic`). `MainActivity` caches the current notebook's page list + active id (from `store.listPages`), and a `goToPage(id)` idiom does `drawView.clearAll()` → `store.switchPage(id){ mergeLoadedStrokes + fullRefresh }` → refresh the indicator. The page picker reuses the `showClearConfirmation` AlertDialog pattern. Uses only the Phase 1 store API (`listPages`, `switchPage`, `createPage`, `deletePage`, `load`).

**Tech Stack:** Kotlin, Android Views, JUnit 4, `kotlin.test`.

**Scope:** Phase 2 of 3.

**Codebase verified:** 2026-05-23 (codebase-investigator). Note: there is NO `strings.xml`; existing toolbar labels/contentDescriptions are inline in `toolbar.xml` — stay consistent (inline strings) for the new nav controls.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### multi-notebook-multi-page.AC5: Reopen where you left off (UI wiring)
- **multi-notebook-multi-page.AC5.1 Success:** on launch the repository restores the active notebook+page from `app_state` (not always the first). *(Storage proven in Phase 1; this phase wires the launch load + indicator to it.)*

### multi-notebook-multi-page.AC6: Page navigation UI
- **multi-notebook-multi-page.AC6.1 Success:** prev/next move between pages of the current notebook in sort order; the indicator shows "N / M".
- **multi-notebook-multi-page.AC6.2 Success:** navigation bounds are correct — `canPrev` false on the first page, `canNext` false on the last (pure `PageNavigationLogic`).
- **multi-notebook-multi-page.AC6.3 Success:** the page picker lists pages and switches on tap; New Page and Delete Page work; Delete is unavailable when only one page exists.
- **multi-notebook-multi-page.AC6.4 Edge (on-device):** switching a page commits any in-progress stroke first and refreshes the canvas — no lost stroke, no e-ink ghosting.

---

## Context for the engineer

- Depends on Phase 1 (`NotebookStore` exposes `listPages(onResult: (List<PageMeta>, activePageId) -> Unit)`, `switchPage(id, onLoaded)`, `createPage(onCreated)`, `deletePage(id, onDone)`, and the existing `load(onLoaded)` which loads the repo's current page).
- `DrawView` already provides everything for a page swap: `clearAll()` (clears list + bitmap, nulls `currentStroke`, re-pushes background), `mergeLoadedStrokes(strokes)` (replays a page's ink), `fullRefresh()` (re-composites the overlay to clear e-ink ghosting). No DrawView change is required for AC6.4 — a toolbar/nav tap only fires after the stylus has lifted from the canvas (ACTION_UP already finalized + saved the stroke), and `clearAll()` nulls any residual `currentStroke`.
- `ToolBar` callback-setter pattern (`setOnClearClicked`) and `MainActivity.showClearConfirmation()` AlertDialog are the models for wiring and the picker. Nav-bar views are looked up in `MainActivity` directly (like `drawView`/`toolBar`), keeping pure logic in `PageNavigationLogic`.
- `app:notes` tests: JVM only, no Robolectric — so only `PageNavigationLogic` is unit-tested here; the nav bar, picker, and ghosting are verified on-device (added to `docs/manual-test-checklist.md`).

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: `PageNavigationLogic` (pure)

**Verifies:** multi-notebook-multi-page.AC6.2 (and supplies the math for AC6.1/AC6.3; tested in Task 2)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/PageNavigationLogic.kt`

**Implementation:** A pure, Android-free object/class (mirror `ToolSelectionLogic`'s style and the `// pattern: Functional Core` classification). Operate on a page-id list + the active id:
```kotlin
// pattern: Functional Core
object PageNavigationLogic {
    /** 0-based index of the active page, or -1 if absent/empty. */
    fun indexOf(pageIds: List<String>, activeId: String): Int = pageIds.indexOf(activeId)

    fun canPrev(pageIds: List<String>, activeId: String): Boolean = indexOf(pageIds, activeId) > 0

    fun canNext(pageIds: List<String>, activeId: String): Boolean {
        val i = indexOf(pageIds, activeId)
        return i in 0 until pageIds.lastIndex
    }

    fun prevId(pageIds: List<String>, activeId: String): String? =
        if (canPrev(pageIds, activeId)) pageIds[indexOf(pageIds, activeId) - 1] else null

    fun nextId(pageIds: List<String>, activeId: String): String? =
        if (canNext(pageIds, activeId)) pageIds[indexOf(pageIds, activeId) + 1] else null

    /** "N / M" label, 1-based; "0 / 0" when empty, "? / M" if active not found. */
    fun label(pageIds: List<String>, activeId: String): String {
        if (pageIds.isEmpty()) return "0 / 0"
        val i = indexOf(pageIds, activeId)
        val n = if (i >= 0) i + 1 else 0
        return "$n / ${pageIds.size}"
    }

    /** A notebook must keep ≥1 page, so delete is only allowed when >1 exists. */
    fun canDelete(pageIds: List<String>): Boolean = pageIds.size > 1
}
```

**Verification:**
Run: `./gradlew :app:notes:compileDebugKotlin`
Expected: compiles.

**Commit:** (defer to end of Subcomponent A)
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `PageNavigationLogic` tests

**Verifies:** multi-notebook-multi-page.AC6.2

**Files:**
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/PageNavigationLogicTest.kt` (unit)

**Testing** (JUnit 4 / `kotlin.test`, assertion-with-message, mirror `ToolBarLogicTest`). Use simple id lists like `listOf("a","b","c")`:
- **AC6.2 bounds — first page:** active = "a" → `canPrev` false, `canNext` true, `prevId` null, `nextId` "b".
- **AC6.2 bounds — last page:** active = "c" → `canNext` false, `canPrev` true, `nextId` null, `prevId` "b".
- **AC6.2 middle:** active = "b" → both true; `prevId` "a", `nextId` "c".
- **label:** `["a","b","c"]`, active "b" → "2 / 3"; single page `["a"]`, active "a" → "1 / 1"; empty list → "0 / 0".
- **canDelete:** size 1 → false; size 2 → true.
- **Edge:** active id not in list → `indexOf` -1, `canPrev`/`canNext` false, `prevId`/`nextId` null, `label` "0 / N".

**Verification:**
Run: `./gradlew :app:notes:test`
Expected: all tests pass.

**Commit:** `feat(notes): PageNavigationLogic for page indicator + prev/next bounds (+tests)`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->

<!-- START_TASK_3 -->
### Task 3: Navigation bar layout

**Files:**
- Create: `app/notes/src/main/res/layout/navbar.xml`
- Modify: `app/notes/src/main/res/layout/activity_main.xml`

**Implementation:**
- Create `navbar.xml`: a horizontal `LinearLayout` (match_parent width, ~48dp height, centered) with three children, styled to match `toolbar.xml`'s inline approach (no `strings.xml`):
  - `ImageButton` `@+id/btn_prev_page` (use an existing/simple chevron drawable or `android:src="@android:drawable/ic_media_previous"`; `contentDescription="Previous page"`).
  - `TextView` `@+id/page_indicator`, `layout_weight=1`, `gravity=center`, `clickable=true`, `focusable=true`, initial `text="1 / 1"` (tapping opens the page picker).
  - `ImageButton` `@+id/btn_next_page` (`ic_media_next`; `contentDescription="Next page"`).
- Modify `activity_main.xml`: add `<include layout="@layout/navbar" />` as the FIRST child of the root vertical `LinearLayout` (above the `DrawView`), so the order is: nav bar (wrap/48dp) → DrawView (weight=1) → toolbar (60dp).

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds; layout inflates (verified at runtime on-device).

**Commit:** (defer to end of phase)
<!-- END_TASK_3 -->

<!-- START_TASK_4 -->
### Task 4: `MainActivity` — nav wiring, goToPage, page picker, launch restore

**Verifies:** multi-notebook-multi-page.AC5.1, AC6.1, AC6.3, AC6.4 (UI; verified on-device — see checklist task)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**
- Add fields: `private lateinit var pageIndicator: TextView`, and a cache `private var pageIds: List<String> = emptyList()`, `private var activePageId: String = ""`.
- In `onCreate`, after the existing `drawView`/`toolBar` wiring:
  - Look up `pageIndicator`, `btn_prev_page`, `btn_next_page`.
  - Wire listeners using the cache + `PageNavigationLogic`:
    ```kotlin
    btnPrev.setOnClickListener {
        PageNavigationLogic.prevId(pageIds, activePageId)?.let { goToPage(it) }
    }
    btnNext.setOnClickListener {
        PageNavigationLogic.nextId(pageIds, activePageId)?.let { goToPage(it) }
    }
    pageIndicator.setOnClickListener { showPagePicker() }
    ```
  - Replace the bare `store.load { … }` restore with one that also seeds the indicator:
    ```kotlin
    store.load { strokes ->
        drawView.mergeLoadedStrokes(strokes) // active page restored from app_state (AC5.1)
        refreshPageIndicator()
    }
    ```
- Add helpers:
  ```kotlin
  /** Reload the current notebook's page list + active id; update the indicator. */
  private fun refreshPageIndicator() {
      store.listPages { pages, activeId ->
          pageIds = pages.map { it.id }
          activePageId = activeId
          pageIndicator.text = PageNavigationLogic.label(pageIds, activePageId)
      }
  }

  /** Swap to another page: clear canvas, load its ink, refresh overlay + indicator. */
  private fun goToPage(pageId: String) {
      drawView.clearAll()
      store.switchPage(pageId) { strokes ->
          drawView.mergeLoadedStrokes(strokes)
          drawView.fullRefresh()       // clears e-ink ghosting on switch (AC6.4)
          refreshPageIndicator()
      }
  }

  /** Reload whatever page the repo currently considers active (after a delete). */
  private fun reloadCurrentPage() {
      drawView.clearAll()
      store.load { strokes ->
          drawView.mergeLoadedStrokes(strokes)
          drawView.fullRefresh()
          refreshPageIndicator()
      }
  }

  private fun showPagePicker() {
      store.listPages { pages, activeId ->
          val labels = Array(pages.size) { i -> "Page ${i + 1}" }
          val builder = AlertDialog.Builder(this)
              .setTitle("Pages")
              .setItems(labels) { _, which -> goToPage(pages[which].id) }
              .setPositiveButton("New Page") { _, _ ->
                  store.createPage { newId -> goToPage(newId) }
              }
              .setNegativeButton("Cancel", null)
          if (PageNavigationLogic.canDelete(pages.map { it.id })) {
              builder.setNeutralButton("Delete Current Page") { _, _ ->
                  store.deletePage(activeId) { deleted -> if (deleted) reloadCurrentPage() }
              }
          }
          builder.show()
      }
  }
  ```
- Imports: `android.widget.ImageButton`, `android.widget.TextView`.

**Verification:**
Run: `./gradlew :app:notes:assembleDebug` and `./gradlew test`
Expected: APK builds; whole unit suite still green (`PageNavigationLogic` tests included). Runtime behavior (prev/next, indicator, picker, New/Delete page, ghosting) is verified on-device per the checklist task below.

**Commit:** `feat(notes): page navigation UI — prev/next + indicator + page picker; restore active page`
<!-- END_TASK_4 -->

<!-- START_TASK_5 -->
### Task 5: Manual test checklist — multi-page nav

**Files:**
- Modify: `docs/manual-test-checklist.md`

**Implementation:** Under a new "multi-notebook-multi-page" section, add on-device checks for what can't be unit-tested here:
- AC6.1: prev/next move between pages in order; indicator shows the correct "N / M".
- AC6.3: the page picker lists pages, switching loads the right ink; New Page adds and opens a blank page; Delete Current Page is hidden when only one page exists and works otherwise.
- AC6.4: drawing then tapping next/prev keeps the just-drawn stroke (no loss) and the new page renders without ghosting from the old one.
- AC5.1: draw on page 2 of 3, kill + relaunch → reopens on page 2 with its ink.

**Verification:** N/A (documentation).

**Commit:** `docs: on-device checklist for multi-page navigation`
<!-- END_TASK_5 -->
