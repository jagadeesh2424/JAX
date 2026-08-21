package com.jax.assistant.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRun(run: AgentRunEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: AgentEventEntity)

    @Query("SELECT * FROM agent_runs ORDER BY startedAt DESC LIMIT :limit")
    suspend fun recentRuns(limit: Int): List<AgentRunEntity>

    @Query("SELECT * FROM agent_events WHERE runId = :runId ORDER BY timestamp ASC")
    suspend fun eventsForRun(runId: String): List<AgentEventEntity>

    @Query("SELECT * FROM agent_runs WHERE status = 'COMPLETED' ORDER BY finishedAt DESC LIMIT :limit")
    suspend fun completedRuns(limit: Int): List<AgentRunEntity>

    @Query(
        "SELECT r.* FROM agent_runs r LEFT JOIN consolidated_runs c ON c.runId = r.id " +
            "WHERE r.status = 'COMPLETED' AND r.finishedAt >= :since AND c.runId IS NULL " +
            "ORDER BY r.finishedAt ASC"
    )
    suspend fun unconsolidatedCompletedRuns(since: Long): List<AgentRunEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConsolidatedRun(run: ConsolidatedRunEntity)
}
