package com.jax.assistant.data

import com.jax.assistant.ai.agent.AgentEvent
import com.jax.assistant.ai.agent.WorkflowLearner
import com.jax.assistant.db.AgentEventEntity
import com.jax.assistant.db.AgentRunDao
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.ConsolidatedRunEntity
import com.jax.assistant.db.FactEntity
import java.util.UUID

// Persists agent runs and their event streams (Phase 2 durable state).
class AgentRunRepository(private val dao: AgentRunDao) {
    suspend fun learnedWorkflows(limit: Int = 2): List<String> =
        WorkflowLearner().suggestions(dao.completedRuns(50), limit)

    suspend fun consolidateSince(since: Long, saveFact: suspend (FactEntity) -> Unit): Int {
        val runs = dao.unconsolidatedCompletedRuns(since)
        runs.forEach { run ->
            if (run.toolsUsed.isNotBlank()) {
                val eventSummary = dao.eventsForRun(run.id)
                    .filter { it.type != "thought" }
                    .take(6)
                    .joinToString("\n") { "${it.type}: ${it.detail.take(180)}" }
                saveFact(
                    FactEntity(
                        id = UUID.randomUUID().toString(),
                        title = "Completed: ${run.goal.take(120)}",
                        category = "Episodic",
                        details = buildString {
                            append("Outcome: ${run.reply.take(600)}\nTools: ${run.toolsUsed}")
                            if (eventSummary.isNotBlank()) append("\nEvents:\n$eventSummary")
                        },
                        createdAt = run.finishedAt
                    )
                )
            }
            dao.insertConsolidatedRun(ConsolidatedRunEntity(run.id))
        }
        return runs.count { it.toolsUsed.isNotBlank() }
    }

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
