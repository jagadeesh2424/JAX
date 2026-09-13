package com.jax.assistant.ai.agent

import com.jax.assistant.db.TaskEntity
import java.time.LocalDate

// Proactivity autonomy ladder (Priority #5), from least to most autonomous. Ordinal order
// is the escalation order, so callers can compare levels (e.g. level >= RECOMMEND).
//   OFF       - do nothing
//   NOTIFY    - surface an observation only
//   RECOMMEND - surface an observation plus a suggested next action
//   ASK       - propose the action and require the user's confirmation before acting
//   ACT       - allowed to act autonomously (still passes the tool permission gate)
enum class AutonomyLevel { OFF, NOTIFY, RECOMMEND, ASK, ACT }

// A single proactive signal and, above NOTIFY, what J.A.X. proposes to do about it.
data class ProactiveSuggestion(
    val observation: String,
    val suggestedAction: String?,
    val requiresConfirmation: Boolean,
    val autoActable: Boolean
)

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

    // Structured proactive suggestions gated by the autonomy ladder. OFF yields nothing;
    // higher levels progressively attach a suggested action and consent requirements.
    fun suggest(tasks: List<TaskEntity>, today: LocalDate, level: AutonomyLevel): List<ProactiveSuggestion> {
        if (level == AutonomyLevel.OFF) return emptyList()
        val openTasks = tasks.filter { !it.isCompleted }
        val suggestions = mutableListOf<ProactiveSuggestion>()

        val overdue = openTasks.filter { task -> task.deadline?.let { parseDate(it)?.isBefore(today) } == true }
        if (overdue.isNotEmpty()) {
            suggestions += buildSuggestion(
                observation = "${overdue.size} task(s) are overdue, starting with \"${overdue.first().title}\".",
                action = "Reschedule \"${overdue.first().title}\" or mark it done.",
                level = level
            )
        }

        val dueToday = openTasks.filter { it.deadline == today.toString() }
        if (dueToday.isNotEmpty()) {
            suggestions += buildSuggestion(
                observation = "${dueToday.size} task(s) are due today, starting with \"${dueToday.first().title}\".",
                action = "Prioritise \"${dueToday.first().title}\" for today.",
                level = level
            )
        }

        if (overdue.isEmpty() && dueToday.isEmpty()) {
            val highPriority = openTasks.filter { it.priority.equals("HIGH", ignoreCase = true) }
            if (highPriority.isNotEmpty()) {
                suggestions += buildSuggestion(
                    observation = "${highPriority.size} HIGH priority task(s) are open.",
                    action = "Begin with \"${highPriority.first().title}\".",
                    level = level
                )
            }
        }
        return suggestions
    }

    private fun buildSuggestion(observation: String, action: String, level: AutonomyLevel): ProactiveSuggestion =
        ProactiveSuggestion(
            observation = observation,
            // NOTIFY only informs; from RECOMMEND up we attach the proposed action.
            suggestedAction = if (level.ordinal >= AutonomyLevel.RECOMMEND.ordinal) action else null,
            requiresConfirmation = level == AutonomyLevel.ASK,
            autoActable = level == AutonomyLevel.ACT
        )

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()
}