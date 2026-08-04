package com.jax.assistant.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig

class GeminiAIService(var apiKey: String) : AIService {

    override suspend fun generate(prompt: String, modelName: String): String {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            throw AIException(AIError.InvalidApiKey)
        }

        val effectiveModelName = modelName.ifBlank { "gemini-2.0-flash" }

        return try {
            val model = GenerativeModel(
                modelName = effectiveModelName,
                apiKey = keyToUse,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                }
            )

            val response = model.generateContent(prompt)
            val text = response.text
            if (text.isNullOrBlank()) {
                throw AIException(AIError.UnknownError("Empty response from AI service."))
            }
            text
        } catch (e: Exception) {
            val msg = e.message ?: e.localizedMessage ?: ""
            val error = when {
                msg.contains("API_KEY", ignoreCase = true) || msg.contains("401") || msg.contains("UNAUTHENTICATED", ignoreCase = true) -> AIError.InvalidApiKey
                msg.contains("404") || msg.contains("NOT_FOUND", ignoreCase = true) -> AIError.ModelNotFound
                msg.contains("429") || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true) || msg.contains("QUOTA", ignoreCase = true) -> AIError.QuotaExceeded
                msg.contains("Unable to resolve host") || msg.contains("ConnectException") || msg.contains("UnknownHostException") -> AIError.NetworkError
                msg.contains("Timeout") || msg.contains("SocketTimeoutException") -> AIError.Timeout
                else -> AIError.UnknownError(msg.ifBlank { "Unable to process AI request" })
            }
            throw AIException(error)
        }
    }
}
