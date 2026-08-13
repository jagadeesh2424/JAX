package com.jax.assistant.ai.agent

// Holds all available tools and renders their catalog for the planner prompt (S4 seam).
// A future McpProvider can register remote tools into this same registry with no loop changes.
class ToolRegistry(tools: List<JaxTool>) {

    private val byName: Map<String, JaxTool> = tools.associateBy { it.name }

    fun get(name: String): JaxTool? = byName[name]

    fun all(): List<JaxTool> = byName.values.toList()

    // JSON-schema-style catalog injected into the planner prompt.
    fun catalogText(): String = byName.values.joinToString("\n") { tool ->
        val params = tool.parameters.joinToString(", ") { p ->
            "${p.name} (${p.type}${if (p.required) ", required" else ""}): ${p.description}"
        }
        "- ${tool.name}: ${tool.description} | args: [$params]"
    }
}
