package com.jax.assistant.data

import android.content.Context
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.GoalEntity
import com.jax.assistant.db.HabitEntity
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Facade that delegates to focused domain repositories provided by ServiceLocator.
class JaxRepository(context: Context) {

    private val locator = ServiceLocator.apply { init(context) }
    private val prefs = locator.userPreferences
    private val taskRepo = locator.tasks
    private val memoryRepo = locator.memory
    private val goalRepo = locator.goals
    private val projectRepo = locator.projects
    private val habitRepo = locator.habits
    private val aiRepo = locator.ai

    fun getApiKey(): String = prefs.getApiKey()

    fun saveApiKey(key: String) {
        prefs.saveApiKey(key)
        aiRepo.updateApiKey(key)
    }

    fun getSelectedModel(): String = prefs.getSelectedModel()

    fun saveSelectedModel(modelName: String) = prefs.saveSelectedModel(modelName)

    fun isDeveloperMode(): Boolean = prefs.isDeveloperMode()

    fun setDeveloperMode(enabled: Boolean) = prefs.setDeveloperMode(enabled)

    fun getModelCatalog(): List<ModelInfo> = aiRepo.getModelCatalog()

    val requestLogs: StateFlow<List<RequestLog>> get() = aiRepo.requestLogs

    fun getActiveModel(): String = aiRepo.getActiveModel()

    suspend fun runHealthCheck(): String = aiRepo.runHealthCheck(getApiKey())

    suspend fun testConnection(modelName: String = getSelectedModel()): TestConnectionResult =
        aiRepo.testConnection(modelName)

    fun getAllTasks(): Flow<List<TaskEntity>> = taskRepo.getAllTasks()

    fun getAllFacts(): Flow<List<FactEntity>> = memoryRepo.getAllFacts()

    suspend fun insertTask(task: TaskEntity) = taskRepo.insertTask(task)

    suspend fun createManualTask(title: String, category: String, priority: String, deadline: String?): TaskEntity =
        taskRepo.createManualTask(title, category, priority, deadline)

    suspend fun updateTask(task: TaskEntity) = taskRepo.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskRepo.deleteTask(task)

    suspend fun insertFact(fact: FactEntity) = memoryRepo.insertFact(fact)

    suspend fun createManualFact(title: String, category: String, details: String): FactEntity =
        memoryRepo.createManualFact(title, category, details)

    suspend fun deleteFact(fact: FactEntity) = memoryRepo.deleteFact(fact)

    fun searchFacts(query: String): Flow<List<FactEntity>> = memoryRepo.searchFacts(query)

    suspend fun processUserInput(
        input: String,
        factsList: List<FactEntity> = emptyList(),
        conversationSummary: String = ""
    ): JaxParseResult =
        aiRepo.processUserInput(input, getSelectedModel(), factsList, conversationSummary)

    // ---- Executive Intelligence (Phase 2) ----

    fun getAllGoals(): Flow<List<GoalEntity>> = goalRepo.getAllGoals()

    suspend fun createGoal(title: String, category: String, targetValue: Int, deadline: String?): GoalEntity =
        goalRepo.createGoal(title, category, targetValue, deadline)

    suspend fun incrementGoalProgress(goal: GoalEntity, delta: Int) = goalRepo.incrementProgress(goal, delta)

    suspend fun updateGoal(goal: GoalEntity) = goalRepo.updateGoal(goal)

    suspend fun deleteGoal(goal: GoalEntity) = goalRepo.deleteGoal(goal)

    fun getAllProjects(): Flow<List<ProjectEntity>> = projectRepo.getAllProjects()

    suspend fun createProject(name: String, description: String): ProjectEntity =
        projectRepo.createProject(name, description)

    suspend fun cycleProjectStatus(project: ProjectEntity) = projectRepo.cycleStatus(project)

    suspend fun deleteProject(project: ProjectEntity) = projectRepo.deleteProject(project)

    fun getAllHabits(): Flow<List<HabitEntity>> = habitRepo.getAllHabits()

    suspend fun createHabit(name: String, category: String): HabitEntity =
        habitRepo.createHabit(name, category)

    suspend fun toggleHabitToday(habit: HabitEntity) = habitRepo.toggleToday(habit)

    suspend fun deleteHabit(habit: HabitEntity) = habitRepo.deleteHabit(habit)

    suspend fun generateDailyPlan(openTasks: List<TaskEntity>, currentDate: String): String =
        aiRepo.generateDailyPlan(getSelectedModel(), openTasks, currentDate)
}
