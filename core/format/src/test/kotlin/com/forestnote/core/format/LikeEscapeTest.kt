package com.forestnote.core.format

import org.junit.Test
import kotlin.test.assertEquals

/** Pure tests for the LIKE-escape helper — verifies the contract the .sq queries assume. */
class LikeEscapeTest {

    @Test
    fun `escape passes alphanumerics through unchanged`() {
        assertEquals("hello 123", LikeEscape.escapeLikeArg("hello 123"))
    }

    @Test
    fun `escape protects percent so a literal percent is searchable`() {
        assertEquals("100\\%", LikeEscape.escapeLikeArg("100%"))
    }

    @Test
    fun `escape protects underscore so a literal underscore is searchable`() {
        assertEquals("snake\\_case", LikeEscape.escapeLikeArg("snake_case"))
    }

    @Test
    fun `escape protects backslash itself so escape pairs cannot be forged by user input`() {
        assertEquals("path\\\\to", LikeEscape.escapeLikeArg("path\\to"))
    }

    @Test
    fun `containsPattern wraps escaped query in percent wildcards`() {
        assertEquals("%foo\\_bar%", LikeEscape.containsPattern("foo_bar"))
    }
}
