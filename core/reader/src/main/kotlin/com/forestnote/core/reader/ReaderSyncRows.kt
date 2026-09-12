package com.forestnote.core.reader

import com.forestnote.core.format.SchemaReconciliation
import io.rhizome.core.*
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Candidate mixed-library row boundary. Use one instance per active sync owner.
 * Capability/schema admission precedes local replay preparation. The POST still
 * enforces authentication and schema admission; a failed POST never ACKs local edits.
 * This does not enroll a device, enable sync, or install the writer policy schema.
 */
class ReaderSyncRows(
    private val storage: ReaderStorage,
    transport: BoundedRowTransport,
    private val registry: Registry,
    limits: RowLimits = RowLimits(),
) : ScheduledRows {
    private val gate = Mutex()
    private val session = BoundedSyncSession(storage.sync, transport, registry.schemaHash(),
        requireAssets = true, localLimits = limits)

    override suspend fun admission(): RowAdmission = gate.withLock { admit() }

    private suspend fun admit(): RowAdmission {
        val result = withContext(storage.dispatcher) { session.admission() }
        if (result is RowAdmission.Allowed) withContext(storage.dispatcher) {
            SchemaReconciliation.prepare(storage.db, registry.schemaHash())
        }
        return result
    }

    override suspend fun exchange(): RowExchange = gate.withLock {
        when (val allowed = admit()) {
            is RowAdmission.Stopped -> RowExchange.Stopped(allowed.reason)
            is RowAdmission.Allowed -> withContext(storage.dispatcher) {
                val result = session.exchange()
                if (result is RowExchange.Page && !result.hasMore && storage.sync.pendingColumnRepairs() > 0) {
                    RowExchange.Stopped(SyncResult.Failed("Upgrade replay ended with missing source fields; explicit recovery required"))
                } else result
            }
        }
    }
}
