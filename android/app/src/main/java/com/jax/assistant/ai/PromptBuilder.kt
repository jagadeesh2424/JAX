package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity

object PromptBuilder {

    fun buildPrompt(
        userProfile: String = "Jagadeesh",
        relevantMemories: List<FactEntity> = emptyList(),
        conversationSummary: String = "",
        currentDate: String = "",
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
            "CONVERSATION CONTEXT (most recent last):\n$conversationSummary\n"
        } else ""

        val dateBlock = if (currentDate.isNotBlank()) "TODAY'S DATE: $currentDate\n" else ""

        return """
            You are J.A.X. (Jagadeesh Agent X), an executive AI butler for $userProfile.
            Classify user message into TASK, COMPLETE_TASK, MEMORY (Fact), or QUESTION.

            $dateBlock
            $memoryBlock
            $summaryBlock
            SLOT-FILLING RULES:
            - If the user wants a task/reminder but has NOT given a due date, respond with itemType "QUESTION" and ask specifically for the due date. Do NOT invent a date.
            - If the priority is unclear you may ask, otherwise default to "MED".
            - Use CONVERSATION CONTEXT to gather details provided in earlier turns. Once you have a title AND a due date, respond with itemType "TASK".
            - Resolve relative dates (today, tomorrow, next Monday) against TODAY'S DATE into YYYY-MM-DD.
            - If the user asks to cancel, complete, delete, remove, finish, or mark done an EXISTING task, reminder, or appointment, respond with itemType "COMPLETE_TASK" and put the identifying phrase in "title" (e.g. "Saturday appointment"). NEVER create a new TASK for a cancellation or completion request.

            Respond STRICTLY with valid JSON format matching:
            {
              "itemType": "TASK" | "COMPLETE_TASK" | "MEMORY" | "QUESTION",
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
