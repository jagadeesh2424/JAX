package com.jax.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.work.*
import com.jax.assistant.ui.MainViewModel
import com.jax.assistant.ui.screens.MainScreen
import com.jax.assistant.ui.theme.JAXAssistantTheme
import com.jax.assistant.voice.VoiceManager
import com.jax.assistant.worker.DailyAgentWorker
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var voiceManager: VoiceManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Setup WorkManager for Daily 9:00 AM Agent Briefing
        scheduleDailyBriefingWorker()

        setContent {
            JAXAssistantTheme {
                val messages by viewModel.messages.collectAsState()
                val tasks by viewModel.tasks.collectAsState()
                val facts by viewModel.facts.collectAsState()
                val apiKey by viewModel.apiKey.collectAsState()

                // Initialize Voice STT & TTS
                voiceManager = remember {
                    VoiceManager(this@MainActivity) { text ->
                        viewModel.sendMessage(text) { speechText ->
                            voiceManager.speak(speechText)
                        }
                    }
                }

                MainScreen(
                    messages = messages,
                    tasks = tasks,
                    facts = facts,
                    apiKey = apiKey,
                    onSendMessage = { input ->
                        viewModel.sendMessage(input) { speechText ->
                            voiceManager.speak(speechText)
                        }
                    },
                    onToggleTask = { task ->
                        viewModel.toggleTask(task)
                    },
                    onAddTask = { title, category, priority, deadline ->
                        viewModel.addManualTask(title, category, priority, deadline)
                    },
                    onSearchFacts = { query ->
                        viewModel.searchFacts(query)
                    },
                    onAddFact = { title, category, details ->
                        viewModel.addManualFact(title, category, details)
                    },
                    onUpdateApiKey = { newKey ->
                        viewModel.updateApiKey(newKey)
                    },
                    onMicClick = {
                        voiceManager.startListening()
                    },
                    onSpeakBriefing = { text ->
                        voiceManager.speak(text)
                    },
                    onStopSpeaking = {
                        voiceManager.stopSpeaking()
                    }
                )
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
        if (::voiceManager.isInitialized) {
            voiceManager.shutdown()
        }
    }
}
