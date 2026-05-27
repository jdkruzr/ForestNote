package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/**
 * Floating action pill for a selected text box: Edit / Options / Delete, anchored above the box
 * (or below when there's no room). Mirrors [SelectionMenuView]'s e-ink-friendly styling (white bg,
 * 1px border, no elevation) and its non-focusable window so touches outside reach the canvas (so a
 * drag on the box still moves it). Dismissed explicitly by the caller (selection cleared, tool
 * switch, edit/delete).
 */
class TextBoxMenuView(private val isEInk: Boolean) {

    class Callbacks(
        val onEdit: () -> Unit,
        val onOptions: () -> Unit,
        val onDelete: () -> Unit
    )

    private var popup: PopupWindow? = null

    fun show(anchor: View, screenBounds: RectF, callbacks: Callbacks) {
        dismiss()
        val ctx = anchor.context
        val density = ctx.resources.displayMetrics.density
        val padH = (12 * density).toInt()
        val padV = (8 * density).toInt()
        val gap = (8 * density).toInt()

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(1, Color.BLACK)
            }
        }

        val pop = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            false // non-focusable: touches outside reach the canvas (drag-to-move/resize)
        )
        pop.isOutsideTouchable = false
        if (isEInk) pop.elevation = 0f
        popup = pop

        fun button(label: String, action: () -> Unit) {
            container.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(Color.BLACK)
                setPadding(gap, padV, gap, padV)
                isClickable = true
                setOnClickListener {
                    action()
                    dismiss()
                }
            })
        }

        button("Edit") { callbacks.onEdit() }
        button("Options") { callbacks.onOptions() }
        button("Delete") { callbacks.onDelete() }

        container.measure(
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val pillW = container.measuredWidth
        val pillH = container.measuredHeight
        val margin = (6 * density).toInt()

        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val boxTop = loc[1] + screenBounds.top.toInt()
        val boxBottom = loc[1] + screenBounds.bottom.toInt()
        val boxCenterX = loc[0] + screenBounds.centerX().toInt()

        var y = boxTop - pillH - margin
        if (y < loc[1]) y = boxBottom + margin
        var x = boxCenterX - pillW / 2
        if (x < loc[0]) x = loc[0]

        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
