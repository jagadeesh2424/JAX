package com.jax.assistant.ai

import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.collect

// Gemini through Firebase AI Logic: the API key stays in the Firebase project (protected by App
// Check), so none is stored on the phone. Text, JSON mode, system instructions and streaming.
// Native function calling is not wired here yet (it needs kotlinx-serialization types for tool
// arguments), so tool-using requests go to GeminiRestClient or the JSON tool protocol instead.
class FirebaseAiClient : ModelClient {
    override val name = "Firebase AI Logic"
    override val supportsTools = false
    override val requiresApiKey = false

    override suspend fun generate(request: ModelRequest, apiKey: String, onText: ((String) -> Unit)?): ModelAttempt {
        if (request.tools.isNotEmpty()) {
            return ModelAttempt(errorCode = ModelAttempt.TOOLS_UNSUPPORTED, errorMessage = "Firebase AI Logic tools are not enabled.")
        }
        val modelId = request.model.removePrefix("models/").trim()
        return try {
            val model = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
                modelName = modelId,
                generationConfig = generationConfig {
                    if (request.json) responseMimeType = "application/json"
                    request.temperature?.let { temperature = it.toFloat() }
                },
                systemInstruction = request.systemInstruction?.takeIf { it.isNotBlank() }?.let { system -> content { text(system) } }
            )
            val turns: List<com.google.firebase.ai.type.Content> = request.messages.filter { it.text.isNotBlank() }.map { message ->
                content(role = if (message.role == ModelRole.MODEL) "model" else "user") { text(message.text) }
            }

            val response = if (onText != null) {
                val text = StringBuilder()
                var usage = TokenUsage()
                model.generateContentStream(turns).collect { chunk ->
                    chunk.text?.takeIf { it.isNotEmpty() }?.let {
                        text.append(it)
                        onText(text.toString())
                    }
                    chunk.usageMetadata?.let { u -> usage = TokenUsage(u.promptTokenCount, u.candidatesTokenCount ?: 0) }
                }
                ModelResponse(text = text.toString(), usage = usage)
            } else {
                val result = model.generateContent(turns)
                val usage = result.usageMetadata?.let { u -> TokenUsage(u.promptTokenCount, u.candidatesTokenCount ?: 0) } ?: TokenUsage()
                ModelResponse(text = result.text.orEmpty(), usage = usage)
            }
            ModelAttempt.from(response.copy(modelId = modelId, backend = name))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            classify(e)
        }
    }

    companion object {
        // Classified by name and message so this does not depend on the SDK's exception class list.
        fun classify(e: Exception): ModelAttempt {
            val kind = e.javaClass.simpleName
            val message = e.message.orEmpty()
            val text = "$kind $message ${e.cause?.message.orEmpty()}"
            return when {
                kind.contains("ServiceDisabled") || kind.contains("APINotConfigured") || kind.contains("InvalidAPIKey") ||
                    kind.contains("AppCheck") ||
                    Regex("SERVICE_DISABLED|has not been used in project|API has not been|App Check|PERMISSION_DENIED|API_KEY_INVALID|FirebaseApp is not initialized", RegexOption.IGNORE_CASE)
                        .containsMatchIn(text) ->
                    ModelAttempt(httpStatus = 403, errorCode = ModelAttempt.BACKEND_NOT_CONFIGURED,
                        errorMessage = "Firebase AI Logic is not set up for this app ($message)")
                kind.contains("QuotaExceeded") || Regex("RESOURCE_EXHAUSTED|\\b429\\b|quota", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    ModelAttempt(httpStatus = 429, errorCode = "QUOTA_EXCEEDED", errorMessage = message)
                Regex("NOT_FOUND|\\b404\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    ModelAttempt(httpStatus = 404, errorCode = "NOT_FOUND", errorMessage = message)
                kind.contains("PromptBlocked") || kind.contains("ResponseStopped") || kind.contains("ContentBlocked") ->
                    ModelAttempt(errorCode = "BLOCKED", errorMessage = "The request was blocked by safety filters.")
                Regex("timed? ?out|Timeout", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    ModelAttempt(httpStatus = 408, errorCode = ModelAttempt.TIMEOUT, errorMessage = message)
                Regex("UnknownHost|Unable to resolve host|ConnectException|network", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                    ModelAttempt(errorCode = ModelAttempt.NETWORK, errorMessage = message)
                else -> ModelAttempt(errorCode = "FIREBASE_ERROR", errorMessage = message.ifBlank { kind })
            }
        }
    }
}
