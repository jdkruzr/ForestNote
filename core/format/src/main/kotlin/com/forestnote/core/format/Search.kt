package com.forestnote.core.format

// pattern: Functional Core
// Pure data + pure helpers for the Library search interface. No DB / no Android — the
// imperative shell that fires the SQL queries and assembles results lives in
// NotebookRepository.search().

/**
 * One library-wide search hit, sealed across the four searchable entity kinds.
 *
 * The repository emits hits in a stable group order — folders → notebooks → text-box →
 * OCR — and within each group in the SQL ORDER BY of [notebook.sq]: name first (case-
 * insensitive), then page sort_order/created_at for page-level hits. The UI consumes the
 * list directly without re-sorting.
 */
sealed interface SearchHit {
    /** A folder whose name matched. [parentFolderId] NULL = root-level folder. */
    data class FolderHit(
        val folderId: String,
        val name: String,
        val parentFolderId: String?
    ) : SearchHit

    /** A notebook whose name matched. [folderId] NULL = root-level notebook. */
    data class NotebookHit(
        val notebookId: String,
        val name: String,
        val folderId: String?
    ) : SearchHit

    /**
     * A text box whose content matched, with the notebook + page context needed to open
     * the right page on tap. [pageIndex] is 1-based and reflects the page's position in
     * its notebook's live page list (the same number the editor's "N / M" indicator shows).
     */
    data class TextBoxHit(
        val textBoxId: String,
        val notebookId: String,
        val notebookName: String,
        val pageId: String,
        val pageIndex: Int,
        val snippet: SearchSnippet
    ) : SearchHit

    /** A page whose server-OCR'd text matched. [pageIndex] is 1-based like [TextBoxHit]. */
    data class PageOcrHit(
        val notebookId: String,
        val notebookName: String,
        val pageId: String,
        val pageIndex: Int,
        val snippet: SearchSnippet
    ) : SearchHit
}

/**
 * A short window of text around the first match, plus the match's offsets WITHIN the
 * window (not within the original full text). [matchStart] is inclusive, [matchEnd] is
 * exclusive — [text].substring(matchStart, matchEnd) yields the matched substring (with
 * the original casing). The UI bolds [matchStart, matchEnd) via a SpannableString.
 */
data class SearchSnippet(val text: String, val matchStart: Int, val matchEnd: Int)

/** A complete search response: ordered hits + a flag noting whether any branch was capped. */
data class SearchResults(val hits: List<SearchHit>, val truncated: Boolean)

/**
 * Build a short, presentable window of [text] around the first case-insensitive occurrence
 * of [query]. The window is centered on the match and clipped to [contextChars] characters
 * on each side; leading/trailing ellipses are added when content was clipped. Match offsets
 * are returned relative to the resulting snippet text so the UI can bold them directly.
 *
 * Falls back to a head-of-text snippet (no match offsets, both 0) when [query] is empty or
 * not found in [text] — defensive: a hit_text returned by the search SQL is guaranteed to
 * contain the query, but a caller could pass through an empty query for an empty-state UI.
 */
object SnippetExtractor {
    private const val ELLIPSIS = "…"

    fun extract(text: String, query: String, contextChars: Int = 40): SearchSnippet {
        if (query.isEmpty() || text.isEmpty()) {
            return SearchSnippet(text.take(contextChars * 2), 0, 0)
        }
        val matchAt = text.indexOf(query, ignoreCase = true)
        if (matchAt < 0) {
            return SearchSnippet(text.take(contextChars * 2), 0, 0)
        }
        val matchEnd = matchAt + query.length
        val start = (matchAt - contextChars).coerceAtLeast(0)
        val end = (matchEnd + contextChars).coerceAtMost(text.length)
        val prefix = if (start > 0) ELLIPSIS else ""
        val suffix = if (end < text.length) ELLIPSIS else ""
        val window = text.substring(start, end)
        val cleanWindow = window.replace('\n', ' ').replace('\r', ' ')
        return SearchSnippet(
            text = prefix + cleanWindow + suffix,
            matchStart = prefix.length + (matchAt - start),
            matchEnd = prefix.length + (matchEnd - start)
        )
    }
}

/**
 * Escape a user-supplied query for SQLite `LIKE ... ESCAPE '\'`. The LIKE wildcards `%`
 * and `_` must be escaped so the user can search for a literal `100%` without it matching
 * everything; the escape character itself (`\`) is escaped too so a stray backslash in the
 * query is harmless. The caller wraps the result in `%...%` to build the contains-pattern.
 *
 * Mirrors the ESCAPE clause used in [notebook.sq] — change them together.
 */
object LikeEscape {
    fun escapeLikeArg(q: String): String = buildString(q.length) {
        for (c in q) {
            when (c) {
                '\\', '%', '_' -> { append('\\'); append(c) }
                else -> append(c)
            }
        }
    }

    /** Convenience: build the `%escaped%` contains-pattern in one step. */
    fun containsPattern(q: String): String = "%${escapeLikeArg(q)}%"
}
