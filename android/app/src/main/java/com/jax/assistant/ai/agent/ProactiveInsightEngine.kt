package com.jax.assistant.ai.agent

import com.jax.assistant.ai.agent.tools.WeatherSnapshot
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.TaskEntity
import org.json.JSONObject
import java.time.LocalDate

// Proactivity autonomy ladder (Priority #5), from least to most autonomous. Ordinal order
// is the escalation order, so callers can compare levels (e.g. level >= RECOMMEND).
//   OFF       - do nothing
//   NOTIFY    - surface an observation only
//   RECOMMEND - surface an observation plus a suggested next action
//   ASK       - propose the action and require the user's confirmation before acting
//   ACT       - allowed to act autonomously (still passes the tool permission gate)
enum class AutonomyLevel { OFF, NOTIFY, RECOMMEND, ASK, ACT }

// importance/urgency: 1 (low) .. 3 (high). dedupKey + cooldownHours keep it from repeating.
data class ProactiveSuggestion(
    val observation: String,
    val suggestedAction: String?,
    val requiresConfirmation: Boolean,
    val autoActable: Boolean,
    val confidence: Double = 1.0,
    val importance: Int = 2,
    val urgency: Int = 2,
    val reason: String = "",
    val dedupKey: String = observation,
    val cooldownHours: Int = 24
)

