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

    private val jsonSchema = """
        {
          "type": "object",
          "properties": {
            "itemType": { "type": "string", "enum": ["TASK", "MEMORY", "QUESTION"] },
            "isComplete": { "type": "boolean" },
            "reply": { "type": "string" },
            "title": { "type": "string" },
            "category": { "type": "string" },
            "priority": { "type": "string", "enum": ["HIGH", "MED", "LOW"] },
            "deadline": { "type": "string" },
            "details": { "type": "string" }
          },
          "required": ["itemType", "isComplete", "reply", "title", "category"]
        }
    """.trimIndent()

    suspend fun processUserInput(input: String): JaxParseResult {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            return JaxParseResult.QuestionResult(
                "Gemini API key is missing. Please enter your API key in Settings to activate J.A.X. AI responses."
            )
        }

        return try {
            val model = GenerativeModel(
                modelName = "gemini-1.5-flash",
                apiKey = keyToUse,
                generationConfig = generationConfig {
                    responseMimeType = "application/json"
                }
            )

            val prompt = """
                You are J.A.X. (Jagadeesh Agent X), an executive AI butler for Jagadeesh.
                Classify user message into TASK, MEMORY (Fact), or QUESTION.
                
                Respond strictly with valid JSON adhering to this schema:
                $jsonSchema
                
                User message: "$input"
            """.trimIndent()

            val response = model.generateContent(prompt)
            val jsonText = response.text
                ?: return JaxParseResult.QuestionResult("I apologize, Jagadeesh. I could not generate a response.")

            val json = JSONObject(jsonText)
            val itemType = json.optString("itemType", "QUESTION")
            val reply = json.optString("reply", "Understood, Jagadeesh.")
            val title = json.optString("title", input.take(30))
            val category = json.optString("category", "General")

            when (itemType) {
                "TASK" -> {
                    val priority = json.optString("priority", "MED")
                    val deadline = json.optString("deadline", null)
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
                    val details = json.optString("details", input)
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
            JaxParseResult.QuestionResult(
                "J.A.X. System Notice: ${e.localizedMessage ?: "Unable to connect to Gemini API"}. Please check your API key in Settings."
            )
        }
    }
}
