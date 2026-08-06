package com.jax.assistant.executive

import com.jax.assistant.data.JaxRepository
import com.jax.assistant.executive.agent.AssistantAgent
import com.jax.assistant.executive.agent.BriefingAgent
import com.jax.assistant.executive.agent.KnowledgeAgent
import com.jax.assistant.executive.agent.MemoryAgent
import com.jax.assistant.executive.agent.TaskAgent
import com.jax.assistant.executive.agent.TimelineAgent
import com.jax.assistant.executive.agent.VoiceAgent
import com.jax.assistant.executive.context.ContextManager
import com.jax.assistant.executive.conversation.ConversationManager
import com.jax.assistant.executive.planner.Planner

data class ExecutiveProcessResult(
    val handledLocallyByAgent: Boolean,
    val responseText: String,
    val agentName: String? = null,
    val isWorkflowInProgress: Boolean = false
)

class ExecutiveIntelligenceEngine(private val repository: JaxRepository) {

    val contextManager = ContextManager()
    val conversationManager = ConversationManager()
    val planner = Planner(conversationManager)
    val knowledgeEngine = com.jax.assistant.executive.knowledge.KnowledgeWorkspaceEngine()
    val timelineEngine = com.jax.assistant.executive.timeline.TimelineEngine()
    val briefingEngine = com.jax.assistant.executive.briefing.ExecutiveBriefingEngine()
    val knowledgeGraph = com.jax.assistant.executive.graph.KnowledgeGraph()
    val voicePipeline = com.jax.assistant.executive.voice.VoicePipeline(this)

    private val agents: List<AssistantAgent> = listOf(
        TaskAgent(repository),
        MemoryAgent(repository),
        BriefingAgent(repository),
        TimelineAgent(repository),
        KnowledgeAgent(knowledgeEngine),
        VoiceAgent()
    )

    suspend fun processUserPrompt(userInput: String): ExecutiveProcessResult {
        val currentContext = contextManager.contextState.value

        // Step 1: Planner evaluates intent and slot status
        val plan = planner.plan(userInput, currentContext)

        // Step 2: Slot filling required?
        if (plan.requiresSlotFilling && !plan.missingSlotQuestion.isNullOrBlank()) {
            contextManager.updateActiveWorkflow(plan.intent.name)
            contextManager.recordExecution("Slot filling active for ${plan.intent.name}. Prompting user: \"${plan.missingSlotQuestion}\"")

            return ExecutiveProcessResult(
                handledLocallyByAgent = true,
                responseText = plan.missingSlotQuestion,
                agentName = plan.assignedAgent,
                isWorkflowInProgress = true
            )
        }

        // Step 3: Check if an Agent can handle this plan locally
        val targetAgent = agents.find { it.agentId == plan.assignedAgent || it.canHandle(plan) }

        if (targetAgent != null && plan.isCompleteAndReadyToExecute) {
            contextManager.recordExecution("Executing plan via ${targetAgent.displayName} for intent ${plan.intent.name}")
            val executionResult = targetAgent.execute(plan, currentContext)

            // Reset active workflow upon completion
            conversationManager.cancelWorkflow()
            contextManager.updateActiveWorkflow(null)

            return ExecutiveProcessResult(
                handledLocallyByAgent = true,
                responseText = executionResult.outputMessage,
                agentName = targetAgent.displayName,
                isWorkflowInProgress = false
            )
        }

        // Step 4: Fallback to AI Router for general conversation or complex reasoning
        contextManager.recordExecution("Routing request to AI Router for fallback execution.")
        return ExecutiveProcessResult(
            handledLocallyByAgent = false,
            responseText = "",
            agentName = "AI Router",
            isWorkflowInProgress = false
        )
    }
}
