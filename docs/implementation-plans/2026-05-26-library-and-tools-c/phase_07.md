# Library & Tools — Area C Implementation Plan (Phase 7 / C6)

**Goal:** Make the launch contract explicit and finish the picker→Library cutover: cold launch opens the editor on the last-active notebook, or the Library when there is no active notebook; confirm no notebook-picker code remains.

**Architecture:** A tiny pure `LaunchLogic` decides whether to open the Library overlay at launch. `MainActivity` consults it after its initial load. Most of the C6 cutover (label → Library, `showNotebookPicker` removal, Settings/+Notebook in the Library header) already landed in C3a; this phase formalizes the launch behavior and verifies the cleanup. No schema/repository changes.

**Tech Stack:** Kotlin, Android Views, JUnit4 + `kotlin.test`.

**Scope:** Phase 7 of 7 (area C) — the final phase. Depends on **C3a** (Library overlay + label-repoint + picker removal) and the rest of area C. Small by design ("S"), and smaller still because C3a folded the picker removal forward.

**Codebase verified:** 2026-05-26 (base + designed interfaces) — `bootstrap()` guarantees ≥1 notebook with ≥1 page and restores the active notebook/page from `app_state`; deleting the last notebook re-bootstraps (so a truly empty library is normally unreachable — the Library-at-launch path is defensive). `NotebookStore.listNotebooks(onResult: (List<NotebookMeta>, activeNotebookId: String))` exposes the active id. `MainActivity.openLibrary()` exists (C3a).

---

## Acceptance Criteria Coverage

This phase implements and tests:

### library-and-tools.AC4: Library
- **library-and-tools.AC4.1 Success:** App launches into the Library if no notebook was previously open; otherwise launches into the editor on the last-active page (existing `app_state` behaviour preserved).

> The "otherwise launches into the editor on the last-active page" half is the existing, unchanged behavior (preserved). This phase adds the "launches into the Library if no notebook was previously open" half as a defensive guard and verifies the full picker→Library cutover. `LaunchLogic` is unit-tested; the cold-launch cases are verified on-device.

---

## Decisions (confirmed with the human)

1. **Keep C6 as a small 2-task phase** (LaunchLogic + wiring + verification) — a clean "area C complete" checkpoint.
2. The Library-at-launch path is **defensive** (bootstrap normally prevents the zero-notebook state); it protects the rare "deleted everything" edge.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->

<!-- START_TASK_1 -->
### Task 1: `LaunchLogic` (pure) + test

**Verifies:** library-and-tools.AC4.1

**Files:**
- Create: `app/notes/src/main/kotlin/com/forestnote/app/notes/LaunchLogic.kt`
- Create: `app/notes/src/test/kotlin/com/forestnote/app/notes/LaunchLogicTest.kt`

**Implementation:** pure decision, no Android.

```kotlin
package com.forestnote.app.notes

/** Decides whether the app opens the Library overlay at launch (AC4.1). Pure. */
object LaunchLogic {
    /**
     * Open the Library at launch only when there is no notebook to resume into:
     * a blank/absent active notebook id, or an empty library. Otherwise the editor
     * opens on the last-active page (existing app_state behaviour).
     */
    fun shouldOpenLibraryOnLaunch(activeNotebookId: String?, notebookCount: Int): Boolean =
        activeNotebookId.isNullOrEmpty() || notebookCount == 0
}
```

**Testing:**
- non-empty active id + count ≥ 1 → false (editor).
- null active id → true.
- empty-string active id → true.
- count 0 (regardless of id) → true.

**Verification:**
```bash
./gradlew :app:notes:test
```

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/LaunchLogic.kt \
        app/notes/src/test/kotlin/com/forestnote/app/notes/LaunchLogicTest.kt
git commit -m "feat(app): LaunchLogic for Library-at-launch decision (C6)"
```
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: Wire launch decision + verify picker removal

**Verifies:** library-and-tools.AC4.1 (operational)

**Files:**
- Modify: `app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt`

**Implementation:**
- After the existing startup load, query `store.listNotebooks { notebooks, activeId -> if (LaunchLogic.shouldOpenLibraryOnLaunch(activeId, notebooks.size)) openLibrary() }`. Place this so it runs once on cold start (e.g. right after `store.load { … }` in `onCreate`, or chained after it). The editor remains the base view; the Library shows as an overlay on top in the edge case (consistent with the overlay architecture). In the normal case (active notebook present) nothing extra happens and the editor shows as today.
- **Cleanup verification (no code change expected):** confirm `showNotebookPicker` and any picker-only imports (`ListView`, `ArrayAdapter`) were already removed in C3a. If any reference survived, remove it now.

**Verification:**
```bash
# Confirm the picker is fully gone:
grep -rn "showNotebookPicker" app/notes/src/main || echo "OK: no showNotebookPicker references"
./gradlew :app:notes:assembleDebug
./gradlew test
```
Expected: grep finds nothing; builds; all unit tests pass. **On-device:** cold launch with an existing active notebook → opens the editor on the last-active page (no Library flash); tapping the notebook label → Library; (edge) if the library could be emptied, cold launch → Library showing a +Notebook affordance. No regression in editor or Library behavior.

**Commit:**
```bash
git add app/notes/src/main/kotlin/com/forestnote/app/notes/MainActivity.kt
git commit -m "feat(app): open Library at launch when no active notebook; verify picker gone (C6)"
```
<!-- END_TASK_2 -->

<!-- END_SUBCOMPONENT_A -->
