package com.jax.assistant.ai

sealed class AIError(val userFriendlyMessage: String) {
    object InvalidApiKey : AIError("Invalid or missing Gemini API key. Please tap Settings ⚙️ to enter your API key.")
    object QuotaExceeded : AIError("API quota or rate limit reached. Please check your Google AI key usage limits.")
    object ModelNotFound : AIError("Selected AI model is unavailable or not supported for this API key.")
    object NetworkError : AIError("Network connection error. Please check your internet connection.")
    object Timeout : AIError("Request timed out waiting for J.A.X. AI response.")
    data class UnknownError(val message: String) : AIError("J.A.X. Notice: $message")
}

class AIException(val error: AIError) : Exception(error.userFriendlyMessage)

interface AIService {
    suspend fun generate(prompt: String, modelName: String): String
}
