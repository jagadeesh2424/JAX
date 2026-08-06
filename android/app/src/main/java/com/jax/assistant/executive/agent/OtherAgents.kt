package com.jax.assistant.executive.agent

import com.jax.assistant.data.JaxRepository
import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.planner.PlanResult
import kotlinx.coroutines.flow.first

class TimelineAgent(private val repository: JaxRepository) : AssistantAgent {

    override val agentId: String = "TimelineAgent"
    override val displayName: String = "Unified Timeline Projection Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        val tasks = repository.getAllTasks().first()
        val timelineItems = tasks.map { task ->
            "• [${task.deadline ?: "Today"}] Task: ${task.title} (${task.priority})"
        }

        val output = if (timelineItems.isEmpty()) {
            "No timeline items recorded for today."
        } else {
            "UNIFIED TIMELINE VIEW:\n\n" + timelineItems.joinToString("\n")
        }

        return AgentExecutionResult(
            success = true,
            outputMessage = output
        )
    }
}

class KnowledgeAgent(private val knowledgeEngine: com.jax.assistant.executive.knowledge.KnowledgeWorkspaceEngine) : AssistantAgent {
    override val agentId: String = "KnowledgeAgent"
    override val displayName: String = "Knowledge Workspace & Block Editor Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        val content = plan.extractedParameters["content"] ?: plan.rawPrompt
        val defaultNotebook = knowledgeEngine.notebooks.value.firstOrNull()?.id ?: "nb_executive_general"
        val newPage = knowledgeEngine.createPage(
            notebookId = defaultNotebook,
            title = if (content.length > 25) content.take(25) + "..." else content,
            initialBlocks = listOf(
                com.jax.assistant.executive.knowledge.KnowledgeBlock(
                    type = com.jax.assistant.executive.knowledge.BlockType.PARAGRAPH,
                    content = content
                )
            ),
            tags = listOf("ExecutiveNote")
        )

        return AgentExecutionResult(
            success = true,
            outputMessage = "Knowledge Page created in Workspace:\n\n• Page Title: \"${newPage.title}\"\n• Initial Block: Paragraph (\"$content\")",
            data = mapOf("pageId" to newPage.id)
        )
    }
}

class VoiceAgent : AssistantAgent {
    override val agentId: String = "VoiceAgent"
    override val displayName: String = "Voice Pipeline Agent"

    override fun canHandle(plan: PlanResult): Boolean {
        return plan.assignedAgent == agentId
    }

    override suspend fun execute(plan: PlanResult, contextState: AppContextState): AgentExecutionResult {
        return AgentExecutionResult(
            success = true,
            outputMessage = "Voice Pipeline processing command: \"${plan.rawPrompt}\""
        )
    }
}
