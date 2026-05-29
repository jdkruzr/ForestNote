package com.forestnote.app.notes.caldav

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [OkHttpCalDavClient] is the imperative shell around [VTodoBuilder]'s output:
 * a single PUT with Basic Auth, `If-None-Match: *`, and `Content-Type: text/calendar`.
 *
 * These tests exercise the wire by spinning up a [MockWebServer] in-process and
 * recording what the client actually sent. The pure body building is tested
 * separately in [VTodoBuilderTest]; here we only care that the bytes go on the
 * wire correctly and that response codes fold into the right [CalDavResult].
 */
class CalDavClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpCalDavClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpCalDavClient(
            OkHttpClient.Builder()
                .connectTimeout(2, TimeUnit.SECONDS)
                .readTimeout(2, TimeUnit.SECONDS)
                .build()
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `201 Created is Ok and request is PUT with Basic auth, If-None-Match star, text-calendar body`() {
        server.enqueue(MockResponse().setResponseCode(201))
        val creds = CalDavCredentials(
            collectionUrl = server.url("/dav/calendars/me/tasks/").toString(),
            username = "alice",
            password = "s3cret",
        )

        val result = client.putVtodo(creds, body = "BEGIN:VCALENDAR\r\n…", uid = "abc-123")

        assertEquals(CalDavResult.Ok, result)
        val recorded = server.takeRequest()
        assertEquals("PUT", recorded.method)
        assertEquals("/dav/calendars/me/tasks/abc-123.ics", recorded.path)
        assertEquals("text/calendar; charset=utf-8", recorded.getHeader("Content-Type"))
        assertEquals("*", recorded.getHeader("If-None-Match"))
        // "alice:s3cret" base64 = "YWxpY2U6czNjcmV0"
        assertEquals("Basic YWxpY2U6czNjcmV0", recorded.getHeader("Authorization"))
        assertEquals("BEGIN:VCALENDAR\r\n…", recorded.body.readUtf8())
    }

    @Test
    fun `collection URL without trailing slash gets one before joining the uid`() {
        server.enqueue(MockResponse().setResponseCode(201))
        val creds = CalDavCredentials(
            collectionUrl = server.url("/dav/calendars/me/tasks").toString(), // no trailing /
            username = "u", password = "p",
        )

        client.putVtodo(creds, body = "x", uid = "uid-1")

        val recorded = server.takeRequest()
        assertEquals("/dav/calendars/me/tasks/uid-1.ics", recorded.path)
    }

    @Test
    fun `4xx response folds to HttpError with code and short message`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))
        val creds = CalDavCredentials(server.url("/x/").toString(), "u", "p")

        val result = client.putVtodo(creds, body = "x", uid = "u1")

        assertTrue(result is CalDavResult.HttpError, "got $result")
        assertEquals(401, result.code)
        assertTrue(result.message.contains("Unauthorized"), "message was '${result.message}'")
    }

    @Test
    fun `transport failure folds to TransportError`() {
        // Shut the server down so the connection is refused — exercises the catch path.
        val url = server.url("/x/").toString()
        server.shutdown()
        val creds = CalDavCredentials(url, "u", "p")

        val result = client.putVtodo(creds, body = "x", uid = "u1")

        assertTrue(result is CalDavResult.TransportError, "got $result")
    }
}
