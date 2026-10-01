package com.jax.assistant.notify

// What JAX can surface. Business logic creates these; only NotificationDispatcher talks to Android.
enum class NotificationKind(val dedupWindowMs: Long, val alwaysAllowed: Boolean) {
    REMINDER(60_000L, alwaysAllowed = true),           // user asked for it explicitly
    INSIGHT(10 * 60_000L, alwaysAllowed = false),       // proactive briefing / suggestions
    AGENT_RESULT(10 * 60_000L, alwaysAllowed = false),  // a background task finished
    ATTENTION(5 * 60_000L, alwaysAllowed = true)        // failure or approval that needs the user
}

// `key` identifies the subject (e.g. "reminder:<taskId>"): posting the same key again updates the
// existing notification instead of adding another.
data class JaxNotification(
    val key: String,
    val kind: NotificationKind,
    val title: String,
    val body: String
) {
    val id: Int get() = key.hashCode()
    val actionRequired: Boolean get() = kind == NotificationKind.ATTENTION
    val contentHash: Int get() = (title + "\u0000" + body).hashCode()
}

data class DeliveryRecord(val contentHash: Int, val deliveredAt: Long)

object NotificationPolicy {
    enum class Decision {
        POST, UPDATE, SUPPRESS_DUPLICATE, SUPPRESS_DISABLED;

        val delivers: Boolean get() = this == POST || this == UPDATE
    }

    fun decide(
        notification: JaxNotification,
        previous: DeliveryRecord?,
        enabled: Boolean,
        nowMillis: Long
    ): Decision {
        if (!enabled && !notification.kind.alwaysAllowed) return Decision.SUPPRESS_DISABLED
        if (previous == null) return Decision.POST
        val sameContent = previous.contentHash == notification.contentHash
        return when {
            sameContent && nowMillis - previous.deliveredAt < notification.kind.dedupWindowMs -> Decision.SUPPRESS_DUPLICATE
            sameContent -> Decision.POST
            else -> Decision.UPDATE
        }
    }
}
