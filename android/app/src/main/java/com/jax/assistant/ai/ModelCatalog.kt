package com.jax.assistant.ai

object ModelCatalog {
    fun getDefaultModels(): List<ModelInfo> {
        return listOf(
            ModelInfo(
                id = "gemini-2.0-flash",
                displayName = "Gemini 2.0 Flash",
                provider = "Gemini",
                priority = 1,
                speedScore = 9,
                reasoningScore = 8,
                contextWindow = 1048576,
                supportsJson = true
            ),
            ModelInfo(
                id = "gemini-2.5-flash",
                displayName = "Gemini 2.5 Flash",
                provider = "Gemini",
                priority = 2,
                speedScore = 9,
                reasoningScore = 9,
                contextWindow = 1048576,
                supportsJson = true
            ),
            ModelInfo(
                id = "gemini-2.5-flash-lite",
                displayName = "Gemini 2.5 Flash Lite",
                provider = "Gemini",
                priority = 3,
                speedScore = 10,
                reasoningScore = 7,
                contextWindow = 1048576,
                supportsJson = true
            ),
            ModelInfo(
                id = "gemini-2.5-pro",
                displayName = "Gemini 2.5 Pro",
                provider = "Gemini",
                priority = 4,
                speedScore = 6,
                reasoningScore = 10,
                contextWindow = 2097152,
                supportsJson = true
            ),
            ModelInfo(
                id = "gemini-1.5-flash",
                displayName = "Gemini 1.5 Flash",
                provider = "Gemini",
                priority = 5,
                speedScore = 8,
                reasoningScore = 7,
                contextWindow = 1048576,
                supportsJson = true
            ),
            ModelInfo(
                id = "gemini-1.5-pro",
                displayName = "Gemini 1.5 Pro",
                provider = "Gemini",
                priority = 6,
                speedScore = 5,
                reasoningScore = 9,
                contextWindow = 2097152,
                supportsJson = true
            )
        )
    }
}
