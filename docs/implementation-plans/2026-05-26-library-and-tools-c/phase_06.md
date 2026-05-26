# Library & Tools — Area C Implementation Plan (Phase 6 / C5)

**Goal:** Folders nest n-levels deep. The breadcrumb shows the path with each segment jumpable; the back chevron goes up ONE level (to the parent), not all the way to root. Deep paths collapse the middle to "Library / … / Current".

**Architecture:** Pure `BreadcrumbLogic` (app:notes) turns a root-first folder path into display segments (with truncation) and computes the back target. `BreadcrumbView` renders those segments into the Library header. `LibraryView` fetches the path via the C2 `folderPath` wrapper on each reload. No schema or repository changes.

**Tech Stack:** Kotlin, Android Views, JUnit4 + `kotlin.test`.

**Scope:** Phase 6 of 7 (area C). Depends on **C2** (`folderPath` / `FolderPathLogic`, `FolderMeta`) and **C4** (`LibraryView` folder navigation + the header back chevron + the breadcrumb container). Defined earlier in this plan.

**Codebase verified:** 2026-05-26 (base + designed interfaces) — `FolderPathLogic.path` returns the chain root-first ending at the target (C2); `NotebookStore.folderPath(folderId, onResult)` exists (C2); `LibraryView` owns `currentFolderId` and a header with a back chevron + a breadcrumb slot (C3a/C4).

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC4: Library
- **library-and-tools.AC4.6 Success:** Header shows: Settings cell, Recycle Bin cell (with numeric badge when non-empty), back chevron (when not at root), breadcrumb, item count, and right-side actions (Select / +Folder / +Notebook). Header height matches the editor nav bar height (~30 dp).
- **library-and-tools.AC4.7 Edge:** When deeper than two folder levels, breadcrumb collapses middle segments to "Library / … / Current".

> This phase completes the **breadcrumb** clause of AC4.6 (the static label becomes a navigable breadcrumb; back chevron walks up one level) and all of AC4.7. Recycle Bin badge / Select remain greyed (D/E areas). Automated tests cover the pure `BreadcrumbLogic`; the rendered breadcrumb + navigation are verified on-device.

---

## Decisions (confirmed with the human)

