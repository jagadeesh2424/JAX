package com.jax.assistant.ai.agent

import org.json.JSONObject

// Provenance of a piece of context/content. Thin now; the Phase 4/5 Dual-LLM enforcement
// keys off this (S6 seam) so untrusted content can never gain tool authority later.
enum class TrustLevel { TRUSTED, UNTRUSTED }

// JSON-Schema-friendly parameter descriptor. Kept declarative so a native
// function-calling provider or an MCP server can expose the same tool unchanged (S4 seam).
data class ToolParam(
    val name: String,
    val type: String,          // "string" | "number" | "boolean"
    val description: String,
    val required: Boolean = false
)

// Uniform result every tool returns. `data` carries structured output (e.g. task ids)
// back to the planner so it can chain the next step.
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null
) {
    companion object {
        fun ok(message: String, data: JSONObject? = null) = ToolResult(true, message, data)
        fun error(message: String) = ToolResult(false, message, null)
    }
}

// A single capability JAX can invoke. Definitions are transport-agnostic so the same
// tool works over the current JSON loop today and native/MCP providers later.
interface JaxTool {
    val name: String
    val description: String
    val parameters: List<ToolParam>
    val isDestructive: Boolean

    suspend fun execute(args: JSONObject): ToolResult
}
