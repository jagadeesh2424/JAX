package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Cached embedding for a fact (Phase 3 semantic memory). vector is a little-endian float BLOB.
@Entity(tableName = "fact_embeddings")
data class FactEmbeddingEntity(
    @PrimaryKey val factId: String,
    val dim: Int,
    val vector: ByteArray
)
