package com.jax.assistant.data

import com.jax.assistant.ai.agent.AgentEvent
import com.jax.assistant.db.AgentEventEntity
import com.jax.assistant.db.AgentRunDao
import com.jax.assistant.db.AgentRunEntity
import java.util.UUID

// Persists agent runs and their event streams (Phase 2 durable state).
class AgentRunRepository(private val dao: AgentRunDao) {
    suspend fun saveRun(
        runId: String,
        goal: String,
        status: String,
        reply: String,
        toolsUsed: List<String>,
        startedAt: Long,
        finishedAt: Long,
        events: List<AgentEvent>
    ) {
        dao.insertRun(
            AgentRunEntity(runId, goal, status, reply, toolsUsed.joinToString(","), startedAt, finishedAt)
        )
        events.forEach { e ->
            dao.insertEvent(AgentEventEntity(UUID.randomUUID().toString(), runId, e.type, e.detail, e.timestamp))
        }
    }
}
