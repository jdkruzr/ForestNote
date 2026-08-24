package com.forestnote.app.notes.transcription

import com.forestnote.core.format.TranscriptionProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.Base64

data class TranscriptionConfig(
    val provider: TranscriptionProvider,
    val baseUrl: String,
    val model: String,
    val apiKey: String,
) {
    val isComplete: Boolean
        get() = provider != TranscriptionProvider.OFF && baseUrl.isNotBlank() && model.isNotBlank()

    val modelLabel: String
        get() = when (provider) {
            TranscriptionProvider.OPENAI_COMPATIBLE -> "openai-compatible:$model"
            TranscriptionProvider.ANTHROPIC_COMPATIBLE -> "anthropic-compatible:$model"
            TranscriptionProvider.OFF -> "endpoint-disabled"
        }
}

/**
 * Manual full-page transcription client. No background component owns this class: a request is
 * made only after the user taps Run endpoint in the OCR dialog (or Test in Settings).
 */
class TranscriptionClient(private val http: OkHttpClient) {

    suspend fun transcribe(config: TranscriptionConfig, jpeg: ByteArray): Result<String> =
        request(config, jpeg, TRANSCRIPTION_PROMPT).mapCatching { text ->
            text.trim().takeIf { it.isNotEmpty() }
                ?: throw TranscriptionException("The endpoint returned no transcription.")
        }

    /** Text-only probe: validates URL, auth, model, and response parsing without uploading a page. */
    suspend fun test(config: TranscriptionConfig): Result<Unit> =
        request(config, null, TEST_PROMPT).map { Unit }

    private suspend fun request(
        config: TranscriptionConfig,
        jpeg: ByteArray?,
        prompt: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(config.isComplete) { "Choose a provider and enter its base URL and model." }
            val request = when (config.provider) {
                TranscriptionProvider.OPENAI_COMPATIBLE -> openAiRequest(config, jpeg, prompt)
                TranscriptionProvider.ANTHROPIC_COMPATIBLE -> anthropicRequest(config, jpeg, prompt)
                TranscriptionProvider.OFF -> error("Endpoint transcription is disabled.")
            }
            http.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    throw TranscriptionException(errorMessage(body) ?: "Endpoint returned HTTP ${response.code}.")
                }
                when (config.provider) {
                    TranscriptionProvider.OPENAI_COMPATIBLE -> parseOpenAi(body)
                    TranscriptionProvider.ANTHROPIC_COMPATIBLE -> parseAnthropic(body)
                    TranscriptionProvider.OFF -> error("Endpoint transcription is disabled.")
                }
            }
        }
    }

    private fun openAiRequest(config: TranscriptionConfig, jpeg: ByteArray?, prompt: String): Request {
        val content = buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", prompt)
            })
            if (jpeg != null) add(buildJsonObject {
                put("type", "image_url")
                put("image_url", buildJsonObject {
                    put("url", "data:image/jpeg;base64,${Base64.getEncoder().encodeToString(jpeg)}")
                })
            })
        }
        val body = buildJsonObject {
            put("model", config.model.trim())
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    // A plain string makes the text-only Test probe work with older local
                    // OpenAI-compatible servers; vision requests require the typed content array.
                    put("content", if (jpeg == null) JsonPrimitive(prompt) else content)
                })
            })
        }
        return Request.Builder()
            .url(endpoint(config.baseUrl, ProviderPath.OPENAI))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .apply {
                if (config.apiKey.isNotBlank()) header("Authorization", "Bearer ${config.apiKey}")
            }
            .build()
    }

    private fun anthropicRequest(config: TranscriptionConfig, jpeg: ByteArray?, prompt: String): Request {
        val content = buildJsonArray {
            if (jpeg != null) add(buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", Base64.getEncoder().encodeToString(jpeg))
                })
            })
            add(buildJsonObject {
                put("type", "text")
                put("text", prompt)
            })
        }
        val body = buildJsonObject {
            put("model", config.model.trim())
            put("max_tokens", MAX_TOKENS)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", content)
                })
            })
        }
        return Request.Builder()
            .url(endpoint(config.baseUrl, ProviderPath.ANTHROPIC))
            .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("anthropic-version", ANTHROPIC_VERSION)
            .apply {
                if (config.apiKey.isNotBlank()) header("x-api-key", config.apiKey)
            }
            .build()
    }

    private fun parseOpenAi(body: String): String {
        val content = parse(body).jsonObject["choices"]?.jsonArray?.firstOrNull()
            ?.jsonObject?.get("message")?.jsonObject?.get("content")
            ?: throw TranscriptionException("OpenAI-compatible response has no message content.")
        return textContent(content)
    }

    private fun parseAnthropic(body: String): String {
        val content = parse(body).jsonObject["content"]
            ?: throw TranscriptionException("Anthropic-compatible response has no content.")
        return textContent(content)
    }

    private fun textContent(content: JsonElement): String = when (content) {
        is JsonPrimitive -> content.contentOrNull.orEmpty()
        is JsonArray -> content.mapNotNull { block ->
            (block as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
        }.joinToString("\n")
        else -> ""
    }.takeIf { it.isNotBlank() }
        ?: throw TranscriptionException("Endpoint response contains no text.")

    private fun errorMessage(body: String): String? = runCatching {
        parse(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
    }.getOrNull()

    private fun parse(body: String): JsonElement = runCatching { JSON.parseToJsonElement(body) }
        .getOrElse { throw TranscriptionException("Endpoint returned invalid JSON.", it) }

    private enum class ProviderPath { OPENAI, ANTHROPIC }

    private fun endpoint(baseUrl: String, provider: ProviderPath): HttpUrl {
        val trimmed = baseUrl.trim().trimEnd('/')
        require(trimmed.isNotEmpty()) { "Endpoint base URL is blank." }
        val full = when (provider) {
            ProviderPath.OPENAI -> when {
                trimmed.endsWith("/chat/completions") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/chat/completions"
                else -> "$trimmed/v1/chat/completions"
            }
            ProviderPath.ANTHROPIC -> when {
                trimmed.endsWith("/v1/messages") -> trimmed
                trimmed.endsWith("/v1") -> "$trimmed/messages"
                else -> "$trimmed/v1/messages"
            }
        }
        return full.toHttpUrl()
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true }
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val MAX_TOKENS = 4096
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val TEST_PROMPT = "Reply with the single word OK."
        private const val TRANSCRIPTION_PROMPT =
            "Transcribe all handwritten and typed text on this notebook page. " +
                "Preserve reading order and line breaks. Return only the transcription, " +
                "with no commentary or Markdown fences."
    }
}

class TranscriptionException(message: String, cause: Throwable? = null) : Exception(message, cause)
