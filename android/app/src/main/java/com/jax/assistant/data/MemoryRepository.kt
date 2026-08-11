package com.jax.assistant.data

import com.jax.assistant.db.FactDao
import com.jax.assistant.db.FactEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns fact/memory-vault persistence and search.
class MemoryRepository(private val factDao: FactDao) {

    fun getAllFacts(): Flow<List<FactEntity>> = factDao.getAllFacts()

    fun searchFacts(query: String): Flow<List<FactEntity>> = factDao.searchFacts(query)

    suspend fun insertFact(fact: FactEntity) = factDao.insertFact(fact)

    suspend fun deleteFact(fact: FactEntity) = factDao.deleteFact(fact)

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
}
