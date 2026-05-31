package com.forestnote.app.notes.caldav

import java.net.URLEncoder

/**
 * Pure builder + parser for ForestNote task links. Two forms:
 *
 *  - **Native** `forestnote://notebook/{notebookId}/page/{pageId}` — the
 *    authoritative deep link. FN owns this format (UltraBridge stores it
 *    opaquely as `X-FORESTNOTE-NATIVE-URL`), and the inbound scheme handler
 *    parses it back via [parse].
 *  - **Web** `{syncServerBase}/files/forestnote?notebook=…&page=…` — the
 *    clickable https link to the UltraBridge web UI for the source page.
 *
 * No Android dependencies — fully unit-testable. [parse] never throws; any
 * malformed/foreign URI yields null so the deep-link handler can ignore it.
 */
object ForestNoteLink {

    private const val SCHEME = "forestnote"
    private const val HOST = "notebook"
    private const val PAGE_SEGMENT = "page"
    private const val NATIVE_PREFIX = "$SCHEME://$HOST/"

    data class Target(val notebookId: String, val pageId: String)

    /** `forestnote://notebook/{notebookId}/page/{pageId}`. Ids are URL-safe ULIDs, emitted raw. */
    fun native(notebookId: String, pageId: String): String =
        "$NATIVE_PREFIX$notebookId/$PAGE_SEGMENT/$pageId"

    /**
     * `{base}/files/forestnote?notebook={enc}&page={enc}`. Returns null when
     * [syncServerBase] is blank (no UltraBridge configured → no resolvable link).
     */
    fun web(syncServerBase: String, notebookId: String, pageId: String): String? {
        val base = syncServerBase.trim().trimEnd('/')
        if (base.isEmpty()) return null
        return "$base/files/forestnote?notebook=${enc(notebookId)}&page=${enc(pageId)}"
    }

    /**
     * Parse a native `forestnote://notebook/{nb}/page/{pg}` link back to its ids.
     * Returns null for anything that isn't exactly that shape (wrong scheme/host,
     * missing or blank segments, extra path). Never throws.
     */
    fun parse(uri: String): Target? {
        if (!uri.startsWith(NATIVE_PREFIX)) return null
        val rest = uri.substring(NATIVE_PREFIX.length)
        val parts = rest.split("/")
        if (parts.size != 3) return null
        val (notebookId, pageMarker, pageId) = parts
        if (pageMarker != PAGE_SEGMENT) return null
        if (notebookId.isBlank() || pageId.isBlank()) return null
        return Target(notebookId, pageId)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}
