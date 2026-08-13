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
        currentDate: String
    ): AssembledContext {
        val facts = if (relevantFacts.isEmpty()) "None"
        else relevantFacts.joinToString("\n") { "- [${it.category}] ${it.title}: ${it.details}" }

        val tasks = if (openTasks.isEmpty()) "None"
        else openTasks.joinToString("\n") {
            "- (${it.id}) [${it.priority}] ${it.title}" + (it.deadline?.let { d -> " due $d" } ?: "")
        }

        val convo = if (conversationSummary.isBlank()) "" else "\nRECENT CONVERSATION:\n$conversationSummary"

        val text = """
            USER: $userName
            TODAY: $currentDate
            KNOWN FACTS:
            $facts
            OPEN TASKS:
            $tasks$convo
        """.trimIndent()

        return AssembledContext(text)
    }
}
