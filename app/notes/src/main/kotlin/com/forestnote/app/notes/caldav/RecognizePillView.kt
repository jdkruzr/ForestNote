package com.forestnote.app.notes.caldav

import android.graphics.RectF
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import com.forestnote.app.notes.R

/**
 * Floating pill shown after a lasso-recognize succeeds, when CalDAV is
 * configured. Acts as a confirmation step that surfaces the recognized text
 * and a single "Create task" affordance.
 *
 * Modeled on the existing lasso `SelectionMenuView`: non-focusable so the
 * canvas keeps receiving stylus input under the pill, dismissed by tapping a
 * full-screen transparent scrim or by Back.
 */
class RecognizePillView {

    private var root: View? = null
    private var scrim: View? = null
    private var host: ViewGroup? = null

    val isShowing: Boolean get() = root != null

    /**
     * Attach the pill to [host], anchored near [anchorScreenBounds] (the lasso
     * bounds in screen coordinates). [onCreateTask] fires with the original
     * recognized text when the user taps Create task; the pill auto-hides.
     */
    fun show(
        host: ViewGroup,
        recognizedText: String,
        anchorScreenBounds: RectF?,
        onCreateTask: (text: String) -> Unit,
        onDismiss: () -> Unit = {},
    ) {
        if (isShowing) return
        this.host = host

        // Transparent scrim that catches taps-outside so the user can dismiss without
        // hitting the pill itself. Kept above the canvas/pill so it's the first thing
        // a tap-outside hits.
        val scrimView = View(host.context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
            setOnClickListener {
                hide()
                onDismiss()
            }
        }
        host.addView(scrimView)
        scrim = scrimView

        val pill = LayoutInflater.from(host.context)
            .inflate(R.layout.view_recognize_pill, host, false) as ViewGroup
        pill.findViewById<TextView>(R.id.pill_text).text =
            CalDavTaskSheetLogic.pillLabel(recognizedText, maxLen = 40)
        pill.findViewById<TextView>(R.id.btn_create_task).setOnClickListener {
            // Hide first so the caller can immediately show another overlay (the
            // task sheet) without z-order shenanigans.
            hide()
            onCreateTask(recognizedText)
        }

        // Position the pill above the lasso bounds; fall back to top-centre if no anchor.
        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        if (anchorScreenBounds != null) {
            // Measure first so we know the pill's height for the y placement.
            pill.measure(
                View.MeasureSpec.makeMeasureSpec(host.width, View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(host.height, View.MeasureSpec.AT_MOST),
            )
            val pillW = pill.measuredWidth
            val pillH = pill.measuredHeight
            val cx = anchorScreenBounds.centerX().toInt()
            // Prefer above; if no room above, place below.
            val y = if (anchorScreenBounds.top - pillH - PILL_GAP_PX >= 0) {
                (anchorScreenBounds.top - pillH - PILL_GAP_PX).toInt()
            } else {
                (anchorScreenBounds.bottom + PILL_GAP_PX).toInt()
            }
            lp.leftMargin = (cx - pillW / 2).coerceIn(PILL_EDGE_PAD, host.width - pillW - PILL_EDGE_PAD)
            lp.topMargin = y.coerceIn(PILL_EDGE_PAD, host.height - pillH - PILL_EDGE_PAD)
        } else {
            lp.leftMargin = host.width / 2 - 100
            lp.topMargin = PILL_EDGE_PAD
        }
        pill.layoutParams = lp
        host.addView(pill)
        root = pill
    }

    fun hide() {
        root?.let { host?.removeView(it) }
        root = null
        scrim?.let { host?.removeView(it) }
        scrim = null
        host = null
    }

    companion object {
        // Small visual gap between the pill and the lasso bounds it's anchored to.
        private const val PILL_GAP_PX = 12

        // Keep the pill at least this far from screen edges.
        private const val PILL_EDGE_PAD = 16
    }
}
