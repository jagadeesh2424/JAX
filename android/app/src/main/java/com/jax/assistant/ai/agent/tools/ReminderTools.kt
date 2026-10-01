package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.Verification
import com.jax.assistant.data.TaskRepository
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters
import java.util.Locale

data class ReminderRequest(val title: String, val date: LocalDate, val time: LocalTime)

// Deterministic parser for common reminder phrasings. Returns null whenever it is unsure,
// so ambiguous requests fall through to the agent instead of guessing.
object ReminderParser {

    private val trigger = Regex("^(?:please\\s+)?(?:remind me|(?:create|set|add|make)\\s+(?:a|an|me a)?\\s*reminder)\\b(.*)$")
    private val timePattern = Regex("\\b(?:at\\s+)?(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|a\\.m\\.|p\\.m\\.)|\\bat\\s+(\\d{1,2})[:.](\\d{2})\\b")
    private val isoDate = Regex("\\b(\\d{4}-\\d{2}-\\d{2})\\b")
    private val weekday = Regex("\\b(?:on\\s+)?(?:next\\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday)\\b")

    fun parse(input: String, now: LocalDateTime): ReminderRequest? {
        val lower = input.trim().lowercase(Locale.US).trimEnd('.', '!', '?')
        val body = trigger.find(lower)?.groupValues?.get(1)?.trim() ?: return null

        val timeMatch = timePattern.find(body) ?: return null
        val time = parseTime(timeMatch) ?: return null

        val (explicitDate, rest) = extractDate(body.removeRange(timeMatch.range), now.toLocalDate()) ?: return null
        // No day given: the next occurrence of that time.
        val date = explicitDate
            ?: if (time.isAfter(now.toLocalTime())) now.toLocalDate() else now.toLocalDate().plusDays(1)

        return ReminderRequest(restoreCasing(input.trim(), extractTitle(rest)), date, time)
    }

    // Recovers the user's original capitalisation (e.g. names) for a title parsed from lowercased text.
    fun restoreCasing(original: String, title: String): String {
        val idx = original.lowercase(Locale.US).indexOf(title.lowercase(Locale.US))
        val cased = if (idx >= 0 && idx + title.length <= original.length) original.substring(idx, idx + title.length) else title
        return cased.replaceFirstChar { it.titlecase(Locale.US) }
    }

    // Finds and removes a day phrase (today, tomorrow, a weekday, YYYY-MM-DD). Returns the date
    // (null when no day is mentioned) and the remaining text, or null for an invalid date.
    fun extractDate(text: String, today: LocalDate): Pair<LocalDate?, String>? {
        var rest = text
        val date: LocalDate? = when {
            "day after tomorrow" in rest -> today.plusDays(2).also { rest = rest.replace("day after tomorrow", " ") }
            Regex("\\btomorrow\\b").containsMatchIn(rest) -> today.plusDays(1).also { rest = rest.replace(Regex("\\btomorrow\\b"), " ") }
            Regex("\\b(tonight|today)\\b").containsMatchIn(rest) -> today.also { rest = rest.replace(Regex("\\b(tonight|today)\\b"), " ") }
            isoDate.containsMatchIn(rest) -> {
                val match = isoDate.find(rest)!!
                rest = rest.removeRange(match.range)
                runCatching { LocalDate.parse(match.groupValues[1]) }.getOrNull() ?: return null
            }
            weekday.containsMatchIn(rest) -> {
                val match = weekday.find(rest)!!
                rest = rest.removeRange(match.range)
                today.with(TemporalAdjusters.next(DayOfWeek.valueOf(match.groupValues[1].uppercase(Locale.US))))
            }
            else -> null
        }
        return date to rest
    }

    // A time anywhere in the text ("6 pm", "at 18:30", or a bare "18:30") and where it was found.
    fun findTime(text: String): Pair<LocalTime, IntRange>? {
        val lower = text.lowercase(Locale.US)
        timePattern.find(lower)?.let { m -> parseTime(m)?.let { return it to m.range } }
        val clock = bareClock.find(lower) ?: return null
        return LocalTime.of(clock.groupValues[1].toInt(), clock.groupValues[2].toInt()) to clock.range
    }

    private val bareClock = Regex("\\b([01]?\\d|2[0-3])[:.]([0-5]\\d)\\b")

