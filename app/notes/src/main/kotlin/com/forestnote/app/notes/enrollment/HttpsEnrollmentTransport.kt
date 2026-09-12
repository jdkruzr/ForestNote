package com.forestnote.app.notes.enrollment

import com.forestnote.app.notes.caldav.ReplicaCredentialScope
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URL
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLException

internal fun interface EnrollmentTransport {
    suspend fun enroll(scope: ReplicaCredentialScope, tokenHash: String, approval: EnrollmentApproval): EnrollmentResult
}

/** Native D16 enrollment adapter. Production uses platform TLS/hostname validation.
 * No cleartext exception, redirect, response-body logging, Bearer/admin fallback,
 * sync activation or automatic legacy adoption. Server is the UB base URL, optionally
 * including a reverse-proxy prefix; it is not a /sync/v1 endpoint URL.
 */
internal class HttpsEnrollmentTransport(
    private val open: (URL) -> HttpsURLConnection = { it.openConnection() as HttpsURLConnection },
) : EnrollmentTransport {
    override suspend fun enroll(scope: ReplicaCredentialScope, tokenHash: String,
        approval: EnrollmentApproval): EnrollmentResult = withContext(Dispatchers.IO) {
        require(approval.account == scope.account)
        require(Regex("[0-9a-f]{64}").matches(tokenHash))
        val url = URL(scope.server.trimEnd('/') + "/sync/devices/v1/enroll")
        require(url.protocol == "https")
        val body = buildJsonObject {
            put("site_id", scope.replica); put("token_hash", tokenHash); put("adopt_legacy", approval.adoptLegacy)
        }.toString().toByteArray(Charsets.UTF_8)
        var connection: HttpsURLConnection? = null
        try {
            currentCoroutineContext().ensureActive()
            connection = open(url)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            connection.useCaches = false
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Cache-Control", "no-store")
            connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                "${approval.account}:${approval.password}".toByteArray(Charsets.UTF_8)))
            connection.setFixedLengthStreamingMode(body.size)
            connection.outputStream.use { it.write(body) }
            val code = connection.responseCode
            currentCoroutineContext().ensureActive()
            // Ignore all bodies, including error text that might echo sensitive input.
            when (code) {
                204 -> EnrollmentResult.CONFIRMED
                401, 403 -> EnrollmentResult.ADMIN_REJECTED
                409 -> EnrollmentResult.BINDING_CONFLICT
                404, 405 -> EnrollmentResult.SERVER_UNSUPPORTED
                408, 429, in 500..599 -> EnrollmentResult.RETRYABLE
                else -> EnrollmentResult.REQUEST_REJECTED // includes every redirect
            }
        } catch (e: CancellationException) { throw e }
        catch (_: SSLException) { EnrollmentResult.SECURE_CONNECTION_REQUIRED }
        catch (_: IOException) { EnrollmentResult.RETRYABLE }
        finally { connection?.disconnect() }
    }
}
