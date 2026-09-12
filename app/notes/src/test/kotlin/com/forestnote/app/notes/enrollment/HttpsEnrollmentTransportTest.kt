package com.forestnote.app.notes.enrollment

import com.forestnote.app.notes.caldav.ReplicaCredentialScope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.HttpsURLConnection
import kotlin.test.*

class HttpsEnrollmentTransportTest {
    private val certificate=HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
    private val serverTls=HandshakeCertificates.Builder().heldCertificate(certificate).build()
    private val clientTls=HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
    private val approval=EnrollmentApproval("single-author","synthetic-admin-secret")
    private fun server()=MockWebServer().apply {useHttps(serverTls.sslSocketFactory(),false);start()}
    private fun transport()=HttpsEnrollmentTransport {url ->
        (url.openConnection() as HttpsURLConnection).apply {sslSocketFactory=clientTls.sslSocketFactory()}
    }
    private fun scope(server:MockWebServer)=ReplicaCredentialScope(server.url("/prefix/").toString(),
        approval.account,"library","01ARZ3NDEKTSV4RRFFQ69G5FAV")

    @Test fun sendsOnlyHashAndExplicitAuthorityOverTls()=runBlocking {
        server().use {server ->
            server.enqueue(MockResponse().setResponseCode(204))
            val scope=scope(server)
            assertEquals(EnrollmentResult.CONFIRMED,transport().enroll(scope,"a".repeat(64),approval))
            val request=server.takeRequest(5,TimeUnit.SECONDS)!!
            assertEquals("POST",request.method)
            assertEquals("/prefix/sync/devices/v1/enroll",request.path)
            assertEquals("application/json",request.getHeader("Content-Type"))
            assertEquals("no-store",request.getHeader("Cache-Control"))
            assertEquals("Basic "+Base64.getEncoder().encodeToString("${approval.account}:${approval.password}".toByteArray()),
                request.getHeader("Authorization"))
            val body=Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(setOf("site_id","token_hash","adopt_legacy"),body.keys)
            assertEquals(scope.replica,body.getValue("site_id").jsonPrimitive.content)
            assertFalse(body.getValue("adopt_legacy").jsonPrimitive.boolean)
            assertFalse(body.toString().contains("synthetic-admin-secret"))
        }
    }

    @Test fun refusesRedirectsAndClassifiesErrorsWithoutReturningServerBodies()=runBlocking {
        server().use {server -> server().use {other ->
            val transport=transport();val scope=scope(server)
            val cases=mapOf(302 to EnrollmentResult.REQUEST_REJECTED,307 to EnrollmentResult.REQUEST_REJECTED,
                200 to EnrollmentResult.REQUEST_REJECTED,401 to EnrollmentResult.ADMIN_REJECTED,
                403 to EnrollmentResult.ADMIN_REJECTED,409 to EnrollmentResult.BINDING_CONFLICT,
                404 to EnrollmentResult.SERVER_UNSUPPORTED,429 to EnrollmentResult.RETRYABLE,503 to EnrollmentResult.RETRYABLE)
            for((code,result) in cases) {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",other.url("/steal"))
                    .setBody("must-not-surface-admin-secret"))
                assertEquals(result,transport.enroll(scope,"a".repeat(64),approval))
                assertNotNull(server.takeRequest(5,TimeUnit.SECONDS))
            }
            assertEquals(0,other.requestCount)
        } }
    }

    @Test fun defaultTrustRejectsUntrustedCertificateAndCleartextScope()=runBlocking {
        assertFailsWith<IllegalArgumentException> {ReplicaCredentialScope("http://localhost","author","library","replica")}
        server().use {server ->
            assertEquals(EnrollmentResult.SECURE_CONNECTION_REQUIRED,
                HttpsEnrollmentTransport().enroll(scope(server),"a".repeat(64),approval))
            assertEquals(0,server.requestCount)
        }
    }
}
