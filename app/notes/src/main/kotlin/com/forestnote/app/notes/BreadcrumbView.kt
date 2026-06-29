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
        if (path.isEmpty()) {
            // At root, render a dimmed italic placeholder so the user sees at a glance
            // that this row is the folder-path breadcrumb area. The "Library" title in
            // the toolbar row above already names where we are.
            container.addView(rootPlaceholder())
            return
        }
        // In a folder: render only the folder segments. The leading "Library" root segment
        // returned by BreadcrumbLogic.segments is dropped here because the toolbar row's
        // clickable "Library" title + the Up button already cover "jump to root" and "go
        // one level up" — showing "Library" twice would be redundant noise.
        val segments = BreadcrumbLogic.segments(path).dropWhile { it.label == BreadcrumbLogic.ROOT_LABEL }
        segments.forEachIndexed { i, seg ->
            if (i > 0) container.addView(separator())
            container.addView(segmentView(seg))
        }
    }

    private fun rootPlaceholder(): TextView = TextView(container.context).apply {
        text = "Root"
        gravity = Gravity.CENTER_VERTICAL
        textSize = BREADCRUMB_TEXT_SP
        maxLines = 1
        setTypeface(typeface, android.graphics.Typeface.ITALIC)
        alpha = 0.5f
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
        // A little larger than the ~14sp system default without crowding narrow devices.
        const val BREADCRUMB_TEXT_SP = 16f
    }
}
