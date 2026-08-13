package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Persisted chat message so history survives app restarts (Phase 2 durable state).
@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
    @PrimaryKey val id: String,
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)
