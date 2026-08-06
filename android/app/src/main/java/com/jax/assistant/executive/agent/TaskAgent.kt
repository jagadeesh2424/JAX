package com.jax.assistant.executive.agent

import com.jax.assistant.data.JaxRepository
import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.planner.PlanResult
import com.jax.assistant.executive.planner.UserIntent
import kotlinx.coroutines.flow.first

class TaskAgent(private val repository: JaxRepository) : AssistantAgent {

    override val agentId: String = "TaskAgent"
    override val displayName: String = "Executive Task Manager Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId || plan.intent == UserIntent.TASK_CREATE || plan.intent == UserIntent.TASK_QUERY
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        return when (plan.intent) {
            UserIntent.TASK_CREATE -> {
                val title = plan.extractedParameters["title"] ?: "Untitled Task"
                val deadline = plan.extractedParameters["deadline"] ?: "Today"
                val priority = plan.extractedParameters["priority"] ?: "MED"
                val category = plan.extractedParameters["category"] ?: "General"

                val createdTask = repository.createManualTask(
                    title = title,
                    category = category,
                    priority = priority,
                    deadline = deadline
                )

                AgentExecutionResult(
                    success = true,
                    outputMessage = "Task created successfully!\n\n• Goal: ${createdTask.title}\n• Due: ${createdTask.deadline ?: "Not specified"}\n• Priority: ${createdTask.priority}\n• Category: ${createdTask.category}",
                    data = mapOf("taskId" to createdTask.id)
                )
            }

            UserIntent.TASK_QUERY -> {
                val tasks = repository.getAllTasks().first()
                val pending = tasks.filter { !it.isCompleted }

                if (pending.isEmpty()) {
                    AgentExecutionResult(
                        success = true,
                        outputMessage = "You currently have no pending tasks. Great job!"
                    )
                } else {
                    val formattedList = pending.joinToString("\n") { task ->
                        "• [${task.priority}] ${task.title} (Due: ${task.deadline ?: "Today"})"
                    }
                    AgentExecutionResult(
                        success = true,
                        outputMessage = "You have ${pending.size} pending tasks:\n\n$formattedList"
                    )
                }
            }

            else -> AgentExecutionResult(
                success = false,
                outputMessage = "TaskAgent cannot process intent: ${plan.intent}"
            )
        }
    }
}
