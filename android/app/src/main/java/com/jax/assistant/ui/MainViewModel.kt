package com.jax.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.config.AppConfig
import com.jax.assistant.data.JaxRepository
import com.jax.assistant.data.ServiceLocator
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.GoalEntity
import com.jax.assistant.db.HabitEntity
import com.jax.assistant.db.NoteBlockEntity
import com.jax.assistant.db.NotePageEntity
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.screens.ComposeChatMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = JaxRepository(application)
    private val notesRepository = ServiceLocator.apply { init(application) }.notes

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

    private val _isProcessing = MutableStateFlow<Boolean>(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isListening = MutableStateFlow<Boolean>(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _isSpeaking = MutableStateFlow<Boolean>(false)
    val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private val _conversationMode = MutableStateFlow<Boolean>(false)
    val conversationMode: StateFlow<Boolean> = _conversationMode.asStateFlow()

    private val _pages = MutableStateFlow<List<NotePageEntity>>(emptyList())
    val pages: StateFlow<List<NotePageEntity>> = _pages.asStateFlow()

    private val _selectedPageId = MutableStateFlow<String?>(null)
    val selectedPageId: StateFlow<String?> = _selectedPageId.asStateFlow()

    private val _blocks = MutableStateFlow<List<NoteBlockEntity>>(emptyList())
    val blocks: StateFlow<List<NoteBlockEntity>> = _blocks.asStateFlow()

    private var blocksJob: Job? = null

    private val _relatedPages = MutableStateFlow<List<NotePageEntity>>(emptyList())
    val relatedPages: StateFlow<List<NotePageEntity>> = _relatedPages.asStateFlow()

    private var relatedJob: Job? = null

    private val _noteSearchResults = MutableStateFlow<List<NotePageEntity>>(emptyList())
    val noteSearchResults: StateFlow<List<NotePageEntity>> = _noteSearchResults.asStateFlow()

    private var noteSearchJob: Job? = null

    private val _goals = MutableStateFlow<List<GoalEntity>>(emptyList())
    val goals: StateFlow<List<GoalEntity>> = _goals.asStateFlow()

    private val _projects = MutableStateFlow<List<ProjectEntity>>(emptyList())
    val projects: StateFlow<List<ProjectEntity>> = _projects.asStateFlow()

    private val _habits = MutableStateFlow<List<HabitEntity>>(emptyList())
    val habits: StateFlow<List<HabitEntity>> = _habits.asStateFlow()

    private val _dailyPlan = MutableStateFlow<String>("")
    val dailyPlan: StateFlow<String> = _dailyPlan.asStateFlow()

    private val _isPlanning = MutableStateFlow<Boolean>(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

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
        // Observe Note pages
        viewModelScope.launch {
            notesRepository.getAllPages().collectLatest { list ->
                _pages.value = list
            }
        }
        // Observe Goals
        viewModelScope.launch {
            repository.getAllGoals().collectLatest { list ->
                _goals.value = list
            }
        }
        // Observe Projects
        viewModelScope.launch {
            repository.getAllProjects().collectLatest { list ->
                _projects.value = list
            }
        }
        // Observe Habits
        viewModelScope.launch {
            repository.getAllHabits().collectLatest { list ->
                _habits.value = list
            }
        }
    }

    fun setListening(listening: Boolean) { _isListening.value = listening }

    fun setSpeaking(speaking: Boolean) { _isSpeaking.value = speaking }

    fun setConversationMode(enabled: Boolean) { _conversationMode.value = enabled }

    // Records a user command + JAX confirmation in the chat without invoking the AI (used for device actions).
    fun logAssistantAction(userText: String, replyText: String) {
        val now = System.currentTimeMillis()
        val userMsg = ComposeChatMessage(now.toString(), userText, true, "Now")
        val jaxMsg = ComposeChatMessage((now + 1).toString(), replyText, false, "Now")
        _messages.value = _messages.value + userMsg + jaxMsg
    }

    fun sendMessage(input: String, onSpeak: ((String) -> Unit)? = null) {
        if (input.isBlank()) return
        // Build rolling multi-turn context from the last few messages before adding this one.
        val conversationSummary = _messages.value.takeLast(AppConfig.CONVERSATION_CONTEXT_TURNS).joinToString("\n") {
            (if (it.isUser) "Jagadeesh" else "J.A.X.") + ": " + it.text
        }

        val userMsg = ComposeChatMessage(System.currentTimeMillis().toString(), input, true, "Now")
        _messages.value = _messages.value + userMsg
        _isProcessing.value = true

        viewModelScope.launch {
            try {
                val reply = repository.runAgent(input, _facts.value, conversationSummary)
                val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), reply, false, "Now")
                _messages.value = _messages.value + aiMsg
                onSpeak?.invoke(reply)
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

    fun updateTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.updateTask(task)
        }
    }

    fun deleteTask(task: TaskEntity) {
        viewModelScope.launch {
            repository.deleteTask(task)
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

    fun updateFact(fact: FactEntity) {
        viewModelScope.launch {
            repository.updateFact(fact)
        }
    }

    fun deleteFact(fact: FactEntity) {
        viewModelScope.launch {
            repository.deleteFact(fact)
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

    fun getActiveModel(): String = repository.getActiveModel()

    // ---- Notion-style Notes ----

    fun openPage(pageId: String) {
        _selectedPageId.value = pageId
        blocksJob?.cancel()
        blocksJob = viewModelScope.launch {
            notesRepository.getBlocksForPage(pageId).collectLatest { list ->
                _blocks.value = list
            }
        }
        relatedJob?.cancel()
        relatedJob = viewModelScope.launch {
            notesRepository.getRelatedPages(pageId).collectLatest { list ->
                _relatedPages.value = list
            }
        }
    }

    fun closePage() {
        _selectedPageId.value = null
        blocksJob?.cancel()
        blocksJob = null
        _blocks.value = emptyList()
        relatedJob?.cancel()
        relatedJob = null
        _relatedPages.value = emptyList()
    }

    fun createPage(title: String, category: String, tags: String = "") {
        viewModelScope.launch {
            val page = notesRepository.createPage(title, category, tags)
            openPage(page.id)
        }
    }

    fun renamePage(page: NotePageEntity, newTitle: String) {
        viewModelScope.launch { notesRepository.renamePage(page, newTitle) }
    }

    fun updatePageTags(page: NotePageEntity, tags: String) {
        viewModelScope.launch { notesRepository.updatePageTags(page, tags) }
    }

    fun searchNotes(query: String) {
        noteSearchJob?.cancel()
        if (query.isBlank()) {
            _noteSearchResults.value = emptyList()
            return
        }
        noteSearchJob = viewModelScope.launch {
            notesRepository.searchPages(query).collectLatest { _noteSearchResults.value = it }
        }
    }

    fun deletePage(page: NotePageEntity) {
        viewModelScope.launch {
            notesRepository.deletePage(page)
            if (_selectedPageId.value == page.id) closePage()
        }
    }

    fun linkPage(otherPageId: String) {
        val current = _selectedPageId.value ?: return
        viewModelScope.launch { notesRepository.linkPages(current, otherPageId) }
    }

    fun unlinkPage(otherPageId: String) {
        val current = _selectedPageId.value ?: return
        viewModelScope.launch { notesRepository.unlinkPages(current, otherPageId) }
    }

    fun addBlock(type: String) {
        val pageId = _selectedPageId.value ?: return
        val nextPosition = (_blocks.value.maxOfOrNull { it.position } ?: -1) + 1
        viewModelScope.launch { notesRepository.addBlock(pageId, type, nextPosition) }
    }

    fun updateBlockContent(block: NoteBlockEntity, content: String) {
        viewModelScope.launch { notesRepository.updateBlock(block.copy(content = content)) }
    }

    fun toggleBlockChecked(block: NoteBlockEntity) {
        viewModelScope.launch { notesRepository.updateBlock(block.copy(checked = !block.checked)) }
    }

    fun changeBlockType(block: NoteBlockEntity, type: String) {
        viewModelScope.launch { notesRepository.updateBlock(block.copy(type = type)) }
    }

    fun deleteBlock(block: NoteBlockEntity) {
        viewModelScope.launch { notesRepository.deleteBlock(block) }
    }

    // ---- Executive Intelligence (Phase 2) ----

    fun addGoal(title: String, category: String, targetValue: Int, deadline: String?) {
        if (title.isBlank()) return
        viewModelScope.launch { repository.createGoal(title, category, targetValue, deadline) }
    }

    fun incrementGoalProgress(goal: GoalEntity, delta: Int) {
        viewModelScope.launch { repository.incrementGoalProgress(goal, delta) }
    }

    fun toggleGoalComplete(goal: GoalEntity) {
        viewModelScope.launch {
            val done = !goal.isCompleted
            repository.updateGoal(
                goal.copy(
                    isCompleted = done,
                    currentValue = if (done) goal.targetValue else goal.currentValue
                )
            )
        }
    }

    fun deleteGoal(goal: GoalEntity) {
        viewModelScope.launch { repository.deleteGoal(goal) }
    }

    fun addProject(name: String, description: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createProject(name, description) }
    }

    fun cycleProjectStatus(project: ProjectEntity) {
        viewModelScope.launch { repository.cycleProjectStatus(project) }
    }

    fun deleteProject(project: ProjectEntity) {
        viewModelScope.launch { repository.deleteProject(project) }
    }

    fun addHabit(name: String, category: String) {
        if (name.isBlank()) return
        viewModelScope.launch { repository.createHabit(name, category) }
    }

    fun toggleHabitToday(habit: HabitEntity) {
        viewModelScope.launch { repository.toggleHabitToday(habit) }
    }

    fun deleteHabit(habit: HabitEntity) {
        viewModelScope.launch { repository.deleteHabit(habit) }
    }

    fun generateDailyPlan() {
        if (_isPlanning.value) return
        _isPlanning.value = true
        viewModelScope.launch {
            try {
                val openTasks = _tasks.value.filter { !it.isCompleted }
                val today = java.time.LocalDate.now().toString()
                _dailyPlan.value = repository.generateDailyPlan(openTasks, today)
            } catch (e: Exception) {
                _dailyPlan.value = "Could not generate plan: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                _isPlanning.value = false
            }
        }
    }
}
