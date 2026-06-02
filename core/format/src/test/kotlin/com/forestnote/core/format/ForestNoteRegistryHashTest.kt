package com.forestnote.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Phase 8 cutover guard: the RhizomeSync [ForestNoteRegistry] must reproduce ForestNote's live
 * production schema byte-for-byte BEFORE the hand-rolled [SyncWire]/[SyncMerge] core is replaced.
 * Real devices + data are on schema-hash v3; a drift here is silent data-corruption risk, so it
 * fails loudly. Mirrors UltraBridge's `internal/syncstore/parity_test.go`.
 */
class ForestNoteRegistryHashTest {

    private val v3 = "724411eb845ad3487393a77cb5559690e69332c35fdb5ee3e85c1767bf71f3fe"

    @Test
    fun registryReproducesV3Hash() {
        assertEquals(v3, ForestNoteRegistry.registry.schemaHash())
    }

    @Test
    fun registryKnownColsMatchLegacySyncMerge() {
        val registryCols = ForestNoteRegistry.registry.knownCols
        assertEquals(
            SyncMerge.knownCols.keys,
            registryCols.keys,
            "table set differs between registry and legacy SyncMerge",
        )
        for ((table, legacyCols) in SyncMerge.knownCols) {
            assertEquals(
                legacyCols,
                registryCols[table],
                "column list for table '$table' differs (alphabetical order is load-bearing for the hash)",
            )
        }
    }
}
