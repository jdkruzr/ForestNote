package com.forestnote.app.notes

import com.forestnote.core.ink.Stroke

/**
 * A stroke clipboard for cut/copy/paste (library-and-tools.AC2.4, AC1.6).
 *
 * Listener-based rather than coroutine/StateFlow-based to match the codebase's
 * existing async idiom (Executor + Handler + callbacks; no kotlinx-coroutines).
 * The A-phase backing store is in-process ([InProcessClipboard]); B1 re-backs the
 * same interface with `app_state.clipboard_json` (serialized via StrokeSerializer)
 * for cross-notebook, app-kill-surviving paste — without changing this contract.
 */
interface Clipboard {
    /** Current clipboard contents (a snapshot; never the live backing list). */
    fun get(): List<Stroke>

    /** Replace the contents and notify listeners. */
    fun set(strokes: List<Stroke>)

    /** Empty the clipboard and notify listeners. */
    fun clear()

    fun isEmpty(): Boolean

    /** Register a listener invoked synchronously on every set/clear with the new contents. */
    fun addListener(listener: (List<Stroke>) -> Unit)
}

/** In-memory [Clipboard] for the current process. Holds a defensive copy of its contents. */
class InProcessClipboard : Clipboard {
    private var contents: List<Stroke> = emptyList()
    private val listeners = mutableListOf<(List<Stroke>) -> Unit>()

    override fun get(): List<Stroke> = contents

    override fun set(strokes: List<Stroke>) {
        contents = strokes.toList() // defensive copy — later mutation of the source can't leak in
        notifyListeners()
    }

    override fun clear() {
        contents = emptyList()
        notifyListeners()
    }

    override fun isEmpty(): Boolean = contents.isEmpty()

    override fun addListener(listener: (List<Stroke>) -> Unit) {
        listeners.add(listener)
    }

    private fun notifyListeners() {
        for (l in listeners) l(contents)
    }
}
