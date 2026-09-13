package com.jax.assistant.ai.agent

import org.json.JSONObject

// Non-LLM gate every tool call passes through (security + risk-based autonomy).
// Classifies each call and returns the required decision; the orchestrator carries out
// CONFIRM by asking the user, keeping enforcement independent of any specific UI.
class AgentController(
    // Confirmation is required for any tool whose risk is strictly above this threshold.
    // Default: READ/LOW_WRITE run freely; SENSITIVE/DESTRUCTIVE require the user's consent.
    private val confirmAboveRisk: ToolRisk = ToolRisk.LOW_WRITE
) {

    enum class Decision { ALLOW, CONFIRM, DENY }

    fun riskFor(tool: JaxTool, args: JSONObject): ToolRisk = tool.risk

    fun authorize(tool: JaxTool, args: JSONObject): Decision {
        val risk = riskFor(tool, args)
        return if (risk.ordinal > confirmAboveRisk.ordinal) Decision.CONFIRM else Decision.ALLOW
    }
}
