package com.forestnote.core.ink

/**
 * E-ink display refresh strategy. Maps to device-specific mode values.
 *
 * On Viwoods AiPaper: FAST=4 (1-bit partial), NORMAL=3 (GL16 grayscale), FULL_REFRESH=17 (GC full).
 * On generic devices: all modes use standard View.invalidate().
 */
enum class DisplayMode {
    /** Fast partial refresh for pen input (~12ms, 1-bit). */
    FAST,
    /** Normal grayscale refresh for reading (GL16). */
    NORMAL,
    /** Full panel refresh to clear ghosting (GC). */
    FULL_REFRESH
}
