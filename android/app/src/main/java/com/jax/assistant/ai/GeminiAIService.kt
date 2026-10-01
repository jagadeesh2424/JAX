package com.jax.assistant.ai

import android.content.Context
import com.jax.assistant.config.AppConfig

class GeminiAIService(
    var apiKey: String,
    context: Context? = null
) : AIService {

    val router = AIRouter(context)
    var groqApiKey: String = ""
    var groqModel: String = AppConfig.DEFAULT_GROQ_MODEL
    var groqEnabled: Boolean = false

    override suspend fun generate(prompt: String, modelName: String): String {
        return generateFor(prompt, modelName, inferCapability(prompt), requireJson = true)
    }

    // Explicit role routing lets callers select a model capability without brittle prompt
    // keyword inference. AIRouter still owns health checks, fallbacks, and model ranking.
    suspend fun generateFor(
        prompt: String,
        modelName: String,
        capability: TaskCapability,
        requireJson: Boolean
    ): String {
        return router.route(
            prompt = prompt,
            apiKey = apiKey,
            groqApiKey = groqApiKey,
            groqModel = groqModel,
            groqEnabled = groqEnabled,
            requestedModel = modelName,
            capability = capability,
            requireJson = requireJson
        )
    }

    override suspend fun testConnection(modelName: String): TestConnectionResult {
        return router.testConnection(apiKey, modelName, groqApiKey, groqModel, groqEnabled)
    }

    private fun inferCapability(prompt: String): TaskCapability {
        val lower = prompt.lowercase()
        return when {
            lower.contains("code") || lower.contains("function") || lower.contains("kotlin") || lower.contains("bug") -> TaskCapability.CODING
            lower.contains("summary") || lower.contains("summarize") || lower.contains("briefing") -> TaskCapability.LONG_SUMMARY
            lower.contains("itemtype") || lower.contains("json") || lower.contains("task") || lower.contains("fact") -> TaskCapability.FAST_CLASSIFICATION
            else -> TaskCapability.GENERAL_CONVERSATION
        }
    }
}
