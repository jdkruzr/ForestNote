package com.forestnote.core.ink

/**
 * One of seven numeric pen widths (library-and-tools AC10). Chosen per [PenVariant] and
 * persisted; [LEVEL_4] is the v1 default so existing strokes render unchanged. See
 * [PenWidthScale] for the actual widths.
 */
enum class PenWidthLevel(val value: Int) {
    LEVEL_1(1),
    LEVEL_2(2),
    LEVEL_3(3),
    LEVEL_4(4),
    LEVEL_5(5),
    LEVEL_6(6),
    LEVEL_7(7);

    val label: String get() = value.toString()

    companion object {
        val DEFAULT: PenWidthLevel = LEVEL_4

        fun fromStored(value: String): PenWidthLevel? =
            entries.firstOrNull { it.label == value || it.name == value }
                ?: when (value) {
                    "XS" -> LEVEL_1
                    "S" -> LEVEL_2
                    "M" -> LEVEL_4
                    "L" -> LEVEL_6
                    "XL" -> LEVEL_7
                    else -> null
                }
    }
}
