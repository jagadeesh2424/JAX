package com.jax.assistant.ai.agent

import com.jax.assistant.ai.AIError
import com.jax.assistant.ai.AIException
import com.jax.assistant.ai.ModelFunctionCall
import com.jax.assistant.ai.ModelMessage
import com.jax.assistant.ai.ModelResponse
import com.jax.assistant.ai.ModelToolSpec
import com.jax.assistant.ai.TextStreamSink
import com.jax.assistant.ai.TokenUsage
import com.jax.assistant.ai.UsageRecorder
import com.jax.assistant.ai.agent.tools.CompleteTaskTool
import com.jax.assistant.ai.agent.tools.CreateReminderTool
import com.jax.assistant.ai.agent.tools.CreateTaskTool
import com.jax.assistant.ai.agent.tools.DateTimeTool
import com.jax.assistant.ai.agent.tools.HttpGet
import com.jax.assistant.ai.agent.tools.ReminderParser
import com.jax.assistant.ai.agent.tools.ReminderScheduler
import com.jax.assistant.ai.agent.tools.RescheduleReminderTool
import com.jax.assistant.ai.agent.tools.SearchResult
import com.jax.assistant.ai.agent.tools.SearchTasksTool
import com.jax.assistant.ai.agent.tools.WeatherClient
import com.jax.assistant.ai.agent.tools.WeatherSnapshot
import com.jax.assistant.ai.agent.tools.WeatherTool
import com.jax.assistant.ai.agent.tools.WebSearchClient
import com.jax.assistant.ai.agent.tools.WebSearchProvider
import com.jax.assistant.ai.agent.tools.WebSearchTool
import com.jax.assistant.data.AgentRunRepository
import com.jax.assistant.data.TaskRepository
import com.jax.assistant.db.AgentEventEntity
import com.jax.assistant.db.AgentRunDao
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.ConsolidatedRunEntity
import com.jax.assistant.db.TaskDao
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

// JVM tests for the request pipeline: routing, direct tools, planning, verification, safety,
// memory policy, recovery and proactivity. Every model call is scripted and counted.
class AgentPipelineTest {

    // ---------------------------------------------------------------- fakes

    private class ScriptedModel(vararg responses: Any) {
        private val queue = ArrayDeque(responses.toList())
        val prompts = mutableListOf<String>()

        suspend fun call(prompt: String): String {
            prompts += prompt
            val next = queue.removeFirstOrNull() ?: error("unexpected model call")
            if (next is Exception) throw next
            return next as String
        }
    }

    private class FakeTool(
        override val name: String,
        override val risk: ToolRisk = ToolRisk.LOW_WRITE,
        override val parameters: List<ToolParam> = emptyList(),
        override val writesDurableMemory: Boolean = false,
        private val delayMs: Long = 0,
        private val result: () -> ToolResult = { ToolResult.ok("$name done") }
    ) : JaxTool {
        override val description = "Fake $name"
        override val isDestructive = risk == ToolRisk.DESTRUCTIVE
        var executions = 0
        val receivedArgs = mutableListOf<JSONObject>()

        override suspend fun execute(args: JSONObject): ToolResult {
            executions++
            receivedArgs += args
            if (delayMs > 0) delay(delayMs)
            return result()
        }
    }

    private class FakeTaskDao : TaskDao {
        val tasks = mutableListOf<TaskEntity>()
        override fun getAllTasks(): Flow<List<TaskEntity>> = flowOf(tasks.toList())
        override suspend fun getAllTasksList(): List<TaskEntity> = tasks.toList()
        override fun getTasksByCategory(category: String): Flow<List<TaskEntity>> = flowOf(tasks.filter { it.category == category })
        override suspend fun getOpenHighPriorityTasks(): List<TaskEntity> = tasks.filter { !it.isCompleted && it.priority == "HIGH" }
        override suspend fun insertTask(task: TaskEntity) { tasks.removeAll { it.id == task.id }; tasks += task }
        override suspend fun updateTask(task: TaskEntity) { insertTask(task) }
        override suspend fun deleteTask(task: TaskEntity) { tasks.removeAll { it.id == task.id } }
    }

    private class FakeScheduler(private val accept: Boolean = true) : ReminderScheduler {
        val scheduled = mutableListOf<Pair<String, LocalDateTime>>()
        override suspend fun schedule(taskId: String, title: String, at: LocalDateTime): Boolean {
            scheduled += title to at
            return accept
        }
    }

    private class FakeRunStore : AgentRunStore {
        val started = mutableListOf<String>()
        val plans = mutableListOf<String>()
        val finished = mutableListOf<String>()
        val statuses = mutableListOf<String>()
        val events = mutableListOf<AgentEvent>()
        var resumable: ResumableRun? = null
        override suspend fun startRun(runId: String, goal: String, maxSteps: Int) { started += goal }
        override suspend fun checkpoint(runId: String, step: Int, state: String, toolsUsed: List<String>) {}
        override suspend fun savePlan(runId: String, planJson: String, currentStep: Int) { plans += planJson }
        override suspend fun saveEvents(runId: String, events: List<AgentEvent>) { this.events += events }
        override suspend fun finishRun(runId: String, status: String, reply: String, toolsUsed: List<String>) { finished += status }
        override suspend fun updateStatus(runId: String, status: String) { statuses += status }
        override suspend fun latestResumableRun(): ResumableRun? = resumable.also { resumable = null }
    }

    private class FakeAgentRunDao(val runs: MutableList<AgentRunEntity>) : AgentRunDao {
        override suspend fun insertRun(run: AgentRunEntity) { runs.removeAll { it.id == run.id }; runs += run }
        override suspend fun updateProgress(
            runId: String, status: String, reply: String, toolsUsed: String,
            currentStep: Int, recoveryState: String, finishedAt: Long, updatedAt: Long
        ) {
            val i = runs.indexOfFirst { it.id == runId }
            runs[i] = runs[i].copy(status = status, reply = reply, toolsUsed = toolsUsed, currentStep = currentStep,
                recoveryState = recoveryState, finishedAt = finishedAt, updatedAt = updatedAt)
        }
        override suspend fun recoverableRuns(): List<AgentRunEntity> =
            runs.filter { it.status in listOf("CREATED", "PLANNING", "RUNNING", "WAITING") }
        override suspend fun updatePlan(runId: String, plan: String, currentStep: Int, updatedAt: Long) {
            val i = runs.indexOfFirst { it.id == runId }
            runs[i] = runs[i].copy(plan = plan, currentStep = currentStep, updatedAt = updatedAt)
        }
        override suspend fun updateStatus(runId: String, status: String, updatedAt: Long) {
            val i = runs.indexOfFirst { it.id == runId }
            runs[i] = runs[i].copy(status = status, updatedAt = updatedAt)
        }
        override suspend fun latestResumableRun(since: Long): AgentRunEntity? =
            runs.filter { it.status in listOf("INTERRUPTED", "CANCELLED") && it.plan.isNotEmpty() && it.startedAt >= since }
                .maxByOrNull { it.startedAt }
        override suspend fun insertEvent(event: AgentEventEntity) {}
        override suspend fun recentRuns(limit: Int): List<AgentRunEntity> = runs.take(limit)
        override suspend fun eventsForRun(runId: String): List<AgentEventEntity> = emptyList()
        override suspend fun completedRuns(limit: Int): List<AgentRunEntity> = runs.filter { it.status == "COMPLETED" }
        override suspend fun unconsolidatedCompletedRuns(since: Long): List<AgentRunEntity> = emptyList()
        override suspend fun insertConsolidatedRun(run: ConsolidatedRunEntity) {}
    }

    private val now: LocalDateTime = LocalDateTime.of(2026, 9, 30, 10, 0)
    private val clockMillis = 1_000_000L

    private fun weatherHttp(place: String = "Bengaluru", alwaysFail: Boolean = false): Pair<HttpGet, IntArray> {
        val calls = intArrayOf(0)
        val http: HttpGet = { url ->
            calls[0]++
            if (alwaysFail) throw java.io.IOException("network unavailable")
            if (url.contains("geocoding")) {
                """{"results":[{"name":"$place","country":"India","latitude":12.97,"longitude":77.59}]}"""
            } else {
                """{"current":{"temperature_2m":27.5,"weather_code":2},
                   "daily":{"time":["2026-09-30","2026-10-01","2026-10-02"],"weather_code":[2,61,0],
                   "temperature_2m_max":[30.1,28.0,29.0],"temperature_2m_min":[20.2,19.0,19.5],
                   "precipitation_probability_max":[20,80,5]}}"""
            }
        }
        return http to calls
    }

    private fun weatherTool(http: HttpGet) = WeatherTool(WeatherClient(http) { clockMillis }) { clockMillis }

    private fun pipeline(
        tools: List<JaxTool>,
        model: ScriptedModel = ScriptedModel(),
        chat: suspend (String) -> String = { "chat answer" },
        store: FakeRunStore = FakeRunStore(),
        memory: WorkingMemory = WorkingMemory(),
        toolTimeoutMs: Long = ToolExecutor.DEFAULT_TIMEOUT_MS,
        log: (String) -> Unit = {},
        toolModel: ToolCallingModel? = null,
        chatDraft: (suspend (String) -> ChatDraft)? = null,
        metrics: RequestMetrics = RequestMetrics()
    ) = RequestPipeline(
        registry = ToolRegistry(tools),
        controller = AgentController(),
        runStore = store,
        workingMemory = memory,
        modelCall = { prompt, _ -> model.call(prompt) },
        chat = chat,
        buildContext = { _, _ -> "USER: Jagadeesh" },
        selectedModel = { "test-model" },
        now = { now },
        toolTimeoutMs = toolTimeoutMs,
        log = log,
        metrics = metrics,
        toolModel = toolModel,
        chatDraft = chatDraft
    )

    private fun planJson(vararg steps: String, goal: String = "goal") =
        """{"goal":"$goal","slots":{},"steps":[${steps.joinToString(",")}]}"""

