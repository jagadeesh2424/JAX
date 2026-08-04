package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity

object PromptBuilder {

    fun buildPrompt(
        userProfile: String = "Jagadeesh",
        relevantMemories: List<FactEntity> = emptyList(),
        conversationSummary: String = "",
        userInput: String
    ): String {
        val memoryBlock = if (relevantMemories.isNotEmpty()) {
            "RELEVANT MEMORIES & FACTS:\n" + relevantMemories.joinToString("\n") {
                "- [${it.category}] ${it.title}: ${it.details}"
            }
        } else {
            "RELEVANT MEMORIES & FACTS: None retrieved."
        }

        val summaryBlock = if (conversationSummary.isNotBlank()) {
            "CONVERSATION CONTEXT:\n$conversationSummary\n"
        } else ""

        return """
            You are J.A.X. (Jagadeesh Agent X), an executive AI butler for $userProfile.
            Classify user message into TASK, MEMORY (Fact), or QUESTION.
            
            $memoryBlock
            $summaryBlock
            Respond STRICTLY with valid JSON format matching:
            {
              "itemType": "TASK" | "MEMORY" | "QUESTION",
              "reply": "polite, helpful executive butler response to Jagadeesh",
              "title": "short task or memory title",
              "category": "Work" | "Personal" | "General" | "Finance",
              "priority": "HIGH" | "MED" | "LOW",
              "deadline": "YYYY-MM-DD or empty string",
              "details": "full note or memory details"
            }

            User Message: "$userInput"
        """.trimIndent()
    }
}
