package com.jax.assistant.notify

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.jax.assistant.MainActivity
import com.jax.assistant.config.AppConfig

// The only place JAX posts or cancels Android notifications. Applies NotificationPolicy
// (preferences, de-duplication) and keeps a small delivery ledger in SharedPreferences.
class NotificationDispatcher(
    context: Context,
    private val isEnabled: (NotificationKind) -> Boolean = { true },
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val ledger = appContext.getSharedPreferences(LEDGER_PREFS, Context.MODE_PRIVATE)

    fun deliver(notification: JaxNotification, extras: Map<String, Boolean> = emptyMap()): NotificationPolicy.Decision {
        val decision = NotificationPolicy.decide(notification, record(notification.key), isEnabled(notification.kind), clock())
        if (decision.delivers) {
            ensureChannel(notification.kind)
            // On Android 13+ without POST_NOTIFICATIONS this is a silent no-op.
            manager.notify(notification.id, build(notification, extras))
            ledger.edit().putString(notification.key, "${notification.contentHash}|${clock()}").apply()
        }
        return decision
    }

    fun cancel(key: String) {
        manager.cancel(key.hashCode())
        ledger.edit().remove(key).apply()
    }

    private fun record(key: String): DeliveryRecord? {
        val parts = ledger.getString(key, null)?.split('|') ?: return null
        val hash = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val at = parts.getOrNull(1)?.toLongOrNull() ?: return null
        return DeliveryRecord(hash, at)
    }

    private fun build(n: JaxNotification, extras: Map<String, Boolean>) =
        NotificationCompat.Builder(appContext, channelId(n.kind))
            .setSmallIcon(if (n.kind == NotificationKind.REMINDER) android.R.drawable.ic_popup_reminder else android.R.drawable.ic_dialog_info)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(n.body))
            .setContentIntent(openApp(n, extras))
            .setPriority(if (n.actionRequired || n.kind == NotificationKind.REMINDER) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(if (n.kind == NotificationKind.REMINDER) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_STATUS)
            .setAutoCancel(true)
            .build()

    private fun openApp(n: JaxNotification, extras: Map<String, Boolean>): PendingIntent {
        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            extras.forEach { (k, v) -> putExtra(k, v) }
        }
        return PendingIntent.getActivity(appContext, n.id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ensureChannel(kind: NotificationKind) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val (name, importance) = when (kind) {
            NotificationKind.REMINDER -> "J.A.X. Reminders" to NotificationManager.IMPORTANCE_HIGH
            NotificationKind.INSIGHT -> "J.A.X. Daily Briefings" to NotificationManager.IMPORTANCE_DEFAULT
            NotificationKind.AGENT_RESULT -> "J.A.X. Task Results" to NotificationManager.IMPORTANCE_DEFAULT
            NotificationKind.ATTENTION -> "J.A.X. Needs Attention" to NotificationManager.IMPORTANCE_HIGH
        }
        manager.createNotificationChannel(NotificationChannel(channelId(kind), name, importance))
    }

    // Existing channel ids are kept so users' channel settings survive the update.
    private fun channelId(kind: NotificationKind): String = when (kind) {
        NotificationKind.REMINDER -> AppConfig.REMINDER_CHANNEL_ID
        NotificationKind.INSIGHT -> AppConfig.DAILY_CHANNEL_ID
        NotificationKind.AGENT_RESULT -> "jax_agent_results_channel"
        NotificationKind.ATTENTION -> "jax_attention_channel"
    }

    private companion object {
        const val LEDGER_PREFS = "jax_notification_ledger"
    }
}
