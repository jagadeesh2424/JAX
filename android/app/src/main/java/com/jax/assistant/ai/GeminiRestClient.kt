package com.jax.assistant.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

// Gemini API request/response JSON (generateContent / streamGenerateContent). Pure functions, so
// the wire format is unit-tested without a network.
object GeminiWire {

    fun requestBody(request: ModelRequest): JSONObject {
        val body = JSONObject()
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let { system ->
            body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        }
        body.put("contents", JSONArray().apply { request.messages.forEach { put(content(it)) } })
        if (request.tools.isNotEmpty()) {
            val declarations = JSONArray().apply { request.tools.forEach { put(declaration(it)) } }
            body.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", declarations)))
        }
        val config = JSONObject()
        if (request.json) config.put("responseMimeType", "application/json")
        request.temperature?.let { config.put("temperature", it) }
        if (config.length() > 0) body.put("generationConfig", config)
        return body
    }

    private fun content(message: ModelMessage): JSONObject {
        message.raw?.let { return it }
        val parts = JSONArray()
        if (message.text.isNotBlank()) parts.put(JSONObject().put("text", message.text))
        message.functionCalls.forEach { call ->
            val fc = JSONObject().put("name", call.name).put("args", call.args)
            call.id?.let { fc.put("id", it) }
            parts.put(JSONObject().put("functionCall", fc))
        }
        message.functionResponses.forEach { r ->
            val fr = JSONObject().put("name", r.name).put("response", r.response)
            r.id?.let { fr.put("id", it) }
            parts.put(JSONObject().put("functionResponse", fr))
        }
        return JSONObject().put("role", if (message.role == ModelRole.MODEL) "model" else "user").put("parts", parts)
    }

    fun declaration(spec: ModelToolSpec): JSONObject {
        val declaration = JSONObject().put("name", spec.name).put("description", spec.description)
        if (spec.params.isNotEmpty()) {
            val properties = JSONObject()
            spec.params.forEach { p ->
                properties.put(p.name, JSONObject().put("type", schemaType(p.type)).put("description", p.description))
            }
            val parameters = JSONObject().put("type", "OBJECT").put("properties", properties)
            val required = spec.params.filter { it.required }.map { it.name }
            if (required.isNotEmpty()) parameters.put("required", JSONArray(required))
            declaration.put("parameters", parameters)
        }
        return declaration
    }

    fun schemaType(type: String): String = when (type.lowercase()) {
        "number" -> "NUMBER"
        "integer" -> "INTEGER"
        "boolean" -> "BOOLEAN"
        else -> "STRING"
    }

    fun parseResponse(root: JSONObject): ModelResponse {
        val candidate = root.optJSONArray("candidates")?.optJSONObject(0)
        val content = candidate?.optJSONObject("content")
        val collected = PartCollector()
        content?.optJSONArray("parts")?.let(collected::addAll)
        val reason = root.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            .ifBlank { candidate?.optString("finishReason").orEmpty() }
        return collected.toResponse(parseUsage(root.optJSONObject("usageMetadata")), reason)
    }

    fun parseUsage(usage: JSONObject?): TokenUsage = if (usage == null) TokenUsage() else TokenUsage(
        promptTokens = usage.optInt("promptTokenCount", 0),
        outputTokens = usage.optInt("candidatesTokenCount", 0) + usage.optInt("thoughtsTokenCount", 0)
    )

    // Returns (status code, message) from an error body such as {"error":{"status":..,"message":..}}.
    fun parseError(body: String, httpStatus: Int): Pair<String, String> = try {
        val error = JSONObject(body).optJSONObject("error")
        if (error == null) "HTTP_$httpStatus" to body.ifBlank { "HTTP $httpStatus" }
        else error.optString("status", "HTTP_$httpStatus") to error.optString("message", "Request failed with HTTP $httpStatus")
    } catch (e: Exception) {
        "HTTP_$httpStatus" to body.ifBlank { "HTTP $httpStatus" }
    }

    // Accumulates answer text, function calls and the replayable model turn from response parts.
    internal class PartCollector {
        private val text = StringBuilder()
        private val calls = mutableListOf<ModelFunctionCall>()
        private val parts = JSONArray()

        val currentText: String get() = text.toString()

        fun addAll(source: JSONArray) {
            for (i in 0 until source.length()) {
                val part = source.optJSONObject(i) ?: continue
                parts.put(part)
                // Thought summaries are reasoning, not answer text.
                if (part.optBoolean("thought", false)) continue
                if (part.has("text")) text.append(part.optString("text"))
                part.optJSONObject("functionCall")?.let { fc ->
                    calls += ModelFunctionCall(
                        name = fc.optString("name"),
                        args = fc.optJSONObject("args") ?: JSONObject(),
                        id = fc.optString("id").takeIf { it.isNotBlank() }
                    )
                }
            }
        }

