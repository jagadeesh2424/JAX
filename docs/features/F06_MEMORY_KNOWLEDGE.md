# Feature — Memory & Knowledge

Purpose

Store what matters (facts) and let me think in a Notion-style workspace, with links between related pages.

------------------------------------------------

User Story

As a user I can save facts about my life, filter them by category, create note pages made of
editable blocks, tag and search them, and link related pages together.

------------------------------------------------

Architecture

Fact Save / Retrieve
   ↓
MemoryEngine + embedding cache (keyword/category + best-effort semantic retrieval)

Workspace
   Pages → Blocks (text/heading/bullet/checklist/code/quote/divider)
   Tags + cross-content search
   Page ↔ Page links (knowledge graph)

------------------------------------------------

Contract

Reference Kotlin: FactDao, NoteDao, MemoryRepository, NotesRepository.

------------------------------------------------

Files

db/FactEntity.kt, db/FactDao.kt, data/MemoryRepository.kt
db/NotePageEntity.kt, db/NoteBlockEntity.kt, db/NoteDao.kt, data/NotesRepository.kt
db/PageLinkEntity.kt (page ↔ page links)
ui/screens/MemoryVaultScreen.kt, ui/screens/KnowledgeWorkspaceScreen.kt

------------------------------------------------

Current Status — 🟡 Built, verify on device

Facts save/retrieve with typed life categories. Block editor with tags + search. Related-pages
linking (chips + picker). Hybrid retrieval uses lexical matching plus cached embeddings. DB at v10.

------------------------------------------------

Acceptance Criteria

Save a fact → appears filtered by category. Create a page, add/edit blocks, reopen → persisted.
Link two pages → each shows the other under "Related pages".

------------------------------------------------

Known Issues

Embedding retrieval is best-effort and falls back to lexical matching when the API is unavailable.

------------------------------------------------

Future Improvements

Semantic memory (embeddings → ranked retrieval). FTS4 search. Links to tasks/goals, not just pages.
