package com.jax.assistant.data

import android.content.Context
import com.jax.assistant.db.AppDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Lightweight manual dependency container: single source of truth for app-wide singletons.
// (Kept dependency-free on purpose; can be swapped for Hilt once a build/emulator is available.)
object ServiceLocator {

    private lateinit var appContext: Context

    fun init(context: Context) {
        if (!::appContext.isInitialized) {
            appContext = context.applicationContext
        }
    }

    private val database: AppDatabase by lazy { AppDatabase.getDatabase(appContext) }

    val userPreferences: UserPreferencesRepository by lazy { UserPreferencesRepository(appContext) }
    val tasks: TaskRepository by lazy { TaskRepository(database.taskDao()) }
    val memory: MemoryRepository by lazy { MemoryRepository(database.factDao(), database.factEmbeddingDao()) }
    val notes: NotesRepository by lazy { NotesRepository(database) }
    val goals: GoalRepository by lazy { GoalRepository(database.goalDao()) }
    val projects: ProjectRepository by lazy { ProjectRepository(database.projectDao()) }
    val habits: HabitRepository by lazy { HabitRepository(database.habitDao()) }
    val chat: ChatRepository by lazy { ChatRepository(database.chatMessageDao()) }
    val agentRuns: AgentRunRepository by lazy { AgentRunRepository(database.agentRunDao()) }
    val factEmbeddings: FactEmbeddingRepository by lazy { FactEmbeddingRepository(database.factEmbeddingDao()) }
    val ai: AiRepository by lazy { AiRepository(appContext, userPreferences.getApiKey()) }
    val auth: AuthRepository by lazy {
        AuthRepository(appContext, appContext.getString(com.jax.assistant.R.string.jax_web_client_id))
    }
    val firebaseSync: FirebaseSyncManager by lazy { FirebaseSyncManager(database) }

    suspend fun clearAllLocalData() = withContext(Dispatchers.IO) {
        database.clearAllTables()
        userPreferences.clearAll()
    }
}
