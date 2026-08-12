package com.jax.assistant.ai

data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String = "Gemini",
    val priority: Int,
    val speedScore: Int, // 1 to 10
    val reasoningScore: Int, // 1 to 10
    val contextWindow: Int,
    val supportsJson: Boolean = true,
    val supportsStreaming: Boolean = false,
    val supportsVision: Boolean = false,
    val supportedMethods: String = "generateContent",
    var exclusionReason: String? = null,
    var enabled: Boolean = true,
    var lastSuccess: Long = 0L,
    var lastFailure: Long = 0L,
    var cooldownUntil: Long = 0L,
    var failureCount: Int = 0,
    var averageLatency: Long = 0L
)

