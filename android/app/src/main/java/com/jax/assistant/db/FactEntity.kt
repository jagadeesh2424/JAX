package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "facts")
data class FactEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val category: String, // Personal, Finance, Work, Tech
    val details: String,
    val createdAt: Long = System.currentTimeMillis()
)
