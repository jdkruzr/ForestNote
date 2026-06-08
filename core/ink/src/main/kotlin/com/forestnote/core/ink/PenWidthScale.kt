package com.forestnote.core.ink

/**
 * Maps each [PenWidthLevel] to a base `(min, max)` width pair in virtual units (short axis =
 * 10,000), which [PenParams.of] then transforms per variant. [PenWidthLevel.LEVEL_4] is exactly the
 * v1 default `(7, 35)` (= [Stroke.DEFAULT_WIDTH_MIN]/[Stroke.DEFAULT_WIDTH_MAX]) so default
 * rendering is byte-for-byte unchanged. The old XS/S/M/L/XL anchors map to 1/2/4/6/7, with
 * 3 and 5 filling the new intermediate steps.
 */
object PenWidthScale {
    fun pair(level: PenWidthLevel): Pair<Int, Int> = when (level) {
        PenWidthLevel.LEVEL_1 -> 3 to 15
        PenWidthLevel.LEVEL_2 -> 5 to 24
        PenWidthLevel.LEVEL_3 -> 6 to 30
        PenWidthLevel.LEVEL_4 -> Stroke.DEFAULT_WIDTH_MIN to Stroke.DEFAULT_WIDTH_MAX // (7, 35), the v1 default
        PenWidthLevel.LEVEL_5 -> 8 to 42
        PenWidthLevel.LEVEL_6 -> 10 to 50
        PenWidthLevel.LEVEL_7 -> 14 to 70
    }
}