    // ---------------------------------------------------------------- routing

    @Test
    fun routerSendsSimpleRequestsToDeterministicTools() {
        fun toolOf(input: String) = (FastIntentRouter.route(input, now) as? Route.Direct)?.tool

        assertEquals("get_datetime", toolOf("What time is it?"))
        assertEquals("get_datetime", toolOf("What is today's date?"))
        assertEquals("get_weather", toolOf("What's the weather?"))
        assertEquals("navigate", toolOf("Find directions to Bangalore Palace"))
        assertEquals("create_reminder", toolOf("Create a reminder for tomorrow at 8 PM"))
        assertEquals("web_search", toolOf("search for rock and roll"))
    }

    @Test
    fun routerKeepsKnowledgeOnGeminiAndMultiStepOnThePlanner() {
        assertTrue(FastIntentRouter.route("Explain SAP FI.", now) is Route.Chat)
        assertTrue(FastIntentRouter.route("What's the difference between SAP FI and CO?", now) is Route.Chat)
        assertTrue(FastIntentRouter.route("Plan a weekend trip to Mysore.", now) is Route.Plan)
        assertTrue(FastIntentRouter.route("Prepare everything for my Singapore trip.", now) is Route.Plan)
        assertTrue(FastIntentRouter.route("Find a restaurant near Bangalore Palace and create a task to visit it tomorrow.", now) is Route.Plan)
        assertTrue(FastIntentRouter.route("Check tomorrow's weather and tell me whether I should plan an outdoor activity.", now) is Route.Plan)
        assertTrue(FastIntentRouter.route("Delete my dentist task", now) is Route.Agent)
        assertTrue(FastIntentRouter.route("What can you do?", now) is Route.Capabilities)
    }

    @Test
    fun voiceAndTypedInputNormalizeTheSameWay() {
        assertEquals("what time is it?", InputNormalizer.normalize("Hey JAX, what time is it?"))
        assertEquals("What time is it?", InputNormalizer.normalize("  What   time is it?  "))
        assertEquals("Jaxon is here", InputNormalizer.normalize("Jaxon is here"))
    }

    @Test
    fun reminderParserResolvesDatesTimesAndTitles() {
        val r1 = ReminderParser.parse("Create a reminder for tomorrow at 8 PM", now)!!
        assertEquals(LocalDate.of(2026, 10, 1), r1.date)
        assertEquals(LocalTime.of(20, 0), r1.time)
        assertEquals("Reminder", r1.title)

        val r2 = ReminderParser.parse("Remind me to call mom tomorrow at 8:30 pm", now)!!
        assertEquals("Call mom", r2.title)
        assertEquals(LocalTime.of(20, 30), r2.time)

        val r3 = ReminderParser.parse("Remind me to stretch at 9 am", now)!!
        assertEquals(LocalDate.of(2026, 10, 1), r3.date) // 9 AM already passed today

        assertEquals(null, ReminderParser.parse("Remind me to call John tomorrow", now)) // no time: not guessed
    }

    // ---------------------------------------------------------------- simple requests (0 or 1 Gemini call)

    @Test
    fun timeAndDateUseTheDeviceClockWithoutGemini() = runBlocking<Unit> {
        val clock = ZonedDateTime.of(2026, 9, 30, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val p = pipeline(listOf(DateTimeTool { clock }))

        val time = p.handle("What time is it?")
        assertTrue(time.reply.startsWith("It's 10:00"))
        assertEquals(0, time.trace.geminiCalls)
        assertEquals(listOf("get_datetime:VERIFIED"), time.trace.tools)

        val date = p.handle("What is today's date?")
        assertTrue(date.reply.contains("2026"))
        assertEquals(0, date.trace.geminiCalls)
    }

    @Test
    fun weatherUsesLiveDataWithoutGemini() = runBlocking<Unit> {
        val (http, calls) = weatherHttp()
        val result = pipeline(listOf(weatherTool(http))).handle("What's the weather?")

        assertTrue(result.reply, result.reply.contains("Bengaluru") && result.reply.contains("20% chance of rain"))
        assertEquals(0, result.trace.geminiCalls)
        assertEquals(2, calls[0]) // geocode + forecast, no duplicates
    }

    @Test
    fun bangaloreGeocodingRejectsWrongCountryAndReturnsFullIndianLocation() = runBlocking<Unit> {
        var geocodeCalls = 0
        val http: HttpGet = { url ->
            if (url.contains("geocoding")) {
                geocodeCalls++
                if (geocodeCalls == 1) {
                    """{"results":[{"name":"Bangalore Town","admin1":"Punjab","country":"Pakistan","country_code":"PK","latitude":31.5,"longitude":74.3}]}"""
                } else {
                    """{"results":[{"name":"Bengaluru","admin1":"Karnataka","country":"India","country_code":"IN","population":8443675,"latitude":12.97,"longitude":77.59}]}"""
                }
            } else {
                """{"current":{"temperature_2m":27.5,"weather_code":2},"daily":{"time":["2026-09-30"],"weather_code":[2],"temperature_2m_max":[30.1],"temperature_2m_min":[20.2],"precipitation_probability_max":[20]}}"""
            }
        }

        val result = WeatherClient(http) { clockMillis }.fetch("Bangalore", 0)

        assertEquals("Bengaluru, Karnataka, India", result.location)
        assertEquals("Bengaluru", result.city)
        assertEquals("Karnataka", result.region)
        assertEquals("India", result.country)
        assertEquals(2, geocodeCalls)
    }

    @Test
    fun weatherNetworkFailureRetriesBoundedThenFallsBackToWebSearch() = runBlocking<Unit> {
        val (http, calls) = weatherHttp(alwaysFail = true)
        val search = FakeTool("open_web_search", ToolRisk.LAUNCH, listOf(ToolParam("query", "string", "q", true)))
        val result = pipeline(listOf(weatherTool(http), search)).handle("What's the weather?")

        assertEquals(3, calls[0]) // 1 attempt + maxRetries(2), then stop
        assertEquals(1, search.executions)
        assertTrue(result.reply.contains("opened a web search"))
        assertEquals(0, result.trace.geminiCalls)
        assertFalse(result.trace.success)
    }

    @Test
    fun directionsOpenMapsAndAreNeverATask() = runBlocking<Unit> {
        val navigate = FakeTool("navigate", ToolRisk.LAUNCH, listOf(ToolParam("destination", "string", "d", true)))
        val createTask = FakeTool("create_task")
        var confirmations = 0
        val result = pipeline(listOf(navigate, createTask)).handle("Find directions to Bangalore Palace") {
            confirmations++; true
        }

        assertEquals("Bangalore Palace", navigate.receivedArgs.single().getString("destination"))
        assertEquals(0, createTask.executions)
        assertEquals(0, confirmations) // Maps is LOW risk
        assertEquals(0, result.trace.geminiCalls)
    }

    @Test
    fun reminderIsCreatedScheduledAndVerifiedByReadBack() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val tool = CreateReminderTool(TaskRepository(dao), scheduler) { now }
        val result = pipeline(listOf(tool)).handle("Create a reminder for tomorrow at 8 PM")

        assertEquals("2026-10-01", dao.tasks.single().deadline)
        assertEquals("Reminder @ 20:00", dao.tasks.single().title)
        assertEquals(LocalDateTime.of(2026, 10, 1, 20, 0), scheduler.scheduled.single().second)
        assertTrue(result.reply.contains("Reminder set for 2026-10-01 at 20:00"))
        assertEquals(listOf("create_reminder:VERIFIED"), result.trace.tools)
        assertEquals(0, result.trace.geminiCalls)
    }

    @Test
    fun reminderThatCannotBeScheduledIsReportedAndNotRetried() = runBlocking<Unit> {
        val scheduler = FakeScheduler(accept = false)
        val tool = CreateReminderTool(TaskRepository(FakeTaskDao()), scheduler) { now }
        val result = pipeline(listOf(tool)).handle("Create a reminder for tomorrow at 8 PM")

        assertEquals(1, scheduler.scheduled.size)
        assertTrue(result.reply.contains("couldn't schedule"))
        assertFalse(result.trace.success)
    }

    @Test
    fun knowledgeQuestionsCostExactlyOneGeminiCall() = runBlocking<Unit> {
        val result = pipeline(emptyList(), chat = { "SAP FI is SAP's financial accounting module." }).handle("Explain SAP FI.")
        assertEquals("SAP FI is SAP's financial accounting module.", result.reply)
        assertEquals(1, result.trace.geminiCalls)
        assertEquals("GEMINI", result.trace.route)
    }

    @Test
    fun capabilitiesComeFromTheRegistryWithoutGemini() = runBlocking<Unit> {
        val result = pipeline(listOf(DateTimeTool(), FakeTool("create_task"))).handle("What can you do?")
        assertTrue(result.reply.contains("Look things up") && result.reply.contains("Take actions"))
        assertEquals(0, result.trace.geminiCalls)
    }

    // ---------------------------------------------------------------- multi-step (planner)

    @Test
    fun tripPlanRunsToolsVerifiesAndSynthesizesWithTwoGeminiCalls() = runBlocking<Unit> {
        val (http, _) = weatherHttp(place = "Mysuru")
        val model = ScriptedModel(
            """{"goal":"Plan weekend trip to Mysore","slots":{"destination":"Mysore"},
               "steps":[{"id":"1","tool":"get_weather","input":{"location":"Mysore","day":"tomorrow"}}]}""",
            "Day 1: Mysore Palace... Carry an umbrella."
        )
        val store = FakeRunStore()
        val memory = WorkingMemory()
        val result = pipeline(listOf(weatherTool(http)), model, store = store, memory = memory)
            .handle("Plan a weekend trip to Mysore.")

        assertEquals("Day 1: Mysore Palace... Carry an umbrella.", result.reply)
        assertEquals(2, result.trace.geminiCalls)
        assertTrue(model.prompts[1].contains("COMPLETED") && model.prompts[1].contains("80% chance of rain"))
        assertTrue(store.plans.last().contains("\"status\":\"COMPLETED\""))
        assertEquals(listOf("COMPLETED"), store.finished)
        assertEquals("Mysore", memory.state.slots["destination"])
        assertEquals("done", memory.state.status)
    }

