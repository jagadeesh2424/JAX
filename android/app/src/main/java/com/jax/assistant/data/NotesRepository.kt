package com.jax.assistant.data

import com.jax.assistant.db.AppDatabase
import com.jax.assistant.db.BlockType
import com.jax.assistant.db.NoteBlockEntity
import com.jax.assistant.db.NotePageEntity
import com.jax.assistant.db.PageLinkEntity
import kotlinx.coroutines.flow.Flow
import java.util.UUID

// Owns all Notion-style page/block persistence, decoupled from JaxRepository.
class NotesRepository(private val db: AppDatabase) {

    private val noteDao = db.noteDao()

    fun getAllPages(): Flow<List<NotePageEntity>> = noteDao.getAllPages()

    fun searchPages(query: String): Flow<List<NotePageEntity>> = noteDao.searchPages(query.trim())

    fun getBlocksForPage(pageId: String): Flow<List<NoteBlockEntity>> =
        noteDao.getBlocksForPage(pageId)

    suspend fun createPage(title: String, category: String, tags: String = ""): NotePageEntity {
        val page = NotePageEntity(
            id = UUID.randomUUID().toString(),
            title = title.ifBlank { "Untitled" },
            category = category.ifBlank { "Personal Notes" },
            tags = tags.trim()
        )
        noteDao.insertPage(page)
        // Seed with one empty text block so the editor is immediately usable.
        noteDao.insertBlock(
            NoteBlockEntity(id = UUID.randomUUID().toString(), pageId = page.id, type = BlockType.TEXT, position = 0)
        )
        return page
    }

    suspend fun renamePage(page: NotePageEntity, newTitle: String) {
        noteDao.updatePage(page.copy(title = newTitle.ifBlank { "Untitled" }, updatedAt = System.currentTimeMillis()))
    }

    suspend fun updatePageTags(page: NotePageEntity, tags: String) {
        noteDao.updatePage(page.copy(tags = tags.trim(), updatedAt = System.currentTimeMillis()))
    }

    suspend fun deletePage(page: NotePageEntity) {
        noteDao.deleteBlocksForPage(page.id)
        noteDao.deleteLinksForPage(page.id)
        noteDao.deletePage(page)
    }

    // ---- Knowledge-graph edges ----

    fun getRelatedPages(pageId: String): Flow<List<NotePageEntity>> =
        noteDao.getRelatedPages(pageId)

    suspend fun linkPages(fromPageId: String, toPageId: String) {
        if (fromPageId == toPageId) return
        if (noteDao.countLinkBetween(fromPageId, toPageId) > 0) return
        noteDao.insertPageLink(
            PageLinkEntity(
                id = UUID.randomUUID().toString(),
                fromPageId = fromPageId,
                toPageId = toPageId
            )
        )
    }

    suspend fun unlinkPages(a: String, b: String) {
        noteDao.deleteLinkBetween(a, b)
    }

    suspend fun addBlock(pageId: String, type: String, position: Int): NoteBlockEntity {
        val block = NoteBlockEntity(
            id = UUID.randomUUID().toString(),
            pageId = pageId,
            type = type,
            position = position
        )
        noteDao.insertBlock(block)
        touchPage(pageId)
        return block
    }

    suspend fun getBlocksSnapshot(pageId: String): List<NoteBlockEntity> =
        noteDao.getBlocksForPageList(pageId)

    // Inserts a new block right after [afterBlockId] (or at the end when null), shifting
    // later blocks down so the new block lands at the cursor position.
    suspend fun addBlockAfter(pageId: String, afterBlockId: String?, type: String): NoteBlockEntity {
        val existing = noteDao.getBlocksForPageList(pageId)
        val anchor = afterBlockId?.let { id -> existing.firstOrNull { it.id == id } }
        val insertPos = if (anchor != null) anchor.position + 1 else (existing.maxOfOrNull { it.position } ?: -1) + 1
        existing.filter { it.position >= insertPos }.forEach {
            noteDao.updateBlock(it.copy(position = it.position + 1))
        }
        val block = NoteBlockEntity(
            id = UUID.randomUUID().toString(),
            pageId = pageId,
            type = type,
            position = insertPos
        )
        noteDao.insertBlock(block)
        touchPage(pageId)
        return block
    }

    suspend fun updateBlock(block: NoteBlockEntity) {
        noteDao.updateBlock(block)
        touchPage(block.pageId)
    }

    suspend fun deleteBlock(block: NoteBlockEntity) {
        noteDao.deleteBlock(block)
        touchPage(block.pageId)
    }

    private suspend fun touchPage(pageId: String) {
        noteDao.getPage(pageId)?.let {
            noteDao.updatePage(it.copy(updatedAt = System.currentTimeMillis()))
        }
    }

    companion object {
        fun from(db: AppDatabase): NotesRepository = NotesRepository(db)
    }
}
