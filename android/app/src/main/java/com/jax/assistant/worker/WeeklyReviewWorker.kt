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

class WeeklyReviewWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    override suspend fun doWork(): Result {
        val db = AppDatabase.getDatabase(appContext)
        val openHighPriority = db.taskDao().getOpenHighPriorityTasks().size
        val openGoals = db.goalDao().getOpenGoals()
        val goalsOnTrack = openGoals.count { it.targetValue > 0 && it.currentValue.toFloat() / it.targetValue >= 0.5f }

        val summary = buildString {
            append("Weekly review: ")
            append("$openHighPriority high-priority task(s) still open. ")
            append("${openGoals.size} active goal(s), $goalsOnTrack on track. ")
            append("Tap to open your Executive Dashboard.")
        }

        showNotification("J.A.X. Weekly Review", summary)
        return Result.success()
    }

    private fun showNotification(title: String, body: String) {
        val channelId = com.jax.assistant.config.AppConfig.WEEKLY_CHANNEL_ID
        val notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "J.A.X. Weekly Reviews",
                NotificationManager.IMPORTANCE_DEFAULT
            )
            notificationManager.createNotificationChannel(channel)
        }

        val contentIntent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open_dashboard", true)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            2002,
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

        notificationManager.notify(1002, notification)
    }
}
