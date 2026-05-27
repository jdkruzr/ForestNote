package com.forestnote.app.notes

import android.graphics.Color
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.forestnote.core.ink.TextBox

// pattern: Imperative Shell
// Owns the in-place editing EditText overlay and the IME; reports the final (text, pixel height)
// back to DrawView via [onCommit]. Holds no model state — the box lives in DrawView.

/**
 * In-place WYSIWYG text-box editor. While active, a transparent multi-line [EditText] sits over the
 * box on the canvas, wrapping text to the box width and growing downward as the user types (its
 * height is WRAP_CONTENT above a minimum of the box's drawn height). On commit it hands DrawView the
 * typed text and the editor's final pixel height, which DrawView maps back to the box's virtual
 * geometry.
 */
class TextBoxEditor(
    private val container: FrameLayout,
    private val fontResolver: (name: String, weight: Int) -> Typeface,
    private val onCommit: (id: String, text: String, screenHeightPx: Float) -> Unit,
    /** Fired after the IME comes up (begin) and after it goes down (teardown) — the keyboard
     *  shifts the (panned) layout, so the host GC-refreshes the editor once the shift settles. */
    private val onImeShifted: () -> Unit = {}
) {
    private var editText: EditText? = null
    private var boxId: String? = null

    val isActive: Boolean get() = editText != null

    /**
     * Open the editor over [box] at [screenRect], with text rendered at [textSizePx] (the box's
     * virtual font size already mapped to screen px). Commits any prior edit first.
     */
    fun begin(box: TextBox, screenRect: RectF, textSizePx: Float) {
        if (isActive) commit()
        val ctx = container.context
        val et = EditText(ctx).apply {
            id = View.generateViewId()
            // Transparent over the canvas, except an optional matching border while editing.
            background = if (box.borderWidth > 0) {
                GradientDrawable().apply {
                    setColor(Color.TRANSPARENT)
                    setStroke(box.borderWidth, Color.BLACK)
                }
            } else null
            setPadding(0, 0, 0, 0)
            includeFontPadding = false // match StaticLayout.setIncludePad(false)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            setHorizontallyScrolling(false) // wrap to width instead of scrolling sideways
            setTextColor(box.color)
            typeface = fontResolver(box.fontName, box.weight)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, textSizePx)
            setText(box.text)
            setSelection(text?.length ?: 0)
            minimumHeight = screenRect.height().toInt().coerceAtLeast(1)
        }
        val lp = FrameLayout.LayoutParams(
            screenRect.width().toInt().coerceAtLeast(1),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            leftMargin = screenRect.left.toInt()
            topMargin = screenRect.top.toInt()
        }
        container.addView(et, lp)
        editText = et
        boxId = box.id

        et.requestFocus()
        val imm = ctx.getSystemService(InputMethodManager::class.java)
        et.post { imm?.showSoftInput(et, InputMethodManager.SHOW_IMPLICIT) }
        onImeShifted() // keyboard coming up shifts the layout → host clears the ghost
    }

    /** Commit the active edit (no-op if inactive): report text + final height, then tear down. */
    fun commit() {
        val et = editText ?: return
        val id = boxId ?: return
        val text = et.text?.toString() ?: ""
        val heightPx = et.height.toFloat() // grew with content via WRAP_CONTENT
        teardown(et)
        onCommit(id, text, heightPx)
    }

    private fun teardown(et: EditText) {
        val imm = container.context.getSystemService(InputMethodManager::class.java)
        imm?.hideSoftInputFromWindow(et.windowToken, 0)
        container.removeView(et)
        editText = null
        boxId = null
        onImeShifted() // keyboard going down shifts the layout back → host clears the ghost
    }
}
