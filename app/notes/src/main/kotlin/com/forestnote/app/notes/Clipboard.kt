package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke
import com.forestnote.core.ink.TextBox

/**
 * A mixed clipboard (strokes + text boxes) for cut/copy/paste (library-and-tools.AC2.4,
 * AC1.6; lasso-textboxes.AC3, AC4).
 *
 * Listener-based rather than coroutine/StateFlow-based to match the codebase's existing
 * async idiom (Executor + Handler + callbacks; no kotlinx-coroutines). The A-phase
 * backing store is in-process ([InProcessClipboard]). The contract widens *once* at
 * lasso-textboxes (strokes-only → mixed-content via [ClipboardPayload]); B1 re-backs
 * the widened contract with `app_state.clipboard_json` (serialized via [StrokeSerializer]
 * + [TextBoxSerializer]) for cross-notebook, app-kill-surviving paste — without further
 * contract change.
 */

/**
 * Mixed clipboard payload — strokes and text boxes in parallel lists.
 *
 * Parallel lists (rather than a sealed `CanvasElement` union) because `Stroke` and
 * `TextBox` aren't a sealed hierarchy today and z-band ordering is a render-time
 * concern handled by [com.forestnote.app.notes.DrawView]'s `composeStaticBitmap`,
 * not a clipboard concern. Fresh ULIDs for paste are minted by the caller via
 * [com.forestnote.app.notes.LassoSelectionLogic.translate] /
 * [com.forestnote.app.notes.LassoSelectionLogic.translateTextBoxes].
 */
data class ClipboardPayload(
    val strokes: List<Stroke>,
    val textBoxes: List<TextBox>,
) {
    fun isEmpty(): Boolean = strokes.isEmpty() && textBoxes.isEmpty()

    companion object {
        /** Zero-alloc singleton for "nothing selected / nothing copied". */
        val EMPTY = ClipboardPayload(emptyList(), emptyList())
    }
}

interface Clipboard {
    /** Current clipboard contents (a snapshot; never the live backing lists). */
    fun get(): ClipboardPayload

    /** Replace the contents and notify listeners. */
    fun set(payload: ClipboardPayload)

    /** Empty the clipboard and notify listeners. */
    fun clear()

    fun isEmpty(): Boolean

    /** Register a listener invoked synchronously on every set/clear with the new contents. */
    fun addListener(listener: (ClipboardPayload) -> Unit)
}

/** In-memory [Clipboard] for the current process. Holds a defensive copy of its contents. */
class InProcessClipboard : Clipboard {
    private var contents: ClipboardPayload = ClipboardPayload.EMPTY
    private val listeners = mutableListOf<(ClipboardPayload) -> Unit>()

    override fun get(): ClipboardPayload = contents

    override fun set(payload: ClipboardPayload) {
        // Defensive copy of both lists — later mutation of the source can't leak in.
        contents = ClipboardPayload(payload.strokes.toList(), payload.textBoxes.toList())
        notifyListeners()
    }

    override fun clear() {
        contents = ClipboardPayload.EMPTY
        notifyListeners()
    }

    override fun isEmpty(): Boolean = contents.isEmpty()

    override fun addListener(listener: (ClipboardPayload) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) l(contents)
    }
}
