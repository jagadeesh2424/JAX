package com.jax.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jax.assistant.ai.agent.AutonomyLevel
import com.jax.assistant.ai.agent.InsightGate
import com.jax.assistant.ai.agent.ProactiveInsightEngine
import com.jax.assistant.ai.agent.tools.WeatherClient
import com.jax.assistant.data.UserPreferencesRepository
import com.jax.assistant.db.AppDatabase
import com.jax.assistant.notify.JaxNotification
import com.jax.assistant.notify.NotificationDispatcher
import com.jax.assistant.notify.NotificationKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate

class DailyAgentWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val preferences = UserPreferencesRepository(appContext)
        val level = preferences.getProactiveAutonomyLevel()
        if (!preferences.isDailyAutomationEnabled() || level == AutonomyLevel.OFF) return Result.success()

        val database = AppDatabase.getDatabase(appContext)
        val tasks = database.taskDao().getAllTasksList()
        val engine = ProactiveInsightEngine()
        val today = LocalDate.now()
        val useTaskContext = preferences.isTaskContextAwarenessEnabled()

        val history = appContext.getSharedPreferences(INSIGHT_PREFS, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val selected = if (useTaskContext) {
            val recentRuns = database.agentRunDao().recentRuns(RECENT_RUNS)
            val candidates = engine.suggest(tasks, today, level, fetchWeatherBestEffort(), recentRuns, now)
            val lastShown = history.all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
            InsightGate.select(candidates, lastShown, now)
        } else {
            emptyList()
        }

        val body = if (selected.isEmpty()) {
            engine.dailyBriefing(tasks, today, useTaskContext)
        } else {
            selected.joinToString("\n") { s ->
                "\u2022 ${s.observation}" + (s.suggestedAction?.let { " $it" } ?: "")
            }
        }

        NotificationDispatcher(appContext, isEnabled = preferences::notificationsEnabled).deliver(
            JaxNotification("briefing:daily", NotificationKind.INSIGHT, "J.A.X. Daily Briefing", body),
            extras = mapOf("open_briefing" to true)
        )
        history.edit().apply { selected.forEach { putLong(it.dedupKey, now) } }.apply()
        return Result.success()
    }

    // Weather only enriches insights; a network failure must never block the briefing.
    private suspend fun fetchWeatherBestEffort() = try {
        withTimeoutOrNull(WEATHER_TIMEOUT_MS) { WeatherClient().fetch("", dayOffset = 0) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private companion object {
        const val INSIGHT_PREFS = "jax_insight_history"
        const val WEATHER_TIMEOUT_MS = 15_000L
        const val RECENT_RUNS = 20
    }
}
