package com.jax.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jax.assistant.ai.agent.MemoryType
import com.jax.assistant.data.ServiceLocator

// Daily local "sleep cycle": promotes tool-backed completed turns once, without model calls.
class ConsolidationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        ServiceLocator.init(applicationContext)
        val since = System.currentTimeMillis() - DAY_MILLIS
        ServiceLocator.agentRuns.consolidateSince(since) { fact ->
            // Upsert: repeating the same goal refreshes one episodic memory instead of adding copies.
            ServiceLocator.memory.upsertFact(
                fact.title, fact.category, fact.details, MemoryType.EPISODIC, fact.confidence, fact.source, fact.updatedAt
            )
        }
        Result.success()
    }.getOrElse { Result.retry() }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}