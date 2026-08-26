package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import org.json.JSONObject
import java.util.UUID

sealed class JaxParseResult {
    data class TaskResult(val task: TaskEntity, val reply: String) : JaxParseResult()
    data class FactResult(val fact: FactEntity, val reply: String) : JaxParseResult()
    data class CompleteTaskResult(val reference: String, val reply: String) : JaxParseResult()
    data class QuestionResult(val reply: String) : JaxParseResult()
}

class GeminiBrain(
    private val aiService: AIService,
    private val memoryEngine: MemoryEngine = MemoryEngine()
) {

    suspend fun processUserInput(
        input: String,
        selectedModel: String = "gemini-2.0-flash",
        allFacts: List<FactEntity> = emptyList(),
        conversationSummary: String = ""
    ): JaxParseResult {
        return try {
            // Retrieve only relevant memories
            val relevantMemories = memoryEngine.selectRelevantMemories(input, allFacts)

            // Construct prompt using PromptBuilder
            val prompt = PromptBuilder.buildPrompt(
                userProfile = "Jagadeesh",
                relevantMemories = relevantMemories,
                conversationSummary = conversationSummary,
                currentDate = java.time.LocalDate.now().toString(),
                userInput = input
            )

            // Execute AI call via AIService interface
            val responseText = aiService.generate(prompt, selectedModel)

            // Parse response safely
            parseJsonResponse(responseText, input)
                ?: JaxParseResult.QuestionResult("Understood, Jagadeesh.")
        } catch (e: AIException) {
            JaxParseResult.QuestionResult(e.error.userFriendlyMessage)
        } catch (e: Exception) {
            JaxParseResult.QuestionResult(
                "J.A.X. Notice: ${e.localizedMessage ?: "Unable to complete AI request. Please check API Key in Settings ⚙️."}"
            )
        }
    }

    private fun parseJsonResponse(rawText: String, originalInput: String): JaxParseResult? {
        return try {
            val jsonText = extractJsonObjectString(rawText)
            val json = JSONObject(jsonText)

            val itemType = jsonString(json, jsonText, "itemType", "QUESTION").uppercase()
            val reply = jsonString(json, jsonText, "reply", "Understood, Jagadeesh.")
            val title = jsonString(json, jsonText, "title", originalInput.take(30))
            val category = jsonString(json, jsonText, "category", "General")

            when (itemType) {
                "TASK" -> {
                    val priority = jsonString(json, jsonText, "priority", "MED").uppercase()
                    val deadline = jsonString(json, jsonText, "deadline", "").ifBlank { null }
                    val task = TaskEntity(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        category = category,
                        priority = priority,
                        deadline = deadline,
                        isCompleted = false
                    )
                    JaxParseResult.TaskResult(task, reply)
                }
                "MEMORY" -> {
                    val details = jsonString(json, jsonText, "details", originalInput)
                    val fact = FactEntity(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        category = category,
                        details = details
                    )
                    JaxParseResult.FactResult(fact, reply)
                }
                "COMPLETE_TASK" -> JaxParseResult.CompleteTaskResult(reference = title, reply = reply)
                else -> JaxParseResult.QuestionResult(reply)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun extractJsonObjectString(text: String): String {
        val trimmed = text.trim()
        val start = trimmed.indexOf('{')
        val end = trimmed.lastIndexOf('}')
        if (start != -1 && end > start) {
            return trimmed.substring(start, end + 1)
        }
        return trimmed
    }

    // Android's JSONObject optString overloads are nullable in some local JVM stubs.
    // Read through opt() so unit tests and device builds share the same behavior.
    private fun jsonString(json: JSONObject, rawJson: String, key: String, fallback: String): String {
        val parsed = (json.opt(key) as? String)?.takeIf { it.isNotBlank() }
        if (parsed != null) return parsed
        val escapedKey = Regex.escape(key)
        return Regex("\\\"$escapedKey\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
            .find(rawJson)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() } ?: fallback
    }
}
