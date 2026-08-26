package com.jax.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import androidx.work.*
import com.jax.assistant.device.DeviceCommandParser
import com.jax.assistant.device.DeviceController
import com.jax.assistant.ui.MainViewModel
import com.jax.assistant.ui.screens.MainScreen
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.voice.VoiceManager
import com.jax.assistant.voice.GeminiLiveVoiceProvider
import com.jax.assistant.worker.DailyAgentWorker
import com.jax.assistant.worker.ConsolidationWorker
import com.jax.assistant.worker.WeeklyReviewWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var voiceManager: VoiceManager
    private lateinit var liveVoiceProvider: GeminiLiveVoiceProvider
    private val deviceController by lazy { DeviceController(applicationContext) }

    private val requestMicPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                if (viewModel.conversationMode.value) liveVoiceProvider.start()
                else voiceManager.startListening()
            } else {
                Toast.makeText(
                    this,
                    "Microphone permission is required for voice input.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }

    private val googleSignInLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            viewModel.completeGoogleSignIn(result.data)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup WorkManager for Daily 9:00 AM Agent Briefing
        scheduleDailyBriefingWorker()
        scheduleConsolidationWorker()
        // Setup WorkManager for the weekly executive review
        scheduleWeeklyReviewWorker()

        setContent {
            JAXAssistantTheme {
                val messages by viewModel.messages.collectAsState()
                val tasks by viewModel.tasks.collectAsState()
                val facts by viewModel.facts.collectAsState()
                val apiKey by viewModel.apiKey.collectAsState()
                val selectedModel by viewModel.selectedModel.collectAsState()
                val developerMode by viewModel.developerMode.collectAsState()
                val dailyAutomationEnabled by viewModel.dailyAutomationEnabled.collectAsState()
                val taskContextAwarenessEnabled by viewModel.taskContextAwarenessEnabled.collectAsState()
                val voiceResponsesEnabled by viewModel.voiceResponsesEnabled.collectAsState()
                val signedInEmail by viewModel.signedInEmail.collectAsState()
                val syncStatus by viewModel.syncStatus.collectAsState()
                val isSyncing by viewModel.isSyncing.collectAsState()
                val requestLogs by viewModel.requestLogs.collectAsState()
                val pages by viewModel.pages.collectAsState()
                val selectedPageId by viewModel.selectedPageId.collectAsState()
                val blocks by viewModel.blocks.collectAsState()
                val noteSearchResults by viewModel.noteSearchResults.collectAsState()
                val relatedPages by viewModel.relatedPages.collectAsState()
                val goals by viewModel.goals.collectAsState()
                val projects by viewModel.projects.collectAsState()
                val habits by viewModel.habits.collectAsState()
                val dailyPlan by viewModel.dailyPlan.collectAsState()
                val isPlanning by viewModel.isPlanning.collectAsState()
                val isListening by viewModel.isListening.collectAsState()
                val voiceDraft by viewModel.voiceDraft.collectAsState()
                val conversationMode by viewModel.conversationMode.collectAsState()
                val focusBlockId by viewModel.focusBlockId.collectAsState()
                val visionAnalysis by viewModel.visionAnalysis.collectAsState()
                val isAnalyzingImage by viewModel.isAnalyzingImage.collectAsState()
                val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                    uri?.let { viewModel.analyzeImage(it) }
                }

                // Deep-link: the 9 AM briefing notification opens straight to the Briefing tab;
                // the weekly review notification opens the Executive Dashboard.
                val startTab = when {
                    intent?.getBooleanExtra("open_dashboard", false) == true -> 7
                    intent?.getBooleanExtra("open_briefing", false) == true -> 4
                    else -> 0
                }

                // Initialize Voice STT & TTS
                voiceManager = remember {
                    VoiceManager(
                        context = this@MainActivity,
                        onSpeechResult = { text ->
                            viewModel.clearVoiceDraft()
                            val clean = VoiceManager.stripWakePhrase(text)
                            handleUserInput(clean)
                        },
                        onPartialSpeechResult = { text ->
                            viewModel.setVoiceDraft(text)
                        },
                        onError = { message ->
                            viewModel.setListening(false)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
                            }
                        },
                        onListeningStateChanged = { listening ->
                            viewModel.setListening(listening)
                        },
                        onSpeakingStateChanged = { speaking ->
                            viewModel.setSpeaking(speaking)
                            // Hands-free: once J.A.X. finishes speaking, re-open the mic for the next turn.
                            if (!speaking && viewModel.conversationMode.value && !liveVoiceProvider.isActive()) {
                                runOnUiThread { startVoiceInput() }
                            }
                        }
                    )
                }
                if (!::liveVoiceProvider.isInitialized) {
                    liveVoiceProvider = GeminiLiveVoiceProvider(
                        onInputTranscript = { text -> viewModel.setVoiceDraft("You: $text") },
                        onOutputTranscript = { text -> viewModel.setVoiceDraft("J.A.X.: $text") },
                        onActiveChanged = { active -> viewModel.setListening(active) },
                        onError = { message ->
                            viewModel.setListening(false)
                            runOnUiThread {
                                Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()
                            }
                        }
                    )
                }

                MainScreen(
                    messages = messages,
                    tasks = tasks,
                    facts = facts,
                    apiKey = apiKey,
                    selectedModel = selectedModel,
                    developerMode = developerMode,
                    dailyAutomationEnabled = dailyAutomationEnabled,
                    onToggleDailyAutomation = { viewModel.setDailyAutomationEnabled(it) },
                    taskContextAwarenessEnabled = taskContextAwarenessEnabled,
                    onToggleTaskContextAwareness = { viewModel.setTaskContextAwarenessEnabled(it) },
                    voiceResponsesEnabled = voiceResponsesEnabled,
                    onToggleVoiceResponses = { viewModel.setVoiceResponsesEnabled(it) },
                    signedInEmail = signedInEmail,
                    syncStatus = syncStatus,
                    isSyncing = isSyncing,
                    onGoogleSignIn = { googleSignInLauncher.launch(viewModel.googleSignInIntent()) },
                    onSignOut = { viewModel.signOut() },
                    onSyncNow = { viewModel.syncNow() },
                    modelCatalog = viewModel.getModelCatalog(),
                    requestLogs = requestLogs,
                    onSendMessage = { input ->
                        handleUserInput(input)
                    },
                    onToggleTask = { task ->
                        viewModel.toggleTask(task)
                    },
                    onAddTask = { title, category, priority, deadline ->
                        viewModel.addManualTask(title, category, priority, deadline)
                    },
                    onEditTask = { task ->
                        viewModel.updateTask(task)
                    },
                    onDeleteTask = { task ->
                        viewModel.deleteTask(task)
                    },
                    onSearchFacts = { query ->
                        viewModel.searchFacts(query)
                    },
                    onAddFact = { title, category, details ->
                        viewModel.addManualFact(title, category, details)
                    },
                    onEditFact = { fact ->
                        viewModel.updateFact(fact)
                    },
                    onDeleteFact = { fact ->
                        viewModel.deleteFact(fact)
                    },
                    onUpdateApiKey = { newKey ->
                        viewModel.updateApiKey(newKey)
                    },
                    onUpdateSelectedModel = { modelName ->
                        viewModel.updateSelectedModel(modelName)
                    },
                    onToggleDeveloperMode = { enabled ->
                        viewModel.setDeveloperMode(enabled)
                    },
                    onRunHealthCheck = { onResult ->
                        viewModel.runHealthCheck(onResult)
                    },
                    onTestConnection = { onResult ->
                        viewModel.testConnection(onResult)
                    },
                    pages = pages,
                    selectedPageId = selectedPageId,
                    blocks = blocks,
                    noteSearchResults = noteSearchResults,
                    relatedPages = relatedPages,
                    onOpenPage = { viewModel.openPage(it) },
                    onClosePage = { viewModel.closePage() },
                    onSearchNotes = { viewModel.searchNotes(it) },
                    onCreatePage = { title, category, tags -> viewModel.createPage(title, category, tags) },
                    onRenamePage = { page, newTitle -> viewModel.renamePage(page, newTitle) },
                    onUpdatePageTags = { page, tags -> viewModel.updatePageTags(page, tags) },
                    onDeletePage = { viewModel.deletePage(it) },
                    onAddBlock = { type, afterId -> viewModel.addBlock(type, afterId) },
                    focusBlockId = focusBlockId,
                    onFocusHandled = { viewModel.consumeFocusBlock() },
                    onUpdateBlockContent = { block, content -> viewModel.updateBlockContent(block, content) },
                    onToggleBlockChecked = { viewModel.toggleBlockChecked(it) },
                    onChangeBlockType = { block, type -> viewModel.changeBlockType(block, type) },
                    onDeleteBlock = { viewModel.deleteBlock(it) },
                    onLinkPage = { viewModel.linkPage(it) },
                    onUnlinkPage = { viewModel.unlinkPage(it) },
                    goals = goals,
                    projects = projects,
                    dailyPlan = dailyPlan,
                    isPlanning = isPlanning,
                    onGeneratePlan = { viewModel.generateDailyPlan() },
                    onToggleGoalComplete = { viewModel.toggleGoalComplete(it) },
                    onIncrementGoal = { goal, delta -> viewModel.incrementGoalProgress(goal, delta) },
                    onAddGoal = { title, category, target, deadline -> viewModel.addGoal(title, category, target, deadline) },
                    onDeleteGoal = { viewModel.deleteGoal(it) },
                    onAddProject = { name, description -> viewModel.addProject(name, description) },
                    onCycleProjectStatus = { viewModel.cycleProjectStatus(it) },
                    onDeleteProject = { viewModel.deleteProject(it) },
                    habits = habits,
                    onAddHabit = { name, category -> viewModel.addHabit(name, category) },
                    onToggleHabit = { viewModel.toggleHabitToday(it) },
                    onDeleteHabit = { viewModel.deleteHabit(it) },
                    initialTab = startTab,
                    isListening = isListening,
                    voiceDraft = voiceDraft,
                    conversationMode = conversationMode,
                    onToggleConversationMode = {
                        val enabling = !viewModel.conversationMode.value
                        viewModel.setConversationMode(enabling)
                        if (enabling) startVoiceInput() else {
                            liveVoiceProvider.stop()
                            voiceManager.stopListening()
                        }
                    },
                    onMicClick = {
                        handleMicClick()
                    },
                    onNewChat = {
                        viewModel.startNewChat()
                    },
                    onSpeakBriefing = { text ->
                        if (voiceResponsesEnabled) voiceManager.speak(text)
                    },
                    onStopSpeaking = {
                        voiceManager.stopSpeaking()
                    },
                    onRunBriefingNow = {
                        runBriefingNow()
                    },
                    onClearAllData = { viewModel.clearAllLocalData() },
                    visionAnalysis = visionAnalysis,
                    isAnalyzingImage = isAnalyzingImage,
                    onChooseVisionImage = { imagePicker.launch("image/*") }
                )
            }
        }
    }

    // Device commands (open app, call, search, navigate, alarm/timer) run locally; everything else goes to the AI.
    private fun handleUserInput(input: String) {
        val command = DeviceCommandParser.parse(input)
        if (command != null) {
            val reply = deviceController.execute(command)
            viewModel.logAssistantAction(input, reply)
            if (viewModel.voiceResponsesEnabled.value) voiceManager.speak(reply)
        } else {
            viewModel.sendMessage(input) { speechText ->
                if (viewModel.voiceResponsesEnabled.value) voiceManager.speak(speechText)
            }
        }
    }

    private fun handleMicClick() {
        // Tapping the mic while listening stops it; otherwise start a voice turn.
        if (viewModel.isListening.value) {
            if (viewModel.conversationMode.value) liveVoiceProvider.stop()
            else voiceManager.stopListening()
            return
        }
        startVoiceInput()
    }

    private fun startVoiceInput() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            if (viewModel.conversationMode.value) liveVoiceProvider.start()
            else voiceManager.startListening()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun scheduleDailyBriefingWorker() {
        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyAgentWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(computeDelayToNext9amMillis(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "JaxDailyBriefingWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            dailyWorkRequest
        )
    }

    private fun scheduleConsolidationWorker() {
        val request = PeriodicWorkRequestBuilder<ConsolidationWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(computeDelayToNext9pmMillis(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "JaxConsolidationWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            request
        )
    }

    // Enqueues the daily briefing worker immediately so the 9 AM notification can be tested on demand.
    private fun runBriefingNow() {
        val request = OneTimeWorkRequestBuilder<DailyAgentWorker>().build()
        WorkManager.getInstance(this).enqueue(request)
        Toast.makeText(this, "Running briefing now \u2014 check your notifications.", Toast.LENGTH_SHORT).show()
    }

    // Milliseconds from now until the next daily briefing time.
    private fun computeDelayToNext9amMillis(): Long {
        val now = java.util.Calendar.getInstance()
        val next = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, com.jax.assistant.config.AppConfig.DAILY_BRIEFING_HOUR)
            set(java.util.Calendar.MINUTE, com.jax.assistant.config.AppConfig.DAILY_BRIEFING_MINUTE)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    private fun computeDelayToNext9pmMillis(): Long {
        val now = java.util.Calendar.getInstance()
        val next = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.HOUR_OF_DAY, 21)
            set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    private fun scheduleWeeklyReviewWorker() {
        val weeklyWorkRequest = PeriodicWorkRequestBuilder<WeeklyReviewWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(computeDelayToNextSunday6pmMillis(), TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "JaxWeeklyReviewWork",
            ExistingPeriodicWorkPolicy.UPDATE,
            weeklyWorkRequest
        )
    }

    // Milliseconds from now until the next weekly review time.
    private fun computeDelayToNextSunday6pmMillis(): Long {
        val now = java.util.Calendar.getInstance()
        val next = java.util.Calendar.getInstance().apply {
            set(java.util.Calendar.DAY_OF_WEEK, com.jax.assistant.config.AppConfig.WEEKLY_REVIEW_DAY)
            set(java.util.Calendar.HOUR_OF_DAY, com.jax.assistant.config.AppConfig.WEEKLY_REVIEW_HOUR)
            set(java.util.Calendar.MINUTE, com.jax.assistant.config.AppConfig.WEEKLY_REVIEW_MINUTE)
            set(java.util.Calendar.SECOND, 0)
            set(java.util.Calendar.MILLISECOND, 0)
            if (!after(now)) add(java.util.Calendar.WEEK_OF_YEAR, 1)
        }
        return next.timeInMillis - now.timeInMillis
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::voiceManager.isInitialized) {
            voiceManager.shutdown()
        }
        if (::liveVoiceProvider.isInitialized) {
            liveVoiceProvider.shutdown()
        }
    }
}
