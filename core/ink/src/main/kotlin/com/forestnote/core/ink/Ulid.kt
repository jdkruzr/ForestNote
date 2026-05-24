package com.forestnote.core.ink

import kotlin.random.Random

// pattern: Functional Core
// Pure encode/decode; System.currentTimeMillis()/Random.Default appear ONLY as
// injectable default args so callers (the shell) can pin time/randomness for tests.

/**
 * Dependency-free ULID generator: 48-bit millisecond timestamp + 80 bits of
 * randomness, encoded as 26 Crockford base32 chars. Lexicographic order matches
 * chronological order, so ULIDs double as a stable, sortable stroke/page id.
 */
object Ulid {
    private const val ENCODING = "0123456789ABCDEFGHJKMNPQRSTVWXYZ" // Crockford base32, 32 chars
    private const val TIME_CHARS = 10   // 48 bits of time -> 10 chars (top 2 bits unused)
    private const val RANDOM_CHARS = 16 // 80 bits of randomness -> 16 chars
    const val LENGTH = TIME_CHARS + RANDOM_CHARS // 26

    fun generate(now: Long = System.currentTimeMillis(), random: Random = Random.Default): String {
        val sb = StringBuilder(LENGTH)
        sb.append(encodeTime(now))
        repeat(RANDOM_CHARS) { sb.append(ENCODING[random.nextInt(32)]) }
        return sb.toString()
    }

    /**
     * Encode a 48-bit millisecond timestamp as the first 10 Crockford base32 chars.
     *
     * Contract: [millis] must be non-negative and fit in 48 bits (`0..2^48-1`). Real
     * Unix epoch-ms stays well inside this range until year 10889; values outside it
     * would overflow into char 0's unused top 2 bits and break the lexicographic =
     * chronological invariant, so we reject them rather than emit a misordered id.
     */
    fun encodeTime(millis: Long): String {
        require(millis in 0L until (1L shl 48)) { "timestamp out of 48-bit range: $millis" }
        var value = millis
        val chars = CharArray(TIME_CHARS)
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ENCODING[(value and 0x1F).toInt()]
            value = value shr 5
        }
        return String(chars)
    }

    /**
     * Recover the millisecond timestamp from a ULID's first 10 chars (inverse of
     * [encodeTime]). Validates the decode boundary so a malformed or externally-sourced
     * id (Phase 2+ persists ULIDs) fails loudly rather than yielding a garbage timestamp.
     */
    fun timestampOf(ulid: String): Long {
        require(ulid.length >= TIME_CHARS) { "ULID too short to decode timestamp: '$ulid'" }
        var value = 0L
        for (i in 0 until TIME_CHARS) {
            val digit = ENCODING.indexOf(ulid[i])
            require(digit >= 0) { "invalid Crockford base32 char '${ulid[i]}' in '$ulid'" }
            value = (value shl 5) or digit.toLong()
        }
        return value
    }
}
