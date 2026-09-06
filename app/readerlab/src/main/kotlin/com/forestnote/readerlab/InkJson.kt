package com.forestnote.readerlab

import com.forestnote.core.ink.*
import org.json.JSONArray
import org.json.JSONObject

internal object InkJson {
    fun read(array: JSONArray): List<Stroke> = (0 until array.length()).map { i ->
        val s = array.getJSONObject(i)
        val points = s.getJSONArray("points")
        Stroke(
            id = s.getString("id"),
            points = (0 until points.length()).map { j ->
                val p = points.getJSONObject(j)
                StrokePoint(p.getInt("x"), p.getInt("y"), p.getInt("pressure"), p.getLong("timestampMs"),
                    if (p.isNull("tiltRadians")) null else p.getDouble("tiltRadians").toFloat(),
                    if (p.isNull("orientationRadians")) null else p.getDouble("orientationRadians").toFloat())
            },
            color = s.optInt("color", Stroke.COLOR_BLACK),
            penWidthMin = s.optInt("penWidthMin", 7), penWidthMax = s.optInt("penWidthMax", 35),
            brushKind = runCatching { BrushKind.valueOf(s.optString("brushKind", "FOUNTAIN")) }.getOrDefault(BrushKind.FOUNTAIN),
            brushVersion = s.optInt("brushVersion", 1), brushSeed = s.optInt("brushSeed", BrushKind.seedFor(s.getString("id"))),
        )
    }
    fun write(strokes: List<Stroke>): JSONArray = JSONArray().apply {
        strokes.forEach { s -> put(JSONObject().apply {
            put("id", s.id); put("color", s.color); put("penWidthMin", s.penWidthMin); put("penWidthMax", s.penWidthMax)
            put("brushKind", s.brushKind.name); put("brushVersion", s.brushVersion); put("brushSeed", s.brushSeed)
            put("points", JSONArray().apply { s.points.forEach { p -> put(JSONObject().apply {
                put("x", p.x); put("y", p.y); put("pressure", p.pressure); put("timestampMs", p.timestampMs)
                put("tiltRadians", p.tiltRadians ?: JSONObject.NULL); put("orientationRadians", p.orientationRadians ?: JSONObject.NULL)
            }) } })
        }) }
    }
}
