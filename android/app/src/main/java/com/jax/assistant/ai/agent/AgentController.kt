package com.jax.assistant.ai.agent

import org.json.JSONObject

// Deterministic, non-LLM gate every tool call passes through, whether it came from the fast
// router, the planner, or the agent loop. The model can propose actions; only this decides.
class AgentController(
    // Confirmation is required for any tool whose risk is strictly above this threshold.
    // Default: READ/LAUNCH/LOW_WRITE run freely; SENSITIVE/DESTRUCTIVE need consent.
    private val confirmAboveRisk: ToolRisk = ToolRisk.LOW_WRITE
) {

    enum class Decision { ALLOW, CONFIRM, DENY }

    // Uses only the tool's own classification of the arguments; any "confirmed"/"approved"
    // flag the model puts into args is ignored.
    fun riskFor(tool: JaxTool, args: JSONObject): ToolRisk = tool.riskFor(args)

    fun authorize(tool: JaxTool, args: JSONObject): Decision {
        val risk = riskFor(tool, args)
        return when {
            risk.tier == RiskTier.HIGH -> Decision.CONFIRM
            risk.ordinal > confirmAboveRisk.ordinal -> Decision.CONFIRM
            else -> Decision.ALLOW
        }
    }
}
