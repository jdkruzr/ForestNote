# Multiple Notebooks with Multiple Pages — Phase 3: Notebook picker UI

**Goal:** Let the user see the current notebook, pick another, and create / rename / delete notebooks — switching loads that notebook's active/first page.

**Architecture:** A tappable notebook label in the nav bar opens a notebook picker (AlertDialog list → switch; "New Notebook" → name prompt; "Edit Current" → rename/delete). Switching reuses the Phase 2 page-swap idiom against the notebook's loaded page. All persistence goes through the Phase 1 `NotebookStore` API (`listNotebooks`, `switchNotebook`, `createNotebook`, `renameNotebook`, `deleteNotebook`).

**Tech Stack:** Kotlin, Android Views.

**Scope:** Phase 3 of 3.

**Codebase verified:** 2026-05-23 (codebase-investigator). No `strings.xml`; keep inline strings.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### multi-notebook-multi-page.AC7: Notebook picker UI
- **multi-notebook-multi-page.AC7.1 Success:** the notebook picker lists notebooks; selecting one switches to it and loads its active/first page.
- **multi-notebook-multi-page.AC7.2 Success:** New / Rename / Delete notebook work from the picker; Delete confirms first.

---

## Context for the engineer

- Depends on Phases 1–2 (`NotebookStore` exposes `listNotebooks(onResult: (List<NotebookMeta>, activeNotebookId) -> Unit)`, `switchNotebook(id, onLoaded)`, `createNotebook(name, onCreated)`, `renameNotebook(id, name, onDone)`, `deleteNotebook(id, onDone)`; `MainActivity` already has `goToPage`, `reloadCurrentPage`, `refreshPageIndicator`, and the nav bar from Phase 2).
- After `deleteNotebook`, the repository has already switched `currentNotebookId`/`currentPageId` to a remaining notebook (or bootstrapped a fresh one if it was the last) — so the UI just reloads the current page and refreshes labels.
- AlertDialog supports at most three buttons; the picker uses list-items to switch + positive "New Notebook" + neutral "Edit Current" (a second dialog offers Rename/Delete). Text entry uses `setView(EditText)`, like a standard rename dialog. This is all on-device/UI; no unit tests here (no Robolectric) — verified via the manual checklist.

---

<!-- START_TASK_1 -->
### Task 1: Notebook button in the nav bar

**Files:**
- Modify: `app/notes/src/main/res/layout/navbar.xml`

**Implementation:** Add, as the FIRST child of the nav bar `LinearLayout` (left of the prev button), a tappable `TextView` `@+id/btn_notebooks` showing the current notebook name:
- `clickable=true`, `focusable=true`, `gravity=center_vertical`, single line with `ellipsize=end`, a sensible `maxWidth`/weight so it doesn't crowd the page controls, initial `text="Notebook"`, `contentDescription="Notebooks"`.
- Keep the existing prev / indicator / next children unchanged (the indicator keeps `layout_weight=1`).

**Verification:**
Run: `./gradlew :app:notes:assembleDebug`
Expected: builds; nav bar inflates with the notebook label (verified on-device).

**Commit:** (defer to end of phase)
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `MainActivity` — notebook picker + switch/create/rename/delete