    @Test
    fun restaurantPlanCreatesAndVerifiesTheTask() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val model = ScriptedModel(
            planJson("""{"id":"1","tool":"create_task","input":{"title":"Visit Mahesh Lunch Home","deadline":"2026-10-01"}}"""),
            "Added a task to visit Mahesh Lunch Home tomorrow."
        )
        val result = pipeline(listOf(CreateTaskTool(TaskRepository(dao))), model)
            .handle("Find a restaurant near Bangalore Palace and create a task to visit it tomorrow.")

        assertEquals("2026-10-01", dao.tasks.single().deadline)
        assertEquals(listOf("create_task:VERIFIED"), result.trace.tools)
        assertEquals(2, result.trace.geminiCalls)
    }

    @Test
    fun weatherAdvicePlanPassesLiveForecastToTheFinalAnswer() = runBlocking<Unit> {
        val (http, _) = weatherHttp()
        val model = ScriptedModel(
            planJson("""{"id":"1","tool":"get_weather","input":{"day":"tomorrow"}}"""),
            "Rain is likely tomorrow; plan something indoors."
        )
        val result = pipeline(listOf(weatherTool(http)), model)
            .handle("Check tomorrow's weather and tell me whether I should plan an outdoor activity.")

        assertTrue(model.prompts[1].contains("80% chance of rain"))
        assertEquals("Rain is likely tomorrow; plan something indoors.", result.reply)
    }

    // ---------------------------------------------------------------- failures

    @Test
    fun geminiUnavailableReturnsAFriendlyMessageInsteadOfCrashing() = runBlocking<Unit> {
        val result = pipeline(emptyList(), chat = { throw AIException(AIError.NetworkError) }).handle("Explain SAP FI.")
        assertEquals(AIError.NetworkError.userFriendlyMessage, result.reply)
        assertFalse(result.trace.success)
    }

    @Test
    fun rateLimitDuringPlanningStopsAfterOneCall() = runBlocking<Unit> {
        val store = FakeRunStore()
        val result = pipeline(listOf(DateTimeTool()), ScriptedModel(AIException(AIError.QuotaExceeded)), store = store)
            .handle("Plan a weekend trip to Mysore.")

        assertEquals(AIError.QuotaExceeded.userFriendlyMessage, result.reply)
        assertEquals(1, result.trace.geminiCalls)
        assertEquals(listOf(RequestPipeline.STATUS_COMPLETED_WITH_ERRORS), store.finished)
    }

    @Test
    fun malformedPlanFallsBackToTheAgentLoop() = runBlocking<Unit> {
        val model = ScriptedModel("sorry, no JSON here", """{"action":"final","reply":"Fallback answer"}""")
        val result = pipeline(listOf(DateTimeTool()), model).handle("Plan a weekend trip to Mysore.")

        assertEquals("Fallback answer", result.reply)
        assertEquals("PLANNER>AGENT", result.trace.route)
        assertEquals(2, result.trace.geminiCalls)
    }

    @Test
    fun failedActionSkipsDependentsAndGeminiOutageUsesVerifiedSummary() = runBlocking<Unit> {
        val failing = FakeTool("book_table") { ToolResult.error("restaurant is closed") }
        val createTask = FakeTool("create_task")
        val store = FakeRunStore()
        val model = ScriptedModel(
            planJson(
                """{"id":"1","tool":"book_table","input":{}}""",
                """{"id":"2","tool":"create_task","input":{},"dependsOn":["1"]}""",
                """{"id":"3","tool":"get_datetime","input":{}}"""
            ),
            AIException(AIError.NetworkError)
        )
        val result = pipeline(listOf(failing, createTask, DateTimeTool()), model, store = store)
            .handle("Plan my dinner and add it to my plan")

        assertEquals(1, failing.executions) // business errors on writes are not retried
        assertEquals(0, createTask.executions)
        assertTrue(result.reply.contains("couldn't reach Gemini"))
        assertTrue(result.reply.contains("restaurant is closed"))
        assertTrue(store.plans.last().contains("\"status\":\"SKIPPED\""))
        assertEquals(listOf(RequestPipeline.STATUS_COMPLETED_WITH_ERRORS), store.finished)
    }

    @Test
    fun invalidAndUnknownPlanStepsNeverExecute() = runBlocking<Unit> {
        val createTask = FakeTool("create_task", parameters = listOf(ToolParam("title", "string", "t", true)))
        val model = ScriptedModel(
            planJson(
                """{"id":"1","tool":"create_task","input":{}}""",
                """{"id":"2","tool":"launch_rocket","input":{}}"""
            ),
            "Done what I could."
        )
        val store = FakeRunStore()
        pipeline(listOf(createTask), model, store = store).handle("Plan a trip and create a task")

        assertEquals(0, createTask.executions)
        val plan = AgentPlan.fromJson(JSONObject(store.plans.last()))
        assertEquals(StepStatus.FAILED, plan.steps[0].status)
        assertTrue(plan.steps[0].error!!.contains("missing required input: title"))
        assertEquals(StepStatus.SKIPPED, plan.steps[1].status)
        assertTrue(plan.steps[1].error!!.contains("unknown tool"))
    }

    @Test
    fun slowReadToolTimesOutWithBoundedRetries() = runBlocking<Unit> {
        val slow = FakeTool("slow_lookup", ToolRisk.READ, delayMs = 5_000)
        val model = ScriptedModel(planJson("""{"id":"1","tool":"slow_lookup","input":{}}"""), "Timed out.")
        val store = FakeRunStore()
        pipeline(listOf(slow), model, store = store, toolTimeoutMs = 50).handle("Plan a trip and check the lookup")

        val step = AgentPlan.fromJson(JSONObject(store.plans.last())).steps.single()
        assertEquals(StepStatus.FAILED, step.status)
        assertTrue(step.error!!.contains("timed out"))
        assertEquals(2, step.retryCount)
    }

    @Test
    fun duplicatePlanStepsExecuteOnce() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val step = """{"tool":"create_task","input":{"title":"Visit palace","deadline":"2026-10-01"}}"""
        val model = ScriptedModel(
            planJson(step.replace("{\"tool\"", "{\"id\":\"1\",\"tool\""), step.replace("{\"tool\"", "{\"id\":\"2\",\"tool\"")),
            "Done."
        )
        val store = FakeRunStore()
        pipeline(listOf(CreateTaskTool(TaskRepository(dao))), model, store = store).handle("Plan a trip and create a task")

        assertEquals(1, dao.tasks.size)
        val plan = AgentPlan.fromJson(JSONObject(store.plans.last()))
        assertTrue(plan.steps[1].error!!.contains("duplicate of step 1"))
    }

    @Test
    fun appRestartReportsWhereAnInterruptedPlanStopped() = runBlocking<Unit> {
        val plan = AgentPlan(
            "Plan weekend trip to Mysore",
            listOf(
                PlanStep("1", "get_weather", JSONObject(), status = StepStatus.COMPLETED),
                PlanStep("2", "create_task", JSONObject(), status = StepStatus.RUNNING),
                PlanStep("3", "get_datetime", JSONObject())
            )
        )
        val dao = FakeAgentRunDao(mutableListOf(
            AgentRunEntity("run-1", "Plan weekend trip to Mysore", "RUNNING", "", "get_weather", 1L, 0L,
                plan = plan.toJson().toString(), currentStep = 2)
        ))

        val interrupted = AgentRunRepository(dao).reconcileInterruptedRuns()

        assertEquals(1, interrupted.size)
        assertTrue(interrupted.single().contains("step 2/3 (create_task); 1 completed"))
        assertEquals("INTERRUPTED", dao.runs.single().status)
    }

    // ---------------------------------------------------------------- safety

    private suspend fun execute(
        tool: JaxTool,
        args: JSONObject = JSONObject(),
        userInput: String = "do it",
        controller: AgentController = AgentController(),
        confirm: suspend (ToolConfirmation) -> Boolean
    ): ToolOutcome = ToolExecutor(ToolRegistry(listOf(tool)), controller)
        .execute(tool.name, args, userInput, InMemoryEventSink(), confirm)

    @Test
    fun highRiskActionsNeverRunWithoutApproval() = runBlocking<Unit> {
        val highRisk = listOf(
            FakeTool("send_email", ToolRisk.SENSITIVE),
            FakeTool("send_message", ToolRisk.SENSITIVE),
            FakeTool("make_purchase", ToolRisk.SENSITIVE),
            FakeTool("cancel_booking", ToolRisk.DESTRUCTIVE),
            FakeTool("delete_data", ToolRisk.DESTRUCTIVE)
        )
        highRisk.forEach { tool ->
            var asked = 0
            val outcome = execute(tool) { asked++; false }
            assertEquals(tool.name, ExecutionStatus.DECLINED, outcome.status)
            assertEquals(tool.name, 1, asked)
            assertEquals(tool.name, 0, tool.executions)
        }
    }

    @Test
    fun modelCannotBypassApprovalWithArguments() = runBlocking<Unit> {
        val purchase = FakeTool("make_purchase", ToolRisk.SENSITIVE)
        val sneaky = JSONObject().put("confirmed", true).put("approved", true).put("user_said_yes", true)
        val outcome = execute(purchase, sneaky) { false }

        assertEquals(ExecutionStatus.DECLINED, outcome.status)
        assertEquals(0, purchase.executions)
    }

    @Test
    fun approvedHighRiskActionRunsExactlyOnce() = runBlocking<Unit> {
        val email = FakeTool("send_email", ToolRisk.SENSITIVE)
        val outcome = execute(email) { true }
        assertEquals(ExecutionStatus.SUCCEEDED, outcome.status)
        assertEquals(1, email.executions)
    }

    @Test
    fun highRiskAlwaysConfirmsEvenIfThresholdIsMisconfigured() {
        val permissive = AgentController(confirmAboveRisk = ToolRisk.DESTRUCTIVE)
        assertEquals(AgentController.Decision.CONFIRM, permissive.authorize(FakeTool("send_email", ToolRisk.SENSITIVE), JSONObject()))
        assertEquals(AgentController.Decision.CONFIRM, permissive.authorize(FakeTool("delete_data", ToolRisk.DESTRUCTIVE), JSONObject()))
    }

    @Test
    fun calendarEventIsMediumRiskAndRunsWithoutPrompt() = runBlocking<Unit> {
        val calendar = FakeTool("create_calendar_event", ToolRisk.LOW_WRITE)
        var asked = 0
        val outcome = execute(calendar) { asked++; true }
        assertEquals(RiskTier.MEDIUM, calendar.risk.tier)
        assertEquals(ExecutionStatus.SUCCEEDED, outcome.status)
        assertEquals(0, asked)
    }

    @Test
    fun deletingATaskNeedsApprovalButCompletingItDoesNot() = runBlocking<Unit> {
        val dao = FakeTaskDao().apply { tasks += TaskEntity("t1", "Dentist", "Personal", "MED", null) }
        val tool = com.jax.assistant.ai.agent.tools.CompleteTaskTool(TaskRepository(dao))

        var asked = 0
        val complete = execute(tool, JSONObject().put("id", "t1")) { asked++; true }
        assertEquals(0, asked)
        assertEquals(VerificationStatus.VERIFIED, complete.verification?.status)

        val delete = execute(tool, JSONObject().put("id", "t1").put("remove", true)) { asked++; false }
        assertEquals(1, asked)
        assertEquals(ExecutionStatus.DECLINED, delete.status)
        assertEquals(1, dao.tasks.size) // still there
    }

    @Test
    fun contradictedWriteIsReportedAsFailureAndNotRetried() = runBlocking<Unit> {
        val tool = object : JaxTool {
            override val name = "save_note"
            override val description = "Save"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            var executions = 0
            override suspend fun execute(args: JSONObject): ToolResult { executions++; return ToolResult.ok("saved") }
            override suspend fun verify(args: JSONObject, result: ToolResult) = Verification.failed("not found on read-back")
        }
        val outcome = execute(tool) { true }
        assertEquals(ExecutionStatus.FAILED, outcome.status)
        assertEquals(1, tool.executions)
        assertTrue(outcome.result.message.contains("not found on read-back"))
    }

    // ---------------------------------------------------------------- memory

    @Test
    fun onlyExplicitUserFactsBecomeDurableMemory() = runBlocking<Unit> {
        assertEquals(MemoryTrust.EXPLICIT_USER_FACT, MemoryPolicy.classify("Remember that I prefer budget hotels"))
        assertEquals(MemoryTrust.EXPLICIT_USER_FACT, MemoryPolicy.classify("My passport number is AB123"))
        assertEquals(MemoryTrust.EXPLICIT_USER_FACT, MemoryPolicy.classify("I prefer window seats"))
        assertEquals(MemoryTrust.INFERRED, MemoryPolicy.classify("I'm flying to Delhi tomorrow"))
        assertEquals(MemoryTrust.INFERRED, MemoryPolicy.classify("Plan a trip to Mysore"))

        val store = FakeTool("store_memory", writesDurableMemory = true)
        assertEquals(ExecutionStatus.DENIED, execute(store, userInput = "Plan a trip to Mysore") { true }.status)
        assertEquals(0, store.executions)
        assertEquals(ExecutionStatus.SUCCEEDED, execute(store, userInput = "Remember I like budget hotels") { true }.status)
        assertEquals(1, store.executions)
    }

    @Test
    fun workingMemoryIsBoundedAndFeedsTheContext() {
        val memory = WorkingMemory(maxNotes = 2)
        memory.beginGoal("Plan trip", mapOf("destination" to "Mysore"))
        listOf("a", "b", "c").forEach(memory::addNote)
        assertEquals(listOf("b", "c"), memory.state.notes)

        val context = ContextAssembler().build(emptyList(), emptyList(), "", "2026-09-30", workingState = memory.contextBlock()).text
        assertTrue(context.contains("CURRENT TASK STATE") && context.contains("destination: Mysore"))

        memory.clear()
        assertEquals("", memory.contextBlock())
    }

    @Test
    fun workflowLearnerNeedsEvidenceFromSeparateRuns() {
        fun run(id: String, tools: String) = AgentRunEntity(id, "g", "COMPLETED", "", tools, 1L, 2L)
        val oneRunRepeating = WorkflowLearner().suggestions(listOf(run("1", "get_weather,create_task,get_weather,create_task")))
        assertTrue(oneRunRepeating.isEmpty())

        val twoRuns = WorkflowLearner().suggestions(listOf(run("1", "get_weather,create_task"), run("2", "get_weather,create_task")))
        assertEquals(listOf("get_weather -> create_task (observed 2 times)"), twoRuns)
    }

    // ---------------------------------------------------------------- capabilities

    @Test
    fun registrySeparatesReadFromActionTools() {
        val registry = ToolRegistry(listOf(DateTimeTool(), FakeTool("create_task"), FakeTool("navigate", ToolRisk.LAUNCH)))
        assertEquals(listOf("get_datetime"), registry.readTools().map { it.name })
        assertEquals(setOf("create_task", "navigate"), registry.actionTools().map { it.name }.toSet())
        val schema = registry.toolsJsonSchema()
        val kinds = (0 until schema.length()).associate { schema.getJSONObject(it).getString("name") to schema.getJSONObject(it).getString("kind") }
        assertEquals("READ", kinds["get_datetime"])
        assertEquals("ACTION", kinds["create_task"])
    }

    // ---------------------------------------------------------------- proactivity

    @Test
    fun rainInsightTargetsTasksDueThatDay() {
        val weather = WeatherSnapshot("Bengaluru, India", LocalDate.of(2026, 10, 1), "rain", null, 28.0, 19.0, 80, 0L)
        val tasks = listOf(TaskEntity("t1", "Site visit", "Work", "MED", "2026-10-01"))
        val suggestions = ProactiveInsightEngine().suggest(tasks, LocalDate.of(2026, 9, 30), AutonomyLevel.RECOMMEND, weather)

        val rain = suggestions.single { it.dedupKey == "rain:2026-10-01" }
        assertEquals(0.8, rain.confidence, 0.001)
        assertTrue(rain.suggestedAction!!.startsWith("Would you like me to"))
        assertFalse(rain.autoActable)
    }

    @Test
    fun insightGateSuppressesRepeatsLowConfidenceAndNoise() {
        fun insight(key: String, confidence: Double = 1.0, importance: Int = 2) = ProactiveSuggestion(
            observation = key, suggestedAction = null, requiresConfirmation = false, autoActable = false,
            confidence = confidence, importance = importance, dedupKey = key, cooldownHours = 24
        )
        val nowMs = 100L * 60 * 60 * 1000
        val candidates = listOf(
            insight("shown-recently"),
            insight("unsure", confidence = 0.3),
            insight("dup"), insight("dup"),
            insight("a", importance = 3), insight("b"), insight("c")
        )
        val selected = InsightGate.select(candidates, mapOf("shown-recently" to nowMs - 60 * 60 * 1000), nowMs)

        assertEquals(listOf("a", "dup", "b"), selected.map { it.dedupKey })
    }

    @Test
    fun weatherToolRejectsStaleData() = runBlocking<Unit> {
        val tool = WeatherTool(WeatherClient(weatherHttp().first) { 0L }) { 60 * 60 * 1000L }
        val result = tool.execute(JSONObject())
        assertNotNull(result.data)
        assertEquals(VerificationStatus.FAILED, tool.verify(JSONObject(), result).status)
    }

    // ---------------------------------------------------------------- focused corrections: routing (FIX 3/4)

    @Test
    fun deterministicIntentsGoDirectAndAmbiguousOnesGoToGeminiOrThePlanner() {
        fun direct(input: String) = FastIntentRouter.route(input, now) as? Route.Direct

        val maps = direct("Open Bangalore Palace on Maps")!!
        assertEquals("navigate", maps.tool)
        assertEquals("Bangalore Palace", maps.args.getString("destination"))
        assertEquals("NAVIGATION", maps.intent)

        assertTrue(FastIntentRouter.route("What is accounts payable?", now) is Route.Chat)
        assertTrue(FastIntentRouter.route("What do you know about Bangalore Palace?", now) is Route.Chat)
        assertTrue(FastIntentRouter.route("Should I visit Bangalore Palace tomorrow?", now) is Route.Chat)

        val visit = direct("Remind me to visit Bangalore Palace tomorrow")!!
        assertEquals("create_task", visit.tool)
        assertEquals("Visit Bangalore Palace", visit.args.getString("title"))
        assertEquals("2026-10-01", visit.args.getString("deadline"))
        assertEquals("TASK", visit.intent)

        val call = direct("Remind me to call John tomorrow at 8 PM.")!!
        assertEquals("create_reminder", call.tool)
        assertEquals("Call John", call.args.getString("title"))
        assertEquals("2026-10-01", call.args.getString("date"))
        assertEquals("20:00", call.args.getString("time"))

        val task = direct("Create a task to visit Bangalore Palace tomorrow.")!!
        assertEquals("create_task", task.tool)
        assertEquals("Visit Bangalore Palace", task.args.getString("title"))
        assertEquals("2026-10-01", task.args.getString("deadline"))

        assertTrue(FastIntentRouter.route("Look at my calendar and decide when I should call John, then remind me.", now) is Route.Plan)
        // Missing details or judgement needed: never handled deterministically.
        assertTrue(FastIntentRouter.route("Remind me to call John", now) is Route.Clarify)
        assertTrue(FastIntentRouter.route("Create a task to decide which hotel is best", now) is Route.Agent)
    }

    @Test
    fun simpleTaskIsCreatedDirectlyWithZeroGeminiCallsAndLogged() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val logs = mutableListOf<String>()
        val result = pipeline(listOf(CreateTaskTool(TaskRepository(dao))), log = { logs += it })
            .handle("Create a task to visit Bangalore Palace tomorrow.")

        assertEquals("Visit Bangalore Palace", dao.tasks.single().title)
        assertEquals("2026-10-01", dao.tasks.single().deadline)
        assertEquals(0, result.trace.geminiCalls)
        assertEquals(listOf("create_task:VERIFIED"), result.trace.tools)
        assertFalse(result.reply.contains("couldn't independently verify"))
        val line = logs.single()
        listOf(
            "requestId=${result.trace.requestId}", "intent=TASK", "route=DIRECT_TOOL", "geminiCallCount=0",
            "toolsUsed=[create_task:VERIFIED]", "totalLatencyMs="
        ).forEach { assertTrue(line, line.contains(it)) }
    }

    @Test
    fun remindMeWithDayAndTimeSchedulesDirectlyWithoutGemini() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val result = pipeline(listOf(CreateReminderTool(TaskRepository(dao), scheduler) { now }))
            .handle("Remind me to call John tomorrow at 8 PM.")

        assertEquals(LocalDateTime.of(2026, 10, 1, 20, 0), scheduler.scheduled.single().second)
        assertTrue(dao.tasks.single().title.startsWith("Call John"))
        assertEquals(0, result.trace.geminiCalls)
        assertEquals("REMINDER", result.trace.intent)
    }

    @Test
    fun requestNeedingJudgementUsesThePlannerWithBoundedGeminiCalls() = runBlocking<Unit> {
        val reminder = FakeTool("create_reminder", parameters = listOf(ToolParam("title", "string", "t", true)))
        val model = ScriptedModel(
            planJson("""{"id":"1","tool":"create_reminder","input":{"title":"Call John","date":"2026-10-01","time":"18:00"}}"""),
            "Your evening looks free, so I asked for a reminder to call John at 6 PM."
        )
        val result = pipeline(listOf(reminder), model)
            .handle("Look at my calendar and decide when I should call John, then remind me.")

        assertEquals("PLANNER", result.trace.route)
        assertEquals(2, result.trace.geminiCalls)
        assertEquals(1, reminder.executions)
        assertTrue(model.prompts[1].contains("COMPLETED/UNVERIFIED"))
    }

    // ---------------------------------------------------------------- focused corrections: verification states (FIX 2)

    @Test
    fun unverifiedDirectActionIsNotReportedAsConfirmed() = runBlocking<Unit> {
        val navigate = FakeTool("navigate", ToolRisk.LAUNCH, listOf(ToolParam("destination", "string", "d", true)))
        val result = pipeline(listOf(navigate)).handle("Open Bangalore Palace on Maps")

        assertEquals(listOf("navigate:UNVERIFIED"), result.trace.tools)
        assertTrue(result.reply.contains("couldn't independently verify"))
        assertEquals(0, result.trace.geminiCalls)
    }

    @Test
    fun failedDirectActionIsReportedAsFailed() = runBlocking<Unit> {
        val createTask = FakeTool("create_task") { ToolResult.error("storage unavailable") }
        val result = pipeline(listOf(createTask)).handle("Create a task to visit Bangalore Palace tomorrow.")

        assertEquals(listOf("create_task:FAILED"), result.trace.tools)
        assertTrue(result.reply.contains("couldn't complete that: storage unavailable"))
        assertFalse(result.trace.success)
        assertEquals(1, createTask.executions)
    }

    @Test
    fun planStepsRecordVerifiedUnverifiedAndFailedSeparately() {
        val dao = FakeTaskDao()
        val plan = AgentPlan("g", listOf(
            PlanStep("1", "create_task", JSONObject().put("title", "Visit palace").put("deadline", "2026-10-01")),
            PlanStep("2", "navigate", JSONObject().put("destination", "Palace")),
            PlanStep("3", "book_table", JSONObject())
        ))
        runPlan(
            plan,
            CreateTaskTool(TaskRepository(dao)),
            FakeTool("navigate", ToolRisk.LAUNCH),
            FakeTool("book_table") { ToolResult.error("closed") }
        )

        assertEquals(VerificationStatus.VERIFIED, plan.step("1")!!.verification)
        assertEquals(VerificationStatus.UNVERIFIED, plan.step("2")!!.verification)
        assertEquals(VerificationStatus.FAILED, plan.step("3")!!.verification)
        val summary = plan.summary()
        assertTrue(summary, summary.contains("COMPLETED/VERIFIED") && summary.contains("COMPLETED/UNVERIFIED") && summary.contains("-> FAILED"))
        assertEquals(VerificationStatus.UNVERIFIED, AgentPlan.fromJson(plan.toJson()).step("2")!!.verification)
    }

    @Test
    fun summaryWithoutGeminiDoesNotClaimUnverifiedStepsSucceeded() = runBlocking<Unit> {
        val model = ScriptedModel(
            planJson(
                """{"id":"1","tool":"navigate","input":{"destination":"Mysore Palace"}}""",
                """{"id":"2","tool":"create_task","input":{"title":"Visit Mysore Palace","deadline":"2026-10-01"}}"""
            ),
            AIException(AIError.NetworkError)
        )
        val result = pipeline(listOf(FakeTool("navigate", ToolRisk.LAUNCH), CreateTaskTool(TaskRepository(FakeTaskDao()))), model)
            .handle("Plan a weekend trip to Mysore.")

        assertTrue(result.reply, result.reply.contains("navigate: accepted, but I couldn't independently verify it"))
        assertTrue(result.reply, result.reply.contains("create_task: Created task"))
    }

    // ---------------------------------------------------------------- focused corrections: plan dependencies (FIX 1)

    private fun orchestrator(vararg tools: JaxTool) =
        AgentOrchestrator(ToolRegistry(tools.toList()), AgentController(), { _, _ -> error("no model call expected") })

    private fun recording(name: String, order: MutableList<String>, risk: ToolRisk = ToolRisk.READ) =
        FakeTool(name, risk) { order += name; ToolResult.ok("$name done") }

    private fun step(id: String, tool: String, vararg dependsOn: String, status: StepStatus = StepStatus.PENDING) =
        PlanStep(id, tool, JSONObject(), dependsOn.toList(), status)

    private fun runPlan(plan: AgentPlan, vararg tools: JaxTool): List<String> = runBlocking {
        orchestrator(*tools).executePlan(plan, "do it", InMemoryEventSink())
    }

    @Test
    fun stepsListedOutOfOrderRunInDependencyOrder() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(step("3", "t3", "2"), step("2", "t2", "1"), step("1", "t1")))
        runPlan(plan, recording("t1", order), recording("t2", order), recording("t3", order))

        assertEquals(listOf("t1", "t2", "t3"), order)
        assertTrue(plan.steps.all { it.status == StepStatus.COMPLETED })
    }

    @Test
    fun stepWithMultipleDependenciesWaitsForAllOfThem() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(step("c", "t3", "a", "b"), step("a", "t1"), step("b", "t2")))
        runPlan(plan, recording("t1", order), recording("t2", order), recording("t3", order))

        assertEquals(listOf("t1", "t2", "t3"), order)
    }

    @Test
    fun independentStepsRunInListedOrder() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(step("1", "t1"), step("2", "t2")))
        runPlan(plan, recording("t1", order), recording("t2", order))

        assertEquals(listOf("t1", "t2"), order)
    }

    @Test
    fun failedDependencySkipsDependentsButIndependentStepsStillRun() {
        val order = mutableListOf<String>()
        val failing = FakeTool("book_table") { ToolResult.error("restaurant is closed") }
        val plan = AgentPlan("g", listOf(step("1", "book_table"), step("2", "t2", "1"), step("3", "t3", "2"), step("4", "t4")))
        runPlan(plan, failing, recording("t2", order), recording("t3", order), recording("t4", order))

        assertEquals(StepStatus.FAILED, plan.step("1")!!.status)
        assertEquals(1, failing.executions)
        assertEquals(StepStatus.SKIPPED, plan.step("2")!!.status)
        assertTrue(plan.step("2")!!.error!!.contains("dependency step 1 failed: restaurant is closed"))
        assertTrue(plan.step("3")!!.error!!.contains("dependency step 2 skipped"))
        assertEquals(listOf("t4"), order)
    }

    @Test
    fun missingDependencyIsSkippedWithoutRunning() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(step("1", "t1", "9"), step("2", "t2")))
        runPlan(plan, recording("t1", order), recording("t2", order))

        assertEquals(StepStatus.SKIPPED, plan.step("1")!!.status)
        assertTrue(plan.step("1")!!.error!!.contains("missing dependency \"9\""))
        assertEquals(listOf("t2"), order)
    }

    @Test
    fun circularDependenciesAreDetectedAndExecutionTerminates() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(
            step("1", "t1", "2"), step("2", "t2", "1"), step("3", "t3", "1"), step("4", "t4", "4"), step("5", "t5")
        ))
        runPlan(plan, recording("t1", order), recording("t2", order), recording("t3", order), recording("t4", order), recording("t5", order))

        assertTrue(plan.step("1")!!.error!!.contains("circular dependency (1 -> 2 -> 1)"))
        assertTrue(plan.step("2")!!.error!!.contains("circular dependency"))
        assertTrue(plan.step("4")!!.error!!.contains("circular dependency (4 -> 4)"))
        assertTrue(plan.step("3")!!.error!!.contains("dependency step 1 skipped"))
        assertEquals(listOf("t5"), order)
        assertTrue(plan.isFinished)
    }

    @Test
    fun alreadyCompletedStepIsNotRunAgain() {
        val order = mutableListOf<String>()
        val plan = AgentPlan("g", listOf(step("1", "t1", status = StepStatus.COMPLETED), step("2", "t2", "1")))
        runPlan(plan, recording("t1", order), recording("t2", order))

        assertEquals(listOf("t2"), order)
    }

    @Test
    fun plannerKeepsForwardDependenciesAndRejectsInvalidIds() {
        val registry = ToolRegistry(listOf(FakeTool("t1"), FakeTool("t2"), FakeTool("t3")))
        val plan = Planner(registry).parse(planJson(
            """{"id":"2","tool":"t2","input":{},"dependsOn":["1"]}""",
            """{"id":"1","tool":"t1","input":{}}""",
            """{"id":"1","tool":"t3","input":{}}""",
            """{"tool":"t3","input":{"x":1}}"""
        ))!!

        assertEquals(listOf("1"), plan.steps[0].dependsOn)
        assertEquals(StepStatus.PENDING, plan.steps[0].status)
        assertEquals(StepStatus.SKIPPED, plan.steps[2].status)
        assertTrue(plan.steps[2].error!!.contains("invalid step id"))
        assertEquals("auto-4", plan.steps[3].id)
        assertEquals(StepStatus.PENDING, plan.steps[3].status)
    }

    // ---------------------------------------------------------------- focused corrections: retry safety (FIX 6)

    @Test
    fun writeThatThrowsRunsOnceAndAnIdenticalLaterStepIsNotRepeated() {
        val write = FakeTool("create_task") { throw java.io.IOException("connection reset") }
        val plan = AgentPlan("g", listOf(
            PlanStep("1", "create_task", JSONObject().put("title", "Visit palace")),
            PlanStep("2", "create_task", JSONObject().put("title", "Visit palace"))
        ))
        runPlan(plan, write)

        assertEquals(1, write.executions)
        assertEquals(StepStatus.FAILED, plan.step("1")!!.status)
        assertEquals(0, plan.step("1")!!.retryCount)
        assertTrue(plan.step("1")!!.error!!.contains("not retried"))
        assertEquals(StepStatus.SKIPPED, plan.step("2")!!.status)
        assertTrue(plan.step("2")!!.error!!.contains("duplicate of step 1"))
    }

    @Test
    fun readThatThrowsIsRetriedWithinTheBound() {
        var calls = 0
        val flaky = FakeTool("lookup", ToolRisk.READ) {
            calls++
            if (calls == 1) throw java.io.IOException("blip")
            ToolResult.ok("found")
        }
        val alwaysDown = FakeTool("status", ToolRisk.READ) { throw java.io.IOException("down") }
        val plan = AgentPlan("g", listOf(step("1", "lookup"), step("2", "status")))
        runPlan(plan, flaky, alwaysDown)

        assertEquals(StepStatus.COMPLETED, plan.step("1")!!.status)
        assertEquals(2, flaky.executions)
        assertEquals(1, plan.step("1")!!.retryCount)
        assertEquals(StepStatus.FAILED, plan.step("2")!!.status)
        assertEquals(1 + ToolExecutor.DEFAULT_MAX_RETRIES, alwaysDown.executions)
    }

    // ---------------------------------------------------------------- Phase 10 helpers

    private fun results(vararg items: Pair<String, String>) = JSONObject().put(
        "results",
        JSONArray().apply { items.forEach { (title, url) -> put(JSONObject().put("title", title).put("url", url).put("snippet", "$title snippet")) } }
    )

    private fun searchTool(vararg items: Pair<String, String>) =
        FakeTool("web_search", ToolRisk.READ, listOf(ToolParam("query", "string", "q", true))) { ToolResult.ok("results", results(*items)) }

    private fun weatherFake() =
        FakeTool("get_weather", ToolRisk.READ, listOf(ToolParam("location", "string", "l"), ToolParam("day", "string", "d"))) {
            ToolResult.ok("Weather looks fine.")
        }

    // ---------------------------------------------------------------- working memory and references

    @Test
    fun thereResolvesToThePlaceFromAnEarlierTurn() = runBlocking<Unit> {
        val weather = weatherFake()
        val model = ScriptedModel(planJson(goal = "Trip to Mysore"), "Here's a two-day Mysore plan.")
        val p = pipeline(listOf(weather), model)

        p.handle("Plan a trip to Mysore.")
        val follow = p.handle("Check the weather there.")

        assertEquals("Mysore", weather.receivedArgs.single().getString("location"))
        assertEquals("DIRECT_TOOL", follow.trace.route)
        assertEquals(0, follow.trace.geminiCalls)
    }

    @Test
    fun voiceStyleFollowUpKeepsLocationAndChangesTheDay() = runBlocking<Unit> {
        val weather = weatherFake()
        val p = pipeline(listOf(weather))

        p.handle("Hey JAX, what's the weather in Singapore?")
        val follow = p.handle("How about tomorrow?")

        assertEquals("Singapore", weather.receivedArgs[1].getString("location"))
        assertEquals("tomorrow", weather.receivedArgs[1].getString("day"))
        assertEquals(0, follow.trace.geminiCalls)
    }

    @Test
    fun makeItSixPmReschedulesThePreviousReminderInPlace() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val tasks = TaskRepository(dao)
        val p = pipeline(listOf(CreateTaskTool(tasks), RescheduleReminderTool(tasks, scheduler) { now }))

        p.handle("Remind me to visit the temple tomorrow.")
        val edit = p.handle("Make it 6 PM.")

        assertEquals("Visit the temple @ 18:00", dao.tasks.single().title)
        assertEquals("2026-10-01", dao.tasks.single().deadline)
        assertEquals(LocalDateTime.of(2026, 10, 1, 18, 0), scheduler.scheduled.single().second)
        assertEquals(listOf("reschedule_reminder:VERIFIED"), edit.trace.tools)
        assertEquals(0, edit.trace.geminiCalls)
    }

    @Test
    fun thatAndItRefersToTheLastTaskAndDeleteStillNeedsApproval() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val tasks = TaskRepository(dao)
        val p = pipeline(listOf(CreateTaskTool(tasks), com.jax.assistant.ai.agent.tools.CompleteTaskTool(tasks)))

        p.handle("Create a task to renew my passport.")
        var asked = 0
        val delete = p.handle("Delete it.") { asked++; false }
        assertEquals(1, asked)
        assertEquals(1, dao.tasks.size)
        assertTrue(delete.reply.contains("won't"))

        val done = p.handle("Mark that as done.")
        assertTrue(dao.tasks.single().isCompleted)
        assertEquals(0, done.trace.geminiCalls)
    }

    @Test
    fun previousResultsCanBeNarrowedAndSelected() = runBlocking<Unit> {
        val search = searchTool("Hotel A" to "https://a.example", "Hotel B" to "https://b.example")
        val model = ScriptedModel("""{"action":"final","reply":"I can't book directly; here is Hotel B's site."}""")
        val p = pipeline(listOf(search), model)

        assertEquals(0, p.handle("Find hotels in Singapore.").trace.geminiCalls)
        val narrowed = p.handle("Which ones are near Bugis?")
        assertEquals("hotels in Singapore near Bugis", search.receivedArgs[1].getString("query"))
        assertEquals(0, narrowed.trace.geminiCalls)

        val pick = p.handle("Book the second one.")
        assertEquals("AGENT", pick.trace.route)
        assertTrue(model.prompts.single().contains("\"Hotel B\" (https://b.example)"))
    }

    @Test
    fun missingReferentAsksInsteadOfGuessing() = runBlocking<Unit> {
        val weather = weatherFake()
        val p = pipeline(listOf(weather))

        val there = p.handle("Check the weather there.")
        assertEquals("Which place do you mean?", there.reply)
        assertEquals("CLARIFY", there.trace.route)

        assertEquals("Which reminder or task should I change?", p.handle("Make it 6 PM.").reply)
        assertTrue(p.handle("Book the second one.").reply.contains("don't have any recent results"))
        assertEquals(0, weather.executions)
    }

    // ---------------------------------------------------------------- ask -> act -> verify

    @Test
    fun reminderWithoutTimeAsksThenCompletesFromTheAnswer() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val p = pipeline(listOf(CreateReminderTool(TaskRepository(dao), scheduler) { now }))

        val ask = p.handle("Remind me tomorrow.")
        assertEquals("What time tomorrow?", ask.reply)
        assertTrue(dao.tasks.isEmpty())

        val done = p.handle("6 PM")
        assertEquals(LocalDateTime.of(2026, 10, 1, 18, 0), scheduler.scheduled.single().second)
        assertEquals(listOf("create_reminder:VERIFIED"), done.trace.tools)
        assertEquals(0, ask.trace.geminiCalls + done.trace.geminiCalls)
    }

    @Test
    fun reminderWithoutDayOrTimeAsksWhenAndKeepsTheTitle() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val scheduler = FakeScheduler()
        val p = pipeline(listOf(CreateReminderTool(TaskRepository(dao), scheduler) { now }))

        assertEquals("When should I remind you to call John?", p.handle("Remind me to call John").reply)
        p.handle("tomorrow at 8 pm")

        assertEquals("Call John @ 20:00", dao.tasks.single().title)
        assertEquals(LocalDateTime.of(2026, 10, 1, 20, 0), scheduler.scheduled.single().second)
    }

    @Test
    fun unrelatedRequestAbandonsAnOpenQuestion() = runBlocking<Unit> {
        val p = pipeline(listOf(DateTimeTool()))
        p.handle("Remind me tomorrow.")
        val next = p.handle("What can you do?")
        assertEquals("CAPABILITIES", next.trace.intent)
    }

    // ---------------------------------------------------------------- web research

    @Test
    fun researchSearchesReadsPagesAndCitesRealSourcesWithOneGeminiCall() = runBlocking<Unit> {
        val search = searchTool(
            "Kotlin 2.0 released" to "https://kotlinlang.org/a",
            "Other page" to "https://kotlinlang.org/b",
            "K2 on the blog" to "https://blog.jetbrains.com/k2"
        )
        val fetch = FakeTool("web_fetch", ToolRisk.READ, listOf(ToolParam("url", "string", "u", true))) {
            ToolResult.ok("read", JSONObject().put("title", "Page").put("passages", JSONArray().put("Kotlin 2.0 was released in May 2024.")))
        }
        val model = ScriptedModel("From the sources: Kotlin 2.0 was released in May 2024 [1].\nJAX's inference: None.")
        val result = pipeline(listOf(search, fetch), model).handle("What is the latest Kotlin release?")

        assertEquals("RESEARCH", result.trace.route)
        assertEquals("latest Kotlin release", search.receivedArgs.single().getString("query"))
        assertEquals(2, fetch.executions) // one page per site
        assertEquals(1, result.trace.geminiCalls)
        assertTrue(result.reply, result.reply.contains("Sources:\n[1] Page — https://kotlinlang.org/a\n[2] Page — https://blog.jetbrains.com/k2"))
        assertTrue(model.prompts.single().contains("untrusted web content"))
    }

    @Test
    fun researchNetworkFailureAndEmptyResultsNeverCallGemini() = runBlocking<Unit> {
        val down = FakeTool("web_search", ToolRisk.READ, listOf(ToolParam("query", "string", "q", true))) {
            throw java.io.IOException("offline")
        }
        val failed = pipeline(listOf(down)).handle("Research electric scooters")
        assertTrue(failed.reply.contains("couldn't reach web search"))
        assertEquals(0, failed.trace.geminiCalls)
        assertFalse(failed.trace.success)
        assertEquals("WEB_UNAVAILABLE", failed.trace.errorType)

        val empty = pipeline(listOf(searchTool())).handle("Research electric scooters")
        assertTrue(empty.reply.contains("couldn't find any web results"))
        assertEquals(0, empty.trace.geminiCalls)
    }

    @Test
    fun researchWithoutGeminiReturnsRetrievedTextLabelledAsSuch() = runBlocking<Unit> {
        val search = searchTool("Scooter review" to "https://reviews.example/s")
        val fetch = FakeTool("web_fetch", ToolRisk.READ, listOf(ToolParam("url", "string", "u", true))) {
            ToolResult.ok("read", JSONObject().put("title", "Review").put("passages", JSONArray().put("Range is 60 km.")))
        }
        val result = pipeline(listOf(search, fetch), ScriptedModel(AIException(AIError.NetworkError))).handle("Research electric scooters")

        assertTrue(result.reply.contains("retrieved, not interpreted"))
        assertTrue(result.reply.contains("Range is 60 km. [1]"))
        assertTrue(result.reply.contains("[1] Review — https://reviews.example/s"))
        assertTrue(result.trace.fallback)
    }

    @Test
    fun plainWebSearchListsResultsWithoutGemini() = runBlocking<Unit> {
        val provider = object : WebSearchProvider {
            override val name = "Fake"
            override suspend fun search(query: String, limit: Int) =
                listOf(SearchResult("Kotlin 2.0 notes", "https://kotlinlang.org/docs/whatsnew20.html", "K2 compiler"))
        }
        val result = pipeline(listOf(WebSearchTool(WebSearchClient(listOf(provider))))).handle("Search the web for Kotlin 2.0")

        assertTrue(result.reply.contains("https://kotlinlang.org/docs/whatsnew20.html"))
        assertEquals(listOf("web_search:VERIFIED"), result.trace.tools)
        assertEquals(0, result.trace.geminiCalls)
        assertEquals(1, result.trace.depth)
    }

    // ---------------------------------------------------------------- persistent runs

    @Test
    fun resumeContinuesAnInterruptedPlanWithoutRepeatingWrites() = runBlocking<Unit> {
        val createTask = FakeTool("create_task") // cannot confirm whether an interrupted call happened
        val clock = FakeTool("get_datetime", ToolRisk.READ)
        val plan = AgentPlan(
            "Plan weekend trip",
            listOf(
                PlanStep("1", "create_task", JSONObject().put("title", "Book hotel"), status = StepStatus.COMPLETED, result = "done"),
                PlanStep("2", "create_task", JSONObject().put("title", "Pack bags"), status = StepStatus.RUNNING),
                PlanStep("3", "get_datetime", JSONObject(), dependsOn = listOf("1"))
            )
        )
        val store = FakeRunStore().apply { resumable = ResumableRun("run-1", "Plan weekend trip", plan.toJson().toString()) }
        val model = ScriptedModel("The hotel was booked before; please check packing.")
        val result = pipeline(listOf(createTask, clock), model, store = store).handle("Resume")

        assertEquals("RESUME", result.trace.route)
        assertEquals(0, createTask.executions)
        assertEquals(1, clock.executions)
        assertTrue(result.reply.contains("interrupted mid-action"))
        assertEquals(1, result.trace.geminiCalls)
        assertEquals(listOf("COMPLETED"), store.finished)
        assertEquals(StepStatus.SKIPPED, AgentPlan.fromJson(JSONObject(store.plans.last())).step("2")!!.status)

        assertEquals("There's no interrupted request to resume.", pipeline(emptyList(), store = store).handle("resume").reply)
    }

    @Test
    fun runLifecycleRecordsPlanningRunningAndWaitingForApproval() = runBlocking<Unit> {
        val email = FakeTool("send_email", ToolRisk.SENSITIVE)
        val model = ScriptedModel(planJson("""{"id":"1","tool":"send_email","input":{}}"""), "Sent.")
        val store = FakeRunStore()
        pipeline(listOf(email), model, store = store).handle("Plan the trip and send an email to the team") { true }

        assertEquals(listOf("PLANNING", "RUNNING", "WAITING", "RUNNING"), store.statuses)
        assertEquals(1, email.executions)
        assertEquals(listOf("COMPLETED"), store.finished)
    }

    @Test
    fun cancelledRunIsRecordedSoItCanBeResumed() {
        val cancelling = FakeTool("create_task") { throw kotlinx.coroutines.CancellationException("screen closed") }
        val store = FakeRunStore()
        val model = ScriptedModel(planJson("""{"id":"1","tool":"create_task","input":{}}"""))
        assertThrows(kotlinx.coroutines.CancellationException::class.java) {
            runBlocking { pipeline(listOf(cancelling), model, store = store).handle("Plan a trip and create a task") }
        }
        assertEquals(listOf("CANCELLED"), store.finished)
        assertTrue(store.plans.isNotEmpty())
    }

    @Test
    fun interruptedRunIsReportedWithAResumeHintAndIsResumable() = runBlocking<Unit> {
        val plan = AgentPlan("Trip", listOf(PlanStep("1", "get_weather", JSONObject())))
        val dao = FakeAgentRunDao(mutableListOf(
            AgentRunEntity("run-1", "Trip", "WAITING", "", "", 1_000L, 0L, plan = plan.toJson().toString())
        ))
        val repository = AgentRunRepository(dao) { 2_000L }

        assertTrue(repository.reconcileInterruptedRuns().single().contains("Say \"resume\""))
        assertEquals("INTERRUPTED", dao.runs.single().status)
        assertEquals(2_000L, dao.runs.single().updatedAt)
        assertEquals("run-1", repository.latestResumableRun()!!.runId)
    }

    // ---------------------------------------------------------------- Gemini efficiency and observability

    @Test
    fun simpleRequestsNeverCallGemini() = runBlocking<Unit> {
        val clock = ZonedDateTime.of(2026, 9, 30, 10, 0, 0, 0, ZoneId.of("Asia/Kolkata"))
        val dao = FakeTaskDao()
        val tasks = TaskRepository(dao)
        val provider = object : WebSearchProvider {
            override val name = "Fake"
            override suspend fun search(query: String, limit: Int) = listOf(SearchResult("Result", "https://r.example", "s"))
        }
        val tools = listOf(
            DateTimeTool { clock },
            weatherTool(weatherHttp().first),
            FakeTool("navigate", ToolRisk.LAUNCH, listOf(ToolParam("destination", "string", "d", true))),
            CreateTaskTool(tasks),
            CreateReminderTool(tasks, FakeScheduler()) { now },
            WebSearchTool(WebSearchClient(listOf(provider)))
        )
        val p = pipeline(tools, ScriptedModel(), chat = { error("Gemini must not be called") })

        listOf(
            "What time is it?",
            "What is today's date?",
            "What's the weather in Mysore tomorrow?",
            "Open Bangalore Palace on Maps",
            "Remind me to call John tomorrow at 8 PM.",
            "Create a task to visit Bangalore Palace tomorrow.",
            "Search the web for Kotlin 2.0",
            "Find hotels in Singapore",
            "Remind me tomorrow.",
            "What can you do?"
        ).forEach { input ->
            val result = p.handle(input)
            assertEquals(input, 0, result.trace.geminiCalls)
            assertTrue(input, result.trace.depth <= 1)
        }
    }

    @Test
    fun routerChoosesTheCheapestReliableDepth() {
        fun depth(input: String) = FastIntentRouter.route(input, now).depth
        assertEquals(0, depth("What time is it?"))
        assertEquals(1, depth("What's the weather in Singapore tomorrow?"))
        assertEquals(1, depth("Find hotels in Singapore"))
        assertEquals(2, depth("Explain SAP FI."))
        assertEquals(2, depth("Compare iPhone 16 and Pixel 9"))
        assertEquals(3, depth("Plan a weekend trip to Mysore."))

        val weather = FastIntentRouter.route("What's the weather in Singapore tomorrow?", now) as Route.Direct
        assertEquals("Singapore", weather.args.getString("location"))
        assertEquals("tomorrow", weather.args.getString("day"))
        val compare = FastIntentRouter.route("Compare iPhone 16 and Pixel 9", now) as Route.Research
        assertEquals(listOf("iPhone 16", "Pixel 9"), compare.queries)
        assertTrue(FastIntentRouter.route("resume", now) is Route.Resume)
    }

    @Test
    fun logLineCarriesDepthVerificationAndErrorType() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        pipeline(listOf(CreateTaskTool(TaskRepository(FakeTaskDao()))), log = { logs += it })
            .handle("Create a task to visit Bangalore Palace tomorrow.")
        val line = logs.single()
        listOf("depth=0", "toolCalls=1", "verification=VERIFIED", "errorType=NONE", "fallback=false")
            .forEach { assertTrue(line, line.contains(it)) }
    }

    // ---------------------------------------------------------------- native function calling, streaming, tokens

    // Scripted Gemini function-calling model. Like the real router it streams through the request's
    // TextStreamSink and reports token usage through its UsageRecorder.
    private class ScriptedToolModel(vararg responses: ModelResponse) : ToolCallingModel {
        private val queue = ArrayDeque(responses.toList())
        val sent = mutableListOf<List<ModelMessage>>()
        val offered = mutableListOf<List<String>>()

        override fun isAvailable() = true

        override suspend fun call(
            systemInstruction: String,
            messages: List<ModelMessage>,
            tools: List<ModelToolSpec>,
            stream: Boolean
        ): ModelResponse {
            sent += messages.toList()
            offered += tools.map { it.name }
            val next = queue.removeFirstOrNull() ?: error("unexpected model call")
            if (stream && next.text.isNotEmpty()) currentCoroutineContext()[TextStreamSink]?.onText?.invoke(next.text)
            currentCoroutineContext()[UsageRecorder]?.record(next.usage)
            return next
        }

        // The function response JAX sent back on model call number `call` (0-based).
        fun toolResultSentOn(call: Int): JSONObject = sent[call].last().functionResponses.single().response
    }

    private fun calls(name: String, args: JSONObject = JSONObject()) =
        ModelResponse(text = "", functionCalls = listOf(ModelFunctionCall(name, args, "call-$name")))

    private fun says(text: String, usage: TokenUsage = TokenUsage()) = ModelResponse(text = text, usage = usage)

    @Test
    fun nativeChatCreatesATaskThroughTheExecutorAndReportsVerification() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val model = ScriptedToolModel(
            calls("create_task", JSONObject().put("title", "Buy milk").put("deadline", "2026-10-01")),
            says("Added \"Buy milk\" for tomorrow.")
        )
        val result = pipeline(listOf(CreateTaskTool(TaskRepository(dao))), toolModel = model)
            .handle("I need to buy milk tomorrow")

        assertEquals("GEMINI", result.trace.route)
        assertEquals("Added \"Buy milk\" for tomorrow.", result.reply)
        assertEquals("2026-10-01", dao.tasks.single().deadline)
        assertEquals(listOf("create_task:VERIFIED"), result.trace.tools)
        assertEquals(2, result.trace.geminiCalls)
        val sentBack = model.toolResultSentOn(1)
        assertEquals("SUCCESS", sentBack.getString("status"))
        assertEquals("VERIFIED", sentBack.getString("verification"))
    }

    @Test
    fun nativeChatOffersEverydayToolsButNotHighRiskOnes() = runBlocking<Unit> {
        val model = ScriptedToolModel(says("SAP FI is SAP's financial accounting module."))
        val tools = listOf(
            DateTimeTool(),
            CreateTaskTool(TaskRepository(FakeTaskDao())),
            CompleteTaskTool(TaskRepository(FakeTaskDao())),
            FakeTool("send_message", ToolRisk.SENSITIVE),
            FakeTool("wipe_notes", ToolRisk.DESTRUCTIVE)
        )
        val result = pipeline(tools, toolModel = model).handle("Explain SAP FI.")

        assertEquals("SAP FI is SAP's financial accounting module.", result.reply)
        val offered = model.offered.single()
        // complete_task is offered for "mark done"; its delete mode still asks the user.
        assertTrue(offered.containsAll(listOf("get_datetime", "create_task", "complete_task")))
        assertFalse(offered.contains("send_message"))
        assertFalse(offered.contains("wipe_notes"))
    }

    @Test
    fun nativeAgentLooksUpTheTaskAndDeletingStillNeedsApproval() = runBlocking<Unit> {
        val dao = FakeTaskDao().apply { tasks += TaskEntity("t1", "Dentist", "Personal", "MED", null) }
        val repo = TaskRepository(dao)
        val model = ScriptedToolModel(
            calls("search_tasks", JSONObject().put("query", "dentist")),
            calls("complete_task", JSONObject().put("id", "t1").put("remove", true)),
            says("Okay, I left the dentist task in place.")
        )
        var asked = 0
        val result = pipeline(listOf(SearchTasksTool(repo), CompleteTaskTool(repo)), toolModel = model)
            .handle("Delete my dentist task") { asked++; false }

        assertEquals("AGENT", result.trace.route)
        assertEquals(1, asked)
        assertEquals(1, dao.tasks.size)
        assertEquals(listOf("search_tasks:UNVERIFIED", "complete_task:DECLINED"), result.trace.tools)
        assertTrue(model.toolResultSentOn(1).toString().contains("Dentist"))
        assertEquals("DECLINED", model.toolResultSentOn(2).getString("status"))
        assertEquals(listOf("search_tasks", "complete_task"), model.offered.first())
        assertEquals("Okay, I left the dentist task in place.", result.reply)
    }

    @Test
    fun repeatedNativeCallRunsOnceAndTheModelIsTold() = runBlocking<Unit> {
        val createTask = FakeTool("create_task", parameters = listOf(ToolParam("title", "string", "t", true)))
        val args = JSONObject().put("title", "Buy milk")
        val model = ScriptedToolModel(
            calls("create_task", args),
            calls("create_task", JSONObject(args.toString())),
            says("Added it.")
        )
        pipeline(listOf(createTask), toolModel = model).handle("I need to buy milk tomorrow")

        assertEquals(1, createTask.executions)
        assertTrue(model.toolResultSentOn(2).getString("note").contains("not repeated"))
    }

    @Test
    fun unknownNativeToolIsRefusedWithoutRunning() = runBlocking<Unit> {
        val model = ScriptedToolModel(calls("launch_rocket"), says("I can't do that."))
        val result = pipeline(listOf(DateTimeTool()), toolModel = model).handle("Explain SAP FI.")

        assertEquals("UNKNOWN_TOOL", model.toolResultSentOn(1).getString("status"))
        assertTrue(result.trace.tools.isEmpty())
        assertEquals("I can't do that.", result.reply)
    }

    @Test
    fun answerIsStreamedAndTokensAreCountedPerRequest() = runBlocking<Unit> {
        val logs = mutableListOf<String>()
        val metrics = RequestMetrics()
        val model = ScriptedToolModel(says("SAP FI covers financial accounting.", TokenUsage(120, 30)))
        val partials = mutableListOf<String>()
        val result = pipeline(emptyList(), toolModel = model, log = { logs += it }, metrics = metrics)
            .handle("Explain SAP FI.", onPartial = { partials += it })

        assertEquals(listOf("SAP FI covers financial accounting."), partials)
        assertEquals(120, result.trace.promptTokens)
        assertEquals(30, result.trace.outputTokens)
        assertTrue(logs.first().contains("tokensIn=120 tokensOut=30"))
        assertEquals(150L, metrics.snapshot().totalTokens)
    }

    @Test
    fun nativeModelFailureIsAFriendlyReplyAndAFailedTrace() = runBlocking<Unit> {
        val failing = object : ToolCallingModel {
            override fun isAvailable() = true
            override suspend fun call(
                systemInstruction: String,
                messages: List<ModelMessage>,
                tools: List<ModelToolSpec>,
                stream: Boolean
            ): ModelResponse = throw AIException(AIError.NetworkError)
        }
        val result = pipeline(emptyList(), toolModel = failing).handle("Explain SAP FI.")

        assertEquals(AIError.NetworkError.userFriendlyMessage, result.reply)
        assertFalse(result.trace.success)
    }

    // ---------------------------------------------------------------- single-call chat proposes, the executor acts

    @Test
    fun chatProposedTaskRunsThroughTheExecutorWithOneGeminiCall() = runBlocking<Unit> {
        val dao = FakeTaskDao()
        val draft = ChatDraft(
            "Added \"Buy milk\" for tomorrow.",
            ProposedAction("create_task", JSONObject().put("title", "Buy milk").put("deadline", "2026-10-01"))
        )
        val result = pipeline(listOf(CreateTaskTool(TaskRepository(dao))), chatDraft = { draft })
            .handle("I need to buy milk tomorrow")

        assertEquals("Added \"Buy milk\" for tomorrow.", result.reply)
        assertEquals(listOf("create_task:VERIFIED"), result.trace.tools)
        assertEquals(1, result.trace.geminiCalls)
        assertEquals(1, dao.tasks.size)
    }

    @Test
    fun chatCannotSaveAnInferredFactPermanently() = runBlocking<Unit> {
        val store = FakeTool(
            "store_memory",
            parameters = listOf(ToolParam("title", "string", "t", true), ToolParam("details", "string", "d", true)),
            writesDurableMemory = true
        )
        val memory = WorkingMemory()
        val draft = ChatDraft(
            "Have a good trip!",
            ProposedAction("store_memory", JSONObject().put("title", "Trip").put("details", "Flying to Delhi tomorrow"))
        )
        val result = pipeline(listOf(store), memory = memory, chatDraft = { draft }).handle("I'm flying to Delhi tomorrow")

        assertEquals(0, store.executions)
        assertEquals(listOf("store_memory:DENIED"), result.trace.tools)
        assertTrue(result.reply.startsWith("Have a good trip!"))
        assertTrue(result.reply.contains("Noted for this conversation"))
        assertTrue(memory.state.notes.contains("Trip: Flying to Delhi tomorrow"))
    }

    @Test
    fun declinedChatActionIsNotReportedAsDone() = runBlocking<Unit> {
        val send = FakeTool("send_message", ToolRisk.SENSITIVE)
        val draft = ChatDraft("Sent!", ProposedAction("send_message", JSONObject()))
        val result = pipeline(listOf(send), chatDraft = { draft }).handle("Tell the team I'm late") { false }

        assertEquals(0, send.executions)
        assertEquals("Okay, I won't do that.", result.reply)
    }
}
