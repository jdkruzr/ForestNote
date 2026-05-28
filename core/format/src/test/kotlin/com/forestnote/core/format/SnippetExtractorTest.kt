package com.forestnote.core.format

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pure tests for the snippet windowing + match-offset math used by Library search rows. */
class SnippetExtractorTest {

    @Test
    fun `extract centers a window around the first match with offsets inside the window`() {
        val text = "The quick brown fox jumps over the lazy dog and naps in the meadow."
        val s = SnippetExtractor.extract(text, "jumps", contextChars = 8)
        // Window: "rown fox jumps over the" with both ellipses
        assertTrue(s.text.startsWith("…"), "leading ellipsis when content was clipped on the left")
        assertTrue(s.text.endsWith("…"), "trailing ellipsis when content was clipped on the right")
        assertEquals("jumps", s.text.substring(s.matchStart, s.matchEnd), "offsets bracket the matched word")
    }

    @Test
    fun `extract is case-insensitive but preserves the original casing`() {
        val s = SnippetExtractor.extract("Hello WORLD", "world")
        assertEquals("WORLD", s.text.substring(s.matchStart, s.matchEnd))
    }

    @Test
    fun `extract collapses newlines so the snippet is a single legible line`() {
        val s = SnippetExtractor.extract("alpha\nbeta\ngamma", "beta")
        assertEquals(-1, s.text.indexOf('\n'), "newlines replaced with spaces")
        assertEquals("beta", s.text.substring(s.matchStart, s.matchEnd))
    }

    @Test
    fun `extract returns whole text without ellipses when it already fits`() {
        val s = SnippetExtractor.extract("the cat", "cat", contextChars = 40)
        assertEquals("the cat", s.text)
        assertEquals("cat", s.text.substring(s.matchStart, s.matchEnd))
    }

    @Test
    fun `extract on no-match falls back to head-of-text with zero offsets`() {
        val s = SnippetExtractor.extract("totally unrelated content", "zzz")
        assertEquals(0, s.matchStart)
        assertEquals(0, s.matchEnd)
        assertTrue(s.text.startsWith("totally"), "head-of-text fallback")
    }

    @Test
    fun `extract on empty query yields zero offsets`() {
        val s = SnippetExtractor.extract("hello", "")
        assertEquals(0, s.matchStart)
        assertEquals(0, s.matchEnd)
    }
}
