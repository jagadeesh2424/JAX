package com.jax.assistant.ai.agent

import org.json.JSONArray
import org.json.JSONObject

// Provenance of a piece of context/content. Thin now; the Phase 4/5 Dual-LLM enforcement
// keys off this (S6 seam) so untrusted content can never gain tool authority later.
enum class TrustLevel { TRUSTED, UNTRUSTED }

// Capability risk taxonomy driving the permission gate. Ordinal order is the severity order,
// so a policy can express "confirm anything above LOW_WRITE" as a comparison.
//   READ        - no side effects (time, weather, searches)                 -> LOW risk
//   LAUNCH      - opens an app the user then controls (Maps, dialer, clock)  -> LOW risk
//   LOW_WRITE   - reversible personal-data writes (task, reminder, memory)   -> MEDIUM risk
//   SENSITIVE   - acts on the outside world (send message, book, purchase)   -> HIGH risk
//   DESTRUCTIVE - irreversible data loss (delete, cancel)                    -> HIGH risk
enum class ToolRisk { READ, LAUNCH, LOW_WRITE, SENSITIVE, DESTRUCTIVE }

enum class RiskTier { LOW, MEDIUM, HIGH }

val ToolRisk.tier: RiskTier
    get() = when (this) {
        ToolRisk.READ, ToolRisk.LAUNCH -> RiskTier.LOW
        ToolRisk.LOW_WRITE -> RiskTier.MEDIUM
        ToolRisk.SENSITIVE, ToolRisk.DESTRUCTIVE -> RiskTier.HIGH
    }

// JSON-Schema-friendly parameter descriptor. Kept declarative so a native
// function-calling provider or an MCP server can expose the same tool unchanged (S4 seam).
data class ToolParam(
    val name: String,
    val type: String,          // "string" | "number" | "boolean"
    val description: String,
    val required: Boolean = false
)

// Uniform result every tool returns. `data` carries structured output (e.g. task ids)
// back to the planner and verifier.
data class ToolResult(
    val success: Boolean,
    val message: String,
    val data: JSONObject? = null,
    // false when repeating the call cannot help (bad input, blocked address).
    val retryable: Boolean = true
) {
    val error: String? get() = if (success) null else message

    companion object {
        fun ok(message: String, data: JSONObject? = null) = ToolResult(true, message, data)
        fun error(message: String) = ToolResult(false, message, null)
        fun fatal(message: String) = ToolResult(false, message, null, retryable = false)
    }
}

enum class VerificationStatus {
    VERIFIED,   // independently confirmed (e.g. the task was read back from the database)
    UNVERIFIED, // the tool reported success but offers no independent check
    FAILED      // the independent check contradicts the reported result
}

data class Verification(val status: VerificationStatus, val detail: String) {
    companion object {
        fun verified(detail: String) = Verification(VerificationStatus.VERIFIED, detail)
        fun failed(detail: String) = Verification(VerificationStatus.FAILED, detail)
        fun unverified(detail: String = "No independent check available") =
            Verification(VerificationStatus.UNVERIFIED, detail)
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
    val risk: ToolRisk get() = if (isDestructive) ToolRisk.DESTRUCTIVE else ToolRisk.LOW_WRITE

    // Durable user-memory writes are subject to MemoryPolicy (explicit user facts only).
    val writesDurableMemory: Boolean get() = false

    // Argument-aware risk (e.g. "mark done" vs "delete"). The controller always uses this.
    fun riskFor(args: JSONObject): ToolRisk = risk

    suspend fun execute(args: JSONObject): ToolResult

    // Independent post-condition check. Never trusts the model's claim of success.
    suspend fun verify(args: JSONObject, result: ToolResult): Verification = Verification.unverified()

    // Idempotency check used before resuming an interrupted write: true = the effect already
    // exists, false = it does not, null = cannot tell (so the write must not be repeated).
    suspend fun alreadyDone(args: JSONObject): Boolean? = null
}

val JaxTool.isReadOnly: Boolean get() = risk == ToolRisk.READ

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
        .put("tier", risk.tier.name)
        .put("kind", if (isReadOnly) "READ" else "ACTION")
        .put("inputSchema", inputSchema)
}

