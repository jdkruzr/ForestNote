package com.forestnote.app.notes

/** Device presentation only. Never serialize this into a notebook or Rhizome operation. */
enum class UiDensity { AUTO, COMPACT, COMFORTABLE }

internal data class LibraryDensityProfile(val compact: Boolean, val columns: Int, val list: Boolean)

internal object LibraryDensityPolicy {
    fun parse(value: String?) = UiDensity.entries.firstOrNull { it.name == value } ?: UiDensity.AUTO

    /** Width is usable layout width in dp, not framebuffer pixels. Respect large fonts. */
    fun resolve(mode: UiDensity, widthDp: Float, fontScale: Float): LibraryDensityProfile {
        val compact = mode == UiDensity.COMPACT || (mode == UiDensity.AUTO && widthDp < 600f)
        val minCard = if (compact) 164f else 240f
        val columns = (widthDp.coerceAtLeast(0f) / (minCard * fontScale.coerceAtLeast(1f))).toInt().coerceIn(1, 4)
        return LibraryDensityProfile(compact, columns, compact && columns == 1)
    }
}
