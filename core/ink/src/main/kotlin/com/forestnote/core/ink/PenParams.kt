package com.forestnote.core.ink

/**
 * Per-variant rendering parameters for the pen group, resolved from the active
 * [PenVariant] and the base width preset. Pure — no Android types. The
 * [behind] flag maps to `PorterDuff.Mode.DST_OVER` in the rendering layer.
 *
 * Width is expressed in virtual units (the same space as [Stroke] preset widths).
 * Fixed-width variants set `wMin == wMax` so the pressure curve produces a
 * constant width naturally.
 */
data class PenParams(
    val color: Int,
    val wMin: Int,
    val wMax: Int,
    val behind: Boolean,
) {
    companion object {
        /** Opaque black ink (Fountain, Fineliner). */
        val BLACK: Int = 0xFF000000.toInt()

        /**
         * Opaque muted gray for the highlighter. OPAQUE (alpha 0xFF) is required:
         * with DST_OVER, opaque-over-opaque cannot darken on overlap, which is the
         * hard no-darkening guarantee. (Diverges from WiNote's translucent alpha.)
         */
        val HIGHLIGHTER_GRAY: Int = 0xFFDCDCDC.toInt()

        /**
         * Resolve params for [variant] at width [level] (AC10). The level picks the base
         * `(min, max)` pair via [PenWidthScale]; the per-variant transform below is unchanged
         * from v1, so `(variant, PenWidthLevel.M)` reproduces the original `(7, 35)` rendering.
         */
        fun of(variant: PenVariant, level: PenWidthLevel): PenParams {
            val (baseMin, baseMax) = PenWidthScale.pair(level)
            return when (variant) {
                PenVariant.FOUNTAIN ->
                    PenParams(BLACK, baseMin, baseMax, behind = false)
                PenVariant.FINELINER -> {
                    val w = (baseMin + baseMax) / 2
                    PenParams(BLACK, w, w, behind = false)
                }
                PenVariant.HIGHLIGHTER -> {
                    val w = baseMax * 5 / 2 // ≈ 2.5× base max, integer
                    PenParams(HIGHLIGHTER_GRAY, w, w, behind = true)
                }
            }
        }
    }
}
