package com.jax.assistant.ai

data class TestConnectionResult(
    val isSuccess: Boolean,
    val message: String
)

sealed class AIError(val userFriendlyMessage: String) {
    object InvalidApiKey : AIError("A configured AI provider key is invalid or missing. Please check Settings ⚙️.")
    object QuotaExceeded : AIError("Gemini is temporarily rate-limited. Check provider configuration or try again shortly.")
    object ModelNotFound : AIError("Selected AI model is unavailable or not supported for this API key.")
    object NetworkError : AIError("Network connection error. Please check your internet connection.")
    object Timeout : AIError("Request timed out waiting for J.A.X. AI response.")
    object ProvidersUnavailable : AIError("J.A.X. couldn't reach any configured AI provider right now.")
    object GeminiAndGroqUnavailable : AIError("Gemini and Groq are currently unavailable.")
    data class UnknownError(val message: String) : AIError("J.A.X. Notice: $message")
}

class AIException(val error: AIError) : Exception(error.userFriendlyMessage)

interface AIService {
    suspend fun generate(prompt: String, modelName: String): String
    suspend fun testConnection(modelName: String): TestConnectionResult
}