**Verifies:** multi-notebook-multi-page.AC7.1, AC7.2 (UI; verified on-device — see checklist task)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**
- Add field `private lateinit var btnNotebooks: TextView`; look it up in `onCreate` and wire `btnNotebooks.setOnClickListener { showNotebookPicker() }`.
- Extend the launch restore and every page/notebook switch to also refresh the notebook label. Add `refreshNotebookLabel()` and call it from `onCreate`'s load callback, `goToPage`, `goToNotebook`, and `reloadCurrentPage`:
  ```kotlin
  private fun refreshNotebookLabel() {
      store.listNotebooks { notebooks, activeId ->
          btnNotebooks.text = notebooks.firstOrNull { it.id == activeId }?.name ?: "Notebook"
      }
  }

  /** Swap to another notebook: clear canvas, load its active/first page, refresh labels. */
  private fun goToNotebook(notebookId: String) {
      drawView.clearAll()
      store.switchNotebook(notebookId) { strokes ->
          drawView.mergeLoadedStrokes(strokes)
          drawView.fullRefresh()
          refreshPageIndicator()
          refreshNotebookLabel()
      }
  }

  private fun showNotebookPicker() {
      store.listNotebooks { notebooks, activeId ->
          val names = Array(notebooks.size) { i -> notebooks[i].name }
          AlertDialog.Builder(this)
              .setTitle("Notebooks")
              .setItems(names) { _, which -> goToNotebook(notebooks[which].id) }
              .setPositiveButton("New Notebook") { _, _ -> promptNewNotebook() }
              .setNeutralButton("Edit Current") { _, _ ->
                  val current = notebooks.firstOrNull { it.id == activeId }
                  if (current != null) showEditNotebook(current, canDelete = notebooks.size > 1)
              }
              .setNegativeButton("Cancel", null)
              .show()
      }
  }

  private fun promptNewNotebook() {
      val input = EditText(this).apply { hint = "Notebook name" }
      AlertDialog.Builder(this)
          .setTitle("New Notebook")
          .setView(input)
          .setPositiveButton("Create") { _, _ ->
              val name = input.text.toString().trim().ifEmpty { "Untitled" }
              store.createNotebook(name) { newId -> goToNotebook(newId) }
          }
          .setNegativeButton("Cancel", null)
          .show()
  }

  private fun showEditNotebook(notebook: NotebookMeta, canDelete: Boolean) {
      val builder = AlertDialog.Builder(this)
          .setTitle(notebook.name)
          .setPositiveButton("Rename") { _, _ -> promptRenameNotebook(notebook) }
          .setNegativeButton("Cancel", null)
      if (canDelete) {
          builder.setNeutralButton("Delete") { _, _ -> confirmDeleteNotebook(notebook) }
      }
      builder.show()
  }

  private fun promptRenameNotebook(notebook: NotebookMeta) {
      val input = EditText(this).apply { setText(notebook.name) }
      AlertDialog.Builder(this)
          .setTitle("Rename Notebook")
          .setView(input)
          .setPositiveButton("Save") { _, _ ->
              val name = input.text.toString().trim().ifEmpty { notebook.name }
              store.renameNotebook(notebook.id, name) { refreshNotebookLabel() }
          }
          .setNegativeButton("Cancel", null)
          .show()
  }

  private fun confirmDeleteNotebook(notebook: NotebookMeta) {
      AlertDialog.Builder(this)
          .setTitle("Delete Notebook")
          .setMessage("Delete \"${notebook.name}\" and all its pages?")
          .setPositiveButton("Delete") { _, _ ->
              store.deleteNotebook(notebook.id) {
                  // Repo already switched to a remaining/bootstrapped notebook.
                  reloadCurrentPage()
                  refreshNotebookLabel()
              }
          }
          .setNegativeButton("Cancel", null)
          .show()
  }
  ```
- Imports: `android.widget.EditText`, `com.forestnote.core.format.NotebookMeta` (and existing `TextView`).

**Verification:**
Run: `./gradlew :app:notes:assembleDebug` and `./gradlew test`
Expected: APK builds; unit suite green (unchanged). Notebook switch/create/rename/delete verified on-device per the checklist task.

**Commit:** `feat(notes): notebook picker — list/switch/new/rename/delete in the nav bar`
<!-- END_TASK_2 -->

<!-- START_TASK_3 -->
### Task 3: Manual checklist + refresh project docs

**Files:**
- Modify: `docs/manual-test-checklist.md`
- Modify: `CLAUDE.md` (top-level), `core/format/CLAUDE.md`, `app/notes/CLAUDE.md`

**Implementation:**
1. **Checklist** — add notebook checks: the picker lists notebooks and switches (loading the right pages); New Notebook creates and opens a fresh notebook (one blank page); Rename updates the label; Delete confirms then removes the notebook and its pages, switching to a remaining one (and never leaving zero notebooks). Add an end-to-end: create 2 notebooks with distinct pages, switch between them, relaunch → reopens the last-active notebook+page.
2. **Docs** — the top-level `CLAUDE.md` line "V1 scope: single notebook, single page" is now false; update it to reflect multi-notebook/multi-page. In `core/format/CLAUDE.md`, document the `notebook` table + `page.notebook_id` + `app_state` (active notebook/page restore) and that deletes are transactional (no FK-cascade reliance, schema v3). In `app/notes/CLAUDE.md`, note the nav bar + `PageNavigationLogic` and that `MainActivity` drives page/notebook switching through `NotebookStore`. Bump each touched file's "Last verified" date.

**Verification:** N/A (documentation).

**Commit:** `docs: notebook checklist + refresh CLAUDE.md for multi-notebook/multi-page`
<!-- END_TASK_3 -->
