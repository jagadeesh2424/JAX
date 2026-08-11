package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val category: String, // Health, Career, Personal, Finance, Relationship
    val streak: Int = 0,
    val lastCompletedDate: String? = null, // ISO yyyy-MM-dd of the last completion
    val createdAt: Long = System.currentTimeMillis()
)
