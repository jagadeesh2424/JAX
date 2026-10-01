package com.jax.assistant.ai.agent

import com.jax.assistant.db.AgentRunEntity

// Procedural memory: learns only tool sequences repeated across separate, fully successful runs
// (HIGH_CONFIDENCE evidence). Suggestions are advisory context for the planner, never actions.
class WorkflowLearner(private val minEvidence: Int = 2) {
    fun suggestions(runs: List<AgentRunEntity>, limit: Int = 2): List<String> = runs
        .asSequence()
        .filter { it.status == "COMPLETED" }
        .map { it.toolsUsed.split(',').map(String::trim).filter(String::isNotEmpty) }
        .filter { it.size >= 2 }
        // Each run is one piece of evidence, however often it repeats a pair internally.
        .flatMap { tools -> tools.windowed(2).map { it.joinToString(" -> ") }.distinct().asSequence() }
        .groupingBy { it }
        .eachCount()
        .filterValues { it >= minEvidence }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { "${it.key} (observed ${it.value} times)" }
        .toList()
}
