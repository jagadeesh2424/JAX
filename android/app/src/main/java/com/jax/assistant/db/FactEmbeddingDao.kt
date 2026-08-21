package com.jax.assistant.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface FactEmbeddingDao {
    @Query("SELECT * FROM fact_embeddings")
    suspend fun getAll(): List<FactEmbeddingEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(embedding: FactEmbeddingEntity)

    @Query("DELETE FROM fact_embeddings WHERE factId = :factId")
    suspend fun delete(factId: String)
}
