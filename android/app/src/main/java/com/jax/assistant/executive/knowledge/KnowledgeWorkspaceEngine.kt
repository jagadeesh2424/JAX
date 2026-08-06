package com.jax.assistant.executive.knowledge

import com.jax.assistant.db.FactEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

class KnowledgeWorkspaceEngine {

    private val defaultNotebook = Notebook(
        id = "nb_executive_general",
        title = "Executive Notes & Strategy",
        description = "Core workspace notebook for strategic notes and dynamic blocks.",
        icon = "💼"
    )

    private val _notebooks = MutableStateFlow<List<Notebook>>(listOf(defaultNotebook))
    val notebooks: StateFlow<List<Notebook>> = _notebooks.asStateFlow()

    private val _pages = MutableStateFlow<List<KnowledgePage>>(emptyList())
    val pages: StateFlow<List<KnowledgePage>> = _pages.asStateFlow()

    init {
        // Seed default sample page
        createPage(
            notebookId = defaultNotebook.id,
            title = "J.A.X. Executive Operating Manual",
            initialBlocks = listOf(
                KnowledgeBlock(type = BlockType.HEADING, content = "1. Executive Intelligence Architecture"),
                KnowledgeBlock(type = BlockType.PARAGRAPH, content = "J.A.X. operates using a multi-agent framework powered by ContextManager, ConversationManager, and Planner."),
                KnowledgeBlock(type = BlockType.QUOTE, content = "Bypassing raw prompts with structured intent-driven slot filling creates reliable executive workflows."),
                KnowledgeBlock(type = BlockType.CHECKLIST, content = "ContextManager & StateFlow active", isChecked = true),
                KnowledgeBlock(type = BlockType.CHECKLIST, content = "Knowledge Workspace Block Editor active", isChecked = true),
                KnowledgeBlock(type = BlockType.CODE_BLOCK, content = "fun executePlan(plan: PlanResult) = agent.execute(plan)")
            ),
            tags = listOf("Architecture", "Executive", "Core")
        )
    }

    fun createNotebook(title: String, description: String = "", icon: String = "📁"): Notebook {
        val newNotebook = Notebook(title = title, description = description, icon = icon)
        _notebooks.value = _notebooks.value + newNotebook
        return newNotebook
    }

    fun createPage(
        notebookId: String,
        title: String,
        initialBlocks: List<KnowledgeBlock> = emptyList(),
        tags: List<String> = emptyList()
    ): KnowledgePage {
        val newPage = KnowledgePage(
            notebookId = notebookId,
            title = title,
            blocks = if (initialBlocks.isEmpty()) {
                listOf(KnowledgeBlock(type = BlockType.PARAGRAPH, content = "Start typing page contents here..."))
            } else {
                initialBlocks
            },
            tags = tags
        )

        _pages.value = _pages.value + newPage

        // Update notebook's pageIds
        _notebooks.value = _notebooks.value.map { nb ->
            if (nb.id == notebookId) {
                nb.copy(pageIds = nb.pageIds + newPage.id)
            } else nb
        }

        return newPage
    }

    fun addBlockToPage(pageId: String, blockType: BlockType, content: String = "") {
        _pages.value = _pages.value.map { page ->
            if (page.id == pageId) {
                val updatedBlocks = page.blocks + KnowledgeBlock(type = blockType, content = content)
                page.copy(blocks = updatedBlocks, updatedAt = System.currentTimeMillis())
            } else page
        }
    }

    fun updateBlockContent(pageId: String, blockId: String, newContent: String, isChecked: Boolean? = null) {
        _pages.value = _pages.value.map { page ->
            if (page.id == pageId) {
                val updatedBlocks = page.blocks.map { block ->
                    if (block.id == blockId) {
                        block.copy(
                            content = newContent,
                            isChecked = isChecked ?: block.isChecked
                        )
                    } else block
                }
                page.copy(blocks = updatedBlocks, updatedAt = System.currentTimeMillis())
            } else page
        }
    }

    fun deleteBlock(pageId: String, blockId: String) {
        _pages.value = _pages.value.map { page ->
            if (page.id == pageId) {
                val updatedBlocks = page.blocks.filter { it.id != blockId }
                page.copy(blocks = updatedBlocks, updatedAt = System.currentTimeMillis())
            } else page
        }
    }

    /**
     * MIGRATION STRATEGY:
     * Automatically converts plain text notes or FactEntities into block-structured KnowledgePages.
     */
    fun migratePlainNotesToPages(facts: List<FactEntity>) {
        if (facts.isEmpty()) return

        val existingTitles = _pages.value.map { it.title }.toSet()
        facts.forEach { fact ->
            val pageTitle = if (fact.factText.length > 30) fact.factText.take(30) + "..." else fact.factText
            if (!existingTitles.contains(pageTitle)) {
                val blocks = listOf(
                    KnowledgeBlock(type = BlockType.HEADING, content = fact.category),
                    KnowledgeBlock(type = BlockType.PARAGRAPH, content = fact.factText),
                    KnowledgeBlock(type = BlockType.TAGS, content = "Migrated, ${fact.category}")
                )
                createPage(
                    notebookId = defaultNotebook.id,
                    title = pageTitle,
                    initialBlocks = blocks,
                    tags = listOf("MigratedNote", fact.category)
                )
            }
        }
    }
}
