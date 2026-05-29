package com.forestnote.app.notes

import com.forestnote.core.ink.TextBox
import com.forestnote.core.ink.ZBand
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Hand-written JSON serializer for [TextBox] (lasso-textboxes.AC4.3, AC4.4).
 *
 * Mirrors the *intent* of the design's "TextBoxSerializer like StrokeSerializer":
 * a JSON encoder for the future B1 `app_state.clipboard_json` round-trip. The
 * design plan referred to a hypothetical `org.json.JSONObject` `StrokeSerializer`
 * which does not exist (today's [com.forestnote.core.format.StrokeSerializer] is a
 * binary BLOB codec for `stroke.points`). This serializer uses `kotlinx.serialization.json`
 * instead, which is the only JSON library on the codebase's classpath (Settings.kt,
 * SyncWire.kt). A future serialization-cleanup pass can migrate both serializers
 * together if a single JSON shape is desired.
 *
 * Defensive contract:
 * - [toJson] never throws.
 * - [fromJson] returns null on ANY of: invalid JSON, non-object root, missing required
 *   field, wrong type for any field, unknown [ZBand] string. It never throws.
 *
 * Encoded shape (every field present, no defaults skipped — wire is explicit):
 * ```
 * {
 *   "id": "01H...",
 *   "x": 100, "y": 200, "width": 800, "height": 320,
 *   "text": "hello",
 *   "fontName": "Roboto-Regular.ttf",
 *   "fontSize": 32,
 *   "color": -16777216,
 *   "weight": 400,
 *   "borderWidth": 2,
 *   "zBand": "BOTTOM"
 * }
 * ```
 *
 * `color` is the signed-int ARGB value as-is (Int range). Matches the in-memory shape;
 * sync wire (`SyncWire.textBoxCols`) does the unsigned-Long encoding separately at a
 * different boundary.
 */
object TextBoxSerializer {

    private val json = Json { ignoreUnknownKeys = true }

    fun toJson(box: TextBox): String = buildJsonObject {
        put("id", JsonPrimitive(box.id))
        put("x", JsonPrimitive(box.x))
        put("y", JsonPrimitive(box.y))
        put("width", JsonPrimitive(box.width))
        put("height", JsonPrimitive(box.height))
        put("text", JsonPrimitive(box.text))
        put("fontName", JsonPrimitive(box.fontName))
        put("fontSize", JsonPrimitive(box.fontSize))
        put("color", JsonPrimitive(box.color))
        put("weight", JsonPrimitive(box.weight))
        put("borderWidth", JsonPrimitive(box.borderWidth))
        put("zBand", JsonPrimitive(box.zBand.name))
    }.toString()

    fun fromJson(raw: String): TextBox? {
        val obj: JsonObject = runCatching { json.parseToJsonElement(raw) }
            .getOrNull()
            ?.let { it as? JsonObject }
            ?: return null

        return runCatching {
            TextBox(
                id = obj.stringOrNull("id") ?: return null,
                x = obj.intOrNullField("x") ?: return null,
                y = obj.intOrNullField("y") ?: return null,
                width = obj.intOrNullField("width") ?: return null,
                height = obj.intOrNullField("height") ?: return null,
                text = obj.stringOrNull("text") ?: return null,
                fontName = obj.stringOrNull("fontName") ?: return null,
                fontSize = obj.intOrNullField("fontSize") ?: return null,
                color = obj.intOrNullField("color") ?: return null,
                weight = obj.intOrNullField("weight") ?: return null,
                borderWidth = obj.intOrNullField("borderWidth") ?: return null,
                zBand = parseZBand(obj["zBand"]) ?: return null,
            )
        }.getOrNull()
    }

    private fun parseZBand(el: JsonElement?): ZBand? {
        val name = (el as? JsonPrimitive)?.contentOrNull ?: return null
        return when (name) {
            "BOTTOM" -> ZBand.BOTTOM
            "TOP" -> ZBand.TOP
            else -> null
        }
    }

    private fun JsonObject.stringOrNull(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    // Distinct name to avoid clashing with the imported [intOrNull] extension on JsonPrimitive.
    private fun JsonObject.intOrNullField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.intOrNull
}
