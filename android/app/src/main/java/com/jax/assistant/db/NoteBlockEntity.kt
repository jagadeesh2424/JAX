package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Block types for the Notion-style editor.
object BlockType {
    const val TEXT = "TEXT"
    const val HEADING = "HEADING"
    const val BULLET = "BULLET"
    const val CHECKLIST = "CHECKLIST"
    const val CODE = "CODE"
    const val QUOTE = "QUOTE"
    const val DIVIDER = "DIVIDER"
}

@Entity(
    tableName = "note_blocks",
    indices = [Index("pageId")]
)
data class NoteBlockEntity(
    @PrimaryKey
    val id: String,
    val pageId: String,
    val type: String = BlockType.TEXT,
    val content: String = "",
    val checked: Boolean = false,
    val position: Int = 0
)
