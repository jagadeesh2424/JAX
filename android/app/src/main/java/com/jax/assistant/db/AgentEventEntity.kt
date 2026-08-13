package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// A single event within an agent run (plan/tool_call/tool_result/final/error).
@Entity(tableName = "agent_events")
data class AgentEventEntity(
    @PrimaryKey val id: String,
    val runId: String,
    val type: String,
    val detail: String,
    val timestamp: Long
)
