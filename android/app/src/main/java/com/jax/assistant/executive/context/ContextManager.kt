package com.jax.assistant.executive.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class AppContextState(
    val currentScreen: String = "OmniChat",
    val activeWorkflow: String? = null,
    val activeNotebook: String? = null,
    val selectedTaskId: String? = null,
    val selectedMemoryId: String? = null,
    val currentDate: String = LocalDate.now().toString(),
    val currentTime: String = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")),
    val currentConversationId: String = "default_session",
    val previousConversationSummary: String? = null,
    val userPreferences: Map<String, String> = emptyMap(),
    val executionHistory: List<String> = emptyList()
)

class ContextManager {

    private val _contextState = MutableStateFlow(
        AppContextState(
            currentDate = LocalDate.now().toString(),
            currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        )
    )
    val contextState: StateFlow<AppContextState> = _contextState.asStateFlow()

    fun updateScreen(screenName: String) {
        _contextState.value = _contextState.value.copy(currentScreen = screenName)
    }

    fun updateActiveWorkflow(workflowName: String?) {
        _contextState.value = _contextState.value.copy(activeWorkflow = workflowName)
    }

    fun updateSelectedTask(taskId: String?) {
        _contextState.value = _contextState.value.copy(selectedTaskId = taskId)
    }

    fun updateSelectedMemory(memoryId: String?) {
        _contextState.value = _contextState.value.copy(selectedMemoryId = memoryId)
    }

    fun updateActiveNotebook(notebookName: String?) {
        _contextState.value = _contextState.value.copy(activeNotebook = notebookName)
    }

    fun recordExecution(actionDescription: String) {
        val currentHistory = _contextState.value.executionHistory.toMutableList()
        currentHistory.add(0, "[${LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss"))}] $actionDescription")
        if (currentHistory.size > 50) {
            currentHistory.removeAt(currentHistory.size - 1)
        }
        _contextState.value = _contextState.value.copy(
            executionHistory = currentHistory,
            currentDate = LocalDate.now().toString(),
            currentTime = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"))
        )
    }

    fun getContextSummary(): String {
        val state = _contextState.value
        return "Screen: ${state.currentScreen} | Workflow: ${state.activeWorkflow ?: "None"} | Date: ${state.currentDate} ${state.currentTime}"
    }
}
