package com.forestnote.app.notes

import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import com.forestnote.core.format.FolderMeta

// pattern: Imperative Shell
// Renders BreadcrumbLogic.segments into header TextViews; the segment model is pure.

/**
 * Renders the Library breadcrumb (AC4.6 / AC4.7) from [BreadcrumbLogic.segments] into a
 * header [container]. Interactive segments are clickable and call [onJump]; the current
 * folder and the collapsed "…" are non-interactive (the current segment is bold).
 */
class BreadcrumbView(
    private val container: LinearLayout,
    private val onJump: (folderId: String?) -> Unit
) {
    fun render(path: List<FolderMeta>) {
        container.removeAllViews()
        val segments = BreadcrumbLogic.segments(path)
        segments.forEachIndexed { i, seg ->
            if (i > 0) container.addView(separator())
            container.addView(segmentView(seg))
        }
    }

    private fun separator(): TextView = TextView(container.context).apply {
        text = " / "
        gravity = Gravity.CENTER_VERTICAL
        textSize = BREADCRUMB_TEXT_SP
    }

    private fun segmentView(seg: BreadcrumbLogic.Segment): TextView =
        TextView(container.context).apply {
            text = seg.label
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
            // Treat the breadcrumb as a screen title — system-default ~14sp reads small,
            // especially for the root "Library" segment. The breadcrumb has its own row
            // (see view_library.xml) so this no longer competes with the toolbar height.
            textSize = BREADCRUMB_TEXT_SP
            if (seg.interactive) {
                isClickable = true
                val tv = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                setBackgroundResource(tv.resourceId)
                setOnClickListener { onJump(seg.folderId) }
            } else {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
        }

    private companion object {
        // "A little larger" than the ~14sp system default. Picked so the root segment
        // reads as a screen title without crowding the new dedicated breadcrumb row.
        const val BREADCRUMB_TEXT_SP = 18f
    }
}
