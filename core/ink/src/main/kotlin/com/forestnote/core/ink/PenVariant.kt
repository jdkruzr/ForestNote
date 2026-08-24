package com.forestnote.core.ink

/**
 * Variants of the pen ("Fountain") tool group.
 *
 * The toolbar shows the active variant's name; tapping the group cell opens a
 * modal chooser to switch. Width/colour/compositing per variant are resolved by [PenParams].
 */
enum class PenVariant(val brushKind: BrushKind, val displayName: String) {
    /** The v1 pen: logarithmic pressure curve, full-width range. */
    FOUNTAIN(BrushKind.FOUNTAIN, "Fountain"),

    PENCIL_HB(BrushKind.PENCIL_HB, "Pencil HB"),
    PENCIL_2B(BrushKind.PENCIL_2B, "Pencil 2B"),
    PENCIL_4B(BrushKind.PENCIL_4B, "Pencil 4B"),
    PENCIL_6B(BrushKind.PENCIL_6B, "Pencil 6B"),
    PENCIL_8B(BrushKind.PENCIL_8B, "Pencil 8B"),
    BRUSH(BrushKind.BRUSH, "Brush"),
    BALLPOINT(BrushKind.BALLPOINT, "Ballpoint"),
    TRANSLUCENT_MARKER(BrushKind.TRANSLUCENT_MARKER, "Translucent Marker"),
    MARKER(BrushKind.MARKER, "Marker"),

    /** Constant-width pen: pressure ignored, width = average of the preset. */
    FINELINER(BrushKind.FINELINER, "Fineliner"),

    CALLIGRAPHY(BrushKind.CALLIGRAPHY, "Calligraphy"),

    /** Wide, opaque muted gray, composited behind ink (DST_OVER). */
    HIGHLIGHTER(BrushKind.HIGHLIGHTER, "Highlighter"),
    CALLIGRAPHY_REVERSE(BrushKind.CALLIGRAPHY_REVERSE, "Reverse Calligraphy"),
    CALLIGRAPHY_BROAD(BrushKind.CALLIGRAPHY_BROAD, "Broad Calligraphy"),
    CALLIGRAPHY_CHISEL(BrushKind.CALLIGRAPHY_CHISEL, "Chisel Calligraphy"),
    DASHED(BrushKind.DASHED, "Dashed Line")
}
