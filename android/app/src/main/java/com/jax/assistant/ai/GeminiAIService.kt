package com.jax.assistant.ai

import android.content.Context

class GeminiAIService(
    var apiKey: String,
    context: Context? = null
) : AIService {

    val router = AIRouter(context)

    override suspend fun generate(prompt: String, modelName: String): String {
        val capability = inferCapability(prompt)
        return router.route(
            prompt = prompt,
            apiKey = apiKey,
            requestedModel = modelName,
            capability = capability,
            requireJson = true
        )
    }

    override suspend fun testConnection(modelName: String): TestConnectionResult {
        return router.testConnection(apiKey, modelName)
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
