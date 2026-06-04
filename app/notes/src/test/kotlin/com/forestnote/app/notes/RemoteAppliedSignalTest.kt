package com.forestnote.app.notes

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.forestnote.core.format.ForestNoteRegistry
import com.forestnote.core.format.NotebookRepository
import io.rhizome.core.Op
import io.rhizome.core.WireCodec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Test
import java.util.concurrent.Executors
import kotlin.test.assertEquals

/**
 * The `remoteApplied` revision flow drives the post-sync Library refresh. It MUST bump when a pull
 * applies real rows (so the showing Library re-queries and freshly synced notebooks appear) and MUST
 * NOT bump on an empty apply (an idle periodic sync) — otherwise the e-ink grid repaints on every
 * timer tick. This pins both halves of that contract.
 */
class RemoteAppliedSignalTest {

    private val REMOTE = "0000000000000000000000RMT0"

    private fun newStore(): NotebookStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        return NotebookStore(
            repoProvider = { NotebookRepository.forTesting(driver) { 1000L } },
            executor = Executors.newSingleThreadExecutor(),
            poster = { it.run() },
        )
    }

    private fun wireCols(table: String, values: Map<String, Any?>): JsonObject {
        val def = ForestNoteRegistry.registry.byName.getValue(table)
        return buildJsonObject { for (c in def.columns) put(c.name, WireCodec.encode(c.type, values[c.name])) }
    }

    private fun notebookOp(pk: String, opSeq: Long, opTs: Long) = Op(
        "notebook", pk, REMOTE, opSeq, opTs,
        wireCols("notebook", mapOf("name" to "Pulled", "sort_order" to 0L, "created_at" to opTs)),
    )

    @Test
    fun `applying a non-empty relayed batch bumps remoteApplied`() {
        val store = newStore()
        assertEquals(0L, store.remoteApplied.value, "no pulls applied yet")
        runBlocking { store.syncLocalStore().applyRelayed(listOf(notebookOp("00000000000000000000000RMB", 1, 3000))) }
        assertEquals(1L, store.remoteApplied.value, "a real pull bumps the revision so the UI refreshes")
        store.shutdown()
    }

    @Test
    fun `applying an empty relayed batch does not bump remoteApplied (no e-ink flicker)`() {
        val store = newStore()
        runBlocking { store.syncLocalStore().applyRelayed(emptyList()) }
        assertEquals(0L, store.remoteApplied.value, "an idle sync that pulled nothing must not repaint the Library")
        store.shutdown()
    }
}
