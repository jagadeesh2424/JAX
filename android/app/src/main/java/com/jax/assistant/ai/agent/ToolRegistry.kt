package com.jax.assistant.ai.agent

import org.json.JSONArray

// The central capability system. Every route (fast router, planner, agent loop) resolves
// tools here, so a new capability is added by registering a JaxTool — no core changes.
class ToolRegistry(tools: List<JaxTool>) {

    private val byName: Map<String, JaxTool> = tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "Duplicate tool names in registry" }
    }

    fun get(name: String): JaxTool? = byName[name]

    fun all(): List<JaxTool> = byName.values.toList()

    fun readTools(): List<JaxTool> = byName.values.filter { it.isReadOnly }

    fun actionTools(): List<JaxTool> = byName.values.filterNot { it.isReadOnly }

    // Catalog injected into planner/agent prompts. READ tools return data; ACTION tools change something.
    fun catalogText(): String = byName.values.joinToString("\n") { tool ->
        val params = tool.parameters.joinToString(", ") { p ->
            "${p.name} (${p.type}${if (p.required) ", required" else ""}): ${p.description}"
        }
        val kind = if (tool.isReadOnly) "READ" else "ACTION/${tool.risk.tier}"
        "- ${tool.name} [$kind]: ${tool.description} | args: [$params]"
    }

    // Answer to "What can you do?", generated from the registry so it never goes stale.
    fun describeCapabilities(): String = buildString {
        append("Here's what I can do directly:\n")
        append("Look things up: ")
        append(readTools().joinToString("; ") { it.description.trimEnd('.') })
        append(".\nTake actions: ")
        append(actionTools().joinToString("; ") { it.description.trimEnd('.') })
        append(".\nFor anything else I reason with Gemini. High-risk actions always ask you first.")
    }

    // MCP tools/list-shaped catalog: an array of {name, description, risk, tier, kind, inputSchema}.
    fun toolsJsonSchema(): JSONArray = JSONArray().apply {
        byName.values.forEach { put(it.toJsonSchema()) }
    }
}
