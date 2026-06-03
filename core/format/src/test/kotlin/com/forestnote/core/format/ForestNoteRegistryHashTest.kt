package com.forestnote.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live-cutover guard: the RhizomeSync [ForestNoteRegistry] must reproduce ForestNote's production
 * schema-hash v3 byte-for-byte. Real devices + data sync on v3, so a drift here is silent
 * data-corruption risk — it fails loudly. Mirrors UltraBridge's `internal/syncstore/parity_test.go`
 * and the Go server's `registry.ForestNote()`. (The one-time byte-parity check against the
 * hand-rolled SyncMerge/SyncWire retired with those classes in the Phase 8 cutover.)
 */
class ForestNoteRegistryHashTest {

    private val v3 = "724411eb845ad3487393a77cb5559690e69332c35fdb5ee3e85c1767bf71f3fe"

    @Test
    fun registryReproducesV3Hash() {
        assertEquals(v3, ForestNoteRegistry.registry.schemaHash())
    }
}
