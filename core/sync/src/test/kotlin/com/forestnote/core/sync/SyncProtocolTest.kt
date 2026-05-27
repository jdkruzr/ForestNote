package com.forestnote.core.sync

import com.forestnote.core.format.SyncOp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wire envelope (protocol §4) must serialize with the EXACT snake_case field names the Go
 * server expects, and parse the server's responses the same way. These pin the field names and
 * the [SyncOp] ↔ [WireOp] mapping that the engine relies on.
 */
class SyncProtocolTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun `request serializes with the protocol's snake_case envelope`() {
        val op = SyncOp(
            table = "notebook", pk = "00000000000000000000000NBK", siteId = "0000000000000000000000SITE",
            opSeq = 3, wallTs = 1234, cols = buildJsonObject { put("name", "x") }
        )
        val req = SyncRequest(schemaHash = "deadbeef", siteId = "0000000000000000000000SITE", cursor = 7, ops = listOf(op.toWire()))
        val text = json.encodeToString(SyncRequest.serializer(), req)

        // Envelope + op identity fields use the protocol's snake_case names.
        for (field in listOf("\"protocol_version\":1", "\"schema_hash\":\"deadbeef\"", "\"site_id\":", "\"cursor\":7", "\"ops\":")) {
            assertTrue(field in text.replace(" ", ""), "request JSON must contain $field; was: $text")
        }
        for (field in listOf("\"op_seq\":3", "\"wall_ts\":1234", "\"pk\":\"00000000000000000000000NBK\"")) {
            assertTrue(field in text.replace(" ", ""), "op JSON must contain $field; was: $text")
        }
    }

    @Test
    fun `response parses and maps back to domain SyncOps`() {
        val body = """
            {
              "protocol_version": 1,
              "accepted_through": 5,
              "rejected": [ { "site_id": "0000000000000000000000PEER", "op_seq": 2, "reason": "bad" } ],
              "ops": [
                { "table": "stroke", "pk": "00000000000000000000000STK", "site_id": "0000000000000000000000PEER",
                  "op_seq": 9, "wall_ts": 4242, "cols": { "z": 3, "deleted_at": null } }
              ],
              "cursor": 12,
              "has_more": false
            }
        """.trimIndent()
        val resp = json.decodeFromString(SyncResponse.serializer(), body)

        assertEquals(5, resp.acceptedThrough)
        assertEquals(12, resp.cursor)
        assertEquals(false, resp.hasMore)
        assertEquals(1, resp.rejected.size)
        assertEquals(2L, resp.rejected[0].opSeq)

        val op = resp.ops.single().toSyncOp()
        assertEquals("stroke", op.table)
        assertEquals("00000000000000000000000STK", op.pk)
        assertEquals(9L, op.opSeq)
        assertEquals(4242L, op.wallTs)
        assertEquals(3, op.cols["z"]!!.toString().toInt())
    }

    @Test
    fun `SyncOp round-trips through WireOp`() {
        val op = SyncOp("page", "00000000000000000000000PGE", "0000000000000000000000SITE", 4, 99, buildJsonObject { put("sort_order", 2) })
        assertEquals(op, op.toWire().toSyncOp())
    }
}
