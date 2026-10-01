package com.jax.assistant.data

import com.jax.assistant.ai.agent.AgentEvent
import com.jax.assistant.ai.agent.AgentPlan
import com.jax.assistant.ai.agent.AgentRunStore
import com.jax.assistant.ai.agent.MemoryType
import com.jax.assistant.ai.agent.ResumableRun
import com.jax.assistant.ai.agent.RunStatus
import com.jax.assistant.ai.agent.StepStatus
import com.jax.assistant.ai.agent.WorkflowLearner
import com.jax.assistant.db.AgentEventEntity
import com.jax.assistant.db.AgentRunDao
import com.jax.assistant.db.AgentRunEntity
import com.jax.assistant.db.ConsolidatedRunEntity
import com.jax.assistant.db.FactEntity
import org.json.JSONObject
import java.util.UUID

// Persists agent runs, their plans (as JSON in the existing `plan` column) and event streams.
class AgentRunRepository(
    private val dao: AgentRunDao,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : AgentRunStore {
    override suspend fun startRun(runId: String, goal: String, maxSteps: Int) {
        val now = clock()
        dao.insertRun(
            AgentRunEntity(
                id = runId,
                goal = goal,
                status = RunStatus.CREATED,
                reply = "",
                toolsUsed = "",
                startedAt = now,
                finishedAt = 0L,
                plan = "",
                currentStep = 0,
                maxSteps = maxSteps,
                recoveryState = "Started; awaiting first model decision",
                updatedAt = now
            )
        )
    }

    override suspend fun checkpoint(
        runId: String,
        step: Int,
        state: String,
        toolsUsed: List<String>
    ) {
        dao.updateProgress(
            runId = runId,
            status = RunStatus.RUNNING,
            reply = "",
            toolsUsed = toolsUsed.joinToString(","),
            currentStep = step,
            recoveryState = state,
            finishedAt = 0L,
            updatedAt = clock()
        )
    }

    override suspend fun updateStatus(runId: String, status: String) {
        dao.updateStatus(runId, status, clock())
    }

    suspend fun recoverableRuns(): List<AgentRunEntity> = dao.recoverableRuns()

    // Plan checkpoint: the full step list (status, result, error, retries) plus the current step.
    override suspend fun savePlan(runId: String, planJson: String, currentStep: Int) {
        dao.updatePlan(runId, planJson, currentStep, clock())
    }

    override suspend fun latestResumableRun(): ResumableRun? =
        dao.latestResumableRun(clock() - RESUME_WINDOW_MS)?.let { ResumableRun(it.id, it.goal, it.plan) }

    // Crash recovery: runs a process death left active are marked INTERRUPTED with the exact step
    // they stopped at. Nothing is replayed automatically; the user can say "resume", which
    // continues only what is safe (see RunResumer). Returns a user-facing line per run.
    suspend fun reconcileInterruptedRuns(): List<String> {
        val orphans = dao.recoverableRuns()
        return orphans.map { run ->
            val where = describeStopPoint(run)
            dao.updateProgress(
                runId = run.id,
                status = RunStatus.INTERRUPTED,
                reply = run.reply,
                toolsUsed = run.toolsUsed,
                currentStep = run.currentStep,
                recoveryState = "Interrupted $where; ${run.recoveryState}".take(300),
                finishedAt = clock(),
                updatedAt = clock()
            )
            "\"${run.goal.take(60)}\" stopped $where" +
                if (run.plan.isNotBlank()) ". Say \"resume\" to continue it." else ". Ask me again to continue."
        }
    }

    private fun describeStopPoint(run: AgentRunEntity): String {
        val plan = runCatching { AgentPlan.fromJson(JSONObject(run.plan)) }.getOrNull()
        if (plan == null || plan.steps.isEmpty()) return "at step ${run.currentStep}"
        val index = plan.steps.indexOfFirst { it.status == StepStatus.RUNNING || it.status == StepStatus.PENDING }
        if (index < 0) return "after all ${plan.steps.size} steps, before the final answer"
        val done = plan.steps.count { it.status == StepStatus.COMPLETED }
        return "at step ${index + 1}/${plan.steps.size} (${plan.steps[index].tool}); $done completed"
    }

    override suspend fun finishRun(
        runId: String,
        status: String,
        reply: String,
        toolsUsed: List<String>
    ) {
        dao.updateProgress(
            runId = runId,
            status = status,
            reply = reply,
            toolsUsed = toolsUsed.joinToString(","),
            currentStep = 0,
            recoveryState = when (status) {
                RunStatus.COMPLETED -> "Completed"
                RunStatus.FAILED -> "Failed: ${reply.take(200)}"
                RunStatus.CANCELLED -> "Cancelled before finishing; plan kept for resume"
                else -> "Completed with errors"
            },
            finishedAt = clock(),
            updatedAt = clock()
        )
    }

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
                        createdAt = run.finishedAt,
                        memoryType = MemoryType.EPISODIC.name,
                        confidence = EPISODIC_CONFIDENCE,
                        source = "agent_run:${run.id}",
                        updatedAt = run.finishedAt
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
        dao.insertRun(AgentRunEntity(runId, goal, status, reply, toolsUsed.joinToString(","), startedAt, finishedAt))
        saveEvents(runId, events)
    }

    override suspend fun saveEvents(runId: String, events: List<AgentEvent>) {
        events.forEach { e ->
            dao.insertEvent(AgentEventEntity(UUID.randomUUID().toString(), runId, e.type, e.detail, e.timestamp))
        }
    }

    private companion object {
        const val RESUME_WINDOW_MS = 24L * 60 * 60 * 1000
        const val EPISODIC_CONFIDENCE = 0.8
    }
}
