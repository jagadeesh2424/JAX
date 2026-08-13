package com.jax.assistant.data

import com.jax.assistant.db.ChatMessageDao
import com.jax.assistant.db.ChatMessageEntity

// Owns persisted chat history.
class ChatRepository(private val dao: ChatMessageDao) {
    suspend fun getHistory(): List<ChatMessageEntity> = dao.getAll()
    suspend fun save(message: ChatMessageEntity) = dao.insert(message)
    suspend fun clear() = dao.clearAll()
}
