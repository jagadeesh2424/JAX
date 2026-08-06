package com.jax.assistant.executive.timeline

enum class TimelineSourceType {
    TASK,
    CALENDAR_EVENT,
    REMINDER,
    BIRTHDAY,
    HABIT,
    ANNIVERSARY
}

data class TimelineItem(
    val id: String,
    val title: String,
    val category: String = "General",
    val dateString: String = "Today",
    val timeString: String? = null,
    val sourceType: TimelineSourceType = TimelineSourceType.TASK,
    val priority: String = "MED",
    val isCompleted: Boolean = false,
    val originalEntityId: String = ""
)
