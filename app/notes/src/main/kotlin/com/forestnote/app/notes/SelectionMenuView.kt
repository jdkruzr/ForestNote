package com.forestnote.app.notes

import android.content.Context
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
 * Floating action pill for a lasso selection (library-and-tools.AC2.3): a horizontal
 * row of "N selected" + Cut / Copy / Recognize / To-do / Delete, anchored above the
 * selection's bounding box (or below when there's no room above).
 *
 * Built programmatically and styled like [ToolBar]'s dropdown (white bg, 1px border,
 * no elevation/animation) to stay e-ink friendly. Recognize/To-do are wired to
 * callbacks but stubbed by the caller until Settings (B1) provides a URL — Caveat 1.
 */
class SelectionMenuView(private val isEInk: Boolean) {

    /** Action callbacks for the pill's buttons. */
    class Callbacks(
        val onCut: () -> Unit,
        val onCopy: () -> Unit,
        val onRecognize: () -> Unit,
        val onTodo: () -> Unit,
        val onDelete: () -> Unit
    )

    private var popup: PopupWindow? = null

    /**
     * Show the pill anchored over [screenBounds] (DrawView-local screen coords) of
     * [anchor]. Positions above the box, falling back to below when too near the top.
     * Cut/Copy/Delete dismiss after firing; Recognize/To-do leave it to the caller.
     */
    fun show(anchor: View, count: Int, screenBounds: RectF, callbacks: Callbacks) {
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

        container.addView(TextView(ctx).apply {
            text = "$count selected"
            textSize = 13f
            setTextColor(Color.BLACK)
            setPadding(0, 0, gap, 0)
        })

        val pop = PopupWindow(
            container,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true // focusable: tap-outside dismisses
        )
        pop.isOutsideTouchable = true
        if (isEInk) pop.elevation = 0f
        popup = pop

        fun button(label: String, dismissAfter: Boolean, action: () -> Unit) {
            container.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(Color.BLACK)
                setPadding(gap, padV, gap, padV)
                isClickable = true
                setOnClickListener {
                    action()
                    if (dismissAfter) dismiss()
                }
            })
        }

        button("Cut", dismissAfter = true) { callbacks.onCut() }
        button("Copy", dismissAfter = true) { callbacks.onCopy() }
        button("Recognize", dismissAfter = false) { callbacks.onRecognize() }
        button("To-do", dismissAfter = false) { callbacks.onTodo() }
        button("Delete", dismissAfter = true) { callbacks.onDelete() }

        // Measure to decide above/below and to center horizontally over the box.
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
        if (y < loc[1]) y = boxBottom + margin // no room above -> below the box
        var x = boxCenterX - pillW / 2
        if (x < loc[0]) x = loc[0] // keep on the anchor's left edge at minimum

        pop.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }
}
