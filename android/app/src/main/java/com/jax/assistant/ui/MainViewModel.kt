package com.jax.assistant.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.jax.assistant.ai.ModelInfo
import com.jax.assistant.ai.RequestLog
import com.jax.assistant.ai.TestConnectionResult
import com.jax.assistant.ai.agent.AutonomyLevel
import com.jax.assistant.ai.agent.ToolConfirmation
import com.jax.assistant.config.AppConfig
import com.jax.assistant.data.JaxRepository
import com.jax.assistant.data.ServiceLocator
import com.jax.assistant.db.BlockType
import com.jax.assistant.db.ChatMessageEntity
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.GoalEntity
import com.jax.assistant.db.HabitEntity
import com.jax.assistant.db.NoteBlockEntity
import com.jax.assistant.db.NotePageEntity
import com.jax.assistant.db.ProjectEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.screens.ComposeChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = JaxRepository(application)
    private val notesRepository = ServiceLocator.notes
    private val authRepository = ServiceLocator.auth
    private val firebaseSync = ServiceLocator.firebaseSync

    // One-shot user-facing message (shown as a toast), cleared once displayed.
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    fun consumeNotice() { _notice.value = null }

    // Safety net: an unexpected failure in any background action is reported to the user
    // instead of crashing the app. Cancellation never reaches this handler.
    private val errorHandler = CoroutineExceptionHandler { _, error ->
        Log.e(TAG, "Background action failed", error)
        _notice.value = "Something went wrong: ${error.localizedMessage ?: error.javaClass.simpleName}"
    }

    private fun launchSafely(block: suspend CoroutineScope.() -> Unit): Job =
        viewModelScope.launch(errorHandler, block = block)

    private val _messages = MutableStateFlow(listOf(greeting(GREETING_TEXT)))
    val messages: StateFlow<List<ComposeChatMessage>> = _messages.asStateFlow()

    // Chat turns run one at a time so a pending confirmation can never be orphaned by a second turn.
    private val turnLock = Mutex()
    private var lastMessageAt = 0L

    // A sensitive/destructive tool call awaiting the user's approval, surfaced to the UI.
    // The agent loop suspends on `pendingApproval` until resolveToolConfirmation() is called.
    private val _pendingConfirmation = MutableStateFlow<ToolConfirmation?>(null)
    val pendingConfirmation: StateFlow<ToolConfirmation?> = _pendingConfirmation.asStateFlow()
    private var pendingApproval: CompletableDeferred<Boolean>? = null

    private val _tasks = MutableStateFlow<List<TaskEntity>>(emptyList())
    val tasks: StateFlow<List<TaskEntity>> = _tasks.asStateFlow()

    // Every fact, used as agent context; `facts` below is what the Memory Vault displays,
    // which may be narrowed by a search query.
    private val allFacts = MutableStateFlow<List<FactEntity>>(emptyList())
    private val _facts = MutableStateFlow<List<FactEntity>>(emptyList())
    val facts: StateFlow<List<FactEntity>> = _facts.asStateFlow()
    private var factQuery = ""
    private var factSearchJob: Job? = null

    private val _apiKey = MutableStateFlow<String>(repository.getApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _groqApiKey = MutableStateFlow<String>(repository.getGroqApiKey())
    val groqApiKey: StateFlow<String> = _groqApiKey.asStateFlow()

    private val _groqModel = MutableStateFlow<String>(repository.getGroqModel())
    val groqModel: StateFlow<String> = _groqModel.asStateFlow()

    private val _groqEnabled = MutableStateFlow(repository.isGroqEnabled())
    val groqEnabled: StateFlow<Boolean> = _groqEnabled.asStateFlow()

    private val _selectedModel = MutableStateFlow<String>(repository.getSelectedModel())
    val selectedModel: StateFlow<String> = _selectedModel.asStateFlow()

    private val _developerMode = MutableStateFlow<Boolean>(repository.isDeveloperMode())
    val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

    private val _dailyAutomationEnabled = MutableStateFlow(repository.isDailyAutomationEnabled())
    val dailyAutomationEnabled: StateFlow<Boolean> = _dailyAutomationEnabled.asStateFlow()

    private val _taskContextAwarenessEnabled = MutableStateFlow(repository.isTaskContextAwarenessEnabled())
    val taskContextAwarenessEnabled: StateFlow<Boolean> = _taskContextAwarenessEnabled.asStateFlow()

    private val _voiceResponsesEnabled = MutableStateFlow(repository.isVoiceResponsesEnabled())
    val voiceResponsesEnabled: StateFlow<Boolean> = _voiceResponsesEnabled.asStateFlow()

    private val _proactiveAutonomyLevel = MutableStateFlow(repository.getProactiveAutonomyLevel())
    val proactiveAutonomyLevel: StateFlow<AutonomyLevel> = _proactiveAutonomyLevel.asStateFlow()

    private val _signedInEmail = MutableStateFlow(authRepository.currentEmail)
    val signedInEmail: StateFlow<String?> = _signedInEmail.asStateFlow()

    private val _syncStatus = MutableStateFlow("")
    val syncStatus: StateFlow<String> = _syncStatus.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    val requestLogs: StateFlow<List<RequestLog>> = repository.requestLogs

    private val _isProcessing = MutableStateFlow<Boolean>(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    // The answer text so far while J.A.X. is still generating it (empty when not streaming).
    private val _streamingReply = MutableStateFlow("")
    val streamingReply: StateFlow<String> = _streamingReply.asStateFlow()

    private val _aiBackend = MutableStateFlow(repository.getAiBackend())
    val aiBackend: StateFlow<com.jax.assistant.ai.AiBackend> = _aiBackend.asStateFlow()

    private val _isListening = MutableStateFlow<Boolean>(false)
    val isListening: StateFlow<Boolean> = _isListening.asStateFlow()

    private val _voiceDraft = MutableStateFlow("")
    val voiceDraft: StateFlow<String> = _voiceDraft.asStateFlow()

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

    private val _visionAnalysis = MutableStateFlow("")
    val visionAnalysis: StateFlow<String> = _visionAnalysis.asStateFlow()

    private val _isAnalyzingImage = MutableStateFlow(false)
    val isAnalyzingImage: StateFlow<Boolean> = _isAnalyzingImage.asStateFlow()

    init {
        // Crash recovery: close out agent runs interrupted by a previous process death and tell
        // the user where they stopped. Nothing is replayed automatically; "resume" continues safely.
        launchSafely {
            val interrupted = repository.reconcileInterruptedAgentRuns()
            if (interrupted.isNotEmpty()) {
                _notice.value = "A previous request was interrupted: ${interrupted.first()}"
            }
        }
        // Restore persisted chat history, keeping anything typed while it was loading.
        launchSafely {
            val stored = repository.getChatHistory()
            if (stored.isNotEmpty()) {
                lastMessageAt = maxOf(lastMessageAt, stored.maxOf { it.timestamp })
                val history = stored.map { ComposeChatMessage(it.id, it.text, it.isUser, formatTime(it.timestamp)) }
                _messages.update { current ->
                    (history + current.filter { it.id != GREETING_ID }).distinctBy { it.id }
                }
            }
        }
        launchSafely { repository.getAllTasks().collectLatest { _tasks.value = it } }
        launchSafely {
            repository.getAllFacts().collectLatest { list ->
                allFacts.value = list
                if (factQuery.isBlank()) _facts.value = list
            }
        }
        launchSafely { notesRepository.getAllPages().collectLatest { _pages.value = it } }
        launchSafely { repository.getAllGoals().collectLatest { _goals.value = it } }
        launchSafely { repository.getAllProjects().collectLatest { _projects.value = it } }
        launchSafely { repository.getAllHabits().collectLatest { _habits.value = it } }
    }

    fun setListening(listening: Boolean) { _isListening.value = listening }

    fun setSpeaking(speaking: Boolean) { _isSpeaking.value = speaking }

    fun setConversationMode(enabled: Boolean) { _conversationMode.value = enabled }

    // Appends a chat message to the UI and persists it. IDs are UUIDs (list keys must be
    // unique) and timestamps are strictly increasing so persisted history keeps its order.
    private fun pushMessage(text: String, isUser: Boolean) {
        val at = maxOf(System.currentTimeMillis(), lastMessageAt + 1).also { lastMessageAt = it }
        val message = ComposeChatMessage(UUID.randomUUID().toString(), text, isUser, formatTime(at))
        _messages.update { it + message }
        launchSafely {
            repository.saveChatMessage(ChatMessageEntity(message.id, text, isUser, at))
        }
    }

    // Clears chat history and the current task state, and starts a fresh session.
    fun startNewChat() {
        repository.clearWorkingMemory()
        launchSafely {
            repository.clearChatHistory()
            _messages.value = listOf(greeting(GREETING_TEXT))
        }
    }

    fun clearAllLocalData() {
        launchSafely {
            repository.clearAllLocalData()
            _messages.value = listOf(greeting("Local data cleared. Add an API key to continue using J.A.X."))
            _apiKey.value = repository.getApiKey()
            _groqApiKey.value = repository.getGroqApiKey()
            _groqModel.value = repository.getGroqModel()
            _groqEnabled.value = repository.isGroqEnabled()
            _selectedModel.value = repository.getSelectedModel()
            _developerMode.value = repository.isDeveloperMode()
            _dailyAutomationEnabled.value = repository.isDailyAutomationEnabled()
            _taskContextAwarenessEnabled.value = repository.isTaskContextAwarenessEnabled()
            _voiceResponsesEnabled.value = repository.isVoiceResponsesEnabled()
            _proactiveAutonomyLevel.value = repository.getProactiveAutonomyLevel()
        }
    }

    fun setVoiceDraft(text: String) {
        _voiceDraft.value = text
    }

    fun clearVoiceDraft() {
        _voiceDraft.value = ""
    }

    private fun formatTime(ts: Long): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(ts))

    fun sendMessage(input: String, onSpeak: ((String) -> Unit)? = null) {
        if (input.isBlank()) return
        // Rolling multi-turn context from the messages before this one.
        val conversationSummary = _messages.value.takeLast(AppConfig.CONVERSATION_CONTEXT_TURNS).joinToString("\n") {
            (if (it.isUser) "Jagadeesh" else "J.A.X.") + ": " + it.text
        }
        pushMessage(input, isUser = true)

        launchSafely {
            turnLock.withLock {
                _isProcessing.value = true
                val reply = try {
                    repository.runAgent(
                        input,
                        allFacts.value,
                        conversationSummary,
                        onPartial = { partial -> _streamingReply.value = partial }
                    ) { awaitUserConfirmation(it) }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Chat turn failed", e)
                    "System Error: ${e.localizedMessage ?: "Failed to process message"}"
                } finally {
                    _pendingConfirmation.value = null
                    pendingApproval = null
                    _isProcessing.value = false
                    _streamingReply.value = ""
                }
                pushMessage(reply, isUser = false)
                onSpeak?.invoke(reply)
            }
        }
    }

    // Suspends the agent loop until the user answers the confirmation dialog.
    private suspend fun awaitUserConfirmation(request: ToolConfirmation): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pendingApproval = deferred
        _pendingConfirmation.value = request
        return try {
            deferred.await()
        } finally {
            _pendingConfirmation.value = null
            pendingApproval = null
        }
    }

    // Called from the confirmation dialog to approve/deny a pending sensitive tool call.
    fun resolveToolConfirmation(approved: Boolean) {
        pendingApproval?.complete(approved)
    }

    fun setAiBackend(backend: com.jax.assistant.ai.AiBackend) {
        repository.setAiBackend(backend)
        _aiBackend.value = backend
    }

    fun aiBackendName(): String = repository.aiBackendName()

    fun liveSystemInstruction(): String = repository.liveSystemInstruction()

    fun toggleTask(task: TaskEntity) {
        launchSafely { repository.updateTask(task.copy(isCompleted = !task.isCompleted)) }
    }

    fun updateTask(task: TaskEntity) {
        launchSafely { repository.updateTask(task) }
    }

    fun deleteTask(task: TaskEntity) {
        launchSafely { repository.deleteTask(task) }
    }

    fun addManualTask(title: String, category: String, priority: String, deadline: String?) {
        if (title.isBlank()) return
        launchSafely { repository.createManualTask(title.trim(), category, priority, deadline) }
    }

    fun addManualFact(title: String, category: String, details: String) {
        if (title.isBlank() && details.isBlank()) return
        launchSafely { repository.createManualFact(title.trim(), category, details.trim()) }
    }

    fun updateFact(fact: FactEntity) {
        launchSafely { repository.updateFact(fact) }
    }

    fun deleteFact(fact: FactEntity) {
        launchSafely { repository.deleteFact(fact) }
    }

    // Narrows only the Memory Vault view; the agent keeps seeing every fact.
    fun searchFacts(query: String) {
        factQuery = query.trim()
        factSearchJob?.cancel()
        if (factQuery.isBlank()) {
            _facts.value = allFacts.value
            return
        }
        factSearchJob = launchSafely {
            repository.searchFacts(factQuery).collectLatest { _facts.value = it }
        }
    }

    fun updateApiKey(newKey: String) {
        repository.saveApiKey(newKey)
        _apiKey.value = newKey.trim()
    }

    fun updateGroqApiKey(newKey: String) {
        repository.saveGroqApiKey(newKey)
        _groqApiKey.value = newKey.trim()
    }

    fun updateGroqModel(model: String) {
        repository.saveGroqModel(model)
        _groqModel.value = model.trim()
    }

    fun setGroqEnabled(enabled: Boolean) {
        repository.setGroqEnabled(enabled)
        _groqEnabled.value = enabled
    }

    fun updateSelectedModel(modelName: String) {
        repository.saveSelectedModel(modelName)
        _selectedModel.value = modelName.trim()
    }

    fun setDeveloperMode(enabled: Boolean) {
        repository.setDeveloperMode(enabled)
        _developerMode.value = enabled
    }

    fun setDailyAutomationEnabled(enabled: Boolean) {
        repository.setDailyAutomationEnabled(enabled)
        _dailyAutomationEnabled.value = enabled
    }

    fun setTaskContextAwarenessEnabled(enabled: Boolean) {
        repository.setTaskContextAwarenessEnabled(enabled)
        _taskContextAwarenessEnabled.value = enabled
    }

    fun setVoiceResponsesEnabled(enabled: Boolean) {
        repository.setVoiceResponsesEnabled(enabled)
        _voiceResponsesEnabled.value = enabled
    }

    fun setProactiveAutonomyLevel(level: AutonomyLevel) {
        repository.setProactiveAutonomyLevel(level)
        _proactiveAutonomyLevel.value = level
    }

    fun googleSignInIntent(): Intent = authRepository.signInIntent()

    fun completeGoogleSignIn(data: Intent?, onSuccess: () -> Unit = {}) {
        _isSyncing.value = true
        authRepository.completeSignIn(data) { result ->
            result.onSuccess { email ->
                _signedInEmail.value = email
                syncNow()
                onSuccess()
            }.onFailure { error ->
                _syncStatus.value = "Sign-in failed: ${error.localizedMessage ?: "unknown error"}"
                _isSyncing.value = false
            }
        }
    }

    fun isFirebaseUserSignedIn(): Boolean = authRepository.isSignedIn

    fun syncNow() {
        val uid = authRepository.currentUid
        if (uid.isNullOrBlank()) {
            _syncStatus.value = "Sign in before syncing."
            _isSyncing.value = false
            return
        }
        _syncStatus.value = runCatching { firebaseSync.sync(uid) }
            .getOrElse { "Sync failed: ${it.localizedMessage ?: "unknown error"}" }
        _isSyncing.value = false
    }

    fun signOut() {
        firebaseSync.cancelPending()
        authRepository.signOut {
            _signedInEmail.value = null
            _syncStatus.value = "Signed out. Local data remains on this device."
        }
    }

    fun getModelCatalog(): List<ModelInfo> = repository.getModelCatalog()

    fun runHealthCheck(onResult: (String) -> Unit) {
        launchSafely { onResult(repository.runHealthCheck()) }
    }

    fun testConnection(onResult: (TestConnectionResult) -> Unit) {
        launchSafely { onResult(repository.testConnection()) }
    }

    fun getActiveModel(): String = repository.getActiveModel()

    // ---- Notion-style Notes ----

    // Which block the notes editor should move the cursor to (auto-focus), or null.
    private val _focusBlockId = MutableStateFlow<String?>(null)
    val focusBlockId: StateFlow<String?> = _focusBlockId.asStateFlow()

    // Pending debounced text saves, one per block: only the latest keystroke state is written.
    private val blockSaveJobs = HashMap<String, Job>()

    fun consumeFocusBlock() { _focusBlockId.value = null }

    fun openPage(pageId: String) {
        _selectedPageId.value = pageId
        blocksJob?.cancel()
        blocksJob = launchSafely {
            notesRepository.getBlocksForPage(pageId).collectLatest { _blocks.value = it }
        }
        relatedJob?.cancel()
        relatedJob = launchSafely {
            notesRepository.getRelatedPages(pageId).collectLatest { _relatedPages.value = it }
        }
        // Ensure a writable default text block and drop the cursor into it on a fresh page.
        launchSafely {
            val existing = notesRepository.getBlocksSnapshot(pageId)
            when {
                existing.isEmpty() ->
                    _focusBlockId.value = notesRepository.addBlockAfter(pageId, null, BlockType.TEXT).id
                existing.size == 1 && existing[0].type == BlockType.TEXT && existing[0].content.isBlank() ->
                    _focusBlockId.value = existing[0].id
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
        launchSafely {
            val page = notesRepository.createPage(title, category, tags)
            openPage(page.id)
        }
    }

    fun renamePage(page: NotePageEntity, newTitle: String) {
        launchSafely { notesRepository.renamePage(page, newTitle) }
    }

    fun updatePageTags(page: NotePageEntity, tags: String) {
        launchSafely { notesRepository.updatePageTags(page, tags) }
    }

    fun searchNotes(query: String) {
        noteSearchJob?.cancel()
        if (query.isBlank()) {
            _noteSearchResults.value = emptyList()
            return
        }
        noteSearchJob = launchSafely {
            notesRepository.searchPages(query).collectLatest { _noteSearchResults.value = it }
        }
    }

    fun deletePage(page: NotePageEntity) {
        launchSafely {
            notesRepository.deletePage(page)
            if (_selectedPageId.value == page.id) closePage()
        }
    }

    fun linkPage(otherPageId: String) {
        val current = _selectedPageId.value ?: return
        launchSafely { notesRepository.linkPages(current, otherPageId) }
    }

    fun unlinkPage(otherPageId: String) {
        val current = _selectedPageId.value ?: return
        launchSafely { notesRepository.unlinkPages(current, otherPageId) }
    }

    fun addBlock(type: String, afterBlockId: String?) {
        val pageId = _selectedPageId.value ?: return
        launchSafely {
            val block = notesRepository.addBlockAfter(pageId, afterBlockId, type)
            _focusBlockId.value = block.id
        }
    }

    // Called on every keystroke; debounced so writes land in order and the DB is not hammered.
    fun updateBlockContent(block: NoteBlockEntity, content: String) {
        blockSaveJobs.remove(block.id)?.cancel()
        blockSaveJobs[block.id] = launchSafely {
            delay(BLOCK_SAVE_DEBOUNCE_MS)
            notesRepository.updateBlockContent(block, content)
        }
    }

    fun toggleBlockChecked(block: NoteBlockEntity) {
        launchSafely { notesRepository.setBlockChecked(block, !block.checked) }
    }

    fun changeBlockType(block: NoteBlockEntity, type: String) {
        launchSafely { notesRepository.setBlockType(block, type) }
    }

    fun deleteBlock(block: NoteBlockEntity) {
        blockSaveJobs.remove(block.id)?.cancel()
        launchSafely { notesRepository.deleteBlock(block) }
    }

    // ---- Executive Intelligence ----

    fun addGoal(title: String, category: String, targetValue: Int, deadline: String?) {
        if (title.isBlank()) return
        launchSafely { repository.createGoal(title.trim(), category, targetValue, deadline) }
    }

    fun incrementGoalProgress(goal: GoalEntity, delta: Int) {
        launchSafely { repository.incrementGoalProgress(goal, delta) }
    }

    fun toggleGoalComplete(goal: GoalEntity) {
        launchSafely {
            val done = !goal.isCompleted
            repository.updateGoal(
                goal.copy(isCompleted = done, currentValue = if (done) goal.targetValue else goal.currentValue)
            )
        }
    }

    fun deleteGoal(goal: GoalEntity) {
        launchSafely { repository.deleteGoal(goal) }
    }

    fun addProject(name: String, description: String) {
        if (name.isBlank()) return
        launchSafely { repository.createProject(name.trim(), description) }
    }

    fun cycleProjectStatus(project: ProjectEntity) {
        launchSafely { repository.cycleProjectStatus(project) }
    }

    fun deleteProject(project: ProjectEntity) {
        launchSafely { repository.deleteProject(project) }
    }

    fun addHabit(name: String, category: String) {
        if (name.isBlank()) return
        launchSafely { repository.createHabit(name.trim(), category) }
    }

    fun toggleHabitToday(habit: HabitEntity) {
        launchSafely { repository.toggleHabitToday(habit) }
    }

    fun deleteHabit(habit: HabitEntity) {
        launchSafely { repository.deleteHabit(habit) }
    }

    fun generateDailyPlan() {
        if (_isPlanning.value) return
        _isPlanning.value = true
        launchSafely {
            try {
                val openTasks = _tasks.value.filter { !it.isCompleted }
                _dailyPlan.value = repository.generateDailyPlan(openTasks, java.time.LocalDate.now().toString())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _dailyPlan.value = "Could not generate plan: ${e.localizedMessage ?: "unknown error"}"
            } finally {
                _isPlanning.value = false
            }
        }
    }

    fun analyzeImage(imageUri: Uri) {
        if (_isAnalyzingImage.value) return
        _isAnalyzingImage.value = true
        _visionAnalysis.value = ""
        launchSafely {
            try {
                _visionAnalysis.value = repository.analyzeImage(imageUri)
            } finally {
                _isAnalyzingImage.value = false
            }
        }
    }

    private companion object {
        const val TAG = "MainViewModel"
        const val GREETING_ID = "greeting"
        const val GREETING_TEXT = "Good day, Jagadeesh. J.A.X. is active and synced to your Room database."
        const val BLOCK_SAVE_DEBOUNCE_MS = 300L

        fun greeting(text: String) = ComposeChatMessage(GREETING_ID, text, isUser = false, timestamp = "Now")
    }
}
