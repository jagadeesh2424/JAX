package com.jax.assistant.executive.conversation

data class SlotSchema(
    val slotName: String,
    val displayName: String,
    val promptQuestion: String,
    val isRequired: Boolean = true,
    val defaultValue: String? = null
)

data class WorkflowSlotState(
    val workflowId: String,
    val targetIntent: String,
    val schema: List<SlotSchema>,
    val filledSlots: Map<String, String> = emptyMap(),
    val currentSlotIndex: Int = 0,
    val isComplete: Boolean = false
) {
    val nextMissingSlot: SlotSchema?
        get() {
            if (isComplete) return null
            for (slot in schema) {
                if (slot.isRequired && (!filledSlots.containsKey(slot.slotName) || filledSlots[slot.slotName].isNullOrBlank())) {
                    return slot
                }
            }
            return null
        }
}

object PredefinedSlotSchemas {
    val TASK_CREATION_SCHEMA = listOf(
        SlotSchema(
            slotName = "title",
            displayName = "Task Title",
            promptQuestion = "What task or goal should I create?",
            isRequired = true
        ),
        SlotSchema(
            slotName = "deadline",
            displayName = "Due Date",
            promptQuestion = "When is this task due? (e.g., Today, Tomorrow, YYYY-MM-DD)",
            isRequired = true
        ),
        SlotSchema(
            slotName = "priority",
            displayName = "Priority",
            promptQuestion = "What priority level should I assign? (HIGH, MED, LOW)",
            isRequired = false,
            defaultValue = "MED"
        ),
        SlotSchema(
            slotName = "category",
            displayName = "Category",
            promptQuestion = "Which category does this task belong to? (Work, Personal, General)",
            isRequired = false,
            defaultValue = "General"
        )
    )

    val MEMORY_CREATION_SCHEMA = listOf(
        SlotSchema(
            slotName = "fact",
            displayName = "Fact / Memory",
            promptQuestion = "What key detail or fact should I store in your Memory Vault?",
            isRequired = true
        ),
        SlotSchema(
            slotName = "category",
            displayName = "Category",
            promptQuestion = "What category fits this fact best? (e.g., Preference, Work, Family)",
            isRequired = false,
            defaultValue = "General"
        )
    )
}

class ConversationManager {

    private var activeWorkflowState: WorkflowSlotState? = null

    fun getActiveWorkflow(): WorkflowSlotState? = activeWorkflowState

    fun hasActiveWorkflow(): Boolean = activeWorkflowState != null && !activeWorkflowState!!.isComplete

    fun startWorkflow(
        workflowId: String,
        targetIntent: String,
        schema: List<SlotSchema>,
        initialSlots: Map<String, String> = emptyMap()
    ): WorkflowSlotState {
        val mergedSlots = mutableMapOf<String, String>()
        schema.forEach { slot ->
            if (!slot.defaultValue.isNullOrBlank()) {
                mergedSlots[slot.slotName] = slot.defaultValue
            }
        }
        mergedSlots.putAll(initialSlots.filterValues { it.isNotBlank() })

        var state = WorkflowSlotState(
            workflowId = workflowId,
            targetIntent = targetIntent,
            schema = schema,
            filledSlots = mergedSlots
        )

        state = checkIfComplete(state)
        activeWorkflowState = state
        return state
    }

    fun provideInputForNextSlot(userInput: String): WorkflowSlotState {
        val currentState = activeWorkflowState
            ?: throw IllegalStateException("No active workflow to process input for.")

        val nextSlot = currentState.nextMissingSlot
            ?: return currentState.copy(isComplete = true)

        val updatedSlots = currentState.filledSlots.toMutableMap()
        updatedSlots[nextSlot.slotName] = userInput.trim()

        var updatedState = currentState.copy(filledSlots = updatedSlots)
        updatedState = checkIfComplete(updatedState)

        activeWorkflowState = updatedState
        return updatedState
    }

    fun cancelWorkflow() {
        activeWorkflowState = null
    }

    private fun checkIfComplete(state: WorkflowSlotState): WorkflowSlotState {
        val missing = state.schema.filter { slot ->
            slot.isRequired && (!state.filledSlots.containsKey(slot.slotName) || state.filledSlots[slot.slotName].isNullOrBlank())
        }
        return state.copy(isComplete = missing.isEmpty())
    }
}
