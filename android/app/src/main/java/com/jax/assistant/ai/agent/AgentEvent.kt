package com.jax.assistant.ai.agent

// Structured event stream (S3 seam). In-memory for Phase 1; Phase 2 persists these to Room
// for durable/resumable runs and Phase 3 consolidates them into episodic memory.
data class AgentEvent(
    val type: String,          // user_input | tool_call | tool_result | final | error
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
)

interface AgentEventSink {
    fun emit(event: AgentEvent)
}

// Bounded in-memory log (mirrors the existing RequestLog approach).
class InMemoryEventSink(private val max: Int = 100) : AgentEventSink {
    private val events = ArrayDeque<AgentEvent>()

    override fun emit(event: AgentEvent) {
        events.addLast(event)
        while (events.size > max) events.removeFirst()
    }

    fun snapshot(): List<AgentEvent> = events.toList()
}
