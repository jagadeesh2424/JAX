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
    val createdAt: Long = System.currentTimeMillis(),
    // SEMANTIC (stable fact/preference), EPISODIC (past event), PROCEDURAL (how-to pattern).
    val memoryType: String = "SEMANTIC",
    val confidence: Double = 1.0,
    // Where it came from: "user", "chat", "agent_run:<id>".
    val source: String = "user",
    val updatedAt: Long = 0L
)
