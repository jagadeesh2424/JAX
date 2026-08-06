package com.jax.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.data.JaxRepository
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.screens.ComposeChatMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = JaxRepository(application)

    private val _messages = MutableStateFlow<List<ComposeChatMessage>>(
        listOf(
            ComposeChatMessage("1", "Good day, Jagadeesh. J.A.X. is active and synced to your Room database.", false, "Now")
        )
    )
    val messages: StateFlow<List<ComposeChatMessage>> = _messages.asStateFlow()

    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val tasks: StateFlow<List<TaskEntity>> = _tasks.asStateFlow()

    private val _facts = MutableStateFlow<List<FactEntity>>(emptyList())
    val facts: StateFlow<List<FactEntity>> = _facts.asStateFlow()

    private val _apiKey = MutableStateFlow<String>(repository.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _selectedModel = MutableStateFlow<String>(repository.getSelectedModel())
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _developerMode = MutableStateFlow<Boolean>(repository.isDeveloperMode())
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

    val requestLogs: StateFlow<List<RequestLog>> = repository.requestLogs

    val executiveEngine = repository.executiveEngine
    val knowledgeEngine = repository.executiveEngine.knowledgeEngine

    private val _isProcessing = MutableStateFlow<Boolean>(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    init {
        // Observe Tasks
        viewModelScope.launch {
            repository.getAllTasks().collectLatest { list ->
                _tasks.value = list
            }
        }
        // Observe Facts
        viewModelScope.launch {
            repository.getAllFacts().collectLatest { list ->
                _facts.value = list
            }
        }
    }

    fun sendMessage(input: String, onSpeak: ((String) -> Unit)? = null) {
        if (input.isBlank()) return

        val userMsg = ComposeChatMessage(System.currentTimeMillis().toString(), input, true, "Now")
        _messages.value = _messages.value + userMsg
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                val result = repository.processUserInput(input, _facts.value)
                when (result) {
                    is JaxParseResult.TaskResult -> {
                        repository.insertTask(result.task)
                        val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                        _messages.value = _messages.value + aiMsg
                        onSpeak?.invoke(result.reply)
                    }
                    is JaxParseResult.FactResult -> {
                        repository.insertFact(result.fact)
                        val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                        _messages.value = _messages.value + aiMsg
                        onSpeak?.invoke(result.reply)
                    }
                    is JaxParseResult.QuestionResult -> {
                        val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                        _messages.value = _messages.value + aiMsg
                        onSpeak?.invoke(result.reply)
                    }
                }
            } catch (e: Exception) {
                val errorMsg = "System Error: ${e.localizedMessage ?: "Failed to process message"}"
                val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), errorMsg, false, "Now")
                _messages.value = _messages.value + aiMsg
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun toggleTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(task.copy(isCompleted = !task.isCompleted))
        }
    }

    fun addManualTask(title: String, category: String, priority: String, deadline: String?) {
        viewModelScope.launch {
            repository.createManualTask(title, category, priority, deadline)
        }
    }

    fun addManualFact(title: String, category: String, details: String) {
        viewModelScope.launch {
            repository.createManualFact(title, category, details)
        }
    }

    fun searchFacts(query: String) {
        viewModelScope.launch {
            if (query.isBlank()) {
                repository.getAllFacts().collectLatest { list ->
                    _facts.value = list
                }
            } else {
                repository.searchFacts(query).collectLatest { list ->
                    _facts.value = list
                }
            }
        }
    }

    fun updateApiKey(newKey: String) {
        repository.saveApiKey(newKey)
        _apiKey.value = newKey.trim()
    }

    fun updateSelectedModel(modelName: String) {
        repository.saveSelectedModel(modelName)
        _selectedModel.value = modelName.trim()
    }

    fun setDeveloperMode(enabled: Boolean) {
        repository.setDeveloperMode(enabled)
        _developerMode.value = enabled
    }

    fun getModelCatalog(): List<ModelInfo> = repository.getModelCatalog()

    fun getActiveModel(): String = repository.getActiveModel()

    fun runHealthCheck(onResult: (String) -> Unit) {
        viewModelScope.launch {
            val result = repository.runHealthCheck()
            onResult(result)
        }
    }

    fun testConnection(onResult: (TestConnectionResult) -> Unit) {
        viewModelScope.launch {
            val res = repository.testConnection()
            onResult(res)
        }
    }
}
