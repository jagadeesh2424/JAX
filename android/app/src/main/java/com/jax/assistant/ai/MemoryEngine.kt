package com.jax.assistant.ai

import com.jax.assistant.db.FactEntity

class MemoryEngine {

    /**
     * Filters and returns relevant memories based on the user's input text.
     * Appends only relevant memories (up to top 5) instead of the full database.
     */
    fun selectRelevantMemories(userInput: String, allFacts: List<FactEntity>): List<FactEntity> {
        if (allFacts.isEmpty() || userInput.isBlank()) return emptyList()

        val tokens = userInput.lowercase()
            .split(" ", ",", ".", "?", "!")
            .filter { it.length > 2 && it !in commonStopWords }

        if (tokens.isEmpty()) {
            return allFacts.take(3)
        }

        val scored = allFacts.map { fact ->
            val searchableText = "${fact.title} ${fact.category} ${fact.details}".lowercase()
            var score = 0
            for (token in tokens) {
                if (searchableText.contains(token)) {
                    score += 1
                }
            }
            fact to score
        }.filter { it.second > 0 }
        .sortedByDescending { it.second }

        return if (scored.isNotEmpty()) {
            scored.take(5).map { it.first }
        } else {
            allFacts.take(2)
        }
    }

    companion object {
        private val commonStopWords = setOf(
            "the", "and", "you", "that", "was", "for", "are", "with", "his", "they", "this", "have", "from", "one", "had", "by", "word", "but", "not", "what", "all", "were", "when", "your", "can", "said", "there", "use", "an", "each", "which", "she", "do", "how", "their", "if", "will", "up", "other", "about", "out", "many", "then", "them", "these", "so", "some", "her", "would", "make", "like", "him", "into", "time", "has", "look", "two", "more", "write", "go", "see", "number", "no", "way", "could", "people", "my", "than", "first", "water", "been", "call", "who", "oil", "its", "now", "find", "long", "down", "day", "did", "get", "come", "made", "may", "part"
        )
    }
}
