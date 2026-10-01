package com.jax.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import com.jax.assistant.ai.agent.tools.ReminderScheduler
import com.jax.assistant.notify.JaxNotification
import com.jax.assistant.notify.NotificationDispatcher
import com.jax.assistant.notify.NotificationKind
import kotlinx.coroutines.CancellationException
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

// Schedules one notification per reminder, keyed by task id so re-scheduling replaces it.
class WorkManagerReminderScheduler(private val context: Context) : ReminderScheduler {
    override suspend fun schedule(taskId: String, title: String, at: LocalDateTime): Boolean {
        val delayMs = Duration.between(LocalDateTime.now(ZoneId.systemDefault()), at).toMillis()
        if (delayMs <= 0) return false
        val request = OneTimeWorkRequestBuilder<ReminderWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(workDataOf(ReminderWorker.KEY_TITLE to title, ReminderWorker.KEY_TASK_ID to taskId))
            .addTag(ReminderWorker.TAG)
            .build()
        return try {
            // await() completes only after WorkManager has persisted the request.
            WorkManager.getInstance(context.applicationContext)
                .enqueueUniqueWork("reminder_$taskId", ExistingWorkPolicy.REPLACE, request)
                .await()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}

class ReminderWorker(
    private val appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val title = inputData.getString(KEY_TITLE).orEmpty().ifBlank { "Reminder" }
        val taskId = inputData.getString(KEY_TASK_ID).orEmpty()
        NotificationDispatcher(appContext).deliver(
            JaxNotification("reminder:$taskId", NotificationKind.REMINDER, "J.A.X. Reminder", title)
        )
        return Result.success()
    }

    companion object {
        const val TAG = "jax_reminder"
        const val KEY_TITLE = "title"
        const val KEY_TASK_ID = "task_id"
    }
}
