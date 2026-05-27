package com.forestnote.core.ink

/**
 * An immutable text box: a placed rectangle holding wrapped text, ready for storage, sync, and
 * rendering. Like [Stroke], geometry lives in virtual coordinate space (short axis = 10,000) so a
 * box renders proportionally across device sizes; [fontSize] is likewise virtual. The full [text]
 * is always retained even when the box is resized too small to show it all — rendering clips to the
 * box, the data does not.
 *
 * Identity is a client-minted ULID assigned at construction (no "unsaved" state), matching the
 * stroke/page/notebook convention.
 *
 * @param id Stable ULID identity, minted at creation
 * @param x Left edge in virtual units
 * @param y Top edge in virtual units
 * @param width Box width in virtual units
 * @param height Box height in virtual units (auto-grows downward as text overruns)
 * @param text The wrapped text content — the searchable / server-mutable payload
 * @param fontName Stable font identifier (a /system/fonts basename); resolved at render with fallback
 * @param fontSize Text size in virtual units
 * @param color Text color as ARGB int
 * @param weight Font weight (400 = normal, 700 = bold)
 * @param borderWidth Border thickness in screen px (0 = no/transparent border, 2 = a hairline)
 * @param zBand Whether the box paints below ink ([ZBand.BOTTOM]) or above everything ([ZBand.TOP])
 */
data class TextBox(
    val id: String = Ulid.generate(),
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
    val text: String,
    val fontName: String,
    val fontSize: Int,
    val color: Int = COLOR_BLACK,
    val weight: Int = WEIGHT_NORMAL,
    val borderWidth: Int = DEFAULT_BORDER_WIDTH,
    val zBand: ZBand = ZBand.BOTTOM
) {
    companion object {
        const val COLOR_BLACK = 0xFF000000.toInt()
        const val WEIGHT_NORMAL = 400
        const val DEFAULT_BORDER_WIDTH = 2
    }
}

/**
 * Which paint band a [TextBox] occupies. Stored as the integer `z` column ([value]); two fixed
 * bands rather than arbitrary z-order (see docs/design-notes/future-directions.md). [BOTTOM] paints
 * above the template but below ink; [TOP] paints above everything.
 */
enum class ZBand(val value: Int) {
    BOTTOM(0),
    TOP(1);

    companion object {
        /** Map a stored `z` value back to a band (anything non-zero = TOP). */
        fun fromValue(z: Int): ZBand = if (z == TOP.value) TOP else BOTTOM
    }
}
