package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity

class MemoryEngine {

    /**
     * Filters and returns relevant memories based on the user's input text (top 5).
     * Returns nothing when nothing matches, so unrelated memories never reach the prompt.
     */
    fun selectRelevantMemories(userInput: String, allFacts: List<FactEntity>): List<FactEntity> {
        if (allFacts.isEmpty() || userInput.isBlank()) return emptyList()
        if (selfQuery.containsMatchIn(userInput.lowercase())) {
            return allFacts.sortedByDescending { maxOf(it.updatedAt, it.createdAt) }.take(5)
        }
        return scoreMemories(userInput, allFacts).take(5).map { it.first }
    }

    // Combined ranking: relevance (semantic similarity or keyword match) dominates; recency and
    // confidence break ties. Facts with no relevance at all are excluded, not padded in.
    fun rankForContext(
        query: String,
        facts: List<FactEntity>,
        semanticScores: Map<String, Float>,
        nowMillis: Long,
        limit: Int = 6
    ): List<FactEntity> {
        val lexical = scoreMemories(query, facts).associate { it.first.id to it.second }
        val maxLexical = (queryTokens(query).size * 2).coerceAtLeast(1)
        return facts.mapNotNull { fact ->
            val semantic = semanticScores[fact.id]
                ?.let { ((it - SEMANTIC_FLOOR) / (1f - SEMANTIC_FLOOR)).coerceIn(0f, 1f).toDouble() } ?: 0.0
            val keyword = ((lexical[fact.id] ?: 0).toDouble() / maxLexical).coerceAtMost(1.0)
            val relevance = maxOf(semantic, keyword)
            if (relevance <= 0.0) return@mapNotNull null
            val ageDays = (nowMillis - maxOf(fact.updatedAt, fact.createdAt)).coerceAtLeast(0L) / DAY_MS
            val recency = Math.pow(0.5, ageDays / RECENCY_HALF_LIFE_DAYS)
            fact to (RELEVANCE_WEIGHT * relevance + RECENCY_WEIGHT * recency +
                CONFIDENCE_WEIGHT * fact.confidence.coerceIn(0.0, 1.0))
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }

    // The stored fact this one would duplicate: same memory type and (nearly) the same title words.
    fun findDuplicate(title: String, memoryType: String, existing: List<FactEntity>): FactEntity? {
        val key = titleTokens(title)
        val sameType = existing.filter { it.memoryType == memoryType }
        if (key.isEmpty()) return sameType.firstOrNull { it.title.trim().equals(title.trim(), ignoreCase = true) }
        return sameType
            .map { it to jaccard(key, titleTokens(it.title)) }
            .filter { it.second >= DUPLICATE_SIMILARITY }
            .maxByOrNull { it.second }?.first
    }

    private fun titleTokens(title: String): Set<String> =
        tokenize(title).filter { it.length > 1 && it !in commonStopWords }.toSet()

    private fun jaccard(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else a.intersect(b).size.toDouble() / a.union(b).size

    // Whole-word lexical scoring: title/category matches weigh more than detail matches,
    // and word-boundary matching avoids substring false positives (e.g. "cat" vs "category").
    fun scoreMemories(userInput: String, allFacts: List<FactEntity>): List<Pair<FactEntity, Int>> {
        val tokens = queryTokens(userInput)
        if (tokens.isEmpty()) return emptyList()
        return allFacts.mapNotNull { fact ->
            val titleTokens = tokenize("${fact.title} ${fact.category}").toHashSet()
            val detailTokens = tokenize(fact.details).toHashSet()
            var score = 0
            for (t in tokens) {
                when {
                    t in titleTokens -> score += 2
                    t in detailTokens -> score += 1
                }
            }
            if (score > 0) fact to score else null
        }.sortedByDescending { it.second }
    }

    // Reciprocal Rank Fusion: merges ranked lists without normalizing their different
    // score scales (cosine similarity vs keyword counts). Standard hybrid-search fusion.
    fun fuseByReciprocalRank(rankings: List<List<FactEntity>>, k: Int = 60): List<FactEntity> {
        val scores = LinkedHashMap<String, Double>()
        val byId = HashMap<String, FactEntity>()
        rankings.forEach { ranking ->
            ranking.forEachIndexed { index, fact ->
                byId[fact.id] = fact
                scores[fact.id] = (scores[fact.id] ?: 0.0) + 1.0 / (k + index + 1)
            }
        }
        return scores.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }
    }

    private fun queryTokens(userInput: String): List<String> =
        tokenize(userInput).filter { it.length > 2 && it !in commonStopWords }

    private fun tokenize(text: String): List<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }

    companion object {
        private const val SEMANTIC_FLOOR = 0.3f
        private const val DAY_MS = 24.0 * 60 * 60 * 1000
        private const val RECENCY_HALF_LIFE_DAYS = 60.0
        private const val RELEVANCE_WEIGHT = 0.65
        private const val RECENCY_WEIGHT = 0.2
        private const val CONFIDENCE_WEIGHT = 0.15
        const val DUPLICATE_SIMILARITY = 0.8

        // "What do you know about me?" asks for memory itself, so recent facts are relevant.
        private val selfQuery = Regex("\\b(about me|know about me|remember about me|my profile|what do you remember)\\b")

        private val commonStopWords = setOf(
            "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", "have", "from", "one", "had", "by", "word", "but", "not", "what", "all", "were", "when", "your", "can", "said", "there", "use", "an", "each", "which", "she", "do", "how", "their", "if", "will", "up", "other", "about", "out", "many", "then", "them", "these", "so", "some", "her", "would", "make", "like", "him", "into", "time", "has", "look", "two", "more", "write", "go", "see", "number", "no", "way", "could", "people", "my", "than", "first", "water", "been", "call", "who", "oil", "its", "now", "find", "long", "down", "day", "did", "get", "come", "made", "may", "part"
        )
    }
}
