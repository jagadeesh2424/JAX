package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.Verification
import com.jax.assistant.data.TaskRepository
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.Locale

data class TaskRequest(val title: String, val deadline: LocalDate?)

// Deterministic parser for explicit task commands ("create a task to X [day]", "remind me to X
// tomorrow" with no time). Returns null for anything that needs judgement (questions, "decide",
// "which", "best"...) or lacks the needed details, so those go to Gemini instead.
object TaskParser {
    private val createTrigger = Regex(
        "^(?:please\\s+)?(?:create|add|make)\\s+(?:a\\s+|an\\s+)?(?:new\\s+)?(?:task|to-?do)\\s*(?:to|for|:)?\\s+(.+)$"
    )
    private val remindTrigger = Regex("^(?:please\\s+)?remind me\\s+(?:to\\s+)?(.+)$")
    private val needsReasoning = Regex("\\b(decide|figure out|work out|which|whether|best|should|suggest|recommend)\\b|\\?")

    fun parse(input: String, today: LocalDate): TaskRequest? {
        val lower = input.trim().lowercase(Locale.US).trimEnd('.', '!')
        val create = createTrigger.find(lower)?.groupValues?.get(1)?.trim()
        val remind = if (create == null) remindTrigger.find(lower)?.groupValues?.get(1)?.trim() else null
        val body = create ?: remind ?: return null
        if (needsReasoning.containsMatchIn(body)) return null
        val (deadline, rest) = ReminderParser.extractDate(body, today) ?: return null
        // "Remind me to X" with no day is ambiguous (when?), so let Gemini ask.
        if (remind != null && deadline == null) return null
        val title = ReminderParser.extractTitle(rest)
        return if (title == "Reminder") null else TaskRequest(ReminderParser.restoreCasing(input.trim(), title), deadline)
    }
}

// Create a new task/reminder.
class CreateTaskTool(private val tasks: TaskRepository) : JaxTool {
    override val name = "create_task"
    override val description = "Create a new task or reminder for the user."
    override val parameters = listOf(
        ToolParam("title", "string", "Short task title", true),
        ToolParam("category", "string", "Work, Personal, Finance, or General"),
        ToolParam("priority", "string", "HIGH, MED, or LOW"),
        ToolParam("deadline", "string", "Due date as YYYY-MM-DD, or empty")
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        if (title.isBlank()) return ToolResult.error("title is required")
        val task = tasks.createManualTask(
            title = title,
            category = args.optString("category", "General").ifBlank { "General" },
            priority = args.optString("priority", "MED").ifBlank { "MED" }.uppercase(),
            deadline = args.optString("deadline", "").ifBlank { null }
        )
        return ToolResult.ok(
            "Created task \"${task.title}\" (id ${task.id}).",
            JSONObject().put("task_id", task.id).put("deadline", task.deadline ?: "")
        )
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val id = result.data?.optString("task_id").orEmpty()
        val stored = tasks.getAllTasksSnapshot().firstOrNull { it.id == id }
            ?: return Verification.failed("the task was not saved")
        val wantedDeadline = args.optString("deadline", "").ifBlank { null }
        return if (stored.deadline == wantedDeadline) Verification.verified("task saved")
        else Verification.failed("task saved with the wrong due date")
    }

    override suspend fun alreadyDone(args: JSONObject): Boolean {
        val title = args.optString("title").trim()
        val deadline = args.optString("deadline", "").ifBlank { null }
        return tasks.getAllTasksSnapshot().any { it.title.equals(title, ignoreCase = true) && it.deadline == deadline }
    }
}

// List open tasks so the planner can find an id before completing/cancelling one.
class SearchTasksTool(private val tasks: TaskRepository) : JaxTool {
    override val name = "search_tasks"
    override val description =
        "List the user's tasks (open and completed), optionally filtered by a keyword. Each result has a 'completed' flag. Use this to get a task id before completing or deleting it."
    override val parameters = listOf(
        ToolParam("query", "string", "Optional keyword to filter task titles")
    )
    override val isDestructive = false
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query", "").trim().lowercase()
        val all = tasks.getAllTasksSnapshot()
        val matched = if (query.isBlank()) all else all.filter { it.title.lowercase().contains(query) }
        if (matched.isEmpty()) return ToolResult.ok("No matching tasks.")
        val arr = JSONArray()
        matched.take(20).forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("title", it.title)
                    .put("priority", it.priority)
                    .put("deadline", it.deadline ?: "")
                    .put("completed", it.isCompleted)
            )
        }
        return ToolResult.ok("Found ${matched.size} task(s).", JSONObject().put("tasks", arr))
    }
}

// Complete (mark done) or delete an existing task by id.
class CompleteTaskTool(private val tasks: TaskRepository) : JaxTool {
    override val name = "complete_task"
    override val description =
        "Complete or cancel an existing task by id (obtained from search_tasks). Set remove=true to delete it, otherwise it is marked done."
    override val parameters = listOf(
        ToolParam("id", "string", "The task id from search_tasks", true),
        ToolParam("remove", "boolean", "true to delete, false to mark completed")
    )
    override val isDestructive = true

    // Marking done is a reversible write; deleting is irreversible and always needs consent.
    override fun riskFor(args: JSONObject): ToolRisk =
        if (args.optBoolean("remove", false)) ToolRisk.DESTRUCTIVE else ToolRisk.LOW_WRITE

    override suspend fun execute(args: JSONObject): ToolResult {
        val id = args.optString("id").trim()
        if (id.isBlank()) return ToolResult.error("id is required (call search_tasks first)")
        val target = tasks.getAllTasksSnapshot().firstOrNull { it.id == id }
            ?: return ToolResult.error("No task found with id $id")
        return if (args.optBoolean("remove", false)) {
            tasks.deleteTask(target)
            ToolResult.ok("Deleted task \"${target.title}\".")
        } else {
            tasks.updateTask(target.copy(isCompleted = true))
            ToolResult.ok("Marked \"${target.title}\" as completed.")
        }
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val stored = tasks.getAllTasksSnapshot().firstOrNull { it.id == args.optString("id").trim() }
        return when {
            args.optBoolean("remove", false) ->
                if (stored == null) Verification.verified("task deleted") else Verification.failed("task still exists")
            stored?.isCompleted == true -> Verification.verified("task marked completed")
            else -> Verification.failed("task is not marked completed")
        }
    }
}
