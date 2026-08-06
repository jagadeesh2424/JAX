package com.jax.assistant.executive.agent

import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.planner.PlanResult

data class AgentExecutionResult(
    val success: Boolean,
    val outputMessage: String,
    val data: Map<String, Any> = emptyMap()
)

interface AssistantAgent {
    val agentId: String
    val displayName: String

    fun canHandle(plan: PlanResult): Boolean
    suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult
}
