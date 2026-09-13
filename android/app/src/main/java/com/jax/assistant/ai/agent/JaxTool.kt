package com.jax.assistant.ai.agent

import org.json.JSONArray
import org.json.JSONObject

// Provenance of a piece of context/content. Thin now; the Phase 4/5 Dual-LLM enforcement
// keys off this (S6 seam) so untrusted content can never gain tool authority later.
enum class TrustLevel { TRUSTED, UNTRUSTED }

// Capability risk taxonomy driving the permission gate (Priority #3). Ordinal order is the
// severity order, so a policy can express "confirm anything above LOW_WRITE" as a comparison.
//   READ        - no side effects (searches, reads)
//   LOW_WRITE   - reversible personal-data writes (create task, store memory, set timer)
//   SENSITIVE   - leaves the app / touches the outside world (dial, navigate)
//   DESTRUCTIVE - irreversible data loss (delete)
enum class ToolRisk { READ, LOW_WRITE, SENSITIVE, DESTRUCTIVE }

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

    // Finer-grained than isDestructive; defaults preserve existing tools without edits.
    // A tool overrides this to declare READ / SENSITIVE where the default is wrong.
    val risk: ToolRisk get() = if (isDestructive) ToolRisk.DESTRUCTIVE else ToolRisk.LOW_WRITE

    suspend fun execute(args: JSONObject): ToolResult
}

// MCP-style tool descriptor: the same shape an MCP server returns from tools/list, so a
// native function-calling / MCP transport can expose these tools with no changes (S4 seam).
fun JaxTool.toJsonSchema(): JSONObject {
    val properties = JSONObject()
    val required = JSONArray()
    for (p in parameters) {
        properties.put(
            p.name,
            JSONObject()
                .put("type", if (p.type == "number") "number" else if (p.type == "boolean") "boolean" else "string")
                .put("description", p.description)
        )
        if (p.required) required.put(p.name)
    }
    val inputSchema = JSONObject()
        .put("type", "object")
        .put("properties", properties)
        .put("required", required)
    return JSONObject()
        .put("name", name)
        .put("description", description)
        .put("risk", risk.name)
        .put("inputSchema", inputSchema)
}