1. **Pure `BreadcrumbLogic`** (`segments` + `backTargetId` + truncation) — unit-tested.
2. **`BreadcrumbView`** renders segments with " / " separators; interactive segments call `onJump(folderId: String?)`.
3. **Back chevron walks up one parent level** (upgrades C4's always-root).
4. The collapsed **"…" segment is non-interactive** for v1 (per design — not a dropdown).

---

<!-- START_SUBCOMPONENT_A (tasks 1) -->

<!-- START_TASK_1 -->
### Task 1: `BreadcrumbLogic` (pure) + tests

**Verifies:** library-and-tools.AC4.6, library-and-tools.AC4.7

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/BreadcrumbLogic.kt`
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/BreadcrumbLogicTest.kt`

**Implementation:** pure, no Android. Operates on `FolderMeta` (id + name + parentFolderId).

```kotlin
package com.forestnote.app.notes

import com.forestnote.core.format.FolderMeta

/**
 * Pure breadcrumb model for the Library header (AC4.6 / AC4.7). Turns a root-first folder
 * [path] (as returned by FolderPathLogic.path, ending at the current folder) into display
 * segments, and computes the back-chevron target (one level up).
 */
object BreadcrumbLogic {
    /** A breadcrumb segment. [folderId] null = the Library root. [interactive] false = current / "…". */
    data class Segment(val label: String, val folderId: String?, val interactive: Boolean)

    const val ROOT_LABEL = "Library"
    const val ELLIPSIS = "…"

    /**
     * Segments for a [path] (root-first, ending at the current folder; empty = at root).
     * Always starts with a jumpable "Library" root, except when already at root (then the
     * single "Library" segment is the non-interactive current location). The last segment
     * (current folder) is non-interactive. When [path] has 3+ folders, the middle collapses
     * to a single non-interactive "…": [Library, …, current].
     */
    fun segments(path: List<FolderMeta>): List<Segment> {
        if (path.isEmpty()) return listOf(Segment(ROOT_LABEL, null, interactive = false))
        val root = Segment(ROOT_LABEL, null, interactive = true)
        val current = path.last()
        return if (path.size >= 3) {
            listOf(
                root,
                Segment(ELLIPSIS, null, interactive = false),
                Segment(current.name, current.id, interactive = false)
            )
        } else {
            // 1 or 2 folders deep: Library + each folder; only the last is non-interactive.
            listOf(root) + path.mapIndexed { i, f ->
                Segment(f.name, f.id, interactive = i < path.lastIndex)
            }
        }
    }

    /** The folder to navigate to when the back chevron is tapped: the current folder's parent,
     *  or null (root). Null/empty path means we're at root (chevron should be hidden). */
    fun backTargetId(path: List<FolderMeta>): String? =
        if (path.size >= 2) path[path.size - 2].id else null
}
```

**Testing:**
- empty path → single non-interactive "Library".
- 1 deep (`[A]`) → `[Library(interactive), A(non-interactive)]`; `backTargetId` = null.
- 2 deep (`[A,B]`) → `[Library(i), A(i), B(non-i)]`; `backTargetId` = A.id.
- 3 deep (`[A,B,C]`) → `[Library(i), …(non-i), C(non-i)]` (AC4.7); `backTargetId` = B.id.
- 4 deep → still `[Library, …, current]`; `backTargetId` = the second-to-last folder id.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/BreadcrumbLogic.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/BreadcrumbLogicTest.kt
git commit -m "feat(app): pure BreadcrumbLogic (segments + truncation + back) (C5)"
```
<!-- END_TASK_1 -->

<!-- END_SUBCOMPONENT_A -->

<!-- START_SUBCOMPONENT_B (tasks 2-3) -->

<!-- START_TASK_2 -->
### Task 2: `BreadcrumbView` renderer

**Verifies:** library-and-tools.AC4.6, library-and-tools.AC4.7 (operational)

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/BreadcrumbView.kt`

**Implementation:** renders `BreadcrumbLogic.segments(...)` into a host `LinearLayout` (the header's breadcrumb container from C3a/C4 — repurpose the `text_breadcrumb` slot into a horizontal `LinearLayout`, id `@id/breadcrumb_container`, added in Task 3's layout tweak). For each segment, add a `TextView`; interactive segments get `?attr/selectableItemBackground` + a click → `onJump(segment.folderId)`; non-interactive segments are plain (current = bold). Insert a non-interactive " / " `TextView` between segments. Clears and rebuilds the container on each `render`.

```kotlin
package com.forestnote.app.notes

import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.forestnote.core.format.FolderMeta

class BreadcrumbView(
    private val container: LinearLayout,
    private val onJump: (folderId: String?) -> Unit
) {
    fun render(path: List<FolderMeta>) {
        container.removeAllViews()
        val segments = BreadcrumbLogic.segments(path)
        segments.forEachIndexed { i, seg ->
            if (i > 0) container.addView(separator())
            container.addView(segmentView(seg))
        }
    }

    private fun separator(): TextView = TextView(container.context).apply {
        text = " / "
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun segmentView(seg: BreadcrumbLogic.Segment): TextView =
        TextView(container.context).apply {
            text = seg.label
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            if (seg.interactive) {
                isClickable = true
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onJump(seg.folderId) }
            } else {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }
}
```

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/BreadcrumbView.kt
git commit -m "feat(app): BreadcrumbView header renderer (C5)"
```
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: `LibraryView` breadcrumb + parent-level back

**Verifies:** library-and-tools.AC4.6, library-and-tools.AC4.7 (operational)

**Files:**
- Modify: `app/notes/src/main/res/layout/view_library.xml` (turn the breadcrumb slot into a horizontal `LinearLayout` `@+id/breadcrumb_container`, weighted to take the middle space)
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt`

**Implementation:**
- Construct a `BreadcrumbView(breadcrumbContainer) { folderId -> currentFolderId = folderId; reload() }` in `show`.
- In `reload()`: after loading folders + notebooks, also resolve the path for the breadcrumb + back target:
  - if `currentFolderId == null` → `breadcrumbView.render(emptyList())`; hide back chevron.
  - else `store.folderPath(currentFolderId!!) { path -> breadcrumbView.render(path); set back chevron visible; backTarget = BreadcrumbLogic.backTargetId(path) }`.
- Back chevron tap → `currentFolderId = backTarget; reload()` (replaces C4's "always root"). Keep `backTarget` in a field set from the last path resolution.
- Item count stays folders + notebooks.

**Verification:**
```bash
./gradlew :app:notes:assembleDebug
./gradlew test
```
Expected: builds; tests pass. **On-device:** nest 3 levels deep → breadcrumb shows "Library / … / Current"; tap "Library" → jumps to root; back chevron at depth N goes to depth N-1 (one level up), not straight to root; at root the chevron is hidden and the breadcrumb is just "Library".

**Commit:**
```bash
git add app/notes/src/main/res/layout/view_library.xml \
        app/notes/src/main/kotlin/com/forestnote/app/notes/LibraryView.kt
git commit -m "feat(app): breadcrumb nav + parent-level back in Library (C5)"
```
<!-- END_TASK_3 -->

<!-- END_SUBCOMPONENT_B -->
