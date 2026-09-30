package com.jax.assistant.data

import android.content.Context
import android.net.Uri
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.VectorUtils
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.agent.AgentController
import com.jax.assistant.ai.agent.AgentOrchestrator
import com.jax.assistant.ai.agent.AutonomyLevel
import com.jax.assistant.ai.agent.ContextAssembler
import com.jax.assistant.ai.agent.DurableEventSink
import com.jax.assistant.ai.agent.ToolConfirmation
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

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
            generate = { prompt, model -> aiRepo.generateAgent(prompt, model) },
            maxSteps = AGENT_MAX_STEPS
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

    fun getProactiveAutonomyLevel(): AutonomyLevel = prefs.getProactiveAutonomyLevel()

    fun setProactiveAutonomyLevel(level: AutonomyLevel) = prefs.setProactiveAutonomyLevel(level)

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

    // Runs one chat turn. Plain conversation takes the single-call brain path; requests that
    // need tools go through the durable agent loop. Returns J.A.X.'s final reply.
    suspend fun runAgent(
        input: String,
        facts: List<FactEntity> = emptyList(),
        conversationSummary: String = "",
        // Consulted for SENSITIVE/DESTRUCTIVE tool calls; default approves for non-interactive callers.
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): String {
        if (!requiresAgent(input)) {
            return when (val parsed = aiRepo.processUserInput(input, getSelectedModel(), facts, conversationSummary)) {
                is JaxParseResult.QuestionResult -> parsed.reply
                is JaxParseResult.TaskResult -> {
                    taskRepo.insertTask(parsed.task)
                    parsed.reply
                }
                is JaxParseResult.FactResult -> {
                    memoryRepo.insertFact(parsed.fact)
                    parsed.reply
                }
                is JaxParseResult.CompleteTaskResult -> completeTaskByReference(parsed.reference, parsed.reply)
            }
        }

        val openTasks = taskRepo.getAllTasksSnapshot().filter { !it.isCompleted }
        val context = ContextAssembler().build(
            relevantFacts = selectRelevantFactsHybrid(input, facts),
            openTasks = openTasks,
            conversationSummary = conversationSummary,
            currentDate = java.time.LocalDate.now().toString(),
            userProfile = prefs.getUserProfile(),
            learnedWorkflows = agentRunRepo.learnedWorkflows()
        )
        // Durable run: events are flushed to Room as they happen, so a process death mid-run
        // still leaves an auditable trail that reconcileInterruptedRuns() can close out.
        val runId = UUID.randomUUID().toString()
        val sink = DurableEventSink()
        agentRunRepo.startRun(runId, input, maxSteps = AGENT_MAX_STEPS)
        try {
            val result = agent.run(
                input,
                getSelectedModel(),
                context.text,
                sink,
                onCheckpoint = { checkpoint ->
                    agentRunRepo.checkpoint(runId, checkpoint.step, checkpoint.state, checkpoint.toolsUsed)
                    agentRunRepo.saveEvents(runId, sink.drainPending())
                },
                confirm = confirm
            )
            agentRunRepo.saveEvents(runId, sink.drainPending())
            val status = if (sink.snapshot().any { it.type == "error" }) STATUS_COMPLETED_WITH_ERRORS else STATUS_COMPLETED
            agentRunRepo.finishRun(runId, status, result.reply, result.toolsUsed)
            return result.reply
        } catch (e: CancellationException) {
            // Leave the run RUNNING; startup reconciliation marks it INTERRUPTED.
            throw e
        } catch (e: Exception) {
            agentRunRepo.saveEvents(runId, sink.drainPending())
            agentRunRepo.finishRun(runId, STATUS_FAILED, e.message.orEmpty(), emptyList())
            throw e
        }
    }

    // Resolves "mark X done" from the single-call brain against open tasks. Only acts on an
    // unambiguous match; otherwise asks the user to be more specific.
    private suspend fun completeTaskByReference(reference: String, modelReply: String): String {
        val words = reference.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (words.isEmpty()) return "Which task should I mark as done?"
        val open = taskRepo.getAllTasksSnapshot().filter { !it.isCompleted }
        val scored = open
            .map { task -> task to words.count { task.title.lowercase().contains(it) } }
            .filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second } ?: return "I couldn't find an open task matching \"$reference\"."
        val matches = scored.filter { it.second == best }.map { it.first }
        if (matches.size > 1) {
            return "I found ${matches.size} matching tasks: " +
                matches.take(3).joinToString(", ") { "\"${it.title}\"" } + ". Which one did you mean?"
        }
        val task = matches.single()
        taskRepo.updateTask(task.copy(isCompleted = true))
        return modelReply.ifBlank { "Marked \"${task.title}\" as done." }
    }

    // Crash recovery: reconcile agent runs left RUNNING by a previous process death.
    // Safe to call once at app startup; returns the number of runs reconciled.
    suspend fun reconcileInterruptedAgentRuns(): Int = agentRunRepo.reconcileInterruptedRuns()

    private fun requiresAgent(input: String): Boolean {
        val lower = input.lowercase()
        return AGENT_VERBS.containsMatchIn(lower) || AGENT_PHRASES.any(lower::contains)
    }

    // Hybrid memory retrieval: semantic (embeddings) + lexical (keyword), fused with
    // Reciprocal Rank Fusion so a fact strong in both signals outranks one strong in only one.
    // Embeddings backfill a few facts per turn, so semantic recall warms up over the first messages.
    private suspend fun selectRelevantFactsHybrid(query: String, facts: List<FactEntity>): List<FactEntity> {
        if (facts.isEmpty()) return emptyList()
        val engine = MemoryEngine()
        val lexicalRanked = engine.scoreMemories(query, facts).map { it.first }
        val queryVec = aiRepo.embed(query)
            ?: return lexicalRanked.take(6).ifEmpty { engine.selectRelevantMemories(query, facts) }
        val vectors = factEmbeddingRepo.getAll().toMutableMap()
        // Backfill at most one missing vector per turn. Four sequential embedding calls
        // made agent requests feel stalled on slower networks; lexical ranking remains the
        // fallback when the best-effort embedding request is unavailable.
        var backfilled = 0
        for (f in facts) {
            if (!vectors.containsKey(f.id) && backfilled < 1) {
                val v = aiRepo.embed("${f.title}. ${f.details}")
                if (v != null) {
                    factEmbeddingRepo.save(f.id, v)
                    vectors[f.id] = v
                    backfilled++
                }
            }
        }
        val semanticRanked = facts
            .mapNotNull { f -> vectors[f.id]?.let { f to VectorUtils.cosine(queryVec, it) } }
            .filter { it.second > 0.3f }
            .sortedByDescending { it.second }
            .map { it.first }
        val fused = engine.fuseByReciprocalRank(listOf(semanticRanked, lexicalRanked)).take(6)
        return fused.ifEmpty { engine.selectRelevantMemories(query, facts) }
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

    private companion object {
        const val AGENT_MAX_STEPS = 6
        const val STATUS_COMPLETED = "COMPLETED"
        const val STATUS_COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
        const val STATUS_FAILED = "FAILED"

        // Whole-word action verbs, so e.g. "address" no longer matches "add".
        val AGENT_VERBS = Regex(
            "\\b(create|add|make|remind|schedule|set|complete|finish|delete|remove|cancel|" +
                "open|launch|call|dial|navigate|link)\\b"
        )
        val AGENT_PHRASES = listOf("search my", "find my", "organize my", "save this", "remember this", "update my profile")
    }
}
