package com.forestnote.app.notes

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.widget.TextView

/** Shared native UI weight. Density/font-aware; unrelated to ink/document coordinates. */
internal object EinkUiStyle {
    fun borderPixels(context: Context) = context.resources.getDimensionPixelSize(R.dimen.eink_ui_border).coerceAtLeast(3)

    fun frame(context: Context, transparent: Boolean = false) = GradientDrawable().apply {
        setColor(if (transparent) Color.TRANSPARENT else Color.WHITE)
        setStroke(borderPixels(context), Color.BLACK)
        cornerRadius = context.resources.getDimension(R.dimen.eink_ui_corner_radius)
    }

    fun text(view: TextView, dimension: Int, medium: Boolean = false) {
        view.setTextSize(TypedValue.COMPLEX_UNIT_PX, view.resources.getDimension(dimension))
        view.setTextColor(Color.BLACK)
        if (medium) view.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
}
