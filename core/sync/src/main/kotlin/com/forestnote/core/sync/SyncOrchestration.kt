package com.forestnote.core.sync

/** Exponential backoff for retrying a [SyncResult.Retryable] session (§7.3). Pure. */
object SyncBackoff {
    fun delayMillis(attempt: Int, baseMs: Long = 1_000, capMs: Long = 60_000): Long {
        if (attempt <= 0) return baseMs.coerceAtMost(capMs)
        // 2^attempt * base, guarded against Long overflow at large attempt counts.
        val shift = attempt.coerceAtMost(40)
        val grown = baseMs.toDouble() * (1L shl shift)
        return if (grown >= capMs) capMs else grown.toLong()
    }
}

/** The pull-first join policy: drop the auto-created bootstrap notebook only when it was pristine
 *  AND the pull delivered some other notebook (so a first device keeps and uploads it). Pure. */
object SyncJoinPlan {
    fun shouldDiscardBootstrap(wasPristine: Boolean, bootstrapId: String, notebookIdsAfterPull: List<String>): Boolean =
        wasPristine && notebookIdsAfterPull.any { it != bootstrapId }
}
