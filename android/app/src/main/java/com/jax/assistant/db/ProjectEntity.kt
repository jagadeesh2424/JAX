package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String = "",
    val status: String = ProjectStatus.ACTIVE, // ACTIVE, ON_HOLD, DONE
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

object ProjectStatus {
    const val ACTIVE = "ACTIVE"
    const val ON_HOLD = "ON_HOLD"
    const val DONE = "DONE"
}
