package com.jax.assistant.ai

data class RequestLog(
    val timestamp: Long = System.currentTimeMillis(),
    val modelUsed: String,
    val provider: String,
    val latencyMs: Long,
    val httpStatus: Int?,
    val errorCode: String?,
    val errorMessage: String?,
    val retryCount: Int,
    val isSuccess: Boolean,
    val promptTokens: Int = 0,
    val outputTokens: Int = 0,
    val fallbackFrom: String? = null,
    val fallbackReason: String? = null,
    val errorCategory: ProviderErrorCategory = ProviderErrorCategory.NONE,
    // Request-level fields are populated for pipeline summaries. Provider-attempt entries keep
    // their existing fields and remain available for low-level routing diagnostics.
    val requestId: String? = null,
    val route: String? = null,
    val routingReason: String? = null,
    val fallbackChain: String? = null,
    val modelCallCount: Int = 0,
    val toolCallCount: Int = 0,
    val memoryStatus: String = "NOT_USED",
    val isRequestSummary: Boolean = false
)
