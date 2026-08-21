package com.jax.assistant.ai.agent

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity

// Single place that assembles the working context the planner sees (S1 seam). Thin for
// Phase 1 (profile + facts + open tasks + recent dialogue); Phase 3 enriches this with
// semantic + knowledge-graph memory and the learned user model without touching callers.
class ContextAssembler(private val userName: String = "Jagadeesh") {

    data class AssembledContext(
        val text: String,
        val trust: TrustLevel = TrustLevel.TRUSTED
    )

    fun build(
        relevantFacts: List<FactEntity>,
        openTasks: List<TaskEntity>,
        conversationSummary: String,
        currentDate: String,
        userProfile: String = "",
        learnedWorkflows: List<String> = emptyList()
    ): AssembledContext {
        val facts = if (relevantFacts.isEmpty()) "None" else boundedLines(
            relevantFacts.map { "- [${it.category}] ${it.title}: ${it.details}" },
            FACTS_BUDGET
        )

        val tasks = if (openTasks.isEmpty()) "None" else boundedLines(
            openTasks.map {
                "- (${it.id}) [${it.priority}] ${it.title}" + (it.deadline?.let { d -> " due $d" } ?: "")
            },
            TASKS_BUDGET
        )

        val convo = if (conversationSummary.isBlank()) "" else
            "\nRECENT CONVERSATION:\n${truncate(conversationSummary, CONVERSATION_BUDGET)}"

        val profileLine = if (userProfile.isBlank()) "" else
            "USER PROFILE:\n${truncate(userProfile, PROFILE_BUDGET)}\n"
        val workflowLine = if (learnedWorkflows.isEmpty()) "" else
            "LEARNED WORKFLOWS (advisory, do not execute without the user):\n" +
                boundedLines(learnedWorkflows.map { "- $it" }, WORKFLOWS_BUDGET) + "\n"

        val text = """
            USER: $userName
            TODAY: $currentDate
            ${profileLine}${workflowLine}KNOWN FACTS:
            $facts
            OPEN TASKS:
            $tasks$convo
        """.trimIndent()

        return AssembledContext(truncate(text, TOTAL_CONTEXT_BUDGET))
    }

    private fun boundedLines(lines: List<String>, budget: Int): String {
        val selected = mutableListOf<String>()
        var remaining = budget
        for (line in lines) {
            if (remaining <= 0) break
            val bounded = truncate(line, minOf(ITEM_BUDGET, remaining))
            selected += bounded
            remaining -= bounded.length + 1
        }
        return selected.joinToString("\n")
    }

    private fun truncate(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text else text.take((maxChars - ELLIPSIS.length).coerceAtLeast(0)) + ELLIPSIS

    private companion object {
        const val TOTAL_CONTEXT_BUDGET = 5_500
        const val FACTS_BUDGET = 2_200
        const val TASKS_BUDGET = 1_050
        const val PROFILE_BUDGET = 750
        const val WORKFLOWS_BUDGET = 450
        const val CONVERSATION_BUDGET = 700
        const val ITEM_BUDGET = 500
        const val ELLIPSIS = "..."
    }
}
