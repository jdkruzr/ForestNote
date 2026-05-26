package com.forestnote.core.ink

/**
 * Maps each [PenWidthLevel] to a base `(min, max)` width pair in virtual units (short axis =
 * 10,000), which [PenParams.of] then transforms per variant. [PenWidthLevel.M] is exactly the
 * v1 default `(7, 35)` (= [Stroke.DEFAULT_WIDTH_MIN]/[Stroke.DEFAULT_WIDTH_MAX]) so default
 * rendering is byte-for-byte unchanged; the other levels scale around it (shape informed by
 * WiNote's 5-level progression — the XS/S/L/XL numbers are a starting point, tuned on-device).
 */
object PenWidthScale {
    fun pair(level: PenWidthLevel): Pair<Int, Int> = when (level) {
        PenWidthLevel.XS -> 3 to 15
        PenWidthLevel.S -> 5 to 24
        PenWidthLevel.M -> Stroke.DEFAULT_WIDTH_MIN to Stroke.DEFAULT_WIDTH_MAX // (7, 35), the v1 default
        PenWidthLevel.L -> 10 to 50
        PenWidthLevel.XL -> 14 to 70
    }
}
