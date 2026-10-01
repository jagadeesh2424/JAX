package com.jax.assistant.ai

import com.jax.assistant.config.AppConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** OpenAI-compatible wire format used by Groq Chat Completions. */
object GroqWire {

    fun requestBody(request: ModelRequest, modelId: String, stream: Boolean): JSONObject = JSONObject().apply {
        put("model", modelId)
        put("messages", messages(request))
        if (request.tools.isNotEmpty()) put("tools", JSONArray().apply {
            request.tools.forEach { spec ->
                put(JSONObject().put("type", "function").put("function", functionDeclaration(spec)))
            }
        })
        if (request.json) put("response_format", JSONObject().put("type", "json_object"))
        request.temperature?.let { put("temperature", it) }
        put("stream", stream)
        if (stream) put("stream_options", JSONObject().put("include_usage", true))
    }

    private fun messages(request: ModelRequest): JSONArray = JSONArray().apply {
        request.systemInstruction?.takeIf { it.isNotBlank() }?.let {
            put(JSONObject().put("role", "system").put("content", it))
        }
        request.messages.forEach { message ->
            when (message.role) {
                ModelRole.TOOL -> message.functionResponses.forEach { response ->
                    put(
                        JSONObject()
                            .put("role", "tool")
                            .put("tool_call_id", response.id ?: response.name)
                            .put("name", response.name)
                            .put("content", response.response.toString())
                    )
                }
                else -> put(message(message))
            }
        }
    }

    private fun message(message: ModelMessage): JSONObject = JSONObject().apply {
        put("role", if (message.role == ModelRole.MODEL) "assistant" else "user")
        put("content", message.text.ifBlank { JSONObject.NULL })
        if (message.functionCalls.isNotEmpty()) {
            put("tool_calls", JSONArray().apply {
                message.functionCalls.forEach { call ->
                    put(
                        JSONObject()
                            .put("id", call.id ?: "call_${call.name}")
                            .put("type", "function")
                            .put("function", JSONObject().put("name", call.name).put("arguments", call.args.toString()))
                    )
                }
            })
        }
    }

    fun functionDeclaration(spec: ModelToolSpec): JSONObject = JSONObject().apply {
        put("name", spec.name)
        put("description", spec.description)
        val properties = JSONObject()
        spec.params.forEach { param ->
            properties.put(param.name, JSONObject().put("type", openAiType(param.type)).put("description", param.description))
        }
        if (spec.params.isNotEmpty()) {
            val schema = JSONObject().put("type", "object").put("properties", properties)
            val required = JSONArray()
            spec.params.filter { it.required }.forEach { required.put(it.name) }
            if (required.length() > 0) schema.put("required", required)
            put("parameters", schema)
        }
    }

    private fun openAiType(type: String): String = when (type.lowercase()) {
        "number", "integer", "boolean", "array", "object" -> type.lowercase()
        else -> "string"
    }

    fun parseResponse(root: JSONObject): ModelResponse {
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val message = choice?.optJSONObject("message")
        val text = message?.optString("content").orEmpty().takeUnless { it == "null" }.orEmpty()
        val calls = parseToolCalls(message?.optJSONArray("tool_calls"))
        val finishReason = choice?.optString("finish_reason").orEmpty()
        return ModelResponse(
            text = text,
            functionCalls = calls,
            usage = parseUsage(root.optJSONObject("usage")),
            modelTurn = ModelMessage(ModelRole.MODEL, text, calls),
            finishReason = finishReason
        )
    }

    fun parseUsage(usage: JSONObject?): TokenUsage = usage?.let {
        TokenUsage(it.optInt("prompt_tokens", 0), it.optInt("completion_tokens", 0))
    } ?: TokenUsage()

    fun parseToolCalls(array: JSONArray?): List<ModelFunctionCall> {
        if (array == null) return emptyList()
        val calls = mutableListOf<ModelFunctionCall>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val function = item.optJSONObject("function") ?: continue
            val argsText = function.optString("arguments", "{}")
            val args = runCatching { JSONObject(argsText) }.getOrElse { JSONObject() }
            calls += ModelFunctionCall(
                name = function.optString("name"),
                args = args,
                id = item.optString("id").takeIf { it.isNotBlank() }
            )
        }
        return calls
    }

    fun parseError(body: String, status: Int): Pair<String, String> = runCatching {
        val error = JSONObject(body).optJSONObject("error")
        if (error == null) "HTTP_$status" to body.ifBlank { "HTTP $status" }
        else error.optString("code", "HTTP_$status") to error.optString("message", "Request failed with HTTP $status")
    }.getOrElse { "HTTP_$status" to body.ifBlank { "HTTP $status" } }
}

