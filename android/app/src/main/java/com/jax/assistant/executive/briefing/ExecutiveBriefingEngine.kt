package com.jax.assistant.executive.briefing

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.executive.context.AppContextState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

data class BriefingDiagnostics(
    val lastExecutionTime: String = "Never",
    val nextScheduledTime: String = "08:00 AM Tomorrow",
    val lastExecutionDurationMs: Long = 0,
    val totalExecutions: Int = 0,
    val lastErrorMessage: String? = null,
    val isOperational: Boolean = true
)

data class ExecutiveBriefingContent(
    val dateString: String,
    val pendingTasksCount: Int,
    val highPriorityCount: Int,
    val topTasks: List<TaskEntity>,
    val birthdaysAndEvents: List<FactEntity>,
    val memoryHighlights: List<FactEntity>,
    val focusRecommendation: String
)

class ExecutiveBriefingEngine {

    private val _diagnostics = MutableStateFlow(BriefingDiagnostics())
    val diagnostics: StateFlow<BriefingDiagnostics> = _diagnostics.asStateFlow()

    fun generateBriefing(
        tasks: List<TaskEntity>,
        facts: List<FactEntity>,
        contextState: AppContextState
    ): ExecutiveBriefingContent {
        val startTime = System.currentTimeMillis()
        val pendingTasks = tasks.filter { !it.isCompleted }
        val highPriorityTasks = pendingTasks.filter { it.priority.equals("HIGH", ignoreCase = true) }

        val birthdays = facts.filter {
            val text = "${it.title} ${it.details}"
            text.contains("birthday", ignoreCase = true) ||
            text.contains("anniversary", ignoreCase = true) ||
            text.contains("event", ignoreCase = true)
        }

        val highlights = facts.filter { !birthdays.contains(it) }.take(3)

        val recommendation = when {
            highPriorityTasks.isNotEmpty() -> "Focus immediately on critical high priority item: \"${highPriorityTasks.first().title}\"."
            pendingTasks.isNotEmpty() -> "Clear pending task: \"${pendingTasks.first().title}\" early today."
            else -> "All task queues clear! Excellent opportunity for strategic planning and deep learning."
        }

        val duration = System.currentTimeMillis() - startTime
        val nowFormatted = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

        _diagnostics.value = _diagnostics.value.copy(
            lastExecutionTime = nowFormatted,
            lastExecutionDurationMs = duration,
            totalExecutions = _diagnostics.value.totalExecutions + 1,
            lastErrorMessage = null
        )

        return ExecutiveBriefingContent(
            dateString = contextState.currentDate,
            pendingTasksCount = pendingTasks.size,
            highPriorityCount = highPriorityTasks.size,
            topTasks = pendingTasks.take(5),
            birthdaysAndEvents = birthdays,
            memoryHighlights = highlights,
            focusRecommendation = recommendation
        )
    }
}
