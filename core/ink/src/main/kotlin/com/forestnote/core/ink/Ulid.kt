package com.forestnote.core.ink

import kotlin.random.Random

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

    /** Encode a 48-bit millisecond timestamp as the first 10 Crockford base32 chars. */
    fun encodeTime(millis: Long): String {
        var value = millis
        val chars = CharArray(TIME_CHARS)
        for (i in TIME_CHARS - 1 downTo 0) {
            chars[i] = ENCODING[(value and 0x1F).toInt()]
            value = value shr 5
        }
        return String(chars)
    }

    /** Recover the millisecond timestamp from a ULID's first 10 chars (inverse of [encodeTime]). */
    fun timestampOf(ulid: String): Long {
        var value = 0L
        for (i in 0 until TIME_CHARS) {
            value = (value shl 5) or ENCODING.indexOf(ulid[i]).toLong()
        }
        return value
    }
}
