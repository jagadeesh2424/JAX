package com.jax.assistant.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FactDao {
    @Query("SELECT * FROM facts ORDER BY createdAt DESC")
    fun getAllFacts(): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE title LIKE '%' || :query || '%' OR details LIKE '%' || :query || '%'")
    fun searchFacts(query: String): Flow<List<FactEntity>>

    @Query("SELECT * FROM facts WHERE title LIKE '%' || :query || '%' OR details LIKE '%' || :query || '%' ORDER BY createdAt DESC")
    suspend fun searchFactsList(query: String): List<FactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFact(fact: FactEntity)

    @Delete
    suspend fun deleteFact(fact: FactEntity)
}
