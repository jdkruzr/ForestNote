package com.forestnote.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live-cutover guard: the RhizomeSync [ForestNoteRegistry] must reproduce ForestNote's production
 * schema hash byte-for-byte. Real devices + data sync on this hash, so a drift here is silent
 * data-corruption risk — it fails loudly. Mirrors UltraBridge's `internal/syncstore/parity_test.go`
 * and the Go server's `registry.ForestNote()`; all three MUST agree.
 *
 * v4 adds `notebook.aspect_long_axis` (per-notebook page aspect ratio). The prior v3
 * (`724411eb…`) stays in UltraBridge's `AcceptsSchemaHash` grace window for one release.
 */
class ForestNoteRegistryHashTest {

    private val v4 = "74e6b5d790c919290d0e1fca3462800a5dc4abb288042dda2b48d4eb0482bbf2"

    @Test
    fun registryReproducesV4Hash() {
        assertEquals(v4, ForestNoteRegistry.registry.schemaHash())
    }
}
