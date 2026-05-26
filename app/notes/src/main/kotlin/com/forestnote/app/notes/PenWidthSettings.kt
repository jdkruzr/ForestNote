package com.forestnote.app.notes

import com.forestnote.core.ink.PenVariant
import com.forestnote.core.ink.PenWidthLevel

// pattern: Functional Core
// Pure bridge between Settings' stringly-typed pen-width map and core:ink's enums.

/**
 * Converts between `Settings.penWidthLevels` (a `Map<String, String>` of
 * `PenVariant.name → PenWidthLevel.name`, chosen so core:format needn't depend on core:ink's
 * enums for serialization) and the typed `Map<PenVariant, PenWidthLevel>` the toolbar uses.
 * Unknown/garbage names are dropped on decode (the consumer defaults missing variants to M),
 * so a blob written by a newer build with extra variants/levels can't crash an older one.
 */
object PenWidthSettings {

    /** Decode the persisted string map, skipping any name that doesn't parse to a known enum. */
    fun decode(stored: Map<String, String>): Map<PenVariant, PenWidthLevel> {
        val variants = PenVariant.entries.associateBy { it.name }
        val levels = PenWidthLevel.entries.associateBy { it.name }
        return stored.mapNotNull { (v, l) ->
            val variant = variants[v] ?: return@mapNotNull null
            val level = levels[l] ?: return@mapNotNull null
            variant to level
        }.toMap()
    }

    /** Encode the typed map back to the persisted string form. */
    fun encode(levels: Map<PenVariant, PenWidthLevel>): Map<String, String> =
        levels.entries.associate { (variant, level) -> variant.name to level.name }
}
