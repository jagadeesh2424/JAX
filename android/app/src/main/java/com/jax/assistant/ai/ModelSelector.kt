package com.jax.assistant.ai

object ModelSelector {

    fun selectCandidateModels(
        models: List<ModelInfo>,
        capability: TaskCapability = TaskCapability.GENERAL_CONVERSATION,
        preferredModelId: String? = null
    ): List<ModelInfo> {
        val now = System.currentTimeMillis()

        // 1. Filter for enabled models that are not currently in cooldown
        var candidates = models.filter { it.enabled && now >= it.cooldownUntil }

        // Fallback 1: If all enabled models are in cooldown, include enabled models whose cooldown is closest to expiry
        if (candidates.isEmpty()) {
            candidates = models.filter { it.enabled }
        }

        // Fallback 2: Ultimate safety fallback to all models
        if (candidates.isEmpty()) {
            candidates = models
        }

        // 2. Sort candidate models dynamically based on criteria
        return candidates.sortedWith(
            Comparator { m1, m2 ->
                // Priority override if a valid preferred model ID is requested
                if (!preferredModelId.isNullOrBlank()) {
                    if (m1.id.equals(preferredModelId, ignoreCase = true)) return@Comparator -1
                    if (m2.id.equals(preferredModelId, ignoreCase = true)) return@Comparator 1
                }

                // A. Task Capability alignment
                when (capability) {
                    TaskCapability.CODING, TaskCapability.COMPLEX_REASONING -> {
                        val c = m2.reasoningScore.compareTo(m1.reasoningScore)
                        if (c != 0) return@Comparator c
                    }
                    TaskCapability.FAST_CLASSIFICATION, TaskCapability.MEMORY_CLASSIFICATION, TaskCapability.GENERAL_CONVERSATION -> {
                        val c = m2.speedScore.compareTo(m1.speedScore)
                        if (c != 0) return@Comparator c
                    }
                    TaskCapability.LONG_SUMMARY -> {
                        val c = m2.contextWindow.compareTo(m1.contextWindow)
                        if (c != 0) return@Comparator c
                    }
                }

                // B. Health: Recent success preference
                if (m1.lastSuccess > 0 && m2.lastSuccess == 0L) return@Comparator -1
                if (m2.lastSuccess > 0 && m1.lastSuccess == 0L) return@Comparator 1

                // C. Lower failure count preferred
                val fc = m1.failureCount.compareTo(m2.failureCount)
                if (fc != 0) return@Comparator fc

                // D. Lowest average latency preferred
                if (m1.averageLatency > 0 && m2.averageLatency > 0) {
                    val lat = m1.averageLatency.compareTo(m2.averageLatency)
                    if (lat != 0) return@Comparator lat
                }

                // E. Default priority ordering
                m1.priority.compareTo(m2.priority)
            }
        )
    }
}
