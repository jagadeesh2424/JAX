package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "goals")
data class GoalEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val category: String, // Career, Health, Finance, Personal
    val targetValue: Int = 100, // milestone count or percentage target
    val currentValue: Int = 0,
    val deadline: String?,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)
