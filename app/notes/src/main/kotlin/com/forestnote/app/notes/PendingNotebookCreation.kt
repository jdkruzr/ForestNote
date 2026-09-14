package com.forestnote.app.notes

import kotlinx.coroutines.CompletableDeferred
import java.util.UUID

/** Owner-retained Create receipt. Losing an Activity waiter must not repeat the database command.
 * No work begins until the real writer supplies its measured canvas. */
internal class PendingNotebookCreation(
    private val create: (NotebookAspectPolicy.Geometry, (String) -> Unit) -> Unit,
) {
    val id: String = UUID.randomUUID().toString()
    private var result: CompletableDeferred<String>? = null
    suspend fun open(geometry: NotebookAspectPolicy.Geometry): String {
        val pending = synchronized(this) {
            result ?: CompletableDeferred<String>().also { receipt ->
                result = receipt
                try {
                    create(geometry) { notebook ->
                        if (notebook.isBlank()) receipt.completeExceptionally(IllegalStateException("Notebook creation failed"))
                        else receipt.complete(notebook)
                    }
                } catch (failure: Exception) { receipt.completeExceptionally(failure) }
            }
        }
        return pending.await() // Caller cancellation does not cancel the owner-held receipt.
    }
}
