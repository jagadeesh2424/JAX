package com.jax.assistant.ai

import android.content.Context
import android.util.Log
import com.jax.assistant.config.AppConfig
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private const val TAG = "AIRouter"

class AIRouter(
    context: Context? = null,
    private val restClient: ModelClient = GeminiRestClient(),
    private val firebaseClient: ModelClient = FirebaseAiClient(),
    // Replaceable so routing and fallback are unit-tested without network discovery.
    private val discoverModels: suspend (apiKey: String) -> List<ModelInfo> = { key -> ModelCatalog.discoverModels(key) }
) {

    private val healthStore: ModelHealthStore? = context?.let { ModelHealthStore(it.applicationContext) }
    private val providers = ConcurrentHashMap<String, AIProvider>()

    // Copy-on-write: health checks iterate this list across suspension points while
    // discovery may replace its contents, so iteration must never see a concurrent change.
    private val models = CopyOnWriteArrayList<ModelInfo>()
    private val discoveryLock = Mutex()
    private val _requestLogs = MutableStateFlow<List<RequestLog>>(emptyList())
    val requestLogs: StateFlow<List<RequestLog>> = _requestLogs.asStateFlow()

    @Volatile
    private var activeModel: String = AppConfig.DEFAULT_MODEL

    @Volatile
    var backend: AiBackend = AiBackend.AUTO

    // Set once Firebase AI Logic reports it is not configured, so AUTO stops trying it this session.
    @Volatile
    private var firebaseUnavailable = false

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

    suspend fun ensureModelDiscovery(apiKey: String, force: Boolean = false) {
        if (apiKey.isBlank()) return
        discoveryLock.withLock {
            val isExpired = healthStore?.isDiscoveryExpired() ?: true
            if (!force && !isExpired && models.isNotEmpty()) return

            val discovered = discoverModels(apiKey)
            if (discovered.isEmpty()) return

            val existingMap = models.associateBy { it.id }
            for (disc in discovered) {
                val prev = existingMap[disc.id] ?: continue
                disc.lastSuccess = prev.lastSuccess
                disc.lastFailure = prev.lastFailure
                disc.cooldownUntil = prev.cooldownUntil
                disc.failureCount = prev.failureCount
                disc.averageLatency = prev.averageLatency
                if (!prev.enabled) {
                    disc.enabled = false
                    disc.exclusionReason = prev.exclusionReason
                }
            }
            models.clear()
            models.addAll(discovered)
            healthStore?.markDiscoveryUpdated()
            healthStore?.saveModels(models)

            val firstEnabled = models.firstOrNull { it.enabled }?.id
            if (firstEnabled != null && models.none { it.id == activeModel && it.enabled }) {
                activeModel = firstEnabled
            }
        }
    }

    suspend fun route(
        prompt: String,
        apiKey: String,
        requestedModel: String = "",
        capability: TaskCapability = TaskCapability.GENERAL_CONVERSATION,
        requireJson: Boolean = true
    ): String = routeRequest(
        ModelRequest(messages = listOf(ModelMessage.user(prompt)), json = requireJson),
        apiKey,
        requestedModel,
        capability
    ).text

    // True when the configured backend can run Gemini's native function calling for this key.
    fun nativeToolsAvailable(apiKey: String): Boolean = primaryClient(apiKey, needsTools = true).supportsTools

    fun backendName(apiKey: String): String = primaryClient(apiKey, needsTools = false).name

    private fun primaryClient(apiKey: String, needsTools: Boolean): ModelClient = when (backend) {
        AiBackend.FIREBASE -> firebaseClient
        AiBackend.DIRECT -> restClient
        AiBackend.AUTO -> when {
            needsTools && apiKey.isNotBlank() -> restClient
            firebaseUnavailable && apiKey.isNotBlank() -> restClient
            else -> firebaseClient
        }
    }

    // Every model call goes through here: model choice (health, cooldown, capability), bounded
    // fallback, backend selection, request logs and per-request token accounting.
    suspend fun routeRequest(
        request: ModelRequest,
        apiKey: String,
        requestedModel: String = "",
        capability: TaskCapability = TaskCapability.GENERAL_CONVERSATION,
        onText: ((String) -> Unit)? = null
    ): ModelResponse {
        ensureModelDiscovery(apiKey, force = false)

        val now = System.currentTimeMillis()
        val candidateModels = ModelSelector.selectCandidateModels(
            models = models,
            capability = capability,
            preferredModelId = requestedModel.ifBlank { null }
        )

        var lastHttpStatus: Int? = null
        var lastErrorCode: String? = null
        var lastErrorMessage: String? = null
        var isQuotaExceededOccurred = false
        var attemptCount = 0

        // A transient failure should not make the user wait through every discovered model.
        // Keep one fallback for a selected model and two for automatic routing.
        val maxCandidates = if (requestedModel.isNotBlank()) 2 else 3
        for (candidate in candidateModels.take(maxCandidates)) {
            var client = primaryClient(apiKey, needsTools = request.tools.isNotEmpty())
            attemptCount++
            val startTime = System.currentTimeMillis()
            Log.d(TAG, "[Attempt $attemptCount] Routing request via model '${candidate.id}' (${client.name})")

            var attempt = client.generate(request.copy(model = candidate.id), apiKey, onText)
            if (!attempt.isSuccess && attempt.errorCode == ModelAttempt.BACKEND_NOT_CONFIGURED &&
                backend == AiBackend.AUTO && client === firebaseClient && apiKey.isNotBlank()
            ) {
                Log.w(TAG, "Firebase AI Logic unavailable; falling back to the Gemini API for this session.")
                firebaseUnavailable = true
                client = restClient
                attempt = client.generate(request.copy(model = candidate.id), apiKey, onText)
            }
            val latency = System.currentTimeMillis() - startTime
            val response = attempt.response

            if (response != null) {
                activeModel = candidate.id
                updateModelSuccess(candidate, latency, now)
                logRequest(
                    RequestLog(
                        timestamp = now,
                        modelUsed = candidate.id,
                        provider = client.name,
                        latencyMs = latency,
                        httpStatus = attempt.httpStatus ?: 200,
                        errorCode = null,
                        errorMessage = null,
                        retryCount = attemptCount - 1,
                        isSuccess = true,
                        promptTokens = response.usage.promptTokens,
                        outputTokens = response.usage.outputTokens
                    )
                )
                healthStore?.saveModels(models)
                currentCoroutineContext()[UsageRecorder]?.record(response.usage)
                return response
            }

            lastHttpStatus = attempt.httpStatus
            lastErrorCode = attempt.errorCode
            lastErrorMessage = attempt.errorMessage
            if (attempt.httpStatus == 429 || attempt.errorCode == "QUOTA_EXCEEDED" || attempt.errorCode == "RESOURCE_EXHAUSTED") {
                isQuotaExceededOccurred = true
            }

            // A key/backend error says nothing about this model's health, so it is not penalized.
            val modelFault = attempt.httpStatus != 401 && attempt.errorCode !in NOT_MODEL_FAULTS
            if (modelFault) updateModelFailure(candidate, attempt.httpStatus, attempt.errorMessage, now)
            logRequest(
                RequestLog(
                    timestamp = now,
                    modelUsed = candidate.id,
                    provider = client.name,
                    latencyMs = latency,
                    httpStatus = attempt.httpStatus,
                    errorCode = attempt.errorCode,
                    errorMessage = attempt.errorMessage,
                    retryCount = attemptCount - 1,
                    isSuccess = false
                )
            )
            healthStore?.saveModels(models)

            // Key or backend problems affect every model: fail fast instead of trying others.
            if (attempt.httpStatus == 401 || attempt.errorCode == "INVALID_API_KEY" || attempt.errorCode == "MISSING_API_KEY") {
                throw AIException(AIError.InvalidApiKey)
            }
            if (attempt.errorCode == ModelAttempt.BACKEND_NOT_CONFIGURED) {
                throw AIException(AIError.UnknownError(
                    "Firebase AI Logic is not set up for this app. Enable it in the Firebase console, or add a Gemini API key in Settings."
                ))
            }
            if (attempt.errorCode == ModelAttempt.TOOLS_UNSUPPORTED) {
                throw AIException(AIError.UnknownError("The selected AI backend cannot run tools."))
            }
        }

        val finalError = when {
            isQuotaExceededOccurred || lastHttpStatus == 429 -> AIError.QuotaExceeded
            lastHttpStatus == 404 -> AIError.ModelNotFound
            lastHttpStatus == 408 || lastErrorCode == ModelAttempt.TIMEOUT -> AIError.Timeout
            lastErrorCode == ModelAttempt.NETWORK -> AIError.NetworkError
            else -> AIError.UnknownError(lastErrorMessage ?: "All available AI models failed to respond.")
        }
        throw AIException(finalError)
    }

    suspend fun testConnection(apiKey: String, modelName: String = ""): TestConnectionResult {
        if (primaryClient(apiKey, needsTools = false) === firebaseClient) {
            return try {
                val response = routeRequest(
                    ModelRequest(messages = listOf(ModelMessage.user("Respond with 'OK' to verify connection."))),
                    apiKey,
                    modelName
                )
                TestConnectionResult(true, "${response.backend} connected (model '${response.modelId}').")
            } catch (e: AIException) {
                TestConnectionResult(false, e.error.userFriendlyMessage)
            }
        }
        ensureModelDiscovery(apiKey, force = false)
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
                if (result.message.contains("404")) {
                    candidate.enabled = false
                    candidate.exclusionReason = "HTTP 404: Removed immediately"
                }
            }
            healthStore?.saveModels(models)
        }

        return result
    }

    suspend fun performHealthCheck(apiKey: String): String {
        if (apiKey.isBlank()) {
            return "Health check probes each model with a Gemini API key. With Firebase AI Logic, use Test Connection instead."
        }
        ensureModelDiscovery(apiKey, force = false)
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
        model.exclusionReason = null
        model.averageLatency = if (model.averageLatency == 0L) latency else (model.averageLatency + latency) / 2
    }

    private fun updateModelFailure(model: ModelInfo, httpStatus: Int?, errorMessage: String?, now: Long) {
        model.lastFailure = now
        model.failureCount++

        when (httpStatus) {
            404 -> {
                model.enabled = false
                model.cooldownUntil = now + 86400000L
                model.exclusionReason = "HTTP 404: Removed immediately (Model not found or unsupported for key)"
            }
            429 -> {
                model.cooldownUntil = now + 60000L // 60s cooldown
                model.exclusionReason = "HTTP 429: Cooldown active (Rate limited)"
            }
            500, 502, 503 -> {
                model.cooldownUntil = now + 15000L
                model.exclusionReason = "HTTP $httpStatus: Server error"
            }
            408 -> {
                model.cooldownUntil = now + 30000L
                model.exclusionReason = "HTTP 408: Timeout"
            }
            else -> {
                model.cooldownUntil = now + 10000L
                model.exclusionReason = "HTTP ${httpStatus ?: "ERR"}: ${errorMessage ?: "Failed"}"
            }
        }
    }

    private fun logRequest(logEntry: RequestLog) {
        _requestLogs.update { current -> (listOf(logEntry) + current).take(MAX_REQUEST_LOGS) }
    }

    private companion object {
        const val MAX_REQUEST_LOGS = 30
        val NOT_MODEL_FAULTS = setOf(
            "INVALID_API_KEY", "MISSING_API_KEY", ModelAttempt.BACKEND_NOT_CONFIGURED, ModelAttempt.TOOLS_UNSUPPORTED
        )
    }
}
