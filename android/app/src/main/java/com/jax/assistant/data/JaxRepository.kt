package com.jax.assistant.data

import android.content.Context
import android.net.Uri
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.VectorUtils
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.agent.AgentController
import com.jax.assistant.ai.agent.AgentOrchestrator
import com.jax.assistant.ai.agent.ContextAssembler
import com.jax.assistant.ai.agent.InMemoryEventSink
import com.jax.assistant.ai.agent.ToolRegistry
import com.jax.assistant.ai.agent.tools.CompleteTaskTool
import com.jax.assistant.ai.agent.tools.CreateTaskTool
import com.jax.assistant.ai.agent.tools.DialTool
import com.jax.assistant.ai.agent.tools.NavigateTool
import com.jax.assistant.ai.agent.tools.OpenAppTool
import com.jax.assistant.ai.agent.tools.SearchMemoryTool
import com.jax.assistant.ai.agent.tools.SearchTasksTool
import com.jax.assistant.ai.agent.tools.SetAlarmTool
import com.jax.assistant.ai.agent.tools.SetTimerTool
import com.jax.assistant.ai.agent.tools.StoreMemoryTool
import com.jax.assistant.ai.agent.tools.UpdateProfileTool
import com.jax.assistant.ai.agent.tools.WebSearchTool
import com.jax.assistant.device.DeviceController
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.db.ChatMessageEntity
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
    private val deviceController = DeviceController(context.applicationContext)
    private val chatRepo = locator.chat
    private val agentRunRepo = locator.agentRuns
    private val factEmbeddingRepo = locator.factEmbeddings

    // Phase 1 agent: tool registry + controlled reasoning loop over the existing AI router.
    private val agent: AgentOrchestrator by lazy {
        val registry = ToolRegistry(
            listOf(
                CreateTaskTool(taskRepo),
                SearchTasksTool(taskRepo),
                CompleteTaskTool(taskRepo),
                StoreMemoryTool(memoryRepo),
                SearchMemoryTool(memoryRepo),
                SetAlarmTool(deviceController),
                SetTimerTool(deviceController),
                OpenAppTool(deviceController),
                WebSearchTool(deviceController),
                NavigateTool(deviceController),
                DialTool(deviceController),
                UpdateProfileTool(prefs)
            )
        )
        AgentOrchestrator(
            registry = registry,
            controller = AgentController(),
            generate = { prompt, model -> aiRepo.generateAgent(prompt, model) }
        )
    }

    fun getApiKey(): String = prefs.getApiKey()

    fun saveApiKey(key: String) {
        prefs.saveApiKey(key)
        aiRepo.updateApiKey(key)
    }

    fun getSelectedModel(): String = prefs.getSelectedModel()

    fun saveSelectedModel(modelName: String) = prefs.saveSelectedModel(modelName)

    fun isDeveloperMode(): Boolean = prefs.isDeveloperMode()

    fun setDeveloperMode(enabled: Boolean) = prefs.setDeveloperMode(enabled)

    fun isDailyAutomationEnabled(): Boolean = prefs.isDailyAutomationEnabled()

    fun setDailyAutomationEnabled(enabled: Boolean) = prefs.setDailyAutomationEnabled(enabled)

    fun isTaskContextAwarenessEnabled(): Boolean = prefs.isTaskContextAwarenessEnabled()

    fun setTaskContextAwarenessEnabled(enabled: Boolean) = prefs.setTaskContextAwarenessEnabled(enabled)

    fun isVoiceResponsesEnabled(): Boolean = prefs.isVoiceResponsesEnabled()

    fun setVoiceResponsesEnabled(enabled: Boolean) = prefs.setVoiceResponsesEnabled(enabled)

    fun getUserProfile(): String = prefs.getUserProfile()

    fun saveUserProfile(profile: String) = prefs.saveUserProfile(profile)

    fun getModelCatalog(): List<ModelInfo> = aiRepo.getModelCatalog()

    val requestLogs: StateFlow<List<RequestLog>> get() = aiRepo.requestLogs

    fun getActiveModel(): String = aiRepo.getActiveModel()

    suspend fun runHealthCheck(): String = aiRepo.runHealthCheck(getApiKey())

    suspend fun testConnection(modelName: String = getSelectedModel()): TestConnectionResult =
        aiRepo.testConnection(modelName)

    suspend fun analyzeImage(imageUri: Uri): String = aiRepo.analyzeImage(imageUri, getSelectedModel())

    fun getAllTasks(): Flow<List<TaskEntity>> = taskRepo.getAllTasks()

    fun getAllFacts(): Flow<List<FactEntity>> = memoryRepo.getAllFacts()

    suspend fun insertTask(task: TaskEntity) = taskRepo.insertTask(task)

    suspend fun createManualTask(title: String, category: String, priority: String, deadline: String?): TaskEntity =
        taskRepo.createManualTask(title, category, priority, deadline)

    suspend fun updateTask(task: TaskEntity) = taskRepo.updateTask(task)

    suspend fun deleteTask(task: TaskEntity) = taskRepo.deleteTask(task)

    suspend fun insertFact(fact: FactEntity) = memoryRepo.insertFact(fact)

    suspend fun updateFact(fact: FactEntity) = memoryRepo.updateFact(fact)

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

    // Phase 1: run the tool-using agent loop for a chat turn. Tools persist their own
    // changes; returns J.A.X.'s final reply.
    suspend fun runAgent(
        input: String,
        facts: List<FactEntity> = emptyList(),
        conversationSummary: String = ""
    ): String {
        val openTasks = taskRepo.getAllTasksSnapshot().filter { !it.isCompleted }
        val relevant = selectRelevantFactsHybrid(input, facts)
        val learnedWorkflows = agentRunRepo.learnedWorkflows()
        val context = ContextAssembler().build(
            relevantFacts = relevant,
            openTasks = openTasks,
            conversationSummary = conversationSummary,
            currentDate = java.time.LocalDate.now().toString(),
            userProfile = prefs.getUserProfile(),
            learnedWorkflows = learnedWorkflows
        )
        // Durable run: capture this turn's events and persist the run + events to Room.
        val runId = java.util.UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        val sink = InMemoryEventSink()
        val result = agent.run(input, getSelectedModel(), context.text, sink)
        val events = sink.snapshot()
        val status = if (events.any { it.type == "error" }) "COMPLETED_WITH_ERRORS" else "COMPLETED"
        agentRunRepo.saveRun(runId, input, status, result.reply, result.toolsUsed, startedAt, System.currentTimeMillis(), events)
        return result.reply
    }

    // Hybrid memory retrieval: semantic (embeddings) + lexical (keyword), best-effort.
    // Embeddings backfill a few facts per turn, so semantic recall warms up over the first messages.
    private suspend fun selectRelevantFactsHybrid(query: String, facts: List<FactEntity>): List<FactEntity> {
        if (facts.isEmpty()) return emptyList()
        val lexical = MemoryEngine().selectRelevantMemories(query, facts)
        val queryVec = aiRepo.embed(query) ?: return lexical
        val vectors = factEmbeddingRepo.getAll().toMutableMap()
        var backfilled = 0
        for (f in facts) {
            if (!vectors.containsKey(f.id) && backfilled < 4) {
                val v = aiRepo.embed("${f.title}. ${f.details}")
                if (v != null) {
                    factEmbeddingRepo.save(f.id, v)
                    vectors[f.id] = v
                    backfilled++
                }
            }
        }
        val semantic = facts
            .mapNotNull { f -> vectors[f.id]?.let { f to VectorUtils.cosine(queryVec, it) } }
            .filter { it.second > 0.3f }
            .sortedByDescending { it.second }
            .take(5)
            .map { it.first }
        val merged = LinkedHashSet<FactEntity>()
        semantic.forEach { merged.add(it) }
        lexical.forEach { merged.add(it) }
        return merged.take(6).toList()
    }

    suspend fun getChatHistory(): List<ChatMessageEntity> = chatRepo.getHistory()

    suspend fun saveChatMessage(message: ChatMessageEntity) = chatRepo.save(message)

    suspend fun clearChatHistory() = chatRepo.clear()

    suspend fun clearAllLocalData() {
        locator.clearAllLocalData()
        aiRepo.updateApiKey("")
    }

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
