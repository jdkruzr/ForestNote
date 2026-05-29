package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Tests for [TextBoxSerializer] (lasso-textboxes.AC4.3, AC4.4, AC7.3).
 *
 * Pins the defensive contract: round-trip identity for every field,
 * null (never throw) on every malformed shape, forward-compat tolerance
 * of unknown extra fields.
 */
class TextBoxSerializerTest {

    private fun box(
        id: String = "01HNB6X9ZK7Q3M0NJ2WS5V6PWC",
        x: Int = 100, y: Int = 200,
        w: Int = 800, h: Int = 320,
        text: String = "hello",
        fontName: String = "Roboto-Regular.ttf",
        fontSize: Int = 32,
        color: Int = 0xFF112233.toInt(),
        weight: Int = 700,
        borderWidth: Int = 4,
        zBand: ZBand = ZBand.BOTTOM,
    ) = TextBox(
        id = id, x = x, y = y, width = w, height = h, text = text,
        fontName = fontName, fontSize = fontSize, color = color,
        weight = weight, borderWidth = borderWidth, zBand = zBand,
    )

    // --- AC4.3: every field survives round-trip identity-equal ---

    @Test
    fun roundTripPreservesAllFieldsBottomBand() {
        val original = box(zBand = ZBand.BOTTOM)
        val json = TextBoxSerializer.toJson(original)
        val decoded = TextBoxSerializer.fromJson(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripPreservesAllFieldsTopBand() {
        val original = box(zBand = ZBand.TOP)
        val json = TextBoxSerializer.toJson(original)
        val decoded = TextBoxSerializer.fromJson(json)
        assertEquals(original, decoded)
    }

    @Test
    fun roundTripPreservesNegativeColorSignedInt() {
        // ARGB black (0xFF000000) is a negative Int. Make sure the sign survives.
        val original = box(color = 0xFF000000.toInt())
        val decoded = TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original))
        assertNotNull(decoded)
        assertEquals(0xFF000000.toInt(), decoded.color)
    }

    @Test
    fun roundTripPreservesEmptyText() {
        val original = box(text = "")
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    @Test
    fun roundTripPreservesUnicodeText() {
        val original = box(text = "héllo 世界 🌳 \" quote \\ slash")
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    @Test
    fun roundTripPreservesZeroDimensions() {
        // Edge case: a degenerate 0-sized box. Should still round-trip (model doesn't reject).
        val original = box(w = 0, h = 0)
        assertEquals(original, TextBoxSerializer.fromJson(TextBoxSerializer.toJson(original)))
    }

    // --- AC4.4: malformed input returns null, never throws ---

    @Test
    fun fromJsonReturnsNullOnInvalidJson() {
        assertNull(TextBoxSerializer.fromJson("not json at all"))
        assertNull(TextBoxSerializer.fromJson(""))
        assertNull(TextBoxSerializer.fromJson("{"))
    }

    @Test
    fun fromJsonReturnsNullOnNonObjectRoot() {
        assertNull(TextBoxSerializer.fromJson("[]"))
        assertNull(TextBoxSerializer.fromJson("42"))
        assertNull(TextBoxSerializer.fromJson("\"a string\""))
        assertNull(TextBoxSerializer.fromJson("null"))
    }

    @Test
    fun fromJsonReturnsNullOnMissingRequiredField() {
        // Drop "text" from a complete payload.
        val full = TextBoxSerializer.toJson(box())
        val missingText = full.replace(Regex("\"text\":\\s*\"[^\"]*\",?\\s*"), "")
        assertNull(TextBoxSerializer.fromJson(missingText))
    }

    @Test
    fun fromJsonReturnsNullOnWrongFieldType() {
        // "x" is a string instead of an int.
        val payload = """{"id":"a","x":"abc","y":0,"width":1,"height":1,"text":"t",""" +
            """"fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"BOTTOM"}"""
        assertNull(TextBoxSerializer.fromJson(payload))
    }

    @Test
    fun fromJsonReturnsNullOnUnknownZBand() {
        val payload = """{"id":"a","x":0,"y":0,"width":1,"height":1,"text":"t",""" +
            """"fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"MIDDLE"}"""
        assertNull(TextBoxSerializer.fromJson(payload))
    }

    // --- AC7.3: forward-compat — unknown extra fields are tolerated ---

    @Test
    fun fromJsonIgnoresUnknownExtraFields() {
        val payload = """{"id":"a","x":0,"y":0,"width":1,"height":1,"text":"t",""" +
            """"fontName":"f","fontSize":1,"color":0,"weight":0,"borderWidth":0,"zBand":"BOTTOM",""" +
            """"futureField":"ignored"}"""
        val decoded = TextBoxSerializer.fromJson(payload)
        assertNotNull(decoded)
        assertEquals("a", decoded.id)
    }
}
