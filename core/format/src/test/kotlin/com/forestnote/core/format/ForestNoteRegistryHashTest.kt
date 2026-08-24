package com.forestnote.core.format

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Live-cutover guard: the RhizomeSync [ForestNoteRegistry] must reproduce ForestNote's production
 * schema hash byte-for-byte. Real devices + data sync on this hash, so a drift here is silent
 * data-corruption risk — it fails loudly. Mirrors UltraBridge's `internal/syncstore/parity_test.go`
 * and the Go server's `registry.ForestNote()`; all three MUST agree.
 *
 * v5 adds exact notebook page geometry and portable brush metadata. UltraBridge accepts the
 * immediately preceding v4 hash for one release so existing devices can upgrade without a flag day.
 */
class ForestNoteRegistryHashTest {

    private val v5 = "ed367ffd86b24c3b53f7a85b4f46b7f0cb69e0c6fbd0e1048289a659b4c967dd"

    @Test
    fun registryReproducesV5Hash() {
        assertEquals(v5, ForestNoteRegistry.registry.schemaHash())
    }
}
