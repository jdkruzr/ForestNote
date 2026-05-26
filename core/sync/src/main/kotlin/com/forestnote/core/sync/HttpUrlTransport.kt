package com.forestnote.core.sync

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * The production [SyncTransport]: a single `POST /sync/v1` over [HttpURLConnection] (no third-party
 * HTTP client — dependency-light, in keeping with the locked-device ethos). Auth is UB's existing
 * scheme passed verbatim as the `Authorization` header (Basic over TLS for v1). Every failure mode
 * is folded into a [SyncOutcome] — a non-200 to [SyncOutcome.HttpError], anything thrown (DNS,
 * timeout, unparseable body) to [SyncOutcome.TransportError] — so the engine never sees an
 * exception and the §7.1 mapping stays in one place.
 *
 * Not unit-tested (real sockets); exercised by the local-UB integration check (plan §Verification).
 */
class HttpUrlTransport(
    private val endpoint: String,
    private val authHeader: String,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 30_000
) : SyncTransport {

    override suspend fun post(request: SyncRequest): SyncOutcome = withContext(Dispatchers.IO) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Authorization", authHeader)
            }
            conn.outputStream.use { it.write(json.encodeToString(SyncRequest.serializer(), request).toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code == HttpURLConnection.HTTP_OK) {
                val body = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                SyncOutcome.Ok(json.decodeFromString(SyncResponse.serializer(), body))
            } else {
                val body = (conn.errorStream ?: conn.inputStream)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                SyncOutcome.HttpError(code, body)
            }
        } catch (e: Throwable) {
            SyncOutcome.TransportError(e)
        } finally {
            conn?.disconnect()
        }
    }
}
