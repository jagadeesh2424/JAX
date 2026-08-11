package com.jax.assistant.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jax.assistant.MainActivity
import com.jax.assistant.db.AppDatabase

class DailyAgentWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(appContext)
        val highPriorityTasks = db.taskDao().getOpenHighPriorityTasks()

        val summaryText = if (highPriorityTasks.isNotEmpty()) {
            "Good morning Jagadeesh. You have ${highPriorityTasks.size} HIGH priority task(s) scheduled for today."
        } else {
            "Good morning Jagadeesh. All high priority tasks are clear. Ready for new briefings."
        }

        showNotification("J.A.X. Daily Briefing", summaryText)
        return Result.success()
    }

    private fun showNotification(title: String, body: String) {
        val channelId = com.jax.assistant.config.AppConfig.DAILY_CHANNEL_ID
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "J.A.X. Daily Briefings",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_briefing", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            2001,
            contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(appContext, channelId)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(1001, notification)
    }
}
