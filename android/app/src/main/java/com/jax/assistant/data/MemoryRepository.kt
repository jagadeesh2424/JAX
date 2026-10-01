package com.jax.assistant.data

import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.ai.agent.MemoryType
import com.jax.assistant.db.FactDao
import com.jax.assistant.db.FactEmbeddingDao
import com.jax.assistant.db.FactEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns fact/memory-vault persistence and search.
class MemoryRepository(
    private val factDao: FactDao,
    private val factEmbeddingDao: FactEmbeddingDao
) {

    fun getAllFacts(): Flow<List<FactEntity>> = factDao.getAllFacts()

    fun searchFacts(query: String): Flow<List<FactEntity>> = factDao.searchFacts(query)

    suspend fun searchFactsSnapshot(query: String): List<FactEntity> = factDao.searchFactsList(query)

    suspend fun insertFact(fact: FactEntity) = factDao.insertFact(fact)

    // Drop the cached embedding so hybrid retrieval re-embeds the edited fact.
    suspend fun updateFact(fact: FactEntity) {
        factDao.insertFact(fact)
        factEmbeddingDao.delete(fact.id)
    }

    suspend fun deleteFact(fact: FactEntity) {
        factDao.deleteFact(fact)
        factEmbeddingDao.delete(fact.id)
    }

    suspend fun createManualFact(title: String, category: String, details: String): FactEntity {
        val fact = FactEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = if (category.isBlank()) "General" else category,
            details = details
        )
        factDao.insertFact(fact)
        return fact
    }

    suspend fun getFact(id: String): FactEntity? = factDao.getFactById(id)

    enum class UpsertOutcome { CREATED, UPDATED, UNCHANGED }

    data class UpsertResult(val fact: FactEntity, val outcome: UpsertOutcome)

    // Saves a memory without duplicating it: a fact with (nearly) the same title and type is
    // refreshed (same details) or updated in place (changed details, e.g. a new passport number).
    suspend fun upsertFact(
        title: String,
        category: String,
        details: String,
        memoryType: MemoryType = MemoryType.SEMANTIC,
        confidence: Double = 1.0,
        source: String = "user",
        now: Long = System.currentTimeMillis()
    ): UpsertResult {
        val existing = engine.findDuplicate(title, memoryType.name, factDao.getAllFactsList())
        if (existing != null) {
            if (normalize(existing.details) == normalize(details)) {
                val refreshed = existing.copy(updatedAt = now, confidence = maxOf(existing.confidence, confidence))
                factDao.insertFact(refreshed)
                return UpsertResult(refreshed, UpsertOutcome.UNCHANGED)
            }
            val updated = existing.copy(
                details = details,
                category = category.ifBlank { existing.category },
                confidence = confidence,
                source = source,
                updatedAt = now
            )
            factDao.insertFact(updated)
            factEmbeddingDao.delete(updated.id)
            return UpsertResult(updated, UpsertOutcome.UPDATED)
        }
        val fact = FactEntity(
            id = UUID.randomUUID().toString(),
            title = title,
            category = category.ifBlank { "General" },
            details = details,
            createdAt = now,
            memoryType = memoryType.name,
            confidence = confidence,
            source = source,
            updatedAt = now
        )
        factDao.insertFact(fact)
        return UpsertResult(fact, UpsertOutcome.CREATED)
    }

    private fun normalize(text: String) = text.trim().lowercase().replace(Regex("\\s+"), " ")

    private val engine = MemoryEngine()
}
