package com.jax.assistant.ai.agent

import org.json.JSONObject

// Non-LLM gate every tool call passes through (security + future risk-based autonomy).
// Phase 1 enforces a simple allow policy; the confirmation ("Ask") flow for destructive
// actions arrives with the Phase 4 autonomy engine without changing the orchestrator.
class AgentController {

    enum class Decision { ALLOW, DENY }
    enum class ActionRisk { LOW, HIGH }

    // The classification is deliberately separate from enforcement. Phase 4 can add a
    // confirmation UI without changing tools or the orchestration loop again.
    fun riskFor(tool: JaxTool, args: JSONObject): ActionRisk =
        if (tool.isDestructive) ActionRisk.HIGH else ActionRisk.LOW

    fun authorize(tool: JaxTool, args: JSONObject): Decision {
        riskFor(tool, args)
        // Current assistant mode is user-directed: no background action path exists yet.
        // Confirmation enforcement is introduced with the corresponding UI interaction.
        return Decision.ALLOW
    }
}
