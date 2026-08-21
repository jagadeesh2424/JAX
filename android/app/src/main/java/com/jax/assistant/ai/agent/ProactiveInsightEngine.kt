package com.jax.assistant.ai.agent

import com.jax.assistant.db.TaskEntity
import java.time.LocalDate

// Read-only proactive signals. This never creates, modifies, or completes a task.
class ProactiveInsightEngine {
    fun dailyBriefing(tasks: List<TaskEntity>, today: LocalDate, useTaskContext: Boolean = true): String {
        val openTasks = tasks.filter { !it.isCompleted }
        if (!useTaskContext) {
            val highPriority = openTasks.filter { it.priority.equals("HIGH", ignoreCase = true) }
            return if (highPriority.isNotEmpty()) {
                "Good morning Jagadeesh. You have ${highPriority.size} HIGH priority task(s) scheduled."
            } else {
                "Good morning Jagadeesh. All high priority tasks are clear. Ready for new briefings."
            }
        }
        val overdue = openTasks.filter { task -> task.deadline?.let { parseDate(it)?.isBefore(today) } == true }
        if (overdue.isNotEmpty()) {
            return "Good morning Jagadeesh. ${overdue.size} task(s) are overdue; start with ${overdue.first().title}."
        }

        val dueToday = openTasks.filter { it.deadline == today.toString() }
        if (dueToday.isNotEmpty()) {
            return "Good morning Jagadeesh. ${dueToday.size} task(s) are due today; start with ${dueToday.first().title}."
        }

        val highPriority = openTasks.filter { it.priority.equals("HIGH", ignoreCase = true) }
        return if (highPriority.isNotEmpty()) {
            "Good morning Jagadeesh. You have ${highPriority.size} HIGH priority task(s); start with ${highPriority.first().title}."
        } else {
            "Good morning Jagadeesh. Your priority queue is clear. Ready for new briefings."
        }
    }

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}