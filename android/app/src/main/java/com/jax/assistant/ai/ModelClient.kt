package com.jax.assistant.ai

import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Where Gemini runs. FIREBASE keeps the API key in the Firebase project (Firebase AI Logic);
// DIRECT calls the Gemini API with the user's own key; AUTO prefers Firebase and falls back to
// DIRECT (when a key exists) if Firebase AI Logic is not set up or a feature needs it.
enum class AiBackend { AUTO, FIREBASE, DIRECT }

enum class ModelRole { USER, MODEL, TOOL }

data class ModelFunctionCall(val name: String, val args: JSONObject, val id: String? = null)

data class ModelFunctionResponse(val name: String, val response: JSONObject, val id: String? = null)

// One conversation turn. `raw` is a model turn exactly as the API returned it, replayed verbatim so
// hidden parts (e.g. thought signatures required by function calling) survive the round trip.
data class ModelMessage(
    val role: ModelRole,
    val text: String = "",
    val functionCalls: List<ModelFunctionCall> = emptyList(),
    val functionResponses: List<ModelFunctionResponse> = emptyList(),
    val raw: JSONObject? = null
) {
    companion object {
        fun user(text: String) = ModelMessage(ModelRole.USER, text)
        fun toolResults(responses: List<ModelFunctionResponse>) = ModelMessage(ModelRole.TOOL, functionResponses = responses)
    }
}

data class ModelToolParam(val name: String, val type: String, val description: String, val required: Boolean)

data class ModelToolSpec(val name: String, val description: String, val params: List<ModelToolParam>)

data class ModelRequest(
    val messages: List<ModelMessage>,
    val systemInstruction: String? = null,
    val tools: List<ModelToolSpec> = emptyList(),
    val json: Boolean = false,
    val temperature: Double? = null,
    val model: String = ""
)

data class TokenUsage(val promptTokens: Int = 0, val outputTokens: Int = 0) {
    val total: Int get() = promptTokens + outputTokens

    operator fun plus(other: TokenUsage) = TokenUsage(promptTokens + other.promptTokens, outputTokens + other.outputTokens)
}

data class ModelResponse(
    val text: String,
    val functionCalls: List<ModelFunctionCall> = emptyList(),
    val usage: TokenUsage = TokenUsage(),
    val modelTurn: ModelMessage = ModelMessage(ModelRole.MODEL, text, functionCalls),
    // Why generation stopped when nothing usable came back (e.g. SAFETY, a prompt block reason).
    val finishReason: String = "",
    val modelId: String = "",
    val backend: String = ""
)

// One attempt against one model. Failures carry what AIRouter needs for health tracking and fallback.
data class ModelAttempt(
    val response: ModelResponse? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null
) {
    val isSuccess: Boolean get() = response != null

    companion object {
        const val BACKEND_NOT_CONFIGURED = "BACKEND_NOT_CONFIGURED"
        const val TOOLS_UNSUPPORTED = "TOOLS_UNSUPPORTED"
        const val NETWORK = "NETWORK"
        const val TIMEOUT = "TIMEOUT"
        const val EMPTY_RESPONSE = "EMPTY_RESPONSE"

        fun from(response: ModelResponse, httpStatus: Int? = 200): ModelAttempt =
            if (response.text.isBlank() && response.functionCalls.isEmpty()) {
                val reason = response.finishReason.ifBlank { EMPTY_RESPONSE }
                ModelAttempt(httpStatus = httpStatus, errorCode = reason, errorMessage = "The model returned no content ($reason).")
            } else {
                ModelAttempt(response = response, httpStatus = httpStatus)
            }
    }
}

// A way of calling Gemini. `onText`, when given, streams: it receives the whole text so far (not a
// delta), so a retried attempt simply overwrites what was shown.
interface ModelClient {
    val name: String
    val supportsTools: Boolean
    val requiresApiKey: Boolean

    suspend fun generate(request: ModelRequest, apiKey: String, onText: ((String) -> Unit)? = null): ModelAttempt
}

// Installed by RequestPipeline so every model call made for a request reports its token usage.
class UsageRecorder(val record: (TokenUsage) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<UsageRecorder>
}

// Installed by RequestPipeline when the UI wants the final answer streamed as it is generated.
class TextStreamSink(val onText: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TextStreamSink>
}
