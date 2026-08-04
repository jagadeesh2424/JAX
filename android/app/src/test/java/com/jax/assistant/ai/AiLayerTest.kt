package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

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
}
