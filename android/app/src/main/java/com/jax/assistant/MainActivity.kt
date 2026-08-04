package com.jax.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.lifecycle.lifecycleScope
import androidx.work.*
import com.jax.assistant.ai.GeminiBrain
import com.jax.assistant.ai.JaxParseResult
import com.jax.assistant.db.AppDatabase
import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import com.jax.assistant.ui.screens.ComposeChatMessage
import com.jax.assistant.ui.screens.MainScreen
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.voice.VoiceManager
import com.jax.assistant.worker.DailyAgentWorker
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var db: AppDatabase
    private lateinit var geminiBrain: GeminiBrain
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = AppDatabase.getDatabase(this)
        // Read Gemini key from BuildConfig or environment
        val apiKey = System.getenv("GEMINI_API_KEY") ?: ""
        geminiBrain = GeminiBrain(apiKey)

        // Setup WorkManager for Daily 9:00 AM Agent Briefing
        scheduleDailyBriefingWorker()

        setContent {
            JAXAssistantTheme {
                var messages by remember { mutableStateOf(listOf(
                    ComposeChatMessage("1", "Good day, Jagadeesh. J.A.X. is active and synced to your Room database.", false, "Now")
                )) }

                val tasksState = remember { mutableStateListOf<TaskEntity>() }
                val factsState = remember { mutableStateListOf<FactEntity>() }

                // Observe Room DB Flows
                LaunchedEffect(Unit) {
                    lifecycleScope.launch {
                        db.taskDao().getAllTasks().collectLatest { list ->
                            tasksState.clear()
                            tasksState.addAll(list)
                        }
                    }
                    lifecycleScope.launch {
                        db.factDao().getAllFacts().collectLatest { list ->
                            factsState.clear()
                            factsState.addAll(list)
                        }
                    }
                }

                // Initialize Voice STT & TTS
                voiceManager = remember {
                    VoiceManager(this@MainActivity) { text ->
                        handleUserPrompt(text, messages, { messages = it })
                    }
                }

                MainScreen(
                    messages = messages,
                    tasks = tasksState,
                    facts = factsState,
                    onSendMessage = { input ->
                        handleUserPrompt(input, messages, { messages = it })
                    },
                    onToggleTask = { task ->
                        lifecycleScope.launch {
                            db.taskDao().updateTask(task.copy(isCompleted = !task.isCompleted))
                        }
                    },
                    onSearchFacts = { query ->
                        lifecycleScope.launch {
                            db.factDao().searchFacts(query).collectLatest { list ->
                                factsState.clear()
                                factsState.addAll(list)
                            }
                        }
                    },
                    onMicClick = {
                        voiceManager.startListening()
                    },
                    onSpeakBriefing = { text ->
                        voiceManager.speak(text)
                    }
                )
            }
        }
    }

    private fun handleUserPrompt(
        input: String,
        currentMessages: List<ComposeChatMessage>,
        updateMessages: (List<ComposeChatMessage>) -> Unit
    ) {
        val userMsg = ComposeChatMessage(System.currentTimeMillis().toString(), input, true, "Now")
        val updated = currentMessages + userMsg
        updateMessages(updated)

        lifecycleScope.launch {
            val result = geminiBrain.processUserInput(input)
            when (result) {
                is JaxParseResult.TaskResult -> {
                    db.taskDao().insertTask(result.task)
                    val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                    updateMessages(updated + aiMsg)
                    voiceManager.speak(result.reply)
                }
                is JaxParseResult.FactResult -> {
                    db.factDao().insertFact(result.fact)
                    val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                    updateMessages(updated + aiMsg)
                    voiceManager.speak(result.reply)
                }
                is JaxParseResult.QuestionResult -> {
                    val aiMsg = ComposeChatMessage(System.currentTimeMillis().toString(), result.reply, false, "Now")
                    updateMessages(updated + aiMsg)
                    voiceManager.speak(result.reply)
                }
            }
        }
    }

    private fun scheduleDailyBriefingWorker() {
        val dailyWorkRequest = PeriodicWorkRequestBuilder<DailyAgentWorker>(24, TimeUnit.HOURS)
            .setInitialDelay(2, TimeUnit.HOURS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "JaxDailyBriefingWork",
            ExistingPeriodicWorkPolicy.KEEP,
            dailyWorkRequest
        )
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceManager.shutdown()
    }
}
