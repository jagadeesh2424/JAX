package com.jax.assistant.executive.agent

import com.jax.assistant.data.JaxRepository
import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.planner.PlanResult
import com.jax.assistant.executive.planner.UserIntent
import kotlinx.coroutines.flow.first

class BriefingAgent(private val repository: JaxRepository) : AssistantAgent {

    override val agentId: String = "BriefingAgent"
    override val displayName: String = "Executive Daily Briefing Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId || plan.intent == UserIntent.EXECUTIVE_BRIEFING
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        val tasks = repository.getAllTasks().first()
        val facts = repository.getAllFacts().first()

        val pendingTasks = tasks.filter { !it.isCompleted }
        val highPriority = pendingTasks.filter { it.priority.lowercase() == "high" }

        val briefingBuilder = StringBuilder()
        briefingBuilder.append("🌅 EXECUTIVE MORNING BRIEFING\n")
        briefingBuilder.append("Date: ${contextState.currentDate}\n\n")

        briefingBuilder.append("📋 TASKS OVERVIEW:\n")
        briefingBuilder.append("• Total Pending Tasks: ${pendingTasks.size}\n")
        briefingBuilder.append("• High Priority: ${highPriority.size}\n")

        if (pendingTasks.isNotEmpty()) {
            briefingBuilder.append("\nTop Focus Items:\n")
            pendingTasks.take(3).forEach { task ->
                briefingBuilder.append("  - [${task.priority}] ${task.title} (Due: ${task.deadline ?: "Today"})\n")
            }
        }

        if (facts.isNotEmpty()) {
            briefingBuilder.append("\n💡 MEMORY HIGHLIGHTS:\n")
            facts.take(2).forEach { fact ->
                briefingBuilder.append("  - ${fact.factText}\n")
            }
        }

        briefingBuilder.append("\n🚀 RECOMMENDED ACTION:\n")
        if (highPriority.isNotEmpty()) {
            briefingBuilder.append("Focus immediately on your High Priority task: \"${highPriority.first().title}\".")
        } else if (pendingTasks.isNotEmpty()) {
            briefingBuilder.append("Clear your pending task \"${pendingTasks.first().title}\" early today.")
        } else {
            briefingBuilder.append("All tasks are clear. Good time for strategic planning or learning.")
        }

        return AgentExecutionResult(
            success = true,
            outputMessage = briefingBuilder.toString()
        )
    }
}
