package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.data.MemoryRepository
import org.json.JSONArray
import org.json.JSONObject

// Save a durable fact/memory about the user.
class StoreMemoryTool(private val memory: MemoryRepository) : JaxTool {
    override val name = "store_memory"
    override val description = "Save a durable fact or memory about the user (preferences, IDs, people, etc.)."
    override val parameters = listOf(
        ToolParam("title", "string", "Short title for the memory", true),
        ToolParam("category", "string", "Personal, Work, Finance, Health, or Tech"),
        ToolParam("details", "string", "The full fact/memory content", true)
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val title = args.optString("title").trim()
        val details = args.optString("details").trim()
        if (title.isBlank() || details.isBlank()) return ToolResult.error("title and details are required")
        val fact = memory.createManualFact(
            title = title,
            category = args.optString("category", "Personal").ifBlank { "Personal" },
            details = details
        )
        return ToolResult.ok("Saved memory \"${fact.title}\".")
    }
}

// Search saved facts/memories by keyword.
class SearchMemoryTool(private val memory: MemoryRepository) : JaxTool {
    override val name = "search_memory"
    override val description = "Search the user's saved facts/memories by keyword."
    override val parameters = listOf(
        ToolParam("query", "string", "Keyword to search titles and details", true)
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult.error("query is required")
        val matched = memory.searchFactsSnapshot(query)
        if (matched.isEmpty()) return ToolResult.ok("No memories match \"$query\".")
        val arr = JSONArray()
        matched.take(20).forEach {
            arr.put(
                JSONObject()
                    .put("title", it.title)
                    .put("category", it.category)
                    .put("details", it.details)
            )
        }
        return ToolResult.ok("Found ${matched.size} memory item(s).", JSONObject().put("memories", arr))
    }
}
