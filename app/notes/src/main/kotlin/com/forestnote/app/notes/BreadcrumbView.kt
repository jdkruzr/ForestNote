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
    }

    private fun segmentView(seg: BreadcrumbLogic.Segment): TextView =
        TextView(container.context).apply {
            text = seg.label
            gravity = Gravity.CENTER_VERTICAL
            maxLines = 1
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
}
