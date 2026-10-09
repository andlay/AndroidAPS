package app.aaps.plugins.assistant

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.preparePost
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** A message for the chat API: role is "system", "user" or "assistant". */
internal data class ChatMessage(val role: String, val content: String)

/** A failure the user can read: the API's own error text, or what went wrong on the way. */
internal open class AiException(message: String) : Exception(message)

/** The model refused the temperature parameter (many reasoning models accept only their default). */
internal class TemperatureNotSupportedException(message: String) : AiException(message)

/**
 * Streams a reply from an OpenAI compatible Chat Completions endpoint (`POST {base}/chat/completions`
 * with `stream: true`, server-sent events). Only text deltas are passed on.
 */
internal class OpenAiChatClient {

    private val json = Json { ignoreUnknownKeys = true }

    // The engine comes from the platform source set (OkHttp on Android and desktop, Darwin on iOS)
    private val http = HttpClient {
        install(HttpTimeout) {
            connectTimeoutMillis = 20_000
            socketTimeoutMillis = 90_000
            requestTimeoutMillis = 180_000
        }
    }

    /** @param temperature sent only when not null; the model's default is used otherwise */
    fun stream(baseUrl: String, apiKey: String, model: String, temperature: Double?, messages: List<ChatMessage>): Flow<String> = flow {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            temperature?.let { put("temperature", it) }
            put("messages", buildJsonArray {
                messages.forEach { m -> add(buildJsonObject { put("role", m.role); put("content", m.content) }) }
            })
        }
        http.preparePost("${baseUrl.trimEnd('/')}/chat/completions") {
            header(HttpHeaders.Authorization, "Bearer $apiKey")
            contentType(ContentType.Application.Json)
            setBody(json.encodeToString(JsonObject.serializer(), body))
        }.execute { response ->
            if (!response.status.isSuccess()) {
                val error = response.bodyAsText()
                val text = errorText(response.status.value, error)
                // Refused before any text is sent, so the caller can safely ask again without it
                if (temperature != null && response.status.value == 400 && error.contains("temperature", ignoreCase = true))
                    throw TemperatureNotSupportedException(text)
                throw AiException(text)
            }
            val channel = response.bodyAsChannel()
            while (true) {
                val line = channel.readUTF8Line() ?: break
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                val delta = runCatching {
                    json.parseToJsonElement(data).jsonObject["choices"]?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("delta")?.jsonObject?.get("content")?.jsonPrimitive?.takeIf { it.isString }?.content
                }.getOrNull()
                if (!delta.isNullOrEmpty()) emit(delta)
            }
        }
    }

    /** The API's `error.message` when there is one, otherwise the status and the start of the body. */
    private fun errorText(status: Int, body: String): String {
        val message = runCatching {
            json.parseToJsonElement(body).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
        }.getOrNull()
        return "HTTP $status: ${message ?: body.take(300)}"
    }
}
