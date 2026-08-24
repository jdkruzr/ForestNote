package com.forestnote.app.notes

import com.forestnote.core.ink.PageTransform
import com.forestnote.core.format.NotebookMeta

/** Converts a settled editor canvas size into the stable virtual page shape stored per notebook. */
object NotebookAspectPolicy {
    data class Geometry(val width: Int, val height: Int) {
        val longAxis: Int get() = maxOf(width, height)
    }

    fun geometryFor(width: Int, height: Int): Geometry {
        if (width <= 0 || height <= 0) return Geometry(PageTransform.VIRTUAL_SHORT_AXIS, PageTransform.VIRTUAL_LONG_AXIS)
        return if (width <= height) {
            Geometry(PageTransform.VIRTUAL_SHORT_AXIS, Math.round(PageTransform.VIRTUAL_SHORT_AXIS.toFloat() * height / width))
        } else {
            Geometry(Math.round(PageTransform.VIRTUAL_SHORT_AXIS.toFloat() * width / height), PageTransform.VIRTUAL_SHORT_AXIS)
        }
    }

    fun longAxisFor(width: Int, height: Int): Int {
        return geometryFor(width, height).longAxis
    }

    /** Prefer v5's exact creator shape; fall back to the portrait v4/legacy aspect. */
    fun resolve(notebook: NotebookMeta?): Geometry {
        val width = notebook?.pageWidth
        val height = notebook?.pageHeight
        if (width != null && height != null && width > 0 && height > 0) return Geometry(width, height)
        return Geometry(
            PageTransform.VIRTUAL_SHORT_AXIS,
            notebook?.aspectLongAxis?.takeIf { it > 0 } ?: PageTransform.VIRTUAL_LONG_AXIS,
        )
    }
}
