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
    val isSuccess: Boolean
)
