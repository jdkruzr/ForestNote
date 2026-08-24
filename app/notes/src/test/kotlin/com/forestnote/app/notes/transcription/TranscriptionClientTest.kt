package com.forestnote.app.notes.transcription

import com.forestnote.core.format.TranscriptionProvider
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TranscriptionClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: TranscriptionClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TranscriptionClient(OkHttpClient())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `OpenAI compatible request uses chat completions vision shape and bearer auth`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"one\ntwo"}}]}"""))
        val config = TranscriptionConfig(
            TranscriptionProvider.OPENAI_COMPATIBLE,
            server.url("/v1").toString(),
            "vision-model",
            "sk-test",
        )

        assertEquals("one\ntwo", client.transcribe(config, byteArrayOf(1, 2, 3)).getOrThrow())

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals("Bearer sk-test", request.getHeader("Authorization"))
        val json = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals("vision-model", json.getValue("model").jsonPrimitive.content)
        val content = json.getValue("messages").jsonArray.single().jsonObject
            .getValue("content").jsonArray
        assertEquals("text", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("image_url", content[1].jsonObject.getValue("type").jsonPrimitive.content)
        assertTrue(content[1].toString().contains("data:image/jpeg;base64,AQID"))
    }

    @Test
    fun `Anthropic compatible request uses Messages image shape and required headers`() = runTest {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"hello"}]}"""))
        val config = TranscriptionConfig(
            TranscriptionProvider.ANTHROPIC_COMPATIBLE,
            server.url("/").toString(),
            "claude-compatible",
            "secret",
        )

        assertEquals("hello", client.transcribe(config, byteArrayOf(4, 5)).getOrThrow())

        val request = server.takeRequest()
        assertEquals("/v1/messages", request.path)
        assertEquals("secret", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
        val content = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonArray
        assertEquals("image", content[0].jsonObject.getValue("type").jsonPrimitive.content)
        assertEquals("base64", content[0].jsonObject.getValue("source").jsonObject
            .getValue("type").jsonPrimitive.content)
    }

    @Test
    fun `test probe sends no image and permits keyless local endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"OK"}}]}"""))
        val config = TranscriptionConfig(
            TranscriptionProvider.OPENAI_COMPATIBLE,
            server.url("/v1/chat/completions").toString(),
            "local-model",
            "",
        )

        assertTrue(client.test(config).isSuccess)

        val request = server.takeRequest()
        assertEquals("/v1/chat/completions", request.path)
        assertEquals(null, request.getHeader("Authorization"))
        val requestBody = request.body.readUtf8()
        assertFalse(requestBody.contains("image_url"))
        val messageContent = Json.parseToJsonElement(requestBody).jsonObject
            .getValue("messages").jsonArray.single().jsonObject.getValue("content").jsonPrimitive.content
        assertEquals("Reply with the single word OK.", messageContent)
    }

    @Test
    fun `provider error message is surfaced without leaking response structure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"bad key"}}"""))
        val config = TranscriptionConfig(
            TranscriptionProvider.ANTHROPIC_COMPATIBLE,
            server.url("/").toString(),
            "model",
            "wrong",
        )

        val error = client.test(config).exceptionOrNull()
        assertTrue(error?.message?.contains("bad key") == true)
    }
}
