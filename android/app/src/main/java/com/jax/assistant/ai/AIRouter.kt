package com.jax.assistant.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "AIRouter"

class AIRouter(context: Context? = null) {

    private val healthStore: ModelHealthStore? = context?.let { ModelHealthStore(it.applicationContext) }
    private val providers = mutableMapOf<String, AIProvider>()

    private val models = mutableListOf<ModelInfo>()
    private val _requestLogs = MutableStateFlow<List<RequestLog>>(emptyList())
    val requestLogs: StateFlow<List<RequestLog>> = _requestLogs.asStateFlow()

    private var activeModel: String = "gemini-2.0-flash"

    init {
        // Register default Gemini provider
        registerProvider(GeminiProvider())

        // Load models from store or defaults
        val loaded = healthStore?.loadModels() ?: ModelCatalog.getDefaultModels()
        models.addAll(loaded)
    }

    fun registerProvider(provider: AIProvider) {
        providers[provider.providerName] = provider
    }

    fun getModels(): List<ModelInfo> = models.toList()

    fun getActiveModel(): String = activeModel

    suspend fun route(
        prompt: String,
        apiKey: String,
        requestedModel: String = "",
        capability: TaskCapability = TaskCapability.GENERAL_CONVERSATION,
        requireJson: Boolean = true
    ): String {
        val now = System.currentTimeMillis()
        val candidateModels = ModelSelector.selectCandidateModels(
            models = models,
            capability = capability,
            preferredModelId = requestedModel.ifBlank { null }
        )

        var lastHttpStatus: Int? = null
        var lastErrorMessage: String? = null
        var isQuotaExceededOccurred = false
        var attemptCount = 0

        for (candidate in candidateModels) {
            val provider = providers[candidate.provider] ?: providers["Gemini"]
            if (provider == null) continue

            attemptCount++
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[Attempt $attemptCount] Routing request via model '${candidate.id}' (${provider.providerName})")

            val rawResult = provider.generate(prompt, candidate.id, apiKey, requireJson)
            val latency = System.currentTimeMillis() - startTime

            if (rawResult.isSuccess && !rawResult.text.isNullOrBlank()) {
                // Success path
                activeModel = candidate.id
                updateModelSuccess(candidate, latency, now)
                logRequest(
                    RequestLog(
                        timestamp = now,
                        modelUsed = candidate.id,
                        provider = provider.providerName,
                        latencyMs = latency,
                        httpStatus = 200,
                        errorCode = null,
                        errorMessage = null,
                        retryCount = attemptCount - 1,
                        isSuccess = true
                    )
                )
                healthStore?.saveModels(models)
                return rawResult.text
            } else {
                // Failure path
                lastHttpStatus = rawResult.httpStatus
                lastErrorMessage = rawResult.errorMessage

                if (rawResult.httpStatus == 429 || rawResult.errorCode == "QUOTA_EXCEEDED") {
                    isQuotaExceededOccurred = true
                }

                updateModelFailure(candidate, rawResult, now)
                logRequest(
                    RequestLog(
                        timestamp = now,
                        modelUsed = candidate.id,
                        provider = provider.providerName,
                        latencyMs = latency,
                        httpStatus = rawResult.httpStatus,
                        errorCode = rawResult.errorCode,
                        errorMessage = rawResult.errorMessage,
                        retryCount = attemptCount - 1,
                        isSuccess = false
                    )
                )
                healthStore?.saveModels(models)

                // If invalid API key, fail fast without trying other models
                if (rawResult.httpStatus == 401 || rawResult.errorCode == "INVALID_API_KEY" || rawResult.errorCode == "MISSING_API_KEY") {
                    throw AIException(AIError.InvalidApiKey)
                }
            }
        }

        // If all candidates failed:
        val finalError = when {
            isQuotaExceededOccurred || lastHttpStatus == 429 -> AIError.QuotaExceeded
            lastHttpStatus == 404 -> AIError.ModelNotFound
            lastHttpStatus == 408 -> AIError.Timeout
            lastErrorMessage?.contains("UnknownHostException", ignoreCase = true) == true ||
                    lastErrorMessage?.contains("ConnectException", ignoreCase = true) == true -> AIError.NetworkError
            else -> AIError.UnknownError(lastErrorMessage ?: "All available AI models failed to respond.")
        }
        throw AIException(finalError)
    }

    suspend fun testConnection(apiKey: String, modelName: String = ""): TestConnectionResult {
        val targetModel = modelName.ifBlank { activeModel }
        val provider = providers["Gemini"] ?: return TestConnectionResult(false, "Gemini provider unavailable.")

        val startTime = System.currentTimeMillis()
        val result = provider.testConnection(targetModel, apiKey)
        val latency = System.currentTimeMillis() - startTime

        val candidate = models.find { it.id.equals(targetModel, ignoreCase = true) }
        if (candidate != null) {
            if (result.isSuccess) {
                updateModelSuccess(candidate, latency, System.currentTimeMillis())
            } else {
                candidate.failureCount++
                candidate.lastFailure = System.currentTimeMillis()
            }
            healthStore?.saveModels(models)
        }

        return result
    }

    suspend fun performHealthCheck(apiKey: String): String {
        var healthyCount = 0
        var totalTested = 0

        for (m in models) {
            if (!m.enabled) continue
            totalTested++
            val provider = providers[m.provider] ?: continue
            val res = provider.testConnection(m.id, apiKey)
            if (res.isSuccess) {
                healthyCount++
            }
        }
        healthStore?.saveModels(models)
        return "Health Check complete: $healthyCount/$totalTested operational."
    }

    private fun updateModelSuccess(model: ModelInfo, latency: Long, now: Long) {
        model.lastSuccess = now
        model.cooldownUntil = 0L
        model.failureCount = 0
        model.averageLatency = if (model.averageLatency == 0L) latency else (model.averageLatency + latency) / 2
    }

    private fun updateModelFailure(model: ModelInfo, result: ProviderRawResult, now: Long) {
        model.lastFailure = now
        model.failureCount++

        val cooldownMs = when (result.httpStatus) {
            404 -> 86400000L // 24 hours for missing models
            429 -> 60000L    // 60 seconds for rate limit
            500, 502, 503 -> 15000L // 15 seconds for server error
            408 -> 30000L    // 30 seconds for timeout
            else -> 10000L   // 10 seconds default cooldown
        }
        model.cooldownUntil = now + cooldownMs
    }

    private fun logRequest(logEntry: RequestLog) {
        val current = _requestLogs.value.toMutableList()
        current.add(0, logEntry)
        if (current.size > 30) {
            _requestLogs.value = current.take(30)
        } else {
            _requestLogs.value = current
        }
    }
}
