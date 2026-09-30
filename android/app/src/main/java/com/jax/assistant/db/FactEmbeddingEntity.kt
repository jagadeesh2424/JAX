package com.jax.assistant.db

import androidx.room.Entity
import androidx.room.PrimaryKey

// Cached embedding for a fact (semantic memory). vector is a little-endian float BLOB.
// factId is the primary key, so it is already indexed; no extra index is declared.
@Entity(tableName = "fact_embeddings")
data class FactEmbeddingEntity(
    @PrimaryKey val factId: String,
    val dim: Int,
    val vector: ByteArray
) {
    // Data classes compare arrays by reference; compare by content instead.
    override fun equals(other: Any?): Boolean =
        other is FactEmbeddingEntity && factId == other.factId && dim == other.dim && vector.contentEquals(other.vector)

    override fun hashCode(): Int = 31 * (31 * factId.hashCode() + dim) + vector.contentHashCode()
}
