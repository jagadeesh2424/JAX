package com.jax.assistant.ai.agent

import org.json.JSONObject

// Non-LLM gate every tool call passes through (security + future risk-based autonomy).
// Phase 1 enforces a simple allow policy; the confirmation ("Ask") flow for destructive
// actions arrives with the Phase 4 autonomy engine without changing the orchestrator.
class AgentController {

    enum class Decision { ALLOW, DENY }

    fun authorize(tool: JaxTool, args: JSONObject): Decision {
        // All registered tools are allowed for now. Destructive tools are flagged via
        // JaxTool.isDestructive so a confirmation step can be inserted here later.
        return Decision.ALLOW
    }
}
