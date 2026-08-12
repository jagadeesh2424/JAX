package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "note_pages")
data class NotePageEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    val category: String = "Personal Notes",
    val tags: String = "", // comma-separated tags
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