        fun toResponse(usage: TokenUsage, finishReason: String): ModelResponse {
            val answer = text.toString()
            val raw = if (parts.length() == 0) null else JSONObject().put("role", "model").put("parts", parts)
            return ModelResponse(
                text = answer,
                functionCalls = calls.toList(),
                usage = usage,
                modelTurn = ModelMessage(ModelRole.MODEL, answer, calls.toList(), raw = raw),
                finishReason = finishReason
            )
        }
    }
}

// Assembles a streamed (alt=sse) response: each "data:" line is a partial GenerateContentResponse.
class SseAccumulator {
    private val collector = GeminiWire.PartCollector()
    private var usage = TokenUsage()
    private var finishReason = ""

    val text: String get() = collector.currentText

    // Returns true when the answer text grew.
    fun accept(line: String): Boolean {
        if (!line.startsWith("data:")) return false
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return false
        val chunk = try {
            JSONObject(payload)
        } catch (e: Exception) {
            return false
        }
        val before = collector.currentText.length
        val candidate = chunk.optJSONArray("candidates")?.optJSONObject(0)
        candidate?.optJSONObject("content")?.optJSONArray("parts")?.let(collector::addAll)
        chunk.optJSONObject("usageMetadata")?.let { usage = GeminiWire.parseUsage(it) }
        val reason = chunk.optJSONObject("promptFeedback")?.optString("blockReason").orEmpty()
            .ifBlank { candidate?.optString("finishReason").orEmpty() }
        if (reason.isNotBlank()) finishReason = reason
        return collector.currentText.length > before
    }

    fun result(): ModelResponse = collector.toResponse(usage, finishReason)
}

// Direct Gemini API with the user's key (sent as a header, never in the URL). Supports system
// instructions, JSON mode, native function calling and SSE streaming.
class GeminiRestClient : ModelClient {
    override val name = "Gemini API"
    override val supportsTools = true
    override val requiresApiKey = true

    override suspend fun generate(request: ModelRequest, apiKey: String, onText: ((String) -> Unit)?): ModelAttempt =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isBlank()) {
                return@withContext ModelAttempt(
                    httpStatus = 401,
                    errorCode = ModelAttempt.MISSING_API_KEY,
                    errorMessage = "Gemini API key is missing.",
                    errorCategory = ProviderErrorCategory.AUTHENTICATION_ERROR
                )
            }
            val model = request.model.removePrefix("models/").trim()
            val streaming = onText != null
            val method = if (streaming) "streamGenerateContent?alt=sse" else "generateContent"
            try {
                val connection = (URL("$BASE_URL/models/$model:$method").openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = if (streaming) STREAM_READ_TIMEOUT_MS else READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("x-goog-api-key", key)
                }
                try {
                    connection.outputStream.use { it.write(GeminiWire.requestBody(request).toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        val body = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        val (parsedCode, message) = GeminiWire.parseError(body, status)
                        // A bad key comes back as HTTP 400 INVALID_ARGUMENT; name it so the router fails fast.
                        val code = if (body.contains("API_KEY_INVALID") || message.contains("API key not valid", ignoreCase = true)) {
                            "INVALID_API_KEY"
                        } else {
                            parsedCode
                        }
                        return@withContext ModelAttempt(
                            httpStatus = status,
                            errorCode = code,
                            errorMessage = message,
                            errorCategory = ProviderErrorClassifier.classify(status, code, message)
                        )
                    }
                    val response = if (onText != null) {
                        val stream = SseAccumulator()
                        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { line -> if (stream.accept(line)) onText(stream.text) }
                        }
                        stream.result()
                    } else {
                        GeminiWire.parseResponse(JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }))
                    }
                    ModelAttempt.from(response.copy(modelId = model, backend = name), status)
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                ModelAttempt(httpStatus = 408, errorCode = ModelAttempt.TIMEOUT, errorMessage = "Gemini did not respond in time.")
            } catch (e: IOException) {
                ModelAttempt(errorCode = ModelAttempt.NETWORK, errorMessage = e.localizedMessage ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                ModelAttempt(errorCode = "REST_EXCEPTION", errorMessage = e.localizedMessage ?: e.javaClass.simpleName)
            }
        }

    private companion object {
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val STREAM_READ_TIMEOUT_MS = 60_000
    }
}
