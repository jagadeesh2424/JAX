package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.PageLinkEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ai.agent.WorkflowLearner
import com.jax.assistant.ai.agent.ContextAssembler
import com.jax.assistant.ai.agent.AgentController
import com.jax.assistant.ai.agent.AgentOrchestrator
import com.jax.assistant.ai.agent.ProactiveInsightEngine
import com.jax.assistant.ai.agent.AutonomyLevel
import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolRegistry
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.InMemoryEventSink
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject
import java.time.LocalDate

class AiLayerTest {

    private class MockAIService(var mockResponse: String = "", var shouldFailWith: AIError? = null) : AIService {
        var lastPrompt: String = ""
        var lastModel: String = ""

        override suspend fun generate(prompt: String, modelName: String): String {
            lastPrompt = prompt
            lastModel = modelName
            shouldFailWith?.let { throw AIException(it) }
            return mockResponse
        }

        override suspend fun testConnection(modelName: String): TestConnectionResult {
            lastModel = modelName
            shouldFailWith?.let {
                return TestConnectionResult(false, it.userFriendlyMessage)
            }
            return TestConnectionResult(true, "Connection successful to $modelName")
        }
    }

    @Test
    fun testMemoryEngineSelection() {
        val memoryEngine = MemoryEngine()
        val facts = listOf(
            FactEntity("1", "Passport Number", "Finance", "AB1234567"),
            FactEntity("2", "Wi-Fi Password", "Personal", "HomeSecret99"),
            FactEntity("3", "Flight Time", "Travel", "Flight to NYC at 9 AM")
        )

        val relevant = memoryEngine.selectRelevantMemories("What is my passport number?", facts)
        assertEquals(1, relevant.size)
        assertEquals("Passport Number", relevant[0].title)
    }

    @Test
    fun testMemoryEngineUsesWholeWordMatching() {
        val memoryEngine = MemoryEngine()
        val facts = listOf(
            FactEntity("1", "Expense Categories", "Finance", "Budgeting and category management"),
            FactEntity("2", "Pet Cat", "Personal", "My cat is named Milo")
        )
        // "cat" must not match the word "category"/"categories" (substring false positive).
        val scored = memoryEngine.scoreMemories("where is my cat", facts)
        assertEquals(1, scored.size)
        assertEquals("Pet Cat", scored[0].first.title)
    }

    @Test
    fun testMemoryEngineWeighsTitleAboveDetails() {
        val memoryEngine = MemoryEngine()
        val facts = listOf(
            FactEntity("1", "Random Note", "General", "insurance policy renewal reminder"),
            FactEntity("2", "Insurance Policy", "Finance", "unrelated body text")
        )
        val scored = memoryEngine.scoreMemories("insurance", facts)
        // Title/category match (score 2) should rank above a details-only match (score 1).
        assertEquals("Insurance Policy", scored[0].first.title)
    }

    @Test
    fun testFuseByReciprocalRankPrefersFactStrongInBothSignals() {
        val memoryEngine = MemoryEngine()
        val a = FactEntity("a", "A", "General", "")
        val b = FactEntity("b", "B", "General", "")
        val c = FactEntity("c", "C", "General", "")
        // b is rank 2 semantically but rank 1 lexically -> should win over a (rank 1 semantic only).
        val semantic = listOf(a, b)
        val lexical = listOf(b, c)
        val fused = memoryEngine.fuseByReciprocalRank(listOf(semantic, lexical))
        assertEquals("b", fused[0].id)
    }

    @Test
    fun testPromptBuilderConstruction() {
        val facts = listOf(
            FactEntity("1", "Passport Number", "Finance", "AB1234567")
        )

        val prompt = PromptBuilder.buildPrompt(
            userProfile = "Jagadeesh",
            relevantMemories = facts,
            userInput = "Remind me to renew passport"
        )

        assertTrue(prompt.contains("Passport Number"))
        assertTrue(prompt.contains("Remind me to renew passport"))
        assertTrue(prompt.contains("itemType"))
    }

