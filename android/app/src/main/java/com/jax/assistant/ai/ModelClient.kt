package com.jax.assistant.ai

import org.json.JSONObject
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

// Where Gemini runs. FIREBASE keeps the API key in the Firebase project (Firebase AI Logic);
// DIRECT calls the Gemini API with the user's own key; AUTO prefers Firebase and falls back to
// DIRECT (when a key exists) if Firebase AI Logic is not set up or a feature needs it.
enum class AiBackend { AUTO, FIREBASE, DIRECT, GROQ }

/** Provider-level failure categories used for bounded fallback and safe diagnostics. */
enum class ProviderErrorCategory {
    NONE,
    AUTHENTICATION_ERROR,
    RATE_LIMITED,
    QUOTA_EXCEEDED,
    NETWORK_ERROR,
    TIMEOUT,
    INVALID_REQUEST,
    MODEL_UNAVAILABLE,
    SERVER_ERROR,
    BACKEND_NOT_CONFIGURED,
    TOOLS_UNSUPPORTED,
    SAFETY_REJECTION,
    UNKNOWN
}

object ProviderErrorClassifier {
    fun classify(httpStatus: Int?, errorCode: String?, message: String = ""): ProviderErrorCategory {
        val code = errorCode.orEmpty().uppercase()
        val text = "$code $message".lowercase()
        return when {
            code == ModelAttempt.BACKEND_NOT_CONFIGURED -> ProviderErrorCategory.BACKEND_NOT_CONFIGURED
            code == ModelAttempt.TOOLS_UNSUPPORTED -> ProviderErrorCategory.TOOLS_UNSUPPORTED
            code.contains("BLOCK") || code.contains("SAFETY") -> ProviderErrorCategory.SAFETY_REJECTION
            httpStatus == 401 || httpStatus == 403 || code.contains("API_KEY") || code.contains("AUTH") ->
                ProviderErrorCategory.AUTHENTICATION_ERROR
            code.contains("QUOTA") || httpStatus == 429 && (text.contains("quota") || text.contains("resource_exhausted")) ->
                ProviderErrorCategory.QUOTA_EXCEEDED
            httpStatus == 429 || code.contains("RATE") || code.contains("THROTTL") ->
                ProviderErrorCategory.RATE_LIMITED
            httpStatus == 408 || code.contains("TIMEOUT") || text.contains("timed out") ->
                ProviderErrorCategory.TIMEOUT
            code == ModelAttempt.NETWORK || code.contains("NETWORK") || text.contains("unable to resolve host") ->
                ProviderErrorCategory.NETWORK_ERROR
            httpStatus == 404 || code.contains("NOT_FOUND") || code.contains("MODEL") && code.contains("UNAVAILABLE") ->
                ProviderErrorCategory.MODEL_UNAVAILABLE
            httpStatus != null && httpStatus in 400..499 -> ProviderErrorCategory.INVALID_REQUEST
            httpStatus != null && httpStatus >= 500 -> ProviderErrorCategory.SERVER_ERROR
            errorCode.isNullOrBlank() && message.isBlank() -> ProviderErrorCategory.UNKNOWN
            else -> ProviderErrorCategory.UNKNOWN
        }
    }
}

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

data class ProviderAttemptTrace(
    val provider: String,
    val model: String,
    val route: String,
    val fallbackFrom: String? = null,
    val fallbackReason: String? = null,
    val latencyMs: Long,
    val usage: TokenUsage = TokenUsage(),
    val success: Boolean,
    val errorCategory: ProviderErrorCategory = ProviderErrorCategory.NONE
)

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
    val errorMessage: String? = null,
    val errorCategory: ProviderErrorCategory = if (response != null) ProviderErrorCategory.NONE else
        ProviderErrorClassifier.classify(httpStatus, errorCode, errorMessage.orEmpty())
) {
    val isSuccess: Boolean get() = response != null

    companion object {
        const val BACKEND_NOT_CONFIGURED = "BACKEND_NOT_CONFIGURED"
        const val TOOLS_UNSUPPORTED = "TOOLS_UNSUPPORTED"
        const val NETWORK = "NETWORK"
        const val TIMEOUT = "TIMEOUT"
        const val EMPTY_RESPONSE = "EMPTY_RESPONSE"
        const val AUTHENTICATION = "AUTHENTICATION_ERROR"
        const val INVALID_API_KEY = "INVALID_API_KEY"
        const val MISSING_API_KEY = "MISSING_API_KEY"
        const val RATE_LIMITED = "RATE_LIMITED"
        const val QUOTA_EXCEEDED = "QUOTA_EXCEEDED"

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

// Installed by RequestPipeline so AIRouter can add provider attempts to the content-free trace.
class ProviderTraceRecorder(val record: (ProviderAttemptTrace) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ProviderTraceRecorder>
}

// Installed by RequestPipeline when the UI wants the final answer streamed as it is generated.
class TextStreamSink(val onText: (String) -> Unit) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TextStreamSink>
}
