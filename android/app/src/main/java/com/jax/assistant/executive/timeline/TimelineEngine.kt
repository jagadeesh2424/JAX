package com.jax.assistant.executive.timeline

import com.jax.assistant.db.FactEntity
import com.jax.assistant.db.TaskEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.LocalDate

class TimelineEngine {

    private val _timelineState = MutableStateFlow<List<TimelineItem>>(emptyList())
    val timelineState: StateFlow<List<TimelineItem>> = _timelineState.asStateFlow()

    fun updateTimelineProjection(tasks: List<TaskEntity>, facts: List<FactEntity>) {
        val items = mutableListOf<TimelineItem>()

        // 1. Projection from Tasks
        tasks.forEach { task ->
            items.add(
                TimelineItem(
                    id = "timeline_task_${task.id}",
                    title = task.title,
                    category = task.category,
                    dateString = task.deadline ?: "Today",
                    sourceType = TimelineSourceType.TASK,
                    priority = task.priority,
                    isCompleted = task.isCompleted,
                    originalEntityId = task.id
                )
            )
        }

        // 2. Projection from Memory Facts (Birthdays, Anniversaries, Reminders)
        facts.forEach { fact ->
            val lower = fact.factText.lowercase()
            when {
                lower.contains("birthday") || lower.contains("born on") -> {
                    items.add(
                        TimelineItem(
                            id = "timeline_fact_${fact.id}",
                            title = "🎂 ${fact.factText}",
                            category = fact.category,
                            dateString = extractDateFromText(fact.factText),
                            sourceType = TimelineSourceType.BIRTHDAY,
                            originalEntityId = fact.id
                        )
                    )
                }
                lower.contains("anniversary") -> {
                    items.add(
                        TimelineItem(
                            id = "timeline_fact_${fact.id}",
                            title = "💍 ${fact.factText}",
                            category = fact.category,
                            dateString = extractDateFromText(fact.factText),
                            sourceType = TimelineSourceType.ANNIVERSARY,
                            originalEntityId = fact.id
                        )
                    )
                }
                lower.contains("remind") || lower.contains("meeting") || lower.contains("event") -> {
                    items.add(
                        TimelineItem(
                            id = "timeline_fact_${fact.id}",
                            title = "📅 ${fact.factText}",
                            category = fact.category,
                            dateString = extractDateFromText(fact.factText),
                            sourceType = TimelineSourceType.CALENDAR_EVENT,
                            originalEntityId = fact.id
                        )
                    )
                }
            }
        }

        // Sort items: High priority first, then incomplete, then by title
        items.sortWith(compareByDescending<TimelineItem> { it.priority == "HIGH" }.thenBy { it.isCompleted })
        _timelineState.value = items
    }

    private fun extractDateFromText(text: String): String {
        val lower = text.lowercase()
        return when {
            lower.contains("today") -> "Today"
            lower.contains("tomorrow") -> "Tomorrow"
            lower.contains("monday") -> "Monday"
            lower.contains("friday") -> "Friday"
            else -> LocalDate.now().toString()
        }
    }
}
