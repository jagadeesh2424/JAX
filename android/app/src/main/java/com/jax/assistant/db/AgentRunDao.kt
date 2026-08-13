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
}
