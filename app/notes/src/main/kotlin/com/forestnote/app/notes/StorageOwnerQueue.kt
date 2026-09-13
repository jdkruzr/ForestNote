package com.forestnote.app.notes

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Application-lifetime ordering for the one library, including Activity recreation.
 * Reservations are cheap; only a replacement's database thread waits. A failed close
 * poisons the chain until process restart: it never licenses a second live connection.
 */
internal class StorageOwnerQueue {
    private var tail = CompletableFuture.completedFuture(Unit)
    private var recoveryReserved = false

    @Synchronized fun reserve(): Lease {
        check(!recoveryReserved) { "Library recovery is in progress" }
        return append()
    }

    /** Reserve BEFORE closing the active store. Replacement Activities fail closed
     * throughout preparation, not merely until the old SQLite driver closes. */
    @Synchronized fun reserveRecovery(): Lease {
        check(!recoveryReserved) { "Library recovery is already in progress" }
        recoveryReserved = true
        return append { synchronized(this) { recoveryReserved = false } }
    }

    private fun append(afterRelease: () -> Unit = {}): Lease {
        val previous = tail
        val released = CompletableFuture<Unit>()
        tail = previous.thenCompose { released }
        return Lease(previous, released, afterRelease)
    }

    class Lease internal constructor(
        private val previous: CompletableFuture<Unit>,
        private val released: CompletableFuture<Unit>,
        private val afterRelease: () -> Unit,
    ) {
        fun awaitPreviousClose() { previous.get(5, TimeUnit.SECONDS) }
        fun release(failure: Throwable?) {
            val completed = if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
            if (completed) afterRelease()
        }
    }
}
