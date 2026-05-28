package com.forestnote.app.notes

// pattern: Single Source of Truth (small, pure)
// Text size presets shared between the ToolBar's Text-cell chooser and the per-text-box
// Options dialog (Edit/Options/Delete pill → Options → "Text box options"). Sizes are in
// virtual units (short axis = 10,000), matching [com.forestnote.core.ink.TextBox.fontSize].

/** Text-style preset lists shared across the editor + Options dialog. */
object TextStylePresets {

    /** Label → size in virtual units. Matches the ToolBar's Text-cell chooser strip. */
    val SIZES: List<Pair<String, Int>> = listOf(
        "XS" to 160, "S" to 200, "M" to 240, "L" to 340, "XL" to 480
    )

    /**
     * Best-effort label lookup for a stored [fontSizeV]. Returns the matching preset
     * label when an exact match exists, otherwise a short "v={N}" form for off-preset
     * sizes (a synced box may carry any int; we don't want to silently round it).
     */
    fun labelFor(fontSizeV: Int): String =
        SIZES.firstOrNull { it.second == fontSizeV }?.first ?: "v=$fontSizeV"

    /** Index into [SIZES] for a stored size, or -1 if it isn't an exact preset. */
    fun indexOf(fontSizeV: Int): Int = SIZES.indexOfFirst { it.second == fontSizeV }
}
