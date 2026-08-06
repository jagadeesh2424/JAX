package com.jax.assistant.executive.agent

import com.jax.assistant.data.JaxRepository
import com.jax.assistant.db.FactEntity
import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.planner.PlanResult
import com.jax.assistant.executive.planner.UserIntent
import kotlinx.coroutines.flow.first
import java.util.UUID

class MemoryAgent(private val repository: JaxRepository) : AssistantAgent {

    override val agentId: String = "MemoryAgent"
    override val displayName: String = "Memory Vault & Fact Engine Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId || plan.intent == UserIntent.MEMORY_CREATE || plan.intent == UserIntent.MEMORY_QUERY
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        return when (plan.intent) {
            UserIntent.MEMORY_CREATE -> {
                val factContent = plan.extractedParameters["fact"] ?: plan.rawPrompt
                val category = plan.extractedParameters["category"] ?: "General"

                val newFact = FactEntity(
                    id = UUID.randomUUID().toString(),
                    title = if (factContent.length > 30) factContent.take(30) + "..." else factContent,
                    category = category,
                    details = factContent,
                    createdAt = System.currentTimeMillis()
                )

                repository.insertFact(newFact)

                AgentExecutionResult(
                    success = true,
                    outputMessage = "Memory saved to Vault!\n\n• Fact: \"${newFact.details}\"\n• Category: ${newFact.category}",
                    data = mapOf("factId" to newFact.id)
                )
            }

            UserIntent.MEMORY_QUERY -> {
                val facts = repository.getAllFacts().first()
                if (facts.isEmpty()) {
                    AgentExecutionResult(
                        success = true,
                        outputMessage = "Your Memory Vault is currently empty."
                    )
                } else {
                    val formatted = facts.take(10).joinToString("\n") { fact ->
                        "• [${fact.category}] ${fact.title}: ${fact.details}"
                    }
                    AgentExecutionResult(
                        success = true,
                        outputMessage = "Here are key facts from your Memory Vault:\n\n$formatted"
                    )
                }
            }

            else -> AgentExecutionResult(
                success = false,
                outputMessage = "MemoryAgent cannot handle intent: ${plan.intent}"
            )
        }
    }
}
