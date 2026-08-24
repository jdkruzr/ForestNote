package com.forestnote.core.ink

/** Shared, vendor-neutral opacity rules for canonical brushes and firmware previews. */
object BrushAppearance {
    const val TRANSLUCENT_MARKER_ALPHA = 80

    fun alpha(kind: BrushKind): Int = when (kind) {
        BrushKind.TRANSLUCENT_MARKER -> TRANSLUCENT_MARKER_ALPHA
        else -> 255
    }

    /**
     * Vendor ink layers commonly discard bitmap/stroke alpha. Pre-blend against the white page so
     * their transient preview has the same apparent shade as the canonical translucent stroke.
     */
    fun previewColorOnWhite(kind: BrushKind, argb: Int): Int {
        val alpha = alpha(kind)
        if (alpha == 255) return argb or 0xFF000000.toInt()
        val inverse = 255 - alpha
        fun channel(shift: Int): Int {
            val source = argb ushr shift and 0xFF
            return (source * alpha + 255 * inverse + 127) / 255
        }
        return 0xFF000000.toInt() or
            (channel(16) shl 16) or
            (channel(8) shl 8) or
            channel(0)
    }
}
