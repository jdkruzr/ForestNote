package com.forestnote.app.notes

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.widget.Button
import android.widget.EditText

/** View counterparts of the Compose library controls; presentation only, no store access. */
internal object LibrarySurfaceStyle {
    fun px(context: Context, dimension: Int) = context.resources.getDimensionPixelSize(dimension)
    fun surface(context: Context, filled: Boolean = false, outlined: Boolean = true) = GradientDrawable().apply {
        setColor(if (filled) Color.BLACK else Color.WHITE)
        if (outlined) setStroke(EinkUiStyle.borderPixels(context), Color.BLACK)
        cornerRadius = context.resources.getDimension(R.dimen.library_surface_radius)
    }

    fun action(button: Button, primary: Boolean = false, outlined: Boolean = true, icon: Int? = null) {
        val context = button.context
        button.isAllCaps = false
        button.letterSpacing = 0f
        EinkUiStyle.text(button, R.dimen.eink_ui_label_text, medium = true)
        val states = arrayOf(intArrayOf(android.R.attr.state_pressed), intArrayOf(android.R.attr.state_focused), intArrayOf())
        button.backgroundTintList = null
        button.stateListAnimator = null
        button.background = StateListDrawable().apply {
            addState(states[0], surface(context, filled = true, outlined = outlined))
            addState(states[1], surface(context, filled = true, outlined = outlined))
            addState(states[2], surface(context, filled = primary, outlined = outlined))
        }
        val colors = ColorStateList(states, intArrayOf(Color.WHITE, Color.WHITE, if (primary) Color.WHITE else Color.BLACK))
        button.setTextColor(colors)
        button.minWidth = 0; button.minimumWidth = 0
        button.minHeight = px(context, R.dimen.eink_ui_control_height); button.minimumHeight = button.minHeight
        val inset = px(context, R.dimen.library_surface_inset)
        button.setPaddingRelative(inset, 0, inset, 0)
        button.gravity = Gravity.CENTER
        if (icon != null) {
            val drawable = context.getDrawable(icon)!!.mutate()
            val size = px(context, R.dimen.eink_ui_small_icon)
            drawable.setBounds(0, 0, size, size)
            drawable.setTintList(colors)
            button.setCompoundDrawablesRelative(drawable, null, null, null)
            button.compoundDrawablePadding = px(context, R.dimen.library_surface_gap)
        }
    }

    fun input(view: EditText, search: Boolean = false) {
        EinkUiStyle.text(view, R.dimen.eink_ui_label_text)
        view.setHintTextColor(Color.BLACK)
        view.backgroundTintList = null
        view.background = surface(view.context)
        val inset = px(view.context, R.dimen.library_surface_inset)
        view.setPaddingRelative(inset, inset / 2, inset, inset / 2)
        if (search) {
            val icon = view.context.getDrawable(R.drawable.ic_search)!!.mutate()
            val size = px(view.context, R.dimen.eink_ui_small_icon)
            icon.setBounds(0, 0, size, size); icon.setTint(Color.BLACK)
            view.setCompoundDrawablesRelative(icon, null, null, null)
            view.compoundDrawablePadding = px(view.context, R.dimen.library_surface_gap)
        }
    }
}
