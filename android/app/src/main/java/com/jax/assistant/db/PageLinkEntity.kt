package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// An undirected knowledge-graph edge between two note pages.
// Stored once per pair; queries look both directions.
@Entity(
    tableName = "page_links",
    indices = [Index("fromPageId"), Index("toPageId")]
)
data class PageLinkEntity(
    @PrimaryKey val id: String,
    val fromPageId: String,
    val toPageId: String,
    val createdAt: Long = System.currentTimeMillis()
)
