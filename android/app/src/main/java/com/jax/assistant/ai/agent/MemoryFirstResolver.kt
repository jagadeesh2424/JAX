package com.jax.assistant.ai.agent

import com.jax.assistant.ai.MemoryEngine
import com.jax.assistant.db.FactEntity
import java.util.Locale

/** Answers high-confidence fact questions locally before the model router is entered. */
object MemoryFirstResolver {
    private const val MIN_SCORE = 3
    private const val MIN_CONFIDENCE = 0.75

    fun resolve(query: String, facts: List<FactEntity>): String? {
        if (!FastIntentRouter.isLikelyMemoryQuery(query) || facts.isEmpty()) return null
        val ranked = MemoryEngine().scoreMemories(query, facts)
        val top = ranked.firstOrNull() ?: return null
        if (top.second < MIN_SCORE || top.first.confidence < MIN_CONFIDENCE || top.first.details.isBlank()) return null

        val rival = ranked.getOrNull(1)
        if (rival != null && rival.second == top.second &&
            !rival.first.title.equals(top.first.title, ignoreCase = true)
        ) return null

        val title = top.first.title.trim()
        val details = top.first.details.trim()
        return if (query.lowercase(Locale.US).contains("birthday")) {
            "$title is $details."
        } else {
            "I remember this: $title — $details."
        }
    }
}