    private fun parseTime(match: MatchResult): LocalTime? {
        val g = match.groupValues
        return if (g[4].isNotEmpty()) {
            val hour = g[4].toInt()
            val minute = g[5].toInt()
            if (hour in 0..23 && minute in 0..59) LocalTime.of(hour, minute) else null
        } else {
            var hour = g[1].toInt()
            val minute = g[2].toIntOrNull() ?: 0
            if (hour !in 1..12 || minute !in 0..59) return null
            val pm = g[3].startsWith("p")
            if (pm && hour < 12) hour += 12
            if (!pm && hour == 12) hour = 0
            LocalTime.of(hour, minute)
        }
    }

    fun extractTitle(rest: String): String {
        val cleaned = rest
            .replace(Regex("\\b(for|on|at|by)\\b\\s*$"), " ")
            .replace(Regex("^\\s*(to|that|about|for|on|at)\\b"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .removePrefix("to ")
            .trim()
        return cleaned.ifBlank { "Reminder" }.replaceFirstChar { it.titlecase(Locale.US) }
    }
}

// Schedules the reminder notification. Returns true only when the scheduler accepted it.
interface ReminderScheduler {
    suspend fun schedule(taskId: String, title: String, at: LocalDateTime): Boolean
}

// A reminder = a task due on the reminder date + a notification at the exact time.
// The time is kept in the title because tasks store a date only.
class CreateReminderTool(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.systemDefault()) }
) : JaxTool {
    override val name = "create_reminder"
    override val description = "Create a reminder that notifies the user at a specific date and time."
    override val parameters = listOf(
        ToolParam("title", "string", "What to remind the user about", true),
        ToolParam("date", "string", "Date as YYYY-MM-DD", true),
        ToolParam("time", "string", "24-hour time as HH:mm", true)
    )
    override val isDestructive = false
    override val risk = ToolRisk.LOW_WRITE

    override suspend fun execute(args: JSONObject): ToolResult {
        val date = runCatching { LocalDate.parse(args.optString("date").trim()) }.getOrNull()
            ?: return ToolResult.error("date must be YYYY-MM-DD")
        val time = runCatching { LocalTime.parse(args.optString("time").trim()) }.getOrNull()
            ?: return ToolResult.error("time must be HH:mm")
        val at = LocalDateTime.of(date, time)
        if (!at.isAfter(now())) return ToolResult.error("that time has already passed")

        val hhmm = time.toString()
        val title = "${args.optString("title").trim()} @ $hhmm"
        val task = tasks.createManualTask(title, "Personal", "MED", date.toString())
        // Never schedule a notification for a task that did not persist.
        if (tasks.getAllTasksSnapshot().none { it.id == task.id }) {
            return ToolResult.error("the reminder task could not be saved")
        }
        val scheduled = scheduler.schedule(task.id, title, at)
        val message = if (scheduled) "Reminder set for ${date} at $hhmm: \"${args.optString("title").trim()}\"."
        else "I saved \"$title\" as a task for $date, but couldn't schedule the $hhmm notification."
        return ToolResult(
            success = scheduled,
            message = message,
            data = JSONObject()
                .put("task_id", task.id)
                .put("title", title)
                .put("date", date.toString())
                .put("time", hhmm)
                .put("scheduled", scheduled)
        )
    }

    // Independent read-back: the task must exist with the requested date and time.
    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val data = result.data ?: return Verification.failed("no reminder data")
        val stored = tasks.getAllTasksSnapshot().firstOrNull { it.id == data.optString("task_id") }
            ?: return Verification.failed("the reminder task was not saved")
        return when {
            stored.deadline != data.optString("date") -> Verification.failed("saved with the wrong date")
            !stored.title.endsWith("@ ${data.optString("time")}") -> Verification.failed("saved with the wrong time")
            !data.optBoolean("scheduled") -> Verification.failed("notification not scheduled")
            else -> Verification.verified("task saved for ${stored.deadline} and notification scheduled")
        }
    }

    override suspend fun alreadyDone(args: JSONObject): Boolean {
        val title = "${args.optString("title").trim()} @ ${args.optString("time").trim()}"
        return tasks.getAllTasksSnapshot().any { it.title.equals(title, ignoreCase = true) && it.deadline == args.optString("date").trim() }
    }
}