// Read-only proactive signals. This never creates, modifies, or completes anything
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
    // higher levels attach a suggested next step, phrased as a question and never auto-executed.
    // Each suggestion carries its reason and confidence so it can be explained and filtered.
    fun suggest(
        tasks: List<TaskEntity>,
        today: LocalDate,
        level: AutonomyLevel,
        weather: WeatherSnapshot? = null,
        recentRuns: List<AgentRunEntity> = emptyList(),
        nowMillis: Long = System.currentTimeMillis()
    ): List<ProactiveSuggestion> {
        if (level == AutonomyLevel.OFF) return emptyList()
        val openTasks = tasks.filter { !it.isCompleted }
        val suggestions = mutableListOf<ProactiveSuggestion>()

        val overdue = openTasks.filter { task -> task.deadline?.let { parseDate(it)?.isBefore(today) } == true }
        if (overdue.isNotEmpty()) {
            suggestions += buildSuggestion(
                observation = "${overdue.size} task(s) are overdue, starting with \"${overdue.first().title}\".",
                action = "Would you like to reschedule \"${overdue.first().title}\" or mark it done?",
                level = level,
                importance = 3,
                urgency = 3,
                reason = "deadline passed",
                dedupKey = "overdue:${overdue.first().id}:${overdue.size}",
                cooldownHours = 20
            )
        }

        val dueToday = openTasks.filter { it.deadline == today.toString() }
        if (dueToday.isNotEmpty()) {
            suggestions += buildSuggestion(
                observation = "${dueToday.size} task(s) are due today, starting with \"${dueToday.first().title}\".",
                action = "Would you like to prioritise \"${dueToday.first().title}\" for today?",
                level = level,
                importance = 2,
                urgency = 3,
                reason = "due today",
                dedupKey = "due:$today"
            )
        }

        if (weather != null && weather.rainChancePercent >= RAIN_ALERT_PERCENT) {
            val dueThatDay = openTasks.filter { it.deadline == weather.date.toString() }
            if (dueThatDay.isNotEmpty()) {
                suggestions += buildSuggestion(
                    observation = "Rain is likely in ${weather.location} on ${weather.date} (${weather.rainChancePercent}%), " +
                        "and you have ${dueThatDay.size} task(s) due then, starting with \"${dueThatDay.first().title}\".",
                    action = "Would you like me to set a reminder to leave earlier?",
                    level = level,
                    importance = 2,
                    urgency = if (weather.date == today) 3 else 2,
                    reason = "rain forecast overlaps scheduled tasks",
                    dedupKey = "rain:${weather.date}",
                    cooldownHours = 12,
                    confidence = weather.rainChancePercent / 100.0
                )
            }
        }

        if (overdue.isEmpty() && dueToday.isEmpty()) {
            val highPriority = openTasks.filter { it.priority.equals("HIGH", ignoreCase = true) }
            if (highPriority.isNotEmpty()) {
                suggestions += buildSuggestion(
                    observation = "${highPriority.size} HIGH priority task(s) are open.",
                    action = "Would you like to begin with \"${highPriority.first().title}\"?",
                    level = level,
                    importance = 2,
                    urgency = 1,
                    reason = "open high-priority work",
                    dedupKey = "high:${highPriority.first().id}",
                    cooldownHours = 48
                )
            }
        }

        val tomorrow = today.plusDays(1)
        val dueTomorrow = openTasks.filter { it.deadline == tomorrow.toString() }
        if (dueTomorrow.isNotEmpty()) {
            suggestions += buildSuggestion(
                observation = "${dueTomorrow.size} task(s) are due tomorrow and still open, starting with \"${dueTomorrow.first().title}\".",
                action = "Would you like to set aside time for \"${dueTomorrow.first().title}\" today?",
                level = level,
                importance = 2,
                urgency = 2,
                reason = "deadline tomorrow, not yet completed",
                dedupKey = "due-tomorrow:$tomorrow:${dueTomorrow.first().id}",
                cooldownHours = 20
            )
        }

        tripWithoutWeatherCheck(recentRuns, nowMillis)?.let { (run, destination) ->
            suggestions += buildSuggestion(
                observation = "You recently planned \"${run.goal.take(60)}\" but haven't checked the weather for $destination.",
                action = "Would you like me to check the weather in $destination?",
                level = level,
                importance = 2,
                urgency = 1,
                reason = "trip planned without a weather check",
                dedupKey = "trip-weather:${run.id}",
                cooldownHours = 48,
                confidence = TRIP_CONFIDENCE
            )
        }
        return suggestions
    }

    // Most recent completed trip-like run (last 3 days) that never called the weather tool.
    private fun tripWithoutWeatherCheck(runs: List<AgentRunEntity>, nowMillis: Long): Pair<AgentRunEntity, String>? =
        runs.asSequence()
            .filter { it.status.startsWith("COMPLETED") && nowMillis - it.finishedAt in 0..TRIP_LOOKBACK_MS }
            .filter { tripGoal.containsMatchIn(it.goal.lowercase()) && !it.toolsUsed.contains("get_weather") }
            .sortedByDescending { it.finishedAt }
            .mapNotNull { run -> destinationOf(run)?.let { run to it } }
            .firstOrNull()

    private fun destinationOf(run: AgentRunEntity): String? {
        val fromPlan = runCatching { JSONObject(run.plan).optJSONObject("slots")?.optString("destination") }.getOrNull()
        if (!fromPlan.isNullOrBlank()) return fromPlan
        return EntityExtractor.extract(run.goal, run.finishedAt, LocalDate.now())
            .firstOrNull { it.type == EntityType.PLACE }?.name
    }

    private fun buildSuggestion(
        observation: String,
        action: String,
        level: AutonomyLevel,
        importance: Int,
        urgency: Int,
        reason: String,
        dedupKey: String,
        cooldownHours: Int = 24,
        confidence: Double = 1.0
    ): ProactiveSuggestion =
        ProactiveSuggestion(
            observation = observation,
            suggestedAction = if (level.ordinal >= AutonomyLevel.RECOMMEND.ordinal) action else null,
            requiresConfirmation = level == AutonomyLevel.ASK,
            autoActable = level == AutonomyLevel.ACT,
            confidence = confidence,
            importance = importance,
            urgency = urgency,
            reason = reason,
            dedupKey = dedupKey,
            cooldownHours = cooldownHours
        )

    private fun parseDate(value: String): LocalDate? = runCatching { LocalDate.parse(value) }.getOrNull()

    private companion object {
        const val RAIN_ALERT_PERCENT = 60
        const val TRIP_CONFIDENCE = 0.7
        const val TRIP_LOOKBACK_MS = 3L * 24 * 60 * 60 * 1000
        val tripGoal = Regex("\\b(trip|travel|visit|vacation|holiday|itinerary|getaway)\\b")
    }
}

// Anti-spam gate: drops low-confidence insights, anything still in its cooldown window, and
// duplicates; then keeps only the most important few.
object InsightGate {
    fun select(
        candidates: List<ProactiveSuggestion>,
        lastShownAt: Map<String, Long>,
        nowMillis: Long,
        minConfidence: Double = 0.6,
        max: Int = 3
    ): List<ProactiveSuggestion> = candidates
        .filter { it.confidence >= minConfidence }
        .filter { s -> lastShownAt[s.dedupKey]?.let { nowMillis - it >= s.cooldownHours * HOUR_MS } ?: true }
        .distinctBy { it.dedupKey }
        .sortedByDescending { it.importance * it.urgency * it.confidence }
        .take(max)

    private const val HOUR_MS = 60L * 60L * 1000L
}