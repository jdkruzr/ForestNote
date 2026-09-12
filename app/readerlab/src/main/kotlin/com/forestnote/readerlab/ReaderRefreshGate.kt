package com.forestnote.readerlab

/** Main-thread ordering only. Stale callbacks cannot flash over a new writing session or menu. */
internal class ReaderRefreshGate(
    private val allowed: () -> Boolean,
    private val afterVisualState: ((() -> Unit) -> Unit),
    private val afterFrame: ((() -> Unit) -> Unit),
    private val refresh: () -> Unit,
) {
    private var generation = 0L
    fun cancel() { generation++ }
    fun request() {
        val request = ++generation
        if (!allowed()) return
        afterVisualState {
            if (request == generation && allowed()) afterFrame {
                if (request == generation && allowed()) {
                    generation++ // A request refreshes at most once.
                    refresh()
                }
            }
        }
    }
}