    @Test
    fun testGeminiBrainTaskParsing() = runBlocking {
        val mockService = MockAIService(
            mockResponse = """
                {
                  "itemType": "TASK",
                  "reply": "Scheduled passport renewal task, Jagadeesh.",
                  "title": "Renew Passport",
                  "category": "Personal",
                  "priority": "HIGH",
                  "deadline": "2026-09-01"
                }
            """.trimIndent()
        )

        val brain = GeminiBrain(mockService)
        val result = brain.processUserInput("Remind me to renew passport", "gemini-2.0-flash")

        assertTrue(result is JaxParseResult.TaskResult)
        val taskResult = result as JaxParseResult.TaskResult
        assertEquals("Renew Passport", taskResult.task.title)
        assertEquals("HIGH", taskResult.task.priority)
        assertEquals("2026-09-01", taskResult.task.deadline)
        assertEquals("gemini-2.0-flash", mockService.lastModel)
    }

    @Test
    fun testGeminiBrainErrorHandling() = runBlocking {
        val mockService = MockAIService(shouldFailWith = AIError.InvalidApiKey)
        val brain = GeminiBrain(mockService)

        val result = brain.processUserInput("Hello JAX")
        assertTrue(result is JaxParseResult.QuestionResult)
        val questionResult = result as JaxParseResult.QuestionResult
        assertTrue(questionResult.reply.contains("Invalid or missing Gemini API key"))
    }

    @Test
    fun testTestConnectionFunction() = runBlocking {
        val mockService = MockAIService()
        val connectionResult = mockService.testConnection("gemini-2.0-flash")
        assertTrue(connectionResult.isSuccess)
        assertTrue(connectionResult.message.contains("gemini-2.0-flash"))
    }

    @Test
    fun testWorkflowLearnerSuggestsRepeatedCompletedToolSequence() {
        fun run(id: String, tools: String, status: String = "COMPLETED") = AgentRunEntity(
            id = id,
            goal = "Test goal",
            status = status,
            reply = "Done",
            toolsUsed = tools,
            startedAt = 1L,
            finishedAt = 2L
        )
        val suggestions = WorkflowLearner().suggestions(
            listOf(
                run("one", "create_task,search_tasks"),
                run("two", "create_task,search_tasks"),
                run("three", "create_task,search_tasks", "COMPLETED_WITH_ERRORS")
            )
        )

        assertEquals(listOf("create_task -> search_tasks (observed 2 times)"), suggestions)
    }

    @Test
    fun testAgentRunCarriesRecoverableState() {
        val run = AgentRunEntity(
            id = "run-1",
            goal = "Create and organize tasks",
            status = "RUNNING",
            reply = "",
            toolsUsed = "create_task",
            startedAt = 1L,
            finishedAt = 0L,
            currentStep = 2,
            maxSteps = 6,
            recoveryState = "Executed create_task; awaiting verification"
        )

        assertEquals("RUNNING", run.status)
        assertEquals(2, run.currentStep)
        assertEquals(6, run.maxSteps)
        assertTrue(run.recoveryState.contains("awaiting verification"))
    }

    @Test
    fun testKnowledgeGraphEdgeRetainsRelationAndValidity() {
        val link = PageLinkEntity(
            id = "link",
            fromPageId = "work",
            toPageId = "project",
            relation = "SUPPORTS",
            validFrom = 100L,
            validUntil = 200L
        )

        assertEquals("SUPPORTS", link.relation)
        assertEquals(100L, link.validFrom)
        assertEquals(200L, link.validUntil)
    }

    @Test
    fun testContextAssemblerBoundsLargeSections() {
        val longText = "x".repeat(2_000)
        val context = ContextAssembler().build(
            relevantFacts = List(8) { FactEntity("fact$it", "Fact $it", "General", longText) },
            openTasks = emptyList(),
            conversationSummary = longText,
            currentDate = "2026-08-20",
            userProfile = longText,
            learnedWorkflows = List(4) { "workflow $it $longText" }
        ).text

        assertTrue(context.length <= 5_500)
        assertTrue(context.contains("KNOWN FACTS:"))
        assertTrue(context.contains("RECENT CONVERSATION:"))
    }

