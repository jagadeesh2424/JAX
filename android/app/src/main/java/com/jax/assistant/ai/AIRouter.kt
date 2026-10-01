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

data class ProviderUsageSnapshot(
    val requestCount: Int,
    val totalTokens: Int,
    val rateLimitCount: Int,
    val errorCount: Int,
    val averageLatencyMs: Long
)

private class MutableProviderUsage {
    var requestCount = 0
    var totalTokens = 0
    var rateLimitCount = 0
    var errorCount = 0
    var totalLatencyMs = 0L

    @Synchronized
    fun record(latencyMs: Long, attempt: ModelAttempt) {
        requestCount++
        totalTokens += attempt.response?.usage?.total ?: 0
        totalLatencyMs += latencyMs
        if (!attempt.isSuccess) errorCount++
        if (attempt.errorCategory == ProviderErrorCategory.RATE_LIMITED ||
            attempt.errorCategory == ProviderErrorCategory.QUOTA_EXCEEDED
        ) rateLimitCount++
    }

    @Synchronized
    fun snapshot() = ProviderUsageSnapshot(
        requestCount,
        totalTokens,
        rateLimitCount,
        errorCount,
        if (requestCount == 0) 0L else totalLatencyMs / requestCount
    )
}

class AIRouter(
    context: Context? = null,
    private val restClient: ModelClient = GeminiRestClient(),
    private val firebaseClient: ModelClient = FirebaseAiClient(),
    private val groqClient: ModelClient = GroqModelClient(),
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
    private val providerCooldowns = ConcurrentHashMap<String, Long>()
    private val providerUsage = ConcurrentHashMap<String, MutableProviderUsage>()

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

    fun getProviderUsage(): Map<String, ProviderUsageSnapshot> = providerUsage.mapValues { (_, value) -> value.snapshot() }

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
        requireJson: Boolean = true,
        groqApiKey: String = "",
        groqModel: String = AppConfig.DEFAULT_GROQ_MODEL,
        groqEnabled: Boolean = false
    ): String = routeRequest(
        ModelRequest(messages = listOf(ModelMessage.user(prompt)), json = requireJson),
        apiKey,
        requestedModel,
        capability,
        groqApiKey = groqApiKey,
        groqModel = groqModel,
        groqEnabled = groqEnabled
    ).text

    // True when the configured backend can run native function calling for this configuration.
    fun nativeToolsAvailable(
        apiKey: String,
        groqApiKey: String = "",
        groqModel: String = AppConfig.DEFAULT_GROQ_MODEL,
        groqEnabled: Boolean = false
    ): Boolean = providerTargets(true, apiKey, groqApiKey, groqModel, groqEnabled).firstOrNull()?.client?.supportsTools == true

    fun backendName(
        apiKey: String,
        groqApiKey: String = "",
        groqModel: String = AppConfig.DEFAULT_GROQ_MODEL,
        groqEnabled: Boolean = false
    ): String = providerTargets(false, apiKey, groqApiKey, groqModel, groqEnabled).firstOrNull()?.client?.name
        ?: "No AI provider configured"

    private data class ProviderTarget(val client: ModelClient, val modelId: String, val apiKey: String)

    private fun providerTargets(
        needsTools: Boolean,
        apiKey: String,
        groqApiKey: String,
        groqModel: String,
        groqEnabled: Boolean,
        requestedModel: String = ""
    ): List<ProviderTarget> {
        val geminiModel = requestedModel.ifBlank { activeModel }
        val firebase = ProviderTarget(firebaseClient, geminiModel, "")
        val direct = ProviderTarget(restClient, geminiModel, apiKey)
        val groq = ProviderTarget(groqClient, groqModel.ifBlank { AppConfig.DEFAULT_GROQ_MODEL }, groqApiKey)
        fun usable(target: ProviderTarget): Boolean =
            (!target.client.requiresApiKey || target.apiKey.isNotBlank()) &&
                (!needsTools || target.client.supportsTools) &&
                (providerCooldowns[target.client.name] ?: 0L) <= System.currentTimeMillis()

        return when (backend) {
            AiBackend.FIREBASE -> listOf(firebase)
            AiBackend.DIRECT -> listOf(direct)
            AiBackend.GROQ -> listOf(groq)
            AiBackend.AUTO -> listOf(firebase, direct, groq).filterIndexed { index, target ->
                if (index == 0 && firebaseUnavailable) false
                else if (index == 2 && !groqEnabled) false
                else usable(target)
            }
        }
    }

    // Every model call goes through here: model choice (health, cooldown, capability), bounded
    // fallback, backend selection, request logs and per-request token accounting.
    suspend fun routeRequest(
        request: ModelRequest,
        apiKey: String,
        requestedModel: String = "",
        capability: TaskCapability = TaskCapability.GENERAL_CONVERSATION,
        onText: ((String) -> Unit)? = null,
        groqApiKey: String = "",
        groqModel: String = AppConfig.DEFAULT_GROQ_MODEL,
        groqEnabled: Boolean = false
    ): ModelResponse {
        ensureModelDiscovery(apiKey, force = false)

        val now = System.currentTimeMillis()
        val candidateModels = ModelSelector.selectCandidateModels(
            models = models,
            capability = capability,
            preferredModelId = requestedModel.ifBlank { null }
        )

        var lastAttempt: ModelAttempt? = null
        val attemptedProviders = mutableListOf<String>()
        var attemptCount = 0

        // One attempt per provider per request. The provider order is the resilience policy;
        // model discovery still chooses the best Gemini model for the Gemini targets.
        val targets = providerTargets(
            needsTools = request.tools.isNotEmpty(),
            apiKey = apiKey,
            groqApiKey = groqApiKey,
            groqModel = groqModel,
            groqEnabled = groqEnabled,
            requestedModel = requestedModel
        )
        val selectedGeminiModel = candidateModels.firstOrNull()?.id ?: requestedModel.ifBlank { activeModel }
        val resolvedTargets = targets.map { target ->
            if (target.client === groqClient) target else target.copy(modelId = selectedGeminiModel)
        }
        for (target in resolvedTargets) {
            val client = target.client
            attemptCount++
            val startTime = System.currentTimeMillis()
            val fallbackFrom = attemptedProviders.lastOrNull()
            Log.d(TAG, "[Attempt $attemptCount] Routing request via model '${target.modelId}' (${client.name})")

            val attempt = client.generate(request.copy(model = target.modelId), target.apiKey, onText)
            val latency = System.currentTimeMillis() - startTime
            recordProviderUsage(client.name, latency, attempt)
            recordProviderTrace(
                provider = client.name,
                model = target.modelId,
                latencyMs = latency,
                fallbackFrom = fallbackFrom,
                fallbackReason = lastAttempt?.errorCategory?.name,
                attempt = attempt,
                capability = capability
            )
            attemptedProviders += client.name

            if (!attempt.isSuccess && attempt.errorCategory == ProviderErrorCategory.BACKEND_NOT_CONFIGURED &&
                backend == AiBackend.AUTO && client === firebaseClient
            ) {
                Log.w(TAG, "Firebase AI Logic is unavailable; trying the next configured provider.")
                firebaseUnavailable = true
            }
            val response = attempt.response

            if (response != null) {
                if (client !== groqClient) {
                    models.find { it.id == target.modelId }?.let { updateModelSuccess(it, latency, now) }
                }
                providerCooldowns.remove(client.name)
                logRequest(
                    RequestLog(
                        timestamp = now,
                        modelUsed = target.modelId,
                        provider = client.name,
                        latencyMs = latency,
                        httpStatus = attempt.httpStatus ?: 200,
                        errorCode = null,
                        errorMessage = null,
                        retryCount = attemptCount - 1,
                        isSuccess = true,
                        promptTokens = response.usage.promptTokens,
                        outputTokens = response.usage.outputTokens,
                        fallbackFrom = fallbackFrom,
                        fallbackReason = lastAttempt?.errorCategory?.name,
                        errorCategory = ProviderErrorCategory.NONE,
                        route = capability.name,
                        routingReason = if (fallbackFrom == null) "AUTO mode — primary conversational provider"
                        else "Fallback after ${lastAttempt?.errorCategory?.name ?: "provider failure"}",
                        fallbackChain = (attemptedProviders + client.name).distinct().joinToString(" → "),
                        modelCallCount = attemptCount
                    )
                )
                healthStore?.saveModels(models)
                currentCoroutineContext()[UsageRecorder]?.record(response.usage)
                return response
            }

            val previousFailureReason = lastAttempt?.errorCategory?.name
            lastAttempt = attempt
            if (client !== groqClient) {
                models.find { it.id == target.modelId }?.let { model ->
                    if (attempt.errorCategory !in NOT_MODEL_FAULTS) updateModelFailure(model, attempt.httpStatus, attempt.errorMessage, now)
                }
            }
            markProviderFailure(client.name, attempt.errorCategory, now)
            logRequest(
                RequestLog(
                    timestamp = now,
                    modelUsed = target.modelId,
                    provider = client.name,
                    latencyMs = latency,
                    httpStatus = attempt.httpStatus,
                    errorCode = attempt.errorCode,
                    errorMessage = safeError(attempt.errorMessage),
                    retryCount = attemptCount - 1,
                    isSuccess = false,
                    fallbackFrom = fallbackFrom,
                    fallbackReason = previousFailureReason,
                    errorCategory = attempt.errorCategory,
                    route = capability.name,
                    routingReason = if (fallbackFrom == null) "AUTO mode — primary conversational provider"
                    else "Fallback after ${previousFailureReason ?: "provider failure"}",
                    fallbackChain = (attemptedProviders + client.name).distinct().joinToString(" → "),
                    modelCallCount = attemptCount
                )
            )
            healthStore?.saveModels(models)

            // Permanent request/safety failures are not provider fallbacks. Retryable provider
            // failures continue to the next independently configured target exactly once.
            if (attempt.errorCategory in setOf(
                    ProviderErrorCategory.INVALID_REQUEST,
                    ProviderErrorCategory.SAFETY_REJECTION,
                    ProviderErrorCategory.TOOLS_UNSUPPORTED
                )
            ) break
        }

        val finalError = when {
            lastAttempt == null && backend == AiBackend.GROQ -> AIError.InvalidApiKey
            lastAttempt?.errorCategory == ProviderErrorCategory.INVALID_REQUEST -> AIError.UnknownError("The AI request was invalid and was not retried.")
            lastAttempt?.errorCategory == ProviderErrorCategory.SAFETY_REJECTION -> AIError.UnknownError("The request was blocked by a provider safety policy.")
            lastAttempt?.errorCategory == ProviderErrorCategory.TOOLS_UNSUPPORTED -> AIError.UnknownError("No configured provider can run the requested tools.")
            attemptedProviders.size >= 2 && attemptedProviders.contains("Groq") && lastAttempt != null -> AIError.GeminiAndGroqUnavailable
            lastAttempt?.errorCategory == ProviderErrorCategory.QUOTA_EXCEEDED || lastAttempt?.errorCategory == ProviderErrorCategory.RATE_LIMITED -> AIError.QuotaExceeded
            lastAttempt?.errorCategory == ProviderErrorCategory.MODEL_UNAVAILABLE -> AIError.ModelNotFound
            lastAttempt?.errorCategory == ProviderErrorCategory.TIMEOUT -> AIError.Timeout
            lastAttempt?.errorCategory == ProviderErrorCategory.NETWORK_ERROR -> AIError.NetworkError
            lastAttempt?.errorCategory == ProviderErrorCategory.AUTHENTICATION_ERROR -> AIError.InvalidApiKey
            lastAttempt?.errorCategory == ProviderErrorCategory.BACKEND_NOT_CONFIGURED -> AIError.UnknownError(
                "Firebase AI Logic is not set up for this app. Enable it in Firebase, or configure Gemini/Groq in Settings."
            )
            else -> AIError.ProvidersUnavailable
        }
        throw AIException(finalError)
    }

    suspend fun testConnection(
        apiKey: String,
        modelName: String = "",
        groqApiKey: String = "",
        groqModel: String = AppConfig.DEFAULT_GROQ_MODEL,
        groqEnabled: Boolean = false
    ): TestConnectionResult = try {
        val response = routeRequest(
            ModelRequest(messages = listOf(ModelMessage.user("Respond with 'OK' to verify connection."))),
            apiKey,
            modelName,
            groqApiKey = groqApiKey,
            groqModel = groqModel,
            groqEnabled = groqEnabled
        )
        TestConnectionResult(true, "${response.backend} connected (model '${response.modelId}').")
    } catch (e: AIException) {
        TestConnectionResult(false, e.error.userFriendlyMessage)
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

    // Pipeline-level summaries share the same bounded request log as provider attempts. They are
    // intentionally metadata-only and are not persisted beyond the current app process.
    fun recordRequestLog(logEntry: RequestLog) = logRequest(logEntry)

    private fun recordProviderUsage(provider: String, latencyMs: Long, attempt: ModelAttempt) {
        providerUsage.getOrPut(provider) { MutableProviderUsage() }.record(latencyMs, attempt)
    }

    private suspend fun recordProviderTrace(
        provider: String,
        model: String,
        latencyMs: Long,
        fallbackFrom: String?,
        fallbackReason: String?,
        attempt: ModelAttempt,
        capability: TaskCapability
    ) {
        currentCoroutineContext()[ProviderTraceRecorder]?.record(
            ProviderAttemptTrace(
                provider = provider,
                model = model,
                route = capability.name,
                fallbackFrom = fallbackFrom,
                fallbackReason = fallbackReason,
                latencyMs = latencyMs,
                usage = attempt.response?.usage ?: TokenUsage(),
                success = attempt.isSuccess,
                errorCategory = attempt.errorCategory
            )
        )
    }

    private fun markProviderFailure(provider: String, category: ProviderErrorCategory, now: Long) {
        val cooldown = when (category) {
            ProviderErrorCategory.RATE_LIMITED, ProviderErrorCategory.QUOTA_EXCEEDED -> 60_000L
            ProviderErrorCategory.TIMEOUT -> 30_000L
            ProviderErrorCategory.NETWORK_ERROR, ProviderErrorCategory.SERVER_ERROR -> 15_000L
            ProviderErrorCategory.BACKEND_NOT_CONFIGURED -> 60_000L
            else -> 0L
        }
        if (cooldown > 0L) providerCooldowns[provider] = now + cooldown
    }

    private fun safeError(message: String?): String? = message?.replace(
        Regex("(?i)(?:gsk_|AIza)[A-Za-z0-9_\\-]+"), "REDACTED"
    )

    private companion object {
        const val MAX_REQUEST_LOGS = 30
        val NOT_MODEL_FAULTS = setOf(
            ProviderErrorCategory.AUTHENTICATION_ERROR,
            ProviderErrorCategory.BACKEND_NOT_CONFIGURED,
            ProviderErrorCategory.TOOLS_UNSUPPORTED
        )
    }
}
