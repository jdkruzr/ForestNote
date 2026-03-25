package com.forestnote.core.ink

/**
 * The active tool determines how touch input is interpreted.
 */
sealed class Tool {
    /** Draw strokes with the stylus. */
    data object Pen : Tool()

    /** Erase an entire stroke when any part of it is touched. */
    data object StrokeEraser : Tool()

    /** Erase only the touched region, splitting strokes at boundaries. */
    data object PixelEraser : Tool()
}
