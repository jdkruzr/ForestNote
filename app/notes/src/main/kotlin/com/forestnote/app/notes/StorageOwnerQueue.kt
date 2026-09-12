package com.forestnote.app.notes

import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/** Application-lifetime ordering for the one library, including Activity recreation.
 * Reservations are cheap; only a replacement's database thread waits. A failed close
 * poisons the chain until process restart: it never licenses a second live connection.
 */
internal class StorageOwnerQueue {
    private var tail = CompletableFuture.completedFuture(Unit)

    @Synchronized fun reserve(): Lease {
        val previous = tail
        val released = CompletableFuture<Unit>()
        tail = previous.thenCompose { released }
        return Lease(previous, released)
    }

    class Lease internal constructor(
        private val previous: CompletableFuture<Unit>,
        private val released: CompletableFuture<Unit>,
    ) {
        fun awaitPreviousClose() { previous.get(5, TimeUnit.SECONDS) }
        fun release(failure: Throwable?) {
            if (failure == null) released.complete(Unit) else released.completeExceptionally(failure)
        }
    }
}
