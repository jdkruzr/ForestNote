package com.forestnote.core.ink

/**
 * Variants of the pen ("Fountain") tool group.
 *
 * The toolbar shows the active variant's name; tapping the group cell opens a
 * dropdown to switch. Width/colour/compositing per variant are resolved by
 * `PenParams` (added in phase A2). For now only [FOUNTAIN] exists.
 */
enum class PenVariant {
    /** The v1 pen: logarithmic pressure curve, full-width range. */
    FOUNTAIN
}
