package com.jax.assistant.data

import android.content.Context
import android.net.Uri
import com.jax.assistant.ai.EmbeddingService
import com.jax.assistant.ai.GeminiAIService
import com.jax.assistant.ai.GeminiBrain
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TaskCapability
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.ai.VisionService
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.flow.StateFlow

// Owns the Gemini AI stack (service, router, brain) and conversational processing.
class AiRepository(context: Context, initialApiKey: String) {

    private val aiService = GeminiAIService(initialApiKey, context)
    private val geminiBrain = GeminiBrain(aiService, MemoryEngine())
    private val embeddingService = EmbeddingService { aiService.apiKey }
    private val visionService = VisionService(context.applicationContext.contentResolver) { aiService.apiKey }

    fun updateApiKey(key: String) {
        aiService.apiKey = key.trim()
    }

    val requestLogs: StateFlow<List<RequestLog>> get() = aiService.router.requestLogs

    fun getModelCatalog(): List<ModelInfo> = aiService.router.getModels()

    fun getActiveModel(): String = aiService.router.getActiveModel()

    suspend fun runHealthCheck(apiKey: String): String = aiService.router.performHealthCheck(apiKey)

    suspend fun testConnection(modelName: String): TestConnectionResult =
        aiService.testConnection(modelName)

    suspend fun generate(prompt: String, model: String): String = aiService.generate(prompt, model)

    // Agent tool loops favor capable reasoning models and require machine-readable output.
    suspend fun generateAgent(prompt: String, model: String): String =
        aiService.generateFor(prompt, model, TaskCapability.COMPLEX_REASONING, requireJson = true)

    suspend fun embed(text: String): FloatArray? = embeddingService.embed(text)

    suspend fun analyzeImage(imageUri: Uri, selectedModel: String): String =
        visionService.analyze(imageUri, selectedModel)

    suspend fun processUserInput(
        input: String,
        selectedModel: String,
        facts: List<FactEntity>,
        conversationSummary: String
    ): JaxParseResult =
        geminiBrain.processUserInput(input, selectedModel, facts, conversationSummary)

    // Produces a short, prioritized executive plan from today's open tasks.
    suspend fun generateDailyPlan(
        selectedModel: String,
        openTasks: List<TaskEntity>,
        currentDate: String
    ): String {
        if (openTasks.isEmpty()) {
            return "No open tasks today. Consider planning ahead or reviewing your goals."
        }
        val taskLines = openTasks.joinToString("\n") { t ->
            "- [${t.priority}] ${t.title}" + (t.deadline?.let { " (due $it)" } ?: "")
        }
        val prompt = """
            You are J.A.X., an executive assistant for Jagadeesh. Today is $currentDate.
            Build a concise, prioritized plan for today from these open tasks:
            $taskLines

            Rules:
            - Order by priority (HIGH first) and nearest deadline.
            - Group into "Now", "Next", and "Later".
            - Keep it under 120 words. Use short bullet lines. No preamble.
        """.trimIndent()
        return aiService.generateFor(
            prompt,
            selectedModel,
            TaskCapability.LONG_SUMMARY,
            requireJson = false
        )
    }
}
