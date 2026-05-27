package com.forestnote.core.format

import com.forestnote.core.ink.StrokePoint
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Test
import kotlin.test.assertEquals

/**
 * The decode half of the wire codec: relayed ops arrive as wire `cols` and the apply path
 * (Phase 4) must turn them back into the exact column values to store. The contract is a clean
 * round-trip with [SyncWire]'s encoders — `decode(encode(row)) == row` for every table — which
 * pins the inverse of the two lossy-looking transforms: unsigned-int64 `color` ↔ signed Int
 * (sign-extended Long), and base64 `points` ↔ the raw LE int32 BLOB.
 */
class SyncWireDecodeTest {

    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    @Test
    fun `notebook cols round-trip`() {
        val row = SyncWire.NotebookRow(
            folderId = "00000000000000000000000FLD",
            name = "Field Notes",
            sortOrder = 3,
            createdAt = 1000,
            deletedAt = null
        )
        val encoded = obj(SyncWire.notebookCols(row.folderId, row.name, row.sortOrder, row.createdAt, row.deletedAt))
        assertEquals(row, SyncWire.decodeNotebook(encoded))
    }

    @Test
    fun `notebook cols round-trip with null folder and tombstone`() {
        val row = SyncWire.NotebookRow(folderId = null, name = "Root", sortOrder = 0, createdAt = 5, deletedAt = 99)
        val encoded = obj(SyncWire.notebookCols(row.folderId, row.name, row.sortOrder, row.createdAt, row.deletedAt))
        assertEquals(row, SyncWire.decodeNotebook(encoded))
    }

    @Test
    fun `folder cols round-trip`() {
        val row = SyncWire.FolderRow(
            name = "Work",
            sortOrder = 7,
            createdAt = 200,
            deletedAt = null,
            parentFolderId = null
        )
        val encoded = obj(SyncWire.folderCols(row.name, row.sortOrder, row.createdAt, row.deletedAt, row.parentFolderId))
        assertEquals(row, SyncWire.decodeFolder(encoded))
    }

    @Test
    fun `page cols round-trip with inherit nulls`() {
        val row = SyncWire.PageRow(
            notebookId = "00000000000000000000000NBK",
            sortOrder = 2,
            createdAt = 300,
            deletedAt = null,
            template = null,
            templatePitchMm = null
        )
        val encoded = obj(SyncWire.pageCols(row.notebookId, row.sortOrder, row.createdAt, row.deletedAt, row.template, row.templatePitchMm))
        assertEquals(row, SyncWire.decodePage(encoded))
    }

    @Test
    fun `page cols round-trip with explicit template`() {
        val row = SyncWire.PageRow(
            notebookId = "00000000000000000000000NBK",
            sortOrder = 2,
            createdAt = 300,
            deletedAt = 301,
            template = "GRID",
            templatePitchMm = 7
        )
        val encoded = obj(SyncWire.pageCols(row.notebookId, row.sortOrder, row.createdAt, row.deletedAt, row.template, row.templatePitchMm))
        assertEquals(row, SyncWire.decodePage(encoded))
    }

    @Test
    fun `stroke cols round-trip preserves signed color and points bytes`() {
        val points = StrokeSerializer.encode(
            listOf(
                StrokePoint(10, 20, 128, 5L),
                StrokePoint(-100, 200, 1000, 0x123456789ABCDEFL)
            )
        )
        val row = SyncWire.StrokeRow(
            pageId = "00000000000000000000000PG1",
            color = -16777216L,   // signed ARGB black as stored in the DB
            penWidthMin = 7,
            penWidthMax = 35,
            points = points,
            z = 4,
            createdAt = 1020,
            deletedAt = null
        )
        val encoded = obj(
            SyncWire.strokeCols(row.pageId, row.color, row.penWidthMin, row.penWidthMax, row.points, row.z, row.createdAt, row.deletedAt)
        )
        val decoded = SyncWire.decodeStroke(encoded)
        assertEquals(row.pageId, decoded.pageId)
        assertEquals(row.color, decoded.color, "unsigned-int64 wire color decodes back to the signed DB Long")
        assertEquals(row.penWidthMin, decoded.penWidthMin)
        assertEquals(row.penWidthMax, decoded.penWidthMax)
        assertEquals(row.z, decoded.z)
        assertEquals(row.createdAt, decoded.createdAt)
        assertEquals(row.deletedAt, decoded.deletedAt)
        assertEquals(points.toList(), decoded.points.toList(), "points bytes survive base64 round-trip")
    }

    @Test
    fun `text box cols round-trip`() {
        val row = SyncWire.TextBoxRow(
            pageId = "00000000000000000000000PG1",
            x = 100, y = 200, width = 3000, height = 1200,
            text = "The quick brown fox 🦊\nwraps & reflows",
            fontName = "NotoSerif-Regular.ttf",
            fontSize = 240,
            color = -16777216L,  // signed ARGB black
            weight = 400,
            borderWidth = 2,
            z = 0,
            createdAt = 1700,
            deletedAt = null
        )
        val encoded = obj(
            SyncWire.textBoxCols(
                row.pageId, row.x, row.y, row.width, row.height, row.text, row.fontName,
                row.fontSize, row.color, row.weight, row.borderWidth, row.z, row.createdAt, row.deletedAt
            )
        )
        assertEquals(row, SyncWire.decodeTextBox(encoded))
    }

    @Test
    fun `text box cols round-trip with tombstone, top band, and signed color`() {
        val row = SyncWire.TextBoxRow(
            pageId = "00000000000000000000000PG2",
            x = -50, y = 0, width = 500, height = 500,
            text = "",
            fontName = "Roboto-Regular.ttf",
            fontSize = 180,
            color = -65536L,    // signed ARGB red (0xFFFF0000)
            weight = 700,
            borderWidth = 0,
            z = 1,              // top band
            createdAt = 42,
            deletedAt = 99
        )
        val encoded = obj(
            SyncWire.textBoxCols(
                row.pageId, row.x, row.y, row.width, row.height, row.text, row.fontName,
                row.fontSize, row.color, row.weight, row.borderWidth, row.z, row.createdAt, row.deletedAt
            )
        )
        val decoded = SyncWire.decodeTextBox(encoded)
        assertEquals(row, decoded)
        assertEquals(row.color, decoded.color, "unsigned-int64 wire color decodes back to the signed DB Long")
    }
}
