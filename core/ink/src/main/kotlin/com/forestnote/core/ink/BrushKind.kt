package com.forestnote.core.ink

/**
 * ForestNote-owned, portable brush identities. These values are persisted and synced; never use a
 * Viwoods or Onyx enum as note data. Firmware styles are temporary previews chosen by each backend.
 */
enum class BrushKind(val wireId: String, val displayName: String) {
    FOUNTAIN("fountain", "Fountain Pen"),
    PENCIL_HB("pencil_hb", "Pencil HB"),
    PENCIL_2B("pencil_2b", "Pencil 2B"),
    PENCIL_4B("pencil_4b", "Pencil 4B"),
    PENCIL_6B("pencil_6b", "Pencil 6B"),
    PENCIL_8B("pencil_8b", "Pencil 8B"),
    BRUSH("brush", "Brush"),
    BALLPOINT("ballpoint", "Ballpoint"),
    TRANSLUCENT_MARKER("translucent_marker", "Translucent Marker"),
    MARKER("marker", "Marker"),
    FINELINER("fineliner", "Fineliner"),
    CALLIGRAPHY("calligraphy", "Calligraphy Pen"),
    HIGHLIGHTER("highlighter", "Highlighter"),
    CALLIGRAPHY_REVERSE("calligraphy_reverse", "Reverse Calligraphy"),
    CALLIGRAPHY_BROAD("calligraphy_broad", "Broad Calligraphy"),
    CALLIGRAPHY_CHISEL("calligraphy_chisel", "Chisel Calligraphy"),
    DASHED("dashed", "Dashed Line");

    companion object {
        const val CURRENT_VERSION = 1

        fun fromWireId(id: String?): BrushKind = entries.firstOrNull { it.wireId == id } ?: FOUNTAIN

        /** Stable, JSON-safe 32-bit seed derived from the stroke ULID. */
        fun seedFor(strokeId: String): Int {
            var hash = 0x811C9DC5u
            for (b in strokeId.encodeToByteArray()) {
                hash = (hash xor b.toUByte().toUInt()) * 0x01000193u
            }
            return hash.toInt()
        }
    }
}
