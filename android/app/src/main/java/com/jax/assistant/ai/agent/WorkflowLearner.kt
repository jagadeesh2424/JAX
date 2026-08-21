package com.jax.assistant.ai.agent

import com.jax.assistant.db.AgentRunEntity

// Learns only repeated, completed tool sequences. Suggestions are advisory context, never actions.
class WorkflowLearner {
    fun suggestions(runs: List<AgentRunEntity>, limit: Int = 2): List<String> = runs
        .asSequence()
        .filter { it.status == "COMPLETED" }
        .map { it.toolsUsed.split(',').map(String::trim).filter(String::isNotEmpty) }
        .filter { it.size >= 2 }
        .flatMap { tools -> tools.windowed(2).asSequence().map { it.joinToString(" -> ") } }
        .groupingBy { it }
        .eachCount()
        .filterValues { it >= 2 }
        .entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        .take(limit)
        .map { "${it.key} (observed ${it.value} times)" }
        .toList()