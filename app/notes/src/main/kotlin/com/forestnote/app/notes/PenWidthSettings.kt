package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel

// pattern: Functional Core
// Pure bridge between Settings' stringly-typed pen-width map and core:ink's enums.

/**
 * Converts between `Settings.penWidthLevels` (a `Map<String, String>` of
 * `PenVariant.name → numeric pen width`, chosen so core:format needn't depend on core:ink's
 * enums for serialization) and the typed `Map<PenVariant, PenWidthLevel>` the toolbar uses.
 * Unknown/garbage names are dropped on decode (the consumer defaults missing variants to 4),
 * so a blob written by a newer build with extra variants/levels can't crash an older one. Legacy
 * XS/S/M/L/XL values are accepted and mapped to 1/2/4/6/7.
 */
object PenWidthSettings {

    /** Decode the persisted string map, skipping any value that doesn't parse to a known level. */
    fun decode(stored: Map<String, String>): Map<PenVariant, PenWidthLevel> {
        val variants = PenVariant.entries.associateBy { it.name }
        return stored.mapNotNull { (v, l) ->
            val variant = variants[v] ?: return@mapNotNull null
            val level = PenWidthLevel.fromStored(l) ?: return@mapNotNull null
            variant to level
        }.toMap()
    }

    /** Encode the typed map back to the persisted string form. */
    fun encode(levels: Map<PenVariant, PenWidthLevel>): Map<String, String> =
        levels.entries.associate { (variant, level) -> variant.name to level.label }
}