    @Test
    fun testAgentControllerClassifiesDestructiveToolsAsHighRisk() {
        val controller = AgentController()
        val safeTool = testTool(isDestructive = false)
        val destructiveTool = testTool(isDestructive = true)

        assertEquals(ToolRisk.LOW_WRITE, controller.riskFor(safeTool, JSONObject()))
        assertEquals(ToolRisk.DESTRUCTIVE, controller.riskFor(destructiveTool, JSONObject()))
        // Low-risk runs freely; destructive requires the user's confirmation.
        assertEquals(AgentController.Decision.ALLOW, controller.authorize(safeTool, JSONObject()))
        assertEquals(AgentController.Decision.CONFIRM, controller.authorize(destructiveTool, JSONObject()))
    }

    @Test
    fun testProactiveInsightPrioritizesOverdueTasks() {
        val today = LocalDate.of(2026, 8, 20)
        val message = ProactiveInsightEngine().dailyBriefing(
            listOf(
                TaskEntity("today", "Prepare review", "Work", "HIGH", "2026-08-20"),
                TaskEntity("overdue", "Send report", "Work", "MED", "2026-08-19")
            ),
            today
        )

        assertTrue(message.contains("1 task(s) are overdue"))
        assertTrue(message.contains("Send report"))
    }

    @Test
    fun testProactiveInsightCanDisableTaskContext() {
        val message = ProactiveInsightEngine().dailyBriefing(
            listOf(TaskEntity("overdue", "Send report", "Work", "MED", "2026-08-19")),
            LocalDate.of(2026, 8, 20),
            useTaskContext = false
        )

        assertTrue(message.contains("All high priority tasks are clear"))
        assertFalse(message.contains("overdue"))
    }

    private fun testTool(isDestructive: Boolean): JaxTool = object : JaxTool {
        override val name = "test_tool"
        override val description = "Test tool"
        override val parameters = emptyList<ToolParam>()
        override val isDestructive = isDestructive

        override suspend fun execute(args: JSONObject): ToolResult = ToolResult.ok("done")
    }

