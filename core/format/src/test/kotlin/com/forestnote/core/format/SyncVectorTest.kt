package com.forestnote.core.format

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ironclad cross-language contract: every shared conformance vector (mirrored verbatim from
 * UltraBridge docs/sync/vectors/) is fed through the Kotlin [SyncMerge] and the materialized
 * result MUST equal the vector's expected_state — the same suite the Go server runs. A vector
 * green here and red in Go (or vice-versa) means the implementations disagree: a release blocker.
 */
class SyncVectorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseOp(o: JsonObject) = SyncOp(
        table = o["table"]!!.jsonPrimitive.content,
        pk = o["pk"]!!.jsonPrimitive.content,
        siteId = o["site_id"]!!.jsonPrimitive.content,
        opSeq = o["op_seq"]!!.jsonPrimitive.long,
        wallTs = o["wall_ts"]!!.jsonPrimitive.long,
        cols = o["cols"]!!.jsonObject
    )

    @Test
    fun `every conformance vector merges to its expected state`() {
        val dir = File(javaClass.classLoader.getResource("sync-vectors")!!.toURI())
        val files = dir.listFiles { f -> f.name.endsWith(".vector.json") }!!.sortedBy { it.name }
        assertTrue(files.size >= 17, "expected the full mirrored vector suite, found ${files.size}")

        for (file in files) {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            val name = root["name"]!!.jsonPrimitive.content
            val ops = root["ops"]!!.jsonArray.map { parseOp(it.jsonObject) }
            val expected = root["expected_state"]!!.jsonObject

            val actualByTable = SyncMerge.merge(ops).values.groupBy { it.table }

            for (table in expected.keys + actualByTable.keys) {
                val expRows = (expected[table]?.jsonArray ?: JsonArray(emptyList()))
                    .associate { it.jsonObject["pk"]!!.jsonPrimitive.content to it.jsonObject }
                val actRows = (actualByTable[table] ?: emptyList()).associateBy { it.pk }

                assertEquals(expRows.keys, actRows.keys, "[$name] $table: materialized pks differ")
                for ((pk, expRow) in expRows) {
                    val act = actRows.getValue(pk)
                    assertEquals(expRow["wall_ts"]!!.jsonPrimitive.long, act.wallTs, "[$name] $table/$pk wall_ts")
                    assertEquals(expRow["op_seq"]!!.jsonPrimitive.long, act.opSeq, "[$name] $table/$pk op_seq")
                    assertEquals(expRow["site_id"]!!.jsonPrimitive.content, act.siteId, "[$name] $table/$pk site_id")
                    assertEquals(expRow["cols"]!!.jsonObject, act.cols, "[$name] $table/$pk cols (the winning row state)")
                }
            }
        }
    }
}
