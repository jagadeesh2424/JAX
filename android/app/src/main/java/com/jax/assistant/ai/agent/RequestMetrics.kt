package com.jax.assistant.ai.agent

import java.util.Locale

// In-process rolling metrics over the most recent requests (no external framework). Read from
// the log ("[Metrics]" line) or programmatically via snapshot().
class RequestMetrics(private val capacity: Int = 200) {

    private data class Sample(
        val route: String,
        val latencyMs: Long,
        val geminiCalls: Int,
        val toolCalls: Int,
        val success: Boolean,
        val fallback: Boolean,
        val tokens: Int
    )

    data class Snapshot(
        val requests: Int,
        val avgLatencyMs: Long,
        val p95LatencyMs: Long,
        val geminiCallsPerRequest: Double,
        val toolCallsPerRequest: Double,
        val failureRate: Double,
        val fallbackRate: Double,
        val avgResearchLatencyMs: Long,
        val avgVoiceLatencyMs: Long,
        val tokensPerRequest: Double = 0.0,
        val totalTokens: Long = 0L
    ) {
        fun toLogLine(): String = String.format(
            Locale.US,
            "[Metrics] requests=%d avgLatencyMs=%d p95LatencyMs=%d geminiPerRequest=%.2f toolsPerRequest=%.2f " +
                "failureRate=%.2f fallbackRate=%.2f researchAvgMs=%d voiceAvgMs=%d tokensPerRequest=%.0f totalTokens=%d",
            requests, avgLatencyMs, p95LatencyMs, geminiCallsPerRequest, toolCallsPerRequest,
            failureRate, fallbackRate, avgResearchLatencyMs, avgVoiceLatencyMs, tokensPerRequest, totalTokens
        )
    }

    private val samples = ArrayDeque<Sample>()
    private val voiceLatencies = ArrayDeque<Long>()
    private var total = 0
    private var totalTokens = 0L

    fun record(trace: RequestTrace) = record(
        trace.route, trace.totalLatencyMs, trace.geminiCalls, trace.tools.size, trace.success, trace.fallback,
        trace.promptTokens + trace.outputTokens
    )

    @Synchronized
    fun record(
        route: String,
        latencyMs: Long,
        geminiCalls: Int,
        toolCalls: Int,
        success: Boolean,
        fallback: Boolean,
        tokens: Int = 0
    ) {
        samples.addLast(Sample(route, latencyMs, geminiCalls, toolCalls, success, fallback, tokens))
        while (samples.size > capacity) samples.removeFirst()
        total++
        totalTokens += tokens
    }

    // End-to-end voice turn: speech start -> TTS finished.
    @Synchronized
    fun recordVoiceLatency(ms: Long) {
        voiceLatencies.addLast(ms)
        while (voiceLatencies.size > capacity) voiceLatencies.removeFirst()
    }

    // Total requests recorded since creation (not capped by the window).
    @Synchronized
    fun count(): Int = total

    @Synchronized
    fun snapshot(): Snapshot {
        val n = samples.size
        if (n == 0) return Snapshot(0, 0, 0, 0.0, 0.0, 0.0, 0.0, 0, average(voiceLatencies.toList()))
        val latencies = samples.map { it.latencyMs }.sorted()
        val p95Index = (Math.ceil(0.95 * n).toInt() - 1).coerceIn(0, n - 1)
        return Snapshot(
            requests = n,
            avgLatencyMs = average(latencies),
            p95LatencyMs = latencies[p95Index],
            geminiCallsPerRequest = samples.sumOf { it.geminiCalls }.toDouble() / n,
            toolCallsPerRequest = samples.sumOf { it.toolCalls }.toDouble() / n,
            failureRate = samples.count { !it.success }.toDouble() / n,
            fallbackRate = samples.count { it.fallback }.toDouble() / n,
            avgResearchLatencyMs = average(samples.filter { it.route == "RESEARCH" }.map { it.latencyMs }),
            avgVoiceLatencyMs = average(voiceLatencies.toList()),
            tokensPerRequest = samples.sumOf { it.tokens }.toDouble() / n,
            totalTokens = totalTokens
        )
    }

    private fun average(values: List<Long>): Long = if (values.isEmpty()) 0 else values.sum() / values.size

    companion object {
        val shared = RequestMetrics()
    }
}
