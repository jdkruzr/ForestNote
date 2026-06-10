package com.forestnote.app.notes.recognize

import com.forestnote.core.ink.Stroke

/**
 * ML Kit Digital Ink works best on a word/line-scale ink sample. Full notebook pages can
 * contain hundreds of strokes; feeding the whole page as one request commonly returns a
 * tiny, useless candidate. Segment spatially into line-like bands and recognize each band.
 */
object FullPageOcr {
    fun segmentLines(strokes: List<Stroke>): List<List<Stroke>> {
        val boxes = strokes.mapNotNull { stroke ->
            bounds(stroke)?.let { StrokeBox(stroke, it) }
        }.sortedWith(compareBy<StrokeBox> { it.bounds.centerY }.thenBy { it.bounds.minX })
        if (boxes.isEmpty()) return emptyList()

        val lines = mutableListOf<MutableList<StrokeBox>>()
        for (box in boxes) {
            val line = lines.lastOrNull()
            if (line == null || !belongsToLine(line, box)) {
                lines += mutableListOf(box)
            } else {
                line += box
            }
        }
        return lines
            .map { line -> line.sortedBy { it.bounds.minX }.map { it.stroke } }
            .filter { it.isNotEmpty() }
    }

    suspend fun recognizePage(
        strokes: List<Stroke>,
        langTag: String,
        recognizer: Recognizer,
        onProgress: (String) -> Unit = {},
    ): Result<RecognizedText> {
        val lines = segmentLines(strokes)
        if (lines.isEmpty()) {
            return recognizer.recognize(strokes, langTag)
        }

        val parts = mutableListOf<String>()
        for ((index, line) in lines.withIndex()) {
            onProgress("recognizing ${index + 1}/${lines.size}")
            val result = recognizer.recognize(line, langTag)
            val recognized = result.getOrNull()
            val error = result.exceptionOrNull()
            if (error is RecognizerError.ModelMissing) return Result.failure(error)
            val text = recognized?.text?.trim()
            if (!text.isNullOrBlank()) parts += text
        }

        return if (parts.isNotEmpty()) {
            Result.success(RecognizedText(parts.joinToString("\n"), emptyList()))
        } else {
            recognizer.recognize(strokes, langTag)
        }
    }

    private fun belongsToLine(line: List<StrokeBox>, box: StrokeBox): Boolean {
        val medianCenterY = line.map { it.bounds.centerY }.sorted().let { it[it.size / 2] }
        val medianHeight = line.map { it.bounds.height }.sorted().let { it[it.size / 2] }
            .coerceAtLeast(MIN_LINE_HEIGHT)
        val yTolerance = (medianHeight * LINE_Y_TOLERANCE_FACTOR).toInt().coerceAtLeast(MIN_LINE_Y_TOLERANCE)
        return kotlin.math.abs(box.bounds.centerY - medianCenterY) <= yTolerance
    }

    private fun bounds(stroke: Stroke): Bounds? {
        if (stroke.points.isEmpty()) return null
        var minX = Int.MAX_VALUE
        var minY = Int.MAX_VALUE
        var maxX = Int.MIN_VALUE
        var maxY = Int.MIN_VALUE
        for (p in stroke.points) {
            minX = minOf(minX, p.x)
            minY = minOf(minY, p.y)
            maxX = maxOf(maxX, p.x)
            maxY = maxOf(maxY, p.y)
        }
        return Bounds(minX, minY, maxX, maxY)
    }

    private data class StrokeBox(val stroke: Stroke, val bounds: Bounds)

    private data class Bounds(val minX: Int, val minY: Int, val maxX: Int, val maxY: Int) {
        val centerY: Int get() = (minY + maxY) / 2
        val height: Int get() = (maxY - minY).coerceAtLeast(1)
    }

    private const val LINE_Y_TOLERANCE_FACTOR = 1.1f
    private const val MIN_LINE_HEIGHT = 40
    private const val MIN_LINE_Y_TOLERANCE = 160
}