// Changes the date and/or time of an existing task or reminder. The notification is keyed by task
// id, so rescheduling replaces it instead of adding a second one.
class RescheduleReminderTool(
    private val tasks: TaskRepository,
    private val scheduler: ReminderScheduler,
    private val now: () -> LocalDateTime = { LocalDateTime.now(ZoneId.systemDefault()) }
) : JaxTool {
    override val name = "reschedule_reminder"
    override val description = "Change the date and/or time of an existing task or reminder (by task id)."
    override val parameters = listOf(
        ToolParam("task_id", "string", "Id of the task or reminder", true),
        ToolParam("date", "string", "New date as YYYY-MM-DD (optional)"),
        ToolParam("time", "string", "New 24-hour time as HH:mm (optional)")
    )
    override val isDestructive = false
    override val risk = ToolRisk.LOW_WRITE

    override suspend fun execute(args: JSONObject): ToolResult {
        val id = args.optString("task_id").trim()
        val task = tasks.getAllTasksSnapshot().firstOrNull { it.id == id }
            ?: return ToolResult.fatal("No task found with id $id")
        val requestedDate = args.optString("date").trim()
        val requestedTime = args.optString("time").trim()
        if (requestedDate.isBlank() && requestedTime.isBlank()) return ToolResult.fatal("give a new date or time")

        val base = baseTitle(task.title)
        val time = if (requestedTime.isBlank()) existingTime(task.title)
        else runCatching { LocalTime.parse(requestedTime) }.getOrNull() ?: return ToolResult.fatal("time must be HH:mm")
        val today = now().toLocalDate()
        val date = when {
            requestedDate.isNotBlank() ->
                runCatching { LocalDate.parse(requestedDate) }.getOrNull() ?: return ToolResult.fatal("date must be YYYY-MM-DD")
            task.deadline != null -> runCatching { LocalDate.parse(task.deadline) }.getOrNull() ?: today
            time != null && LocalDateTime.of(today, time).isAfter(now()) -> today
            else -> today.plusDays(1)
        }
        if (time != null && !LocalDateTime.of(date, time).isAfter(now())) return ToolResult.fatal("that time has already passed")

        val title = if (time != null) "$base @ $time" else base
        tasks.updateTask(task.copy(title = title, deadline = date.toString()))
        val scheduled = time?.let { scheduler.schedule(task.id, title, LocalDateTime.of(date, it)) } ?: true
        val whenText = if (time != null) "$date at $time" else "$date"
        return ToolResult(
            success = scheduled,
            message = if (scheduled) "Moved \"$base\" to $whenText." else "I updated \"$base\" to $whenText but couldn't schedule the notification.",
            data = JSONObject()
                .put("task_id", task.id)
                .put("title", base)
                .put("date", date.toString())
                .put("time", time?.toString() ?: "")
                .put("scheduled", scheduled)
        )
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val data = result.data ?: return Verification.failed("no reminder data")
        val stored = tasks.getAllTasksSnapshot().firstOrNull { it.id == data.optString("task_id") }
            ?: return Verification.failed("the task no longer exists")
        val time = data.optString("time")
        return when {
            stored.deadline != data.optString("date") -> Verification.failed("saved with the wrong date")
            time.isNotBlank() && !stored.title.endsWith("@ $time") -> Verification.failed("saved with the wrong time")
            !data.optBoolean("scheduled") -> Verification.failed("notification not scheduled")
            else -> Verification.verified("task updated and read back")
        }
    }

    override suspend fun alreadyDone(args: JSONObject): Boolean {
        val stored = tasks.getAllTasksSnapshot().firstOrNull { it.id == args.optString("task_id").trim() } ?: return false
        val date = args.optString("date").trim()
        val time = args.optString("time").trim()
        return (date.isBlank() || stored.deadline == date) && (time.isBlank() || stored.title.endsWith("@ $time"))
    }

    companion object {
        private val timeSuffix = Regex("\\s*@\\s*(\\d{1,2}:\\d{2})$")

        fun baseTitle(title: String): String = title.replace(timeSuffix, "").trim()

        fun existingTime(title: String): LocalTime? =
            timeSuffix.find(title)?.groupValues?.get(1)?.let { runCatching { LocalTime.parse(it.padStart(5, '0')) }.getOrNull() }
    }
}
