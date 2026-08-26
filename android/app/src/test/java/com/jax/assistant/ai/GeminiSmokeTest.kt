package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * GeminiSmokeTest allows all AI layer components to be verified independently
 * of the Android UI or JVM runtime without launching the device or modifying data.
 */
class GeminiSmokeTest {

    @Test
    fun testGeminiProviderDirectSmokeTest() = runBlocking {
        // This is an opt-in network smoke test. A fake key makes the SDK initialize
        // unnecessarily on the JVM (and is not a meaningful connectivity check).
        val apiKey = System.getenv("GEMINI_API_KEY") ?: ""
        assumeTrue("Set GEMINI_API_KEY to run the live Gemini smoke test", apiKey.isNotBlank())
        val provider = GeminiProvider()
        val model = "gemini-2.0-flash"
        val prompt = "Hello"

        val startTime = System.currentTimeMillis()
        val result = provider.generate(prompt = prompt, modelId = model, apiKey = apiKey, requireJson = false)
        val latency = System.currentTimeMillis() - startTime

        println("==================== GEMINI SMOKE TEST LOG ====================")
        println("Provider        : ${provider.providerName}")
        println("Model           : $model")
        println("SDK Version     : ${GeminiProvider.SDK_VERSION}")
        println("Endpoint        : ${GeminiProvider.REST_ENDPOINT_BASE}")
        println("HTTP URL        : ${GeminiProvider.REST_ENDPOINT_BASE}/models/$model:generateContent")
        println("HTTP Status     : ${result.httpStatus ?: "N/A"}")
        println("Request JSON    : {\"contents\":[{\"parts\":[{\"text\":\"$prompt\"}]}]}")
        println("Response Body   : ${result.responseBody ?: result.text ?: "N/A"}")
        println("Parsed AIResponse: ${result.text ?: "None"}")
        println("Latency         : ${latency}ms")
        println("Errors          : ${result.errorMessage ?: "None"}")
        println("==============================================================")

        assertNotNull(result)
        // Ensure diagnostic metadata is set
        assertEquals("Gemini", provider.providerName)
    }

    @Test
    fun testDynamicModelRankingSmokeTest() {
        val priority36 = ModelCatalog.calculatePriority("gemini-3.6-flash", "Gemini 3.6 Flash")
        val priority35 = ModelCatalog.calculatePriority("gemini-3.5-flash", "Gemini 3.5 Flash")
        val priority31Lite = ModelCatalog.calculatePriority("gemini-3.1-flash-lite", "Gemini 3.1 Flash Lite")
        val priority30 = ModelCatalog.calculatePriority("gemini-3-flash", "Gemini 3 Flash")
        val priority25 = ModelCatalog.calculatePriority("gemini-2.5-flash", "Gemini 2.5 Flash")
        val priority20 = ModelCatalog.calculatePriority("gemini-2.0-flash", "Gemini 2.0 Flash")

        assertTrue("Gemini 3.6 Flash must be highest priority", priority36 < priority35)
        assertTrue("Gemini 3.5 Flash must be higher priority than 3.1", priority35 < priority31Lite)
        assertTrue("Gemini 3.1 Flash Lite must be higher priority than 3 Flash", priority31Lite < priority30)
        assertTrue("Gemini 3 Flash must be higher priority than 2.5", priority30 < priority25)
        assertTrue("Gemini 2.5 Flash must be higher priority than 2.0", priority25 < priority20)

        println("[ModelRanking Smoke Test] Ranks: 3.6=$priority36, 3.5=$priority35, 3.1Lite=$priority31Lite, 3=$priority30, 2.5=$priority25, 2.0=$priority20")
    }

    @Test
    fun testAIRouterRoutingSmokeTest() = runBlocking {

        val router = AIRouter(null)
        val models = router.getModels()

        assertTrue("Router must possess default models", models.isNotEmpty())
        val active = router.getActiveModel()
        assertNotNull(active)
        println("[AIRouter Smoke Test] Available Models: ${models.map { it.id }} | Active: $active")
    }

    @Test
    fun testPromptBuilderSmokeTest() {
        val facts = listOf(
            FactEntity("f1", "Wi-Fi Password", "Home", "Secret123"),
            FactEntity("f2", "Passport Number", "Personal", "A1234567")
        )

        val prompt = PromptBuilder.buildPrompt(
            userProfile = "Jagadeesh",
            relevantMemories = facts,
            userInput = "What is my wifi password?"
        )

        assertTrue(prompt.contains("Jagadeesh"))
        assertTrue(prompt.contains("Wi-Fi Password"))
        assertTrue(prompt.contains("Secret123"))
        println("[PromptBuilder Smoke Test] Built Prompt Length: ${prompt.length} chars")
    }

    @Test
    fun testMemoryEngineSmokeTest() {
        val memoryEngine = MemoryEngine()
        val facts = listOf(
            FactEntity("1", "Passport Number", "Finance", "AB1234567"),
            FactEntity("2", "Wi-Fi Password", "Personal", "HomeSecret99")
        )

        val matched = memoryEngine.selectRelevantMemories("Tell me wifi password", facts)
        assertEquals(1, matched.size)
        assertEquals("Wi-Fi Password", matched[0].title)
        println("[MemoryEngine Smoke Test] Matched Memory: ${matched[0].title}")
    }

    @Test
    fun testJsonParsingSmokeTest() = runBlocking {
        val mockService = object : AIService {
            override suspend fun generate(prompt: String, modelName: String): String {
                return """
                    {
                        "itemType": "TASK",
                        "reply": "Created task to buy milk.",
                        "title": "Buy Milk",
                        "category": "Shopping",
                        "priority": "HIGH",
                        "deadline": "2026-08-10"
                    }
                """.trimIndent()
            }

            override suspend fun testConnection(modelName: String): TestConnectionResult {
                return TestConnectionResult(true, "OK")
            }
        }

        val brain = GeminiBrain(mockService)
        val result = brain.processUserInput("Remind me to buy milk")

        assertTrue(result is JaxParseResult.TaskResult)
        val taskResult = result as JaxParseResult.TaskResult
        assertEquals("Buy Milk", taskResult.task.title)
        assertEquals("HIGH", taskResult.task.priority)
        println("[JsonParsing Smoke Test] Successfully parsed task: ${taskResult.task.title}")
    }
}
