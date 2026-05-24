# Off-Main-Thread Persistence + ULID Identity — Phase 1: ULID generator

**Goal:** Add a dependency-free, time-sortable `Ulid` generator to `core:ink` for use by the stroke model and storage layer.

**Architecture:** A pure Kotlin object in `core:ink` that produces 26-character Crockford base32 ULIDs (48-bit millisecond timestamp + 80-bit randomness). Time goes in the high bits so lexicographic string order matches chronological order. No external dependency.

**Tech Stack:** Kotlin, JUnit 4, `kotlin.test` assertions.

**Scope:** Phase 1 of 4.

**Codebase verified:** 2026-05-23.

---

## Acceptance Criteria Coverage

This phase implements and tests:

### persistence-ulid.AC4: ULID generator correctness
- **persistence-ulid.AC4.1 Success:** `generate()` returns 26 chars from the Crockford base32 alphabet.
- **persistence-ulid.AC4.2 Success:** ids generated over increasing time sort lexicographically in chronological order.
- **persistence-ulid.AC4.3 Success:** the decoded timestamp matches the generation time (ms).
- **persistence-ulid.AC4.4 Edge:** two ids generated within the same millisecond are distinct.

---

## Context for the engineer

- ULID spec: 128 bits = 48-bit big-endian millisecond Unix timestamp + 80 bits of randomness, encoded as 26 characters of Crockford base32 (alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ` — note: no `I`, `L`, `O`, `U`). 26 chars × 5 bits = 130 bits; the first character only uses the low 2 bits of its 5 (the timestamp is 48 bits, encoded in the first 10 chars = 50 bits, top 2 bits always 0).
- This module currently has no id generator. Tests live in `core/ink/src/test/kotlin/com/forestnote/core/ink/`. Run with `./gradlew :core:ink:test`.
- Existing tests use JUnit 4 (`@Test`) and `kotlin.test` (`assertEquals`, `assertTrue`) with a message as the last argument. Match that style (see `StrokeGeometryReconcileTest.kt`).
- Keep the timestamp-encoding logic separately callable so a test can decode the timestamp back out (AC4.3). Expose an internal `encodeTime(millis: Long): String` (first 10 chars) and `decodeTime(ulid: String): Long`, or expose `timestampOf(ulid: String): Long`.

---

<!-- START_SUBCOMPONENT_A (tasks 1-2) -->
<!-- START_TASK_1 -->
### Task 1: `Ulid` generator

**Files:**
- Create: `core/ink/src/main/kotlin/com/forestnote/core/ink/Ulid.kt`

**Implementation:**

Create an `object Ulid` exposing `fun generate(): String`. Suggested structure (engineer may adjust naming to match house style, but keep `generate()` and a way to decode the timestamp for testing):

```kotlin
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
```

Notes:
- `generate()` takes optional `now`/`random` parameters with defaults so tests can pin time and randomness deterministically. Production calls `Ulid.generate()` with no args.
- `encodeTime` MUST be the exact inverse of `timestampOf` — the round-trip test depends on it.

**Verification:**
Run: `./gradlew :core:ink:compileDebugKotlin` (or rely on the test task in Task 2)
Expected: compiles.

**Commit:** `feat(ink): add dependency-free ULID generator`
<!-- END_TASK_1 -->

<!-- START_TASK_2 -->
### Task 2: `Ulid` tests

**Verifies:** persistence-ulid.AC4.1, persistence-ulid.AC4.2, persistence-ulid.AC4.3, persistence-ulid.AC4.4

**Files:**
- Create: `core/ink/src/test/kotlin/com/forestnote/core/ink/UlidTest.kt` (unit)

**Testing:**
Write JUnit 4 / `kotlin.test` tests (TDD: write these first, watch them fail, then implement Task 1 — or if Task 1 already exists, ensure these pass). Tests must verify each AC:
- **AC4.1:** `generate()` returns a string of length 26, and every character is in the Crockford alphabet `0123456789ABCDEFGHJKMNPQRSTVWXYZ` (assert none of `I L O U` appear; assert all chars ∈ alphabet).
- **AC4.2:** Generating with strictly increasing pinned `now` values (e.g. `generate(now = 1000L)`, `generate(now = 2000L)`, …) yields strings that are in ascending lexicographic order. Also: a list of such ids, shuffled then `sorted()`, returns to chronological order.
- **AC4.3:** For several `now` values (including `0L`, a recent realistic epoch-ms value, and a large value within 48 bits), `Ulid.timestampOf(Ulid.generate(now = t)) == t`.
- **AC4.4:** Two calls to `generate(now = fixedT)` (same timestamp) with the default random source produce distinct strings (assert the two results are not equal). To make this deterministic, you may pass a `Random` that yields different sequences, or simply assert inequality across (e.g.) 100 generations at the same `now` — collision probability with 80 random bits is negligible.

Use deterministic `now`/`random` parameters where the AC requires exactness (AC4.2, AC4.3). Follow the assertion-with-message style of existing tests.

**Verification:**
Run: `./gradlew :core:ink:test`
Expected: all tests pass.

**Commit:** `test(ink): cover ULID length, sortability, timestamp round-trip, uniqueness`
<!-- END_TASK_2 -->
<!-- END_SUBCOMPONENT_A -->
