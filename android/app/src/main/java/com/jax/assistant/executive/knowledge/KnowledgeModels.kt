package com.jax.assistant.executive.knowledge

import java.util.UUID

enum class BlockType {
    HEADING,
    PARAGRAPH,
    CHECKLIST,
    BULLET_LIST,
    NUMBERED_LIST,
    QUOTE,
    CODE_BLOCK,
    DIVIDER,
    TAGS
}

data class KnowledgeBlock(
    val id: String = UUID.randomUUID().toString(),
    val type: BlockType,
    val content: String = "",
    val isChecked: Boolean = false,
    val orderIndex: Int = 0
)

data class KnowledgePage(
    val id: String = UUID.randomUUID().toString(),
    val notebookId: String,
    val title: String,
    val blocks: List<KnowledgeBlock> = emptyList(),
    val tags: List<String> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class Notebook(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val icon: String = "📓",
    val pageIds: List<String> = emptyList()
)

data class Workspace(
    val id: String = "default_workspace",
    val title: String = "Executive Knowledge Workspace",
    val notebooks: List<Notebook> = emptyList()
)
