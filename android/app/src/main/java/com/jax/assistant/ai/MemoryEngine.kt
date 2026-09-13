package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity

class MemoryEngine {

    /**
     * Filters and returns relevant memories based on the user's input text.
     * Appends only relevant memories (up to top 5) instead of the full database.
     */
    fun selectRelevantMemories(userInput: String, allFacts: List<FactEntity>): List<FactEntity> {
        if (allFacts.isEmpty() || userInput.isBlank()) return emptyList()
        if (queryTokens(userInput).isEmpty()) return allFacts.take(3)
        val scored = scoreMemories(userInput, allFacts)
        return if (scored.isNotEmpty()) scored.take(5).map { it.first } else allFacts.take(2)
    }

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
        private val commonStopWords = setOf(
            "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", "have", "from", "one", "had", "by", "word", "but", "not", "what", "all", "were", "when", "your", "can", "said", "there", "use", "an", "each", "which", "she", "do", "how", "their", "if", "will", "up", "other", "about", "out", "many", "then", "them", "these", "so", "some", "her", "would", "make", "like", "him", "into", "time", "has", "look", "two", "more", "write", "go", "see", "number", "no", "way", "could", "people", "my", "than", "first", "water", "been", "call", "who", "oil", "its", "now", "find", "long", "down", "day", "did", "get", "come", "made", "may", "part"
        )
    }
}
