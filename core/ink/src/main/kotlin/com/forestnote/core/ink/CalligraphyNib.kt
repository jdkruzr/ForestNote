package com.forestnote.core.ink

/** Shared fallback nib angles for canonical, export, and low-latency preview renderers. */
object CalligraphyNib {
    fun fallbackAngle(kind: BrushKind): Float? = when (kind) {
        BrushKind.CALLIGRAPHY -> 0.75f
        BrushKind.CALLIGRAPHY_REVERSE -> -0.75f
        BrushKind.CALLIGRAPHY_BROAD -> 0f
        BrushKind.CALLIGRAPHY_CHISEL -> 1.05f
        else -> null
    }
}
