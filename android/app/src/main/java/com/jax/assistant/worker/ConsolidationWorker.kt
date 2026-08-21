package com.jax.assistant.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.jax.assistant.data.ServiceLocator

// Daily local "sleep cycle": promotes tool-backed completed turns once, without model calls.
class ConsolidationWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result = runCatching {
        ServiceLocator.init(applicationContext)
        val since = System.currentTimeMillis() - DAY_MILLIS
        ServiceLocator.agentRuns.consolidateSince(since) { ServiceLocator.memory.insertFact(it) }
        Result.success()
    }.getOrElse { Result.retry() }

    private companion object {
        const val DAY_MILLIS = 24L * 60L * 60L * 1000L
    }
}