package com.jax.assistant.data

import android.content.Context
import android.content.SharedPreferences
import com.jax.assistant.ai.GeminiAIService
import com.jax.assistant.ai.GeminiBrain
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.db.AppDatabase
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.executive.ExecutiveIntelligenceEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

class JaxRepository(private val context: Context) {

    private val db: AppDatabase = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("jax_prefs", Context.MODE_PRIVATE)

    private val aiService: GeminiAIService
    private val geminiBrain: GeminiBrain
    val executiveEngine: ExecutiveIntelligenceEngine by lazy { ExecutiveIntelligenceEngine(this) }

    init {
        val storedKey = prefs.getString("gemini_api_key", null)
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
        aiService = GeminiAIService(storedKey, context)
        geminiBrain = GeminiBrain(aiService, MemoryEngine())
    }

    fun getApiKey(): String {
        return prefs.getString("gemini_api_key", null)
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
    }

    fun saveApiKey(key: String) {
        val trimmedKey = key.trim()
        prefs.edit().putString("gemini_api_key", trimmedKey).apply()
        aiService.apiKey = trimmedKey
    }

    fun getSelectedModel(): String {
        return prefs.getString("selected_ai_model", "gemini-2.0-flash") ?: "gemini-2.0-flash"
    }

    fun saveSelectedModel(modelName: String) {
        prefs.edit().putString("selected_ai_model", modelName.trim()).apply()
    }

    fun isDeveloperMode(): Boolean {
        return prefs.getBoolean("developer_mode", false)
    }

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean("developer_mode", enabled).apply()
    }

    fun getModelCatalog(): List<ModelInfo> = aiService.router.getModels()

    val requestLogs: StateFlow<List<RequestLog>> = aiService.router.requestLogs

    fun getActiveModel(): String = aiService.router.getActiveModel()

    suspend fun runHealthCheck(): String {
        return aiService.router.performHealthCheck(getApiKey())
    }

    suspend fun testConnection(modelName: String = getSelectedModel()): TestConnectionResult {
        return aiService.testConnection(modelName)
    }

    fun getAllTasks(): Flow<List<TaskEntity>> = db.taskDao().getAllTasks()

    fun getAllFacts(): Flow<List<FactEntity>> = db.factDao().getAllFacts()

    suspend fun insertTask(task: TaskEntity) = db.taskDao().insertTask(task)

    suspend fun createManualTask(title: String, category: String, priority: String, deadline: String?): TaskEntity {
        val task = TaskEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = if (category.isBlank()) "General" else category,
            priority = if (priority.isBlank()) "MED" else priority,
            deadline = if (deadline.isNullOrBlank()) null else deadline,
            isCompleted = false
        )
        db.taskDao().insertTask(task)
        return task
    }

    suspend fun updateTask(task: TaskEntity) = db.taskDao().updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = db.taskDao().deleteTask(task)

    suspend fun insertFact(fact: FactEntity) = db.factDao().insertFact(fact)

    suspend fun createManualFact(title: String, category: String, details: String): FactEntity {
        val fact = FactEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = if (category.isBlank()) "General" else category,
            details = details
        )
        db.factDao().insertFact(fact)
        return fact
    }

    suspend fun deleteFact(fact: FactEntity) = db.factDao().deleteFact(fact)

    fun searchFacts(query: String): Flow<List<FactEntity>> = db.factDao().searchFacts(query)

    suspend fun processUserInput(input: String, factsList: List<FactEntity> = emptyList()): JaxParseResult {
        // Step 1: Pass input to Executive Intelligence Engine (Planner, ConversationManager, Agent Framework)
        val execResult = executiveEngine.processUserPrompt(input)
        if (execResult.handledLocallyByAgent) {
            return JaxParseResult.QuestionResult(execResult.responseText)
        }

        // Step 2: Fallback to AI Router & Gemini Brain for complex generation
        val selectedModel = getSelectedModel()
        return geminiBrain.processUserInput(input, selectedModel, factsList)
    }
}
