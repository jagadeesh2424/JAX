package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.data.TaskRepository
import org.json.JSONArray
import org.json.JSONObject

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
        return ToolResult.ok("Created task \"${task.title}\" (id ${task.id}).")
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
}
