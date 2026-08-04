package com.jax.assistant.data

import android.content.Context
import android.content.SharedPreferences
import com.jax.assistant.ai.GeminiBrain
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.db.AppDatabase
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

class JaxRepository(private val context: Context) {

    private val db: AppDatabase = AppDatabase.getDatabase(context)
    private val prefs: SharedPreferences = context.getSharedPreferences("jax_prefs", Context.MODE_PRIVATE)

    private val geminiBrain: GeminiBrain

    init {
        val storedKey = prefs.getString("gemini_api_key", null)
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
        geminiBrain = GeminiBrain(storedKey)
    }

    fun getApiKey(): String {
        return prefs.getString("gemini_api_key", null)
            ?: System.getenv("GEMINI_API_KEY")
            ?: ""
    }

    fun saveApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
        geminiBrain.apiKey = key.trim()
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

    suspend fun processUserInput(input: String): JaxParseResult {
        return geminiBrain.processUserInput(input)
    }
}
