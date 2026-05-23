package com.forestnote.core.ink

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tests for [Ulid] — the dependency-free, time-sortable id generator used as the
 * stroke/page identity. Covers length + alphabet (AC4.1), lexicographic =
 * chronological ordering (AC4.2), timestamp round-trip (AC4.3), and same-ms
 * uniqueness (AC4.4).
 */
class UlidTest {

    private val alphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

    // AC4.1: 26 chars, all from the Crockford alphabet (no I, L, O, U).
    @Test
    fun generateProducesTwentySixCrockfordChars() {
        val id = Ulid.generate()
        assertEquals(26, id.length, "ULID must be 26 chars")
        id.forEach { c ->
            assertTrue(c in alphabet, "char '$c' must be in the Crockford alphabet")
        }
        listOf('I', 'L', 'O', 'U').forEach { forbidden ->
            assertTrue(forbidden !in id, "ULID must not contain ambiguous char '$forbidden'")
        }
    }

    // AC4.2: strictly increasing time -> ascending lexicographic order.
    @Test
    fun increasingTimeSortsLexicographically() {
        val times = listOf(1000L, 2000L, 3000L, 4000L, 5000L)
        val ids = times.map { Ulid.generate(now = it) }
        assertEquals(ids, ids.sorted(), "ids minted in time order must already be sorted")

        val shuffledThenSorted = ids.shuffled().sorted()
        assertEquals(ids, shuffledThenSorted, "shuffling then sorting must restore chronological order")
    }

    // AC4.3: decoded timestamp equals the generation time.
    @Test
    fun timestampRoundTrips() {
        listOf(0L, 1_000L, 1_716_400_000_000L, (1L shl 48) - 1).forEach { t ->
            assertEquals(t, Ulid.timestampOf(Ulid.generate(now = t)), "timestamp round-trip for $t")
        }
    }

    // AC4.4: two ids at the same ms are distinct.
    @Test
    fun sameMillisecondIdsAreDistinct() {
        val fixed = 1_716_400_000_000L
        val ids = (0 until 100).map { Ulid.generate(now = fixed) }
        assertEquals(ids.size, ids.toSet().size, "all same-ms ids must be distinct")
        assertNotEquals(Ulid.generate(now = fixed), Ulid.generate(now = fixed), "two same-ms ids differ")
    }
}
