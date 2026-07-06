package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.TextBox

/** Which way a recorded [EditStep] is being replayed. */
enum class EditDirection { UNDO, REDO }

/**
 * The before/after image of a single row (stroke or text box) touched by an edit. `null` means the
 * row was absent (never existed, or tombstoned) on that side. This is the atom of undo: to move a
 * row to a target side, upsert [after]/[before] when non-null, else soft-delete the id.
 */
data class RowChange<T>(val id: String, val before: T?, val after: T?)

/**
 * One undoable editor action, captured as the per-row before/after images of everything it touched,
 * plus the [pageId] it happened on (history is per-notebook, so a step may belong to a page other
 * than the one currently shown). [label] is for diagnostics only.
 *
 * The shape is uniform across add / delete / erase-with-fragments / clear / move / edit — see
 * [targetRows].
 */
data class EditStep(
    val pageId: String,
    val strokeChanges: List<RowChange<Stroke>>,
    val boxChanges: List<RowChange<TextBox>>,
    val label: String,
)

/**
 * The (removedIds, added) split for one direction of an [EditStep] — exactly the arguments the
 * existing `NotebookStore.replaceStrokes` / `replaceTextBoxes` batch paths take (→ `applyErase` /
 * `applyTextBoxBatch`, which soft-delete the removed ids and upsert the added rows, resurrecting a
 * tombstoned id by ULID).
 */
data class Targets(
    val strokeRemovedIds: List<String>,
    val strokeAdded: List<Stroke>,
    val boxRemovedIds: List<String>,
    val boxAdded: List<TextBox>,
)

/**
 * Pure inversion: pick each touched row's target image for [direction] (UNDO → `before`, REDO →
 * `after`); a null target means the row should end tombstoned (removedId), a non-null target means
 * upsert it. Same-id-on-both-sides (a move/edit in place) yields an upsert with no delete, matching
 * the store's "re-added id is a move" pattern.
 */
fun targetRows(step: EditStep, direction: EditDirection): Targets {
    val strokeRemoved = ArrayList<String>()
    val strokeAdded = ArrayList<Stroke>()
    for (c in step.strokeChanges) {
        val target = if (direction == EditDirection.UNDO) c.before else c.after
        if (target == null) strokeRemoved.add(c.id) else strokeAdded.add(target)
    }
    val boxRemoved = ArrayList<String>()
    val boxAdded = ArrayList<TextBox>()
    for (c in step.boxChanges) {
        val target = if (direction == EditDirection.UNDO) c.before else c.after
        if (target == null) boxRemoved.add(c.id) else boxAdded.add(target)
    }
    return Targets(strokeRemoved, strokeAdded, boxRemoved, boxAdded)
}

/**
 * A bounded, session-only undo/redo stack (one per notebook — the owner clears it on notebook
 * switch). Pure Kotlin so it's unit-testable off-device, like [PageNavigationLogic]/[LaunchLogic].
 *
 * Standard cursor model: [steps] holds the applied-then-undone sequence and [cursor] is how many are
 * currently "done". Pushing a new step drops any redo tail (steps after the cursor) and evicts the
 * oldest once past [maxDepth].
 */
class EditHistory(private val maxDepth: Int = DEFAULT_MAX_DEPTH) {
    private val steps = ArrayList<EditStep>()
    private var cursor = 0

    val canUndo: Boolean get() = cursor > 0
    val canRedo: Boolean get() = cursor < steps.size

    fun push(step: EditStep) {
        // Drop the redo tail — a new edit forks history.
        while (steps.size > cursor) steps.removeAt(steps.size - 1)
        steps.add(step)
        cursor = steps.size
        // Evict oldest beyond the cap.
        while (steps.size > maxDepth) {
            steps.removeAt(0)
            cursor--
        }
    }

    /** Move the cursor back one and return the step to reverse, or null if nothing to undo. */
    fun undo(): EditStep? {
        if (!canUndo) return null
        cursor--
        return steps[cursor]
    }

    /** Move the cursor forward one and return the step to replay, or null if nothing to redo. */
    fun redo(): EditStep? {
        if (!canRedo) return null
        val step = steps[cursor]
        cursor++
        return step
    }

    fun clear() {
        steps.clear()
        cursor = 0
    }

    companion object {
        const val DEFAULT_MAX_DEPTH = 50
    }
}
