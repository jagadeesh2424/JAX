package com.jax.assistant.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import org.json.JSONObject
import java.util.UUID

sealed class JaxParseResult {
    data class TaskResult(val task: TaskEntity, val reply: String) : JaxParseResult()
    data class FactResult(val fact: FactEntity, val reply: String) : JaxParseResult()
    data class QuestionResult(val reply: String) : JaxParseResult()
}

class GeminiBrain(var apiKey: String) {

    private val candidateModels = listOf(
        "gemini-1.5-flash",
        "gemini-2.0-flash",
        "gemini-1.5-pro",
        "gemini-flash"
    )

    private val jsonSchemaPrompt = """
        You are J.A.X. (Jagadeesh Agent X), an executive AI butler for Jagadeesh.
        Classify user message into TASK, MEMORY (Fact), or QUESTION.
        
        Respond STRICTLY with valid JSON format matching:
        {
          "itemType": "TASK" | "MEMORY" | "QUESTION",
          "reply": "friendly executive response to Jagadeesh",
          "title": "short task or memory title",
          "category": "Work" | "Personal" | "General" | "Finance",
          "priority": "HIGH" | "MED" | "LOW",
          "deadline": "YYYY-MM-DD or empty string",
          "details": "full note or memory details"
        }
    """.trimIndent()

    suspend fun processUserInput(input: String): JaxParseResult {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            return JaxParseResult.QuestionResult(
                "Gemini API key is missing. Please tap the Settings icon ⚙️ at top right to enter your API key."
            )
        }

        val prompt = "$jsonSchemaPrompt\n\nUser Message: \"$input\""
        var lastExceptionMessage = ""

        // Try candidate models with fallback
        for (modelName in candidateModels) {
            try {
                val model = GenerativeModel(
                    modelName = modelName,
                    apiKey = keyToUse,
                    generationConfig = generationConfig {
                        responseMimeType = "application/json"
                    }
                )

                val response = model.generateContent(prompt)
                val rawText = response.text
                if (!rawText.isNullOrBlank()) {
                    val parsed = parseJsonResponse(rawText, input)
                    if (parsed != null) {
                        return parsed
                    }
                }
            } catch (e: Exception) {
                lastExceptionMessage = e.localizedMessage ?: e.message ?: "Unknown model error"
                // Try fallback without responseMimeType in case model doesn't support json mime type
                try {
                    val fallbackModel = GenerativeModel(
                        modelName = modelName,
                        apiKey = keyToUse
                    )
                    val response = fallbackModel.generateContent(prompt)
                    val rawText = response.text
                    if (!rawText.isNullOrBlank()) {
                        val parsed = parseJsonResponse(rawText, input)
                        if (parsed != null) {
                            return parsed
                        }
                    }
                } catch (e2: Exception) {
                    lastExceptionMessage = e2.localizedMessage ?: e2.message ?: lastExceptionMessage
                }
            }
        }

        // Return clear, user-friendly notice if all attempts failed
        return JaxParseResult.QuestionResult(
            "J.A.X. Notice: Unable to reach Gemini AI service ($lastExceptionMessage). Please verify your API Key in Settings ⚙️."
        )
    }

    private fun parseJsonResponse(rawText: String, originalInput: String): JaxParseResult? {
        return try {
            val jsonText = extractJsonObjectString(rawText)
            val json = JSONObject(jsonText)

            val itemType = json.optString("itemType", "QUESTION").uppercase()
            val reply = json.optString("reply", "Understood, Jagadeesh.")
            val title = json.optString("title", originalInput.take(30))
            val category = json.optString("category", "General")

            when (itemType) {
                "TASK" -> {
                    val priority = json.optString("priority", "MED").uppercase()
                    val deadline = json.optString("deadline", "").ifBlank { null }
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
                    val details = json.optString("details", originalInput)
                    val fact = FactEntity(
                        id = UUID.randomUUID().toString(),
                        title = title,
                        category = category,
                        details = details
                    )
                    JaxParseResult.FactResult(fact, reply)
                }
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
}
