package com.forestnote.app.notes.caldav

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

/**
 * What the wire needs to PUT a single VTODO. `collectionUrl` may or may not end
 * with `/` — the client normalizes it.
 */
data class CalDavCredentials(
    val collectionUrl: String,
    val username: String,
    val password: String,
)

/**
 * The post-PUT folded result. All error modes are surfaced as data (no exceptions
 * escape [CalDavClient.putVtodo]), matching the defensive-coding ethos of
 * `core:sync`'s [HttpUrlTransport].
 */
sealed interface CalDavResult {
    object Ok : CalDavResult
    data class HttpError(val code: Int, val message: String) : CalDavResult
    data class TransportError(val cause: Throwable) : CalDavResult
}

/**
 * PUT a VTODO body to a CalDAV collection. The body comes from [VTodoBuilder.build].
 *
 * Blocking — callers must already be off the main thread (see
 * `NotebookStore.createCalDavTask`, which dispatches onto its single-writer executor).
 */
interface CalDavClient {
    fun putVtodo(creds: CalDavCredentials, body: String, uid: String): CalDavResult
}

/**
 * Production [CalDavClient]. One PUT per call:
 *
 *   - Method: `PUT <collection>/<uid>.ics`
 *   - `Content-Type: text/calendar; charset=utf-8`
 *   - `If-None-Match: *` so the server rejects accidental overwrites with 412
 *   - `Authorization: Basic <base64(user:pass)>`
 *
 * No discovery, no ETag tracking, no retries — those concerns are deliberately
 * out of scope for V1 (see plan §"Explicit non-goals").
 */
class OkHttpCalDavClient(
    private val client: OkHttpClient = OkHttpClient(),
    private val log: (String) -> Unit = {},
) : CalDavClient {

    override fun putVtodo(
        creds: CalDavCredentials,
        body: String,
        uid: String,
    ): CalDavResult {
        return try {
            val url = joinCollectionUrl(creds.collectionUrl, "$uid.ics")
            val request = Request.Builder()
                .url(url)
                .put(body.toRequestBody(MEDIA_ICAL))
                .header("Content-Type", "text/calendar; charset=utf-8")
                .header("If-None-Match", "*")
                .header("Authorization", basicAuthHeader(creds.username, creds.password))
                .build()
            log("CalDAV PUT $url")
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    log("CalDAV PUT $url -> ${response.code}")
                    CalDavResult.Ok
                } else {
                    val msg = response.body?.string().orEmpty()
                        .take(MAX_ERROR_BODY)
                        .ifBlank { response.message }
                    log("CalDAV PUT $url -> ${response.code} $msg")
                    CalDavResult.HttpError(response.code, msg)
                }
            }
        } catch (t: Throwable) {
            log("CalDAV transport exception: $t")
            CalDavResult.TransportError(t)
        }
    }

    private fun basicAuthHeader(user: String, pass: String): String {
        val raw = "$user:$pass".toByteArray(Charsets.UTF_8)
        return "Basic " + Base64.getEncoder().encodeToString(raw)
    }

    private fun joinCollectionUrl(collection: String, leaf: String): String {
        val base = if (collection.endsWith("/")) collection else "$collection/"
        return base + leaf
    }

    companion object {
        private val MEDIA_ICAL = "text/calendar; charset=utf-8".toMediaType()
        private const val MAX_ERROR_BODY = 512
    }
}
