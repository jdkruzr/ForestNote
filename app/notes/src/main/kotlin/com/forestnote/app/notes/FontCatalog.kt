package com.forestnote.app.notes

import android.graphics.Typeface
import java.io.File

// pattern: Imperative Shell
// Reads the device's installed fonts off /system/fonts (file IO + Typeface parsing). Construct via
// [load] on a background thread; the resulting catalog is immutable and safe to share.

/**
 * The fonts available on this device, enumerated from `/system/fonts`. A text box stores its font
 * as a stable file basename (e.g. `Roboto-Regular.ttf`); [resolve] maps that back to a [Typeface],
 * falling back to the system default when the name is empty or absent — so a box authored on
 * another device whose font isn't installed here still renders (in the fallback) without losing its
 * stored `fontName`.
 */
class FontCatalog private constructor(
    private val typefaces: Map<String, Typeface>
) {
    /** Installed font basenames, sorted, for the chooser. */
    val names: List<String> = typefaces.keys.toList()

    /** Resolve a stored font name (+ weight) to a Typeface; system default if unknown/empty. */
    fun resolve(name: String, weight: Int): Typeface {
        val base = typefaces[name] ?: Typeface.DEFAULT
        return if (weight >= BOLD_THRESHOLD) Typeface.create(base, Typeface.BOLD) else base
    }

    companion object {
        private const val DIR = "/system/fonts"
        private const val BOLD_THRESHOLD = 600

        /** Scan /system/fonts and build the catalog. Defensive: skips any file that won't parse. */
        fun load(): FontCatalog {
            val map = LinkedHashMap<String, Typeface>()
            runCatching {
                File(DIR).listFiles { f ->
                    f.isFile && (f.name.endsWith(".ttf", true) || f.name.endsWith(".otf", true))
                }?.sortedBy { it.name.lowercase() }?.forEach { f ->
                    runCatching { Typeface.createFromFile(f) }.getOrNull()?.let { map[f.name] = it }
                }
            }
            return FontCatalog(map)
        }
    }
}
