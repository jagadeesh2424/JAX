package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Checkpoint preventing the sleep cycle from promoting the same agent turn twice.
@Entity(tableName = "consolidated_runs")
data class ConsolidatedRunEntity(
    @PrimaryKey val runId: String,
    val consolidatedAt: Long = System.currentTimeMillis()
)