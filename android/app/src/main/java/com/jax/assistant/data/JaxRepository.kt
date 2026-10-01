package com.jax.assistant.data

import android.content.Context
import android.net.Uri
import com.jax.assistant.ai.AiBackend
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.ModelMessage
import com.jax.assistant.ai.ModelResponse
import com.jax.assistant.ai.ModelToolSpec
import com.jax.assistant.ai.ProviderErrorCategory
import com.jax.assistant.ai.VectorUtils
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.agent.AgentController
import com.jax.assistant.ai.agent.AutonomyLevel
import com.jax.assistant.ai.agent.ChatDraft
import com.jax.assistant.ai.agent.ContextAssembler
import com.jax.assistant.ai.agent.JaxPersona
import com.jax.assistant.ai.agent.MemoryFirstResolver
import com.jax.assistant.ai.agent.ProposedAction
import com.jax.assistant.ai.agent.RequestPipeline
import com.jax.assistant.ai.agent.RequestTrace
import com.jax.assistant.ai.agent.ToolCallingModel
import com.jax.assistant.ai.agent.ToolConfirmation
import com.jax.assistant.ai.agent.ToolRegistry
import com.jax.assistant.ai.agent.WorkingMemory
import com.jax.assistant.ai.agent.tools.CompleteTaskTool
import com.jax.assistant.ai.agent.tools.CreateReminderTool
import com.jax.assistant.ai.agent.tools.CreateTaskTool
import com.jax.assistant.ai.agent.tools.DateTimeTool
import com.jax.assistant.ai.agent.tools.DialTool
import com.jax.assistant.ai.agent.tools.NavigateTool
import com.jax.assistant.ai.agent.tools.OpenAppTool
import com.jax.assistant.ai.agent.tools.OpenCameraTool
import com.jax.assistant.ai.agent.tools.OpenSettingsTool
import com.jax.assistant.ai.agent.tools.OpenWebSearchTool
import com.jax.assistant.ai.agent.tools.RescheduleReminderTool
import com.jax.assistant.ai.agent.tools.SearchMemoryTool
import com.jax.assistant.ai.agent.tools.SearchTasksTool
import com.jax.assistant.ai.agent.tools.SetAlarmTool
import com.jax.assistant.ai.agent.tools.SetTimerTool
import com.jax.assistant.ai.agent.tools.StoreMemoryTool
import com.jax.assistant.ai.agent.tools.UpdateProfileTool
import com.jax.assistant.ai.agent.tools.WeatherClient
import com.jax.assistant.ai.agent.tools.WeatherTool
import com.jax.assistant.ai.agent.tools.WebFetchTool
import com.jax.assistant.ai.agent.tools.WebSearchTool
import com.jax.assistant.device.DeviceController
import com.jax.assistant.worker.WorkManagerReminderScheduler
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
import org.json.JSONObject

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
    private val reminderScheduler = WorkManagerReminderScheduler(context.applicationContext)
    private val controller = AgentController()
    private val workingMemory = WorkingMemory()

    init {
        aiRepo.setBackend(prefs.getAiBackend())
        aiRepo.updateGroqConfiguration(prefs.getGroqApiKey(), prefs.getGroqModel(), prefs.isGroqEnabled())
    }

    // Every capability J.A.X. has. Adding a tool here makes it available to all routes.
    private val registry: ToolRegistry by lazy {
        ToolRegistry(
            listOf(
                DateTimeTool(),
                WeatherTool(WeatherClient()),
                CreateTaskTool(taskRepo),
                CreateReminderTool(taskRepo, reminderScheduler),
                RescheduleReminderTool(taskRepo, reminderScheduler),
                SearchTasksTool(taskRepo),
                CompleteTaskTool(taskRepo),
                StoreMemoryTool(memoryRepo),
                SearchMemoryTool(memoryRepo),
                WebSearchTool(),
                WebFetchTool(),
                SetAlarmTool(deviceController),
                SetTimerTool(deviceController),
                OpenAppTool(deviceController),
                OpenCameraTool(deviceController),
                OpenSettingsTool(deviceController),
                OpenWebSearchTool(deviceController),
                NavigateTool(deviceController),
                DialTool(deviceController),
                UpdateProfileTool(prefs)
            )
        )
    }

    fun getApiKey(): String = prefs.getApiKey()

    fun saveApiKey(key: String) {
        prefs.saveApiKey(key)
        aiRepo.updateApiKey(key)
    }

    fun getGroqApiKey(): String = prefs.getGroqApiKey()

    fun saveGroqApiKey(key: String) {
        prefs.saveGroqApiKey(key)
        aiRepo.updateGroqConfiguration(key, prefs.getGroqModel(), prefs.isGroqEnabled())
    }

    fun isGroqEnabled(): Boolean = prefs.isGroqEnabled()

    fun setGroqEnabled(enabled: Boolean) {
        prefs.setGroqEnabled(enabled)
        aiRepo.updateGroqConfiguration(prefs.getGroqApiKey(), prefs.getGroqModel(), enabled)
    }

    fun getGroqModel(): String = prefs.getGroqModel()

    fun saveGroqModel(modelName: String) {
        prefs.saveGroqModel(modelName)
        aiRepo.updateGroqConfiguration(prefs.getGroqApiKey(), modelName, prefs.isGroqEnabled())
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

    // Runs one chat turn (typed or spoken) through the request pipeline:
    // normalize -> fast route -> direct tool | chat | agent loop | planner -> verified reply.
    // `onPartial` receives the answer text so far while it streams.
    suspend fun runAgent(
        input: String,
        facts: List<FactEntity> = emptyList(),
        conversationSummary: String = "",
        onPartial: ((String) -> Unit)? = null,
        // Consulted for HIGH-risk tool calls; default approves for non-interactive callers.
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): String {
        val pipeline = RequestPipeline(
            registry = registry,
            controller = controller,
            runStore = agentRunRepo,
            workingMemory = workingMemory,
            modelCall = { prompt, model -> aiRepo.generateAgent(prompt, model) },
            chat = { text -> chatDraft(text, facts, withWorkingContext(conversationSummary)).reply },
            buildContext = { text, trace -> buildAgentContext(text, facts, conversationSummary, trace) },
            selectedModel = { getSelectedModel() },
            answerCall = { prompt, model -> aiRepo.generateAnswer(prompt, model, JaxPersona.systemInstruction()) },
            toolModel = toolModel,
            chatDraft = { text -> chatDraft(text, facts, withWorkingContext(conversationSummary)) },
            memoryAnswer = { text -> MemoryFirstResolver.resolve(text, facts) }
        )
        val result = pipeline.handle(input, onPartial, confirm)
        aiRepo.recordRequestLog(result.trace.toRequestLog())
        return result.reply
    }

    private fun RequestTrace.toRequestLog(): RequestLog {
        val attempts = providerAttempts
        val localProvider = when {
            route == "MEMORY" -> "Local"
            tools.any { it.startsWith("get_weather:") } -> "Open-Meteo"
            tools.any { it.startsWith("navigate:") } -> "Maps"
            tools.any { it.startsWith("get_datetime:") || it.startsWith("create_") } -> "Local"
            tools.isNotEmpty() -> "Local"
            else -> "Local"
        }
        val providerName = provider ?: localProvider
        val modelName = model?.takeIf { it.isNotBlank() } ?: "None"
        val routeName = intent.ifBlank { route }.let {
            when (it) {
                "GENERAL" -> "CHAT"
                "MEMORY_FACT" -> "MEMORY"
                "TOOL_REQUEST" -> "AGENT"
                "COMPLEX_AGENT" -> "PLAN"
                else -> it
            }
        }
        val chain = attempts.map { it.provider }.distinct().joinToString(" → ").ifBlank { null }
        val reason = routingReason ?: when {
            route == "MEMORY" -> "Memory-first fact match"
            tools.isNotEmpty() -> "Deterministic ${tools.first().substringBefore(":")}"
            else -> "Local request path"
        }
        return RequestLog(
            timestamp = System.currentTimeMillis(),
            modelUsed = modelName,
            provider = providerName,
            latencyMs = totalLatencyMs,
            httpStatus = attempts.lastOrNull()?.let { if (it.success) 200 else null },
            errorCode = errorType.ifBlank { null },
            errorMessage = null,
            retryCount = (attempts.size - 1).coerceAtLeast(0),
            isSuccess = success,
            promptTokens = promptTokens,
            outputTokens = outputTokens,
            fallbackFrom = fallbackFrom,
            fallbackReason = fallbackReason,
            errorCategory = errorCategory.takeIf { it.isNotBlank() }
                ?.let { runCatching { ProviderErrorCategory.valueOf(it) }.getOrDefault(ProviderErrorCategory.UNKNOWN) }
                ?: ProviderErrorCategory.NONE,
            requestId = requestId,
            route = routeName,
            routingReason = reason,
            fallbackChain = chain,
            modelCallCount = maxOf(geminiCalls, attempts.size),
            toolCallCount = tools.size,
            memoryStatus = memoryStatus,
            isRequestSummary = true
        )
    }

    private val toolModel = object : ToolCallingModel {
        override fun isAvailable(): Boolean = aiRepo.nativeToolsAvailable()

        override suspend fun call(
            systemInstruction: String,
            messages: List<ModelMessage>,
            tools: List<ModelToolSpec>,
            stream: Boolean
        ): ModelResponse = aiRepo.callWithTools(systemInstruction, messages, tools, getSelectedModel(), stream)
    }

    fun getAiBackend(): AiBackend = prefs.getAiBackend()

    fun setAiBackend(backend: AiBackend) {
        prefs.setAiBackend(backend)
        aiRepo.setBackend(backend)
    }

    fun aiBackendName(): String = aiRepo.backendName()

    // Gemini Live gets the same persona plus the compact conversation state, so voice knows the context.
    fun liveSystemInstruction(): String {
        val working = workingMemory.contextBlock()
        val profile = prefs.getUserProfile()
        return buildString {
            append(JaxPersona.systemInstruction())
            append("\n\nYou are speaking aloud: keep answers short and conversational.")
            if (profile.isNotBlank()) append("\n\nUSER PROFILE:\n${profile.take(600)}")
            if (working.isNotBlank()) append("\n\nCURRENT CONTEXT:\n$working")
        }
    }

    // The single-call chat path gets the compact working state (topic, entities, open question)
    // ahead of the recent dialogue, so follow-ups resolve without resending the whole conversation.
    private fun withWorkingContext(conversationSummary: String): String {
        val working = workingMemory.contextBlock()
        return if (working.isBlank()) conversationSummary
        else "CURRENT CONTEXT:\n$working\n\n$conversationSummary".trim()
    }

    // Single-call conversational path. It only proposes an action; RequestPipeline runs it through
    // ToolExecutor (memory policy, risk gate, verification) instead of writing here.
    private suspend fun chatDraft(input: String, facts: List<FactEntity>, conversationSummary: String): ChatDraft =
        when (val parsed = aiRepo.processUserInput(input, getSelectedModel(), facts, conversationSummary)) {
            is JaxParseResult.QuestionResult -> ChatDraft(parsed.reply)
            is JaxParseResult.TaskResult -> ChatDraft(
                parsed.reply,
                ProposedAction(
                    "create_task",
                    JSONObject()
                        .put("title", parsed.task.title)
                        .put("category", parsed.task.category)
                        .put("priority", parsed.task.priority)
                        .put("deadline", parsed.task.deadline ?: "")
                )
            )
            is JaxParseResult.FactResult -> ChatDraft(
                parsed.reply,
                ProposedAction(
                    "store_memory",
                    JSONObject()
                        .put("title", parsed.fact.title)
                        .put("category", parsed.fact.category)
                        .put("details", parsed.fact.details)
                )
            )
            is JaxParseResult.CompleteTaskResult -> completeTaskDraft(parsed.reference, parsed.reply)
        }

    // Only relevant facts (hybrid retrieval, top 6) and bounded sections reach the prompt.
    private suspend fun buildAgentContext(
        input: String,
        facts: List<FactEntity>,
        conversationSummary: String,
        trace: RequestTrace
    ): String {
        val relevantFacts = selectRelevantFactsHybrid(input, facts, trace)
        if (relevantFacts.isNotEmpty()) trace.recordMemoryContextUsed()
        return ContextAssembler().build(
        relevantFacts = relevantFacts,
        openTasks = taskRepo.getAllTasksSnapshot().filter { !it.isCompleted },
        conversationSummary = conversationSummary,
        currentDate = java.time.LocalDate.now().toString(),
        userProfile = prefs.getUserProfile(),
        learnedWorkflows = agentRunRepo.learnedWorkflows(),
        workingState = workingMemory.contextBlock()
        ).text
    }

    fun clearWorkingMemory() = workingMemory.clear()

    // Resolves "mark X done" from the single-call brain against open tasks. Proposes completion only
    // for an unambiguous match; otherwise asks the user to be more specific.
    private suspend fun completeTaskDraft(reference: String, modelReply: String): ChatDraft {
        val words = reference.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 }
        if (words.isEmpty()) return ChatDraft("Which task should I mark as done?")
        val open = taskRepo.getAllTasksSnapshot().filter { !it.isCompleted }
        val scored = open
            .map { task -> task to words.count { task.title.lowercase().contains(it) } }
            .filter { it.second > 0 }
        val best = scored.maxOfOrNull { it.second }
            ?: return ChatDraft("I couldn't find an open task matching \"$reference\".")
        val matches = scored.filter { it.second == best }.map { it.first }
        if (matches.size > 1) {
            return ChatDraft(
                "I found ${matches.size} matching tasks: " +
                    matches.take(3).joinToString(", ") { "\"${it.title}\"" } + ". Which one did you mean?"
            )
        }
        val task = matches.single()
        return ChatDraft(
            modelReply.ifBlank { "Marked \"${task.title}\" as done." },
            ProposedAction("complete_task", JSONObject().put("id", task.id))
        )
    }

    // Crash recovery: close out agent runs left RUNNING by a previous process death.
    // Returns a user-facing line per interrupted run describing where it stopped.
    suspend fun reconcileInterruptedAgentRuns(): List<String> = agentRunRepo.reconcileInterruptedRuns()

    // Hybrid memory retrieval: semantic similarity (embeddings) and keyword match give relevance;
    // recency and confidence break ties (MemoryEngine.rankForContext). Facts relevant by neither
    // signal are left out. Embeddings backfill one fact per turn, so semantic recall warms up.
    private suspend fun selectRelevantFactsHybrid(
        query: String,
        facts: List<FactEntity>,
        trace: RequestTrace
    ): List<FactEntity> {
        if (facts.isEmpty()) return emptyList()
        val engine = MemoryEngine()
        val now = System.currentTimeMillis()
        val queryVec = trace.embedding { aiRepo.embed(query) }
            ?: return engine.rankForContext(query, facts, emptyMap(), now)
        val vectors = factEmbeddingRepo.getAll().toMutableMap()
        // Backfill at most one missing vector per turn. Four sequential embedding calls
        // made agent requests feel stalled on slower networks; lexical ranking remains the
        // fallback when the best-effort embedding request is unavailable.
        var backfilled = 0
        for (f in facts) {
            if (!vectors.containsKey(f.id) && backfilled < 1) {
                val v = trace.embedding { aiRepo.embed("${f.title}. ${f.details}") }
                if (v != null) {
                    factEmbeddingRepo.save(f.id, v)
                    vectors[f.id] = v
                    backfilled++
                }
            }
        }
        val semantic = facts.mapNotNull { f -> vectors[f.id]?.let { f.id to VectorUtils.cosine(queryVec, it) } }.toMap()
        return engine.rankForContext(query, facts, semantic, now)
    }

    suspend fun getChatHistory(): List<ChatMessageEntity> = chatRepo.getHistory()

    suspend fun saveChatMessage(message: ChatMessageEntity) = chatRepo.save(message)

    suspend fun clearChatHistory() = chatRepo.clear()

    suspend fun clearAllLocalData() {
        locator.clearAllLocalData()
        aiRepo.updateApiKey("")
        aiRepo.updateGroqConfiguration("", com.jax.assistant.config.AppConfig.DEFAULT_GROQ_MODEL, false)
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
