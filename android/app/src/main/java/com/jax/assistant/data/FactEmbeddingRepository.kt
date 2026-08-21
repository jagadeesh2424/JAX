package com.jax.assistant.data

import com.jax.assistant.ai.VectorUtils
import com.jax.assistant.db.FactEmbeddingDao
import com.jax.assistant.db.FactEmbeddingEntity

// Stores/loads cached fact embeddings for semantic retrieval.
class FactEmbeddingRepository(private val dao: FactEmbeddingDao) {
    suspend fun getAll(): Map<String, FloatArray> =
        dao.getAll().associate { it.factId to VectorUtils.toFloats(it.vector) }

    suspend fun save(factId: String, vector: FloatArray) =
        dao.upsert(FactEmbeddingEntity(factId, vector.size, VectorUtils.toBytes(vector)))
}
