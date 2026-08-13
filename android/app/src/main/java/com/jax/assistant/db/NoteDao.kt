package com.jax.assistant.db

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {
    @Query("SELECT * FROM note_pages ORDER BY updatedAt DESC")
    fun getAllPages(): Flow<List<NotePageEntity>>

    // Cross-content search over page title/category/tags and block content.
    @Query(
        "SELECT DISTINCT p.* FROM note_pages p " +
            "LEFT JOIN note_blocks b ON b.pageId = p.id " +
            "WHERE p.title LIKE '%' || :q || '%' " +
            "OR p.category LIKE '%' || :q || '%' " +
            "OR p.tags LIKE '%' || :q || '%' " +
            "OR b.content LIKE '%' || :q || '%' " +
            "ORDER BY p.updatedAt DESC"
    )
    fun searchPages(q: String): Flow<List<NotePageEntity>>

    @Query("SELECT * FROM note_pages WHERE id = :pageId LIMIT 1")
    suspend fun getPage(pageId: String): NotePageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPage(page: NotePageEntity)

    @Update
    suspend fun updatePage(page: NotePageEntity)

    @Delete
    suspend fun deletePage(page: NotePageEntity)

    @Query("SELECT * FROM note_blocks WHERE pageId = :pageId ORDER BY position ASC")
    fun getBlocksForPage(pageId: String): Flow<List<NoteBlockEntity>>

    @Query("SELECT * FROM note_blocks WHERE pageId = :pageId ORDER BY position ASC")
    suspend fun getBlocksForPageList(pageId: String): List<NoteBlockEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBlock(block: NoteBlockEntity)

    @Update
    suspend fun updateBlock(block: NoteBlockEntity)

    @Delete
    suspend fun deleteBlock(block: NoteBlockEntity)

    @Query("DELETE FROM note_blocks WHERE pageId = :pageId")
    suspend fun deleteBlocksForPage(pageId: String)

    // ---- Knowledge-graph edges (page <-> page links) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPageLink(link: PageLinkEntity)

    @Query(
        "DELETE FROM page_links WHERE (fromPageId = :a AND toPageId = :b) " +
            "OR (fromPageId = :b AND toPageId = :a)"
    )
    suspend fun deleteLinkBetween(a: String, b: String)

    @Query(
        "SELECT COUNT(*) FROM page_links WHERE (fromPageId = :a AND toPageId = :b) " +
            "OR (fromPageId = :b AND toPageId = :a)"
    )
    suspend fun countLinkBetween(a: String, b: String): Int

    @Query("DELETE FROM page_links WHERE fromPageId = :pageId OR toPageId = :pageId")
    suspend fun deleteLinksForPage(pageId: String)

    @Query(
        "SELECT p.* FROM note_pages p INNER JOIN page_links l " +
            "ON (l.toPageId = p.id AND l.fromPageId = :pageId) " +
            "OR (l.fromPageId = p.id AND l.toPageId = :pageId) " +
            "ORDER BY p.updatedAt DESC"
    )
    fun getRelatedPages(pageId: String): Flow<List<NotePageEntity>>
}
