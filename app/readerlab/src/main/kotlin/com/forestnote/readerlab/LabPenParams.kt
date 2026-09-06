package com.forestnote.readerlab

import com.forestnote.core.ink.*

/** The lab's numeric thickness means maximum width, in virtual units, for every brush. */
internal fun labPenParams(kind: BrushKind, requestedWidth: Int): PenParams {
    val width = requestedWidth.coerceIn(7, 250)
    val fixed = kind in setOf(BrushKind.BALLPOINT, BrushKind.FINELINER, BrushKind.MARKER,
        BrushKind.TRANSLUCENT_MARKER, BrushKind.HIGHLIGHTER)
    return PenParams(if (kind == BrushKind.HIGHLIGHTER) PenParams.HIGHLIGHTER_GRAY else Stroke.COLOR_BLACK,
        if (fixed) width else (width / 5).coerceAtLeast(1), width,
        kind == BrushKind.HIGHLIGHTER, kind)
}