private class GroqSseAccumulator {
    private val text = StringBuilder()
    private val calls = linkedMapOf<Int, ToolCallParts>()
    private var usage = TokenUsage()
    private var finishReason = ""

    fun accept(line: String, onText: ((String) -> Unit)?): Boolean {
        if (!line.startsWith("data:")) return false
        val payload = line.removePrefix("data:").trim()
        if (payload.isBlank() || payload == "[DONE]") return false
        val root = runCatching { JSONObject(payload) }.getOrNull() ?: return false
        val choice = root.optJSONArray("choices")?.optJSONObject(0)
        val delta = choice?.optJSONObject("delta")
        val before = text.length
        delta?.optString("content")?.takeIf { it.isNotBlank() }?.let {
            text.append(it)
            onText?.invoke(text.toString())
        }
        delta?.optJSONArray("tool_calls")?.let { array ->
            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val index = item.optInt("index", i)
                val part = calls.getOrPut(index) { ToolCallParts() }
                item.optString("id").takeIf { it.isNotBlank() }?.let { part.id = it }
                item.optJSONObject("function")?.let { function ->
                    function.optString("name").takeIf { it.isNotBlank() }?.let { part.name = it }
                    part.arguments.append(function.optString("arguments"))
                }
            }
        }
        root.optJSONObject("usage")?.let { usage = GroqWire.parseUsage(it) }
        choice?.optString("finish_reason")?.takeIf { it.isNotBlank() }?.let { finishReason = it }
        return text.length > before
    }

    fun result(): ModelResponse {
        val functionCalls = calls.values.mapNotNull { part ->
            part.name.takeIf { it.isNotBlank() }?.let {
                ModelFunctionCall(it, runCatching { JSONObject(part.arguments.toString()) }.getOrElse { JSONObject() }, part.id)
            }
        }
        return ModelResponse(
            text = text.toString(),
            functionCalls = functionCalls,
            usage = usage,
            modelTurn = ModelMessage(ModelRole.MODEL, text.toString(), functionCalls),
            finishReason = finishReason
        )
    }

    private class ToolCallParts {
        var id: String? = null
        var name: String = ""
        val arguments = StringBuilder()
    }
}

/** Groq fallback using the existing HttpURLConnection-based ModelClient abstraction. */
class GroqModelClient(
    private val defaultModel: String = AppConfig.DEFAULT_GROQ_MODEL
) : ModelClient {
    override val name = "Groq"
    override val supportsTools = true
    override val requiresApiKey = true

    override suspend fun generate(request: ModelRequest, apiKey: String, onText: ((String) -> Unit)?): ModelAttempt =
        withContext(Dispatchers.IO) {
            val key = apiKey.trim()
            if (key.isBlank()) {
                return@withContext ModelAttempt(
                    httpStatus = 401,
                    errorCode = ModelAttempt.AUTHENTICATION,
                    errorMessage = "Groq API key is missing."
                )
            }
            val model = request.model.removePrefix("models/").trim().ifBlank { defaultModel }
            // Tool-call streaming requires assembling fragmented arguments. Keep the existing
            // tool loop safe by using one non-streaming response when tools are present.
            val streaming = onText != null && request.tools.isEmpty()
            val body = GroqWire.requestBody(request, model, streaming)
            try {
                val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = if (streaming) STREAM_READ_TIMEOUT_MS else READ_TIMEOUT_MS
                    setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                    setRequestProperty("Authorization", "Bearer $key")
                }
                try {
                    connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                    val status = connection.responseCode
                    if (status !in 200..299) {
                        val errorBody = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
                        val (code, message) = GroqWire.parseError(errorBody, status)
                        return@withContext ModelAttempt(
                            httpStatus = status,
                            errorCode = code,
                            errorMessage = message,
                            errorCategory = ProviderErrorClassifier.classify(status, code, message)
                        )
                    }
                    val response = if (streaming) {
                        val accumulator = GroqSseAccumulator()
                        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            lines.forEach { accumulator.accept(it, onText) }
                        }
                        accumulator.result()
                    } else {
                        GroqWire.parseResponse(JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }))
                    }
                    ModelAttempt.from(response.copy(modelId = model, backend = name), status)
                } finally {
                    connection.disconnect()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                ModelAttempt(httpStatus = 408, errorCode = ModelAttempt.TIMEOUT, errorMessage = "Groq did not respond in time.")
            } catch (e: IOException) {
                ModelAttempt(errorCode = ModelAttempt.NETWORK, errorMessage = e.localizedMessage ?: e.javaClass.simpleName)
            } catch (e: Exception) {
                ModelAttempt(errorCode = "GROQ_ERROR", errorMessage = e.localizedMessage ?: e.javaClass.simpleName)
            }
        }

    private companion object {
        const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val STREAM_READ_TIMEOUT_MS = 60_000
    }
}