    @Test
    fun testOrchestratorSkipsDuplicateToolExecution() = runBlocking {
        var executions = 0
        val tool = object : JaxTool {
            override val name = "create_task"
            override val description = "Creates a task"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult {
                executions++
                return ToolResult.ok("created")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"create_task","args":{"title":"Report"}}""",
                """{"action":"tool","tool":"create_task","args":{"title":"Report"}}""",
                """{"action":"final","reply":"Done."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(tool)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("create a task", "test-model", "ctx", sink)

        assertEquals(1, executions)
        assertTrue(sink.snapshot().any { it.type == "tool_result" && it.detail.startsWith("ALREADY DONE") })
        assertEquals("Done.", result.reply)
    }

    @Test
    fun testOrchestratorRetriesTransientToolFailure() = runBlocking {
        var attempts = 0
        val tool = object : JaxTool {
            override val name = "flaky_tool"
            override val description = "Fails once then succeeds"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult {
                attempts++
                if (attempts == 1) throw RuntimeException("transient network error")
                return ToolResult.ok("recovered")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"flaky_tool","args":{}}""",
                """{"action":"final","reply":"Recovered."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(tool)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("do it", "test-model", "ctx", sink)

        assertEquals(2, attempts)
        assertTrue(sink.snapshot().any { it.type == "retry" })
        assertTrue(sink.snapshot().any { it.type == "tool_result" && it.detail.startsWith("SUCCESS") })
        assertEquals("Recovered.", result.reply)
    }

    @Test
    fun testOrchestratorSkipsDeclinedSensitiveTool() = runBlocking {
        var executions = 0
        val tool = object : JaxTool {
            override val name = "delete_all"
            override val description = "Deletes everything"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = true
            override suspend fun execute(args: JSONObject): ToolResult {
                executions++
                return ToolResult.ok("deleted")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"delete_all","args":{}}""",
                """{"action":"final","reply":"Cancelled."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(tool)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        // User denies the confirmation -> the tool must never execute.
        val result = orchestrator.run("delete", "test-model", "ctx", sink, confirm = { false })

        assertEquals(0, executions)
        assertTrue(sink.snapshot().any { it.type == "tool_confirm_requested" })
        assertTrue(sink.snapshot().any { it.type == "tool_declined" })
        assertEquals("Cancelled.", result.reply)
    }

    @Test
    fun testOrchestratorRunsConfirmedSensitiveTool() = runBlocking {
        var executions = 0
        val tool = object : JaxTool {
            override val name = "dial"
            override val description = "Dials a number"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override val risk = ToolRisk.SENSITIVE
            override suspend fun execute(args: JSONObject): ToolResult {
                executions++
                return ToolResult.ok("dialed")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"dial","args":{"number":"123"}}""",
                """{"action":"final","reply":"Done."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(tool)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("call", "test-model", "ctx", sink, confirm = { true })

        assertEquals(1, executions)
        assertTrue(sink.snapshot().any { it.type == "tool_confirmed" })
        assertEquals("Done.", result.reply)
    }

    @Test
    fun testToolRegistryExportsMcpJsonSchema() {
        val tool = object : JaxTool {
            override val name = "create_task"
            override val description = "Creates a task"
            override val parameters = listOf(
                ToolParam("title", "string", "Task title", true),
                ToolParam("count", "number", "How many")
            )
            override val isDestructive = false
        }
        val schema = ToolRegistry(listOf(tool)).toolsJsonSchema()
        assertEquals(1, schema.length())
        val entry = schema.getJSONObject(0)
        assertEquals("create_task", entry.getString("name"))
        assertEquals("LOW_WRITE", entry.getString("risk"))
        val input = entry.getJSONObject("inputSchema")
        assertEquals("object", input.getString("type"))
        assertEquals("number", input.getJSONObject("properties").getJSONObject("count").getString("type"))
        val required = input.getJSONArray("required")
        assertEquals(1, required.length())
        assertEquals("title", required.getString(0))
    }

    @Test
    fun testOrchestratorRecordsPlanThenExecutes() = runBlocking {
        var executions = 0
        val tool = object : JaxTool {
            override val name = "create_task"
            override val description = "Creates a task"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult {
                executions++
                return ToolResult.ok("created")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"plan","steps":["find the task","create it"]}""",
                """{"action":"tool","tool":"create_task","args":{"title":"Report"}}""",
                """{"action":"final","reply":"Planned and done."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(tool)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("do a multi-step thing", "test-model", "ctx", sink)

        assertEquals(1, executions)
        assertTrue(sink.snapshot().any { it.type == "plan" && it.detail.contains("create it") })
        assertEquals("Planned and done.", result.reply)
    }

    @Test
    fun testOrchestratorFallsBackToAlternativeToolAfterFailure() = runBlocking {
        var primaryCalls = 0
        var fallbackCalls = 0
        val primary = object : JaxTool {
            override val name = "primary"
            override val description = "Primary tool that fails"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult {
                primaryCalls++
                return ToolResult.error("primary unavailable")
            }
        }
        val fallback = object : JaxTool {
            override val name = "fallback"
            override val description = "Fallback tool that works"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult {
                fallbackCalls++
                return ToolResult.ok("done via fallback")
            }
        }
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"primary","args":{}}""",
                """{"action":"tool","tool":"fallback","args":{}}""",
                """{"action":"final","reply":"Recovered via fallback."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(primary, fallback)),
            controller = AgentController(),
            generate = { _, _ -> responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("get it done", "test-model", "ctx", sink)

        assertEquals(1, primaryCalls)
        assertEquals(1, fallbackCalls)
        assertEquals("Recovered via fallback.", result.reply)
    }

    @Test
    fun testOrchestratorEmitsReplanAfterRepeatedFailures() = runBlocking {
        val failing = object : JaxTool {
            override val name = "fail_a"
            override val description = "Always fails"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult = ToolResult.error("nope")
        }
        val failingB = object : JaxTool {
            override val name = "fail_b"
            override val description = "Also fails"
            override val parameters = emptyList<ToolParam>()
            override val isDestructive = false
            override suspend fun execute(args: JSONObject): ToolResult = ToolResult.error("also nope")
        }
        var lastPrompt = ""
        val responses = ArrayDeque(
            listOf(
                """{"action":"tool","tool":"fail_a","args":{}}""",
                """{"action":"tool","tool":"fail_b","args":{}}""",
                """{"action":"final","reply":"Giving up gracefully."}"""
            )
        )
        val orchestrator = AgentOrchestrator(
            registry = ToolRegistry(listOf(failing, failingB)),
            controller = AgentController(),
            generate = { prompt, _ -> lastPrompt = prompt; responses.removeFirst() }
        )
        val sink = InMemoryEventSink()
        val result = orchestrator.run("try hard", "test-model", "ctx", sink)

        assertTrue(sink.snapshot().any { it.type == "replan" })
        // The re-plan directive must reach the model before it decides to finalize.
        assertTrue(lastPrompt.contains("REPLAN NOTICE"))
        assertEquals("Giving up gracefully.", result.reply)
    }

    @Test
    fun testProactiveInsightEngineEmitsNothingAtOffLevel() {
        val today = LocalDate.of(2026, 9, 1)
        val tasks = listOf(
            TaskEntity("1", "Overdue task", "Work", "HIGH", "2026-08-30")
        )
        val suggestions = ProactiveInsightEngine().suggest(tasks, today, AutonomyLevel.OFF)
        assertEquals(0, suggestions.size)
    }

    @Test
    fun testProactiveInsightEngineNotifiesWithoutAction() {
        val today = LocalDate.of(2026, 9, 1)
        val tasks = listOf(
            TaskEntity("1", "Overdue task", "Work", "HIGH", "2026-08-30")
        )
        val suggestions = ProactiveInsightEngine().suggest(tasks, today, AutonomyLevel.NOTIFY)
        assertEquals(1, suggestions.size)
        assertTrue(suggestions[0].observation.contains("1 task"))
        assertNull(suggestions[0].suggestedAction)
        assertFalse(suggestions[0].requiresConfirmation)
    }

    @Test
    fun testProactiveInsightEngineRecommendsWithAction() {
        val today = LocalDate.of(2026, 9, 1)
        val tasks = listOf(
            TaskEntity("1", "Overdue", "Work", "HIGH", "2026-08-30"),
            TaskEntity("2", "Due today", "Work", "MED", "2026-09-01")
        )
        val suggestions = ProactiveInsightEngine().suggest(tasks, today, AutonomyLevel.RECOMMEND)
        assertEquals(1, suggestions.size)
        assertNotNull(suggestions[0].suggestedAction)
        assertFalse(suggestions[0].requiresConfirmation)
    }

    @Test
    fun testProactiveInsightEngineAskRequiresConfirmation() {
        val today = LocalDate.of(2026, 9, 1)
        val tasks = listOf(
            TaskEntity("1", "Due today", "Work", "HIGH", "2026-09-01")
        )
        val suggestions = ProactiveInsightEngine().suggest(tasks, today, AutonomyLevel.ASK)
        assertEquals(1, suggestions.size)
        assertNotNull(suggestions[0].suggestedAction)
        assertTrue(suggestions[0].requiresConfirmation)
        assertFalse(suggestions[0].autoActable)
    }

    @Test
    fun testProactiveInsightEngineActAllowsAutoExecution() {
        val today = LocalDate.of(2026, 9, 1)
        val tasks = listOf(
            TaskEntity("1", "High priority", "Work", "HIGH", "2026-09-02")
        )
        val suggestions = ProactiveInsightEngine().suggest(tasks, today, AutonomyLevel.ACT)
        assertEquals(1, suggestions.size)
        assertTrue(suggestions[0].autoActable)
    }

    @Test
    fun testGeminiLiveVoiceProviderTrackConnectionState() {
        var lastActive = false
        var lastError: String? = null
        val provider = com.jax.assistant.voice.GeminiLiveVoiceProvider(
            onInputTranscript = {},
            onOutputTranscript = {},
            onActiveChanged = { active -> lastActive = active },
            onError = { error -> lastError = error }
        )
        // Start without Firebase auth should trigger an error state (in actual use, user must sign in).
        provider.start()
        // Without mocking Firebase, the provider will report auth error.
        assertFalse(lastActive)
        assertNotNull(lastError)
    }

    @Test
    fun testGeminiLiveVoiceProviderStopsCleanly() {
        val provider = com.jax.assistant.voice.GeminiLiveVoiceProvider(
            onInputTranscript = {},
            onOutputTranscript = {},
            onActiveChanged = { _ -> },
            onError = { _ -> }
        )
        provider.stop()
        assertFalse(provider.isActive())
    }
}
