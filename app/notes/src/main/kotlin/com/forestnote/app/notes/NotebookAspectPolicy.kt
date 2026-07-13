package com.forestnote.app.notes

import com.forestnote.core.ink.PageTransform

/** Converts a settled editor canvas size into the stable virtual page shape stored per notebook. */
object NotebookAspectPolicy {
    fun longAxisFor(width: Int, height: Int): Int {
        val shortPixels = minOf(width, height)
        val longPixels = maxOf(width, height)
        if (shortPixels <= 0) return PageTransform.VIRTUAL_LONG_AXIS
        return Math.round(
            PageTransform.VIRTUAL_SHORT_AXIS.toFloat() * longPixels / shortPixels
        )
    }
}
