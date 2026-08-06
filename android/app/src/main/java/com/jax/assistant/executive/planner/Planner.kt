package com.jax.assistant.executive.planner

import com.jax.assistant.executive.context.AppContextState
import com.jax.assistant.executive.conversation.ConversationManager
import com.jax.assistant.executive.conversation.PredefinedSlotSchemas
import com.jax.assistant.executive.conversation.WorkflowSlotState
import java.util.UUID

enum class UserIntent {
    TASK_CREATE,
    TASK_QUERY,
    MEMORY_CREATE,
    MEMORY_QUERY,
    KNOWLEDGE_CREATE,
    EXECUTIVE_BRIEFING,
    VOICE_COMMAND,
    GENERAL_CHAT
}

data class PlanResult(
    val intent: UserIntent,
    val requiresSlotFilling: Boolean,
    val missingSlotQuestion: String? = null,
    val assignedAgent: String,
    val isCompleteAndReadyToExecute: Boolean,
    val extractedParameters: Map<String, String> = emptyMap(),
    val rawPrompt: String
)

class Planner(private val conversationManager: ConversationManager) {

    fun plan(userInput: String, context: AppContextState): PlanResult {
        val trimmed = userInput.trim()

        // 1. Check if we are currently inside an active slot-filling conversation workflow
        if (conversationManager.hasActiveWorkflow()) {
            val updatedSlotState = conversationManager.provideInputForNextSlot(trimmed)
            if (!updatedSlotState.isComplete) {
                val nextSlot = updatedSlotState.nextMissingSlot
                return PlanResult(
                    intent = parseIntentName(updatedSlotState.targetIntent),
                    requiresSlotFilling = true,
                    missingSlotQuestion = nextSlot?.promptQuestion,
                    assignedAgent = getAgentForIntent(parseIntentName(updatedSlotState.targetIntent)),
                    isCompleteAndReadyToExecute = false,
                    extractedParameters = updatedSlotState.filledSlots,
                    rawPrompt = trimmed
                )
            } else {
                // Completed workflow
                return PlanResult(
                    intent = parseIntentName(updatedSlotState.targetIntent),
                    requiresSlotFilling = false,
                    missingSlotQuestion = null,
                    assignedAgent = getAgentForIntent(parseIntentName(updatedSlotState.targetIntent)),
                    isCompleteAndReadyToExecute = true,
                    extractedParameters = updatedSlotState.filledSlots,
                    rawPrompt = trimmed
                )
            }
        }

        // 2. Classify intent from scratch
        val detectedIntent = classifyIntent(trimmed)

        return when (detectedIntent) {
            UserIntent.TASK_CREATE -> {
                val initialParams = extractInitialTaskParameters(trimmed)
                val workflowState = conversationManager.startWorkflow(
                    workflowId = UUID.randomUUID().toString(),
                    targetIntent = UserIntent.TASK_CREATE.name,
                    schema = PredefinedSlotSchemas.TASK_CREATION_SCHEMA,
                    initialSlots = initialParams
                )

                if (!workflowState.isComplete) {
                    val nextSlot = workflowState.nextMissingSlot
                    PlanResult(
                        intent = UserIntent.TASK_CREATE,
                        requiresSlotFilling = true,
                        missingSlotQuestion = nextSlot?.promptQuestion,
                        assignedAgent = "TaskAgent",
                        isCompleteAndReadyToExecute = false,
                        extractedParameters = workflowState.filledSlots,
                        rawPrompt = trimmed
                    )
                } else {
                    PlanResult(
                        intent = UserIntent.TASK_CREATE,
                        requiresSlotFilling = false,
                        missingSlotQuestion = null,
                        assignedAgent = "TaskAgent",
                        isCompleteAndReadyToExecute = true,
                        extractedParameters = workflowState.filledSlots,
                        rawPrompt = trimmed
                    )
                }
            }

            UserIntent.MEMORY_CREATE -> {
                val initialParams = extractInitialMemoryParameters(trimmed)
                val workflowState = conversationManager.startWorkflow(
                    workflowId = UUID.randomUUID().toString(),
                    targetIntent = UserIntent.MEMORY_CREATE.name,
                    schema = PredefinedSlotSchemas.MEMORY_CREATION_SCHEMA,
                    initialSlots = initialParams
                )

                if (!workflowState.isComplete) {
                    val nextSlot = workflowState.nextMissingSlot
                    PlanResult(
                        intent = UserIntent.MEMORY_CREATE,
                        requiresSlotFilling = true,
                        missingSlotQuestion = nextSlot?.promptQuestion,
                        assignedAgent = "MemoryAgent",
                        isCompleteAndReadyToExecute = false,
                        extractedParameters = workflowState.filledSlots,
                        rawPrompt = trimmed
                    )
                } else {
                    PlanResult(
                        intent = UserIntent.MEMORY_CREATE,
                        requiresSlotFilling = false,
                        missingSlotQuestion = null,
                        assignedAgent = "MemoryAgent",
                        isCompleteAndReadyToExecute = true,
                        extractedParameters = workflowState.filledSlots,
                        rawPrompt = trimmed
                    )
                }
            }

            UserIntent.EXECUTIVE_BRIEFING -> {
                PlanResult(
                    intent = UserIntent.EXECUTIVE_BRIEFING,
                    requiresSlotFilling = false,
                    missingSlotQuestion = null,
                    assignedAgent = "BriefingAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }

            UserIntent.TASK_QUERY -> {
                PlanResult(
                    intent = UserIntent.TASK_QUERY,
                    requiresSlotFilling = false,
                    assignedAgent = "TaskAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }

            UserIntent.MEMORY_QUERY -> {
                PlanResult(
                    intent = UserIntent.MEMORY_QUERY,
                    requiresSlotFilling = false,
                    assignedAgent = "MemoryAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }

            UserIntent.KNOWLEDGE_CREATE -> {
                PlanResult(
                    intent = UserIntent.KNOWLEDGE_CREATE,
                    requiresSlotFilling = false,
                    assignedAgent = "KnowledgeAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }

            UserIntent.VOICE_COMMAND -> {
                PlanResult(
                    intent = UserIntent.VOICE_COMMAND,
                    requiresSlotFilling = false,
                    assignedAgent = "VoiceAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }

            UserIntent.GENERAL_CHAT -> {
                PlanResult(
                    intent = UserIntent.GENERAL_CHAT,
                    requiresSlotFilling = false,
                    assignedAgent = "AIFallbackAgent",
                    isCompleteAndReadyToExecute = true,
                    rawPrompt = trimmed
                )
            }
        }
    }

    private fun classifyIntent(input: String): UserIntent {
        val lower = input.lowercase()
        return when {
            lower.startsWith("briefing") || lower.contains("morning briefing") || lower.contains("daily briefing") -> UserIntent.EXECUTIVE_BRIEFING
            lower.contains("remind me") || lower.contains("create task") || lower.startsWith("schedule ") || lower.startsWith("book ") || lower.contains("add task") -> UserIntent.TASK_CREATE
            lower.contains("my tasks") || lower.contains("list tasks") || lower.contains("show tasks") || lower.contains("pending tasks") -> UserIntent.TASK_QUERY
            lower.contains("remember that") || lower.contains("save memory") || lower.startsWith("remember ") || lower.contains("store fact") -> UserIntent.MEMORY_CREATE
            lower.contains("what do you remember") || lower.contains("search memory") || lower.contains("list memories") || lower.contains("recall ") -> UserIntent.MEMORY_QUERY
            lower.contains("create note") || lower.contains("add notebook") || lower.contains("knowledge block") -> UserIntent.KNOWLEDGE_CREATE
            else -> UserIntent.GENERAL_CHAT
        }
    }

    private fun extractInitialTaskParameters(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val lower = input.lowercase()

        // Extract deadline keywords if present
        when {
            lower.contains("today") -> params["deadline"] = "Today"
            lower.contains("tomorrow") -> params["deadline"] = "Tomorrow"
            lower.contains("next week") -> params["deadline"] = "Next Week"
            lower.contains("monday") -> params["deadline"] = "Monday"
            lower.contains("friday") -> params["deadline"] = "Friday"
        }

        // Clean action words to derive candidate title
        var candidateTitle = input
            .replace("(?i)^(remind me to|create task to|create task|schedule|book|add task to|add task)\\s*".toRegex(), "")
            .replace("(?i)\\b(tomorrow|today|next week|monday|friday)\\b".toRegex(), "")
            .trim()

        if (candidateTitle.isNotBlank()) {
            params["title"] = candidateTitle
        }

        return params
    }

    private fun extractInitialMemoryParameters(input: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        val candidateFact = input
            .replace("(?i)^(remember that|save memory|remember|store fact)\\s*".toRegex(), "")
            .trim()

        if (candidateFact.isNotBlank()) {
            params["fact"] = candidateFact
        }
        return params
    }

    private fun parseIntentName(name: String): UserIntent {
        return try {
            UserIntent.valueOf(name)
        } catch (e: Exception) {
            UserIntent.GENERAL_CHAT
        }
    }

    private fun getAgentForIntent(intent: UserIntent): String {
        return when (intent) {
            UserIntent.TASK_CREATE, UserIntent.TASK_QUERY -> "TaskAgent"
            UserIntent.MEMORY_CREATE, UserIntent.MEMORY_QUERY -> "MemoryAgent"
            UserIntent.EXECUTIVE_BRIEFING -> "BriefingAgent"
            UserIntent.KNOWLEDGE_CREATE -> "KnowledgeAgent"
            UserIntent.VOICE_COMMAND -> "VoiceAgent"
            else -> "AIFallbackAgent"
        }
    }
}
