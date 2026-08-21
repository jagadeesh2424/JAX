package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.PageLinkEntity
import com.jax.assistant.ai.agent.WorkflowLearner
import com.jax.assistant.ai.agent.ContextAssembler
import com.jax.assistant.ai.agent.AgentController
import com.jax.assistant.ai.agent.ProactiveInsightEngine
import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
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

        assertEquals(AgentController.ActionRisk.LOW, controller.riskFor(safeTool, JSONObject()))
        assertEquals(AgentController.ActionRisk.HIGH, controller.riskFor(destructiveTool, JSONObject()))
        assertEquals(AgentController.Decision.ALLOW, controller.authorize(destructiveTool, JSONObject()))
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
}
