package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// A durable record of one agent turn (Phase 2). Foundation for resumable long-running
// tasks (later) and episodic-memory consolidation (Phase 3).
@Entity(tableName = "agent_runs")
data class AgentRunEntity(
    @PrimaryKey val id: String,
    val goal: String,
    val status: String,
    val reply: String,
    val toolsUsed: String,
    val startedAt: Long,
    val finishedAt: Long
)
