package com.forestnote.core.ink

/**
 * One of five discrete pen widths (library-and-tools AC10). Chosen per [PenVariant] and
 * persisted; [M] is the v1 default so existing strokes render unchanged. Mirrors WiNote's
 * 5-level width count (not its per-pen tables). See [PenWidthScale] for the actual widths.
 */
enum class PenWidthLevel { XS, S, M, L, XL }
