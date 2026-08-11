package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val category: String, // Work, Personal, Finance
    val priority: String, // HIGH, MED, LOW
    val deadline: String?,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
