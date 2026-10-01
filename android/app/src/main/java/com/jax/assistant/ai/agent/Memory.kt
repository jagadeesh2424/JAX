package com.jax.assistant.ai.agent

import java.util.Locale

// How much a piece of information can be trusted, and therefore where it may live.
//   EXPLICIT_USER_FACT - the user said it about themselves or asked to remember it -> durable fact
//   HIGH_CONFIDENCE    - derived from verified outcomes or repeated evidence       -> durable (episodic/procedural)
//   INFERRED           - the model's interpretation                                 -> working memory only
//   TEMPORARY          - relevant to the current request only                        -> working memory only
enum class MemoryTrust { EXPLICIT_USER_FACT, HIGH_CONFIDENCE, INFERRED, TEMPORARY }

// Durable memory kinds (working memory is WorkingMemory, never persisted):
//   SEMANTIC   - stable facts/preferences ("prefers concise answers")
//   EPISODIC   - notable past events ("planned a Singapore trip in November")
//   PROCEDURAL - learned workflows (derived from runs by WorkflowLearner)
enum class MemoryType { SEMANTIC, EPISODIC, PROCEDURAL }

// Guards durable memory against pollution. The decision is made from the user's own words,
// never from the model's claim that something is worth remembering.
object MemoryPolicy {

    private val explicitCue = Regex(
        "\\b(remember|memori[sz]e|save (this|that|it)|note (this|that|down)|don'?t forget|do not forget|keep in mind|for future reference)\\b"
    )
    private val selfStatement = Regex(
        "^(?:please\\s+)?(?:my\\s+[a-z' ]{1,30}?\\s+(?:is|are|was)\\b|i\\s+(?:prefer|always|never|usually|like|love|hate|dislike|am allergic|live in|work (?:at|for|as)))"
    )

    fun classify(userInput: String): MemoryTrust {
        val text = userInput.trim().lowercase(Locale.US)
        return when {
            explicitCue.containsMatchIn(text) -> MemoryTrust.EXPLICIT_USER_FACT
            selfStatement.containsMatchIn(text) -> MemoryTrust.EXPLICIT_USER_FACT
            else -> MemoryTrust.INFERRED
        }
    }

    fun allowsDurableWrite(userInput: String): Boolean = isDurable(classify(userInput))

    fun isDurable(trust: MemoryTrust): Boolean =
        trust == MemoryTrust.EXPLICIT_USER_FACT || trust == MemoryTrust.HIGH_CONFIDENCE
}

enum class EntityType { PERSON, PLACE, TASK, REMINDER, PROJECT, DATE, TIME, APP, DOCUMENT, WEBSITE, ITEM }

data class TrackedEntity(
    val type: EntityType,
    val name: String,
    val source: String,
    val timestamp: Long,
    val confidence: Double,
    val attributes: Map<String, String> = emptyMap()
) {
    // Confidence that decays with age, so the most recent mention wins.
    fun relevance(nowMillis: Long): Double =
        confidence * Math.pow(0.5, (nowMillis - timestamp).coerceAtLeast(0L).toDouble() / RELEVANCE_HALF_LIFE_MS)

    companion object {
        const val RELEVANCE_HALF_LIFE_MS = 10 * 60 * 1000L
    }
}

// A question JAX asked and is waiting on, with the details already known (e.g. reminder date).
data class PendingClarification(
    val intent: String,
    val slots: Map<String, String>,
    val question: String,
    val askedAt: Long = 0L
)

sealed class EntityLookup {
    data class Found(val entity: TrackedEntity) : EntityLookup()
    data class Ambiguous(val options: List<TrackedEntity>) : EntityLookup()
    object None : EntityLookup()
}

// Short-lived state for the current conversation: the active goal and slots, recently mentioned
// entities, the last results the user can refer to ("the second one"), and any open question.
// Lives in memory only, expires with inactivity and is cleared on "New Chat". Durable memory is
// separate (facts table).
class WorkingMemory(
    private val maxNotes: Int = 8,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {

    data class State(
        val goal: String = "",
        val status: String = "",
        val slots: Map<String, String> = emptyMap(),
        val notes: List<String> = emptyList(),
        val topic: String = "",
        val lastIntent: String = "",
        val lastArgs: Map<String, String> = emptyMap(),
        val entities: List<TrackedEntity> = emptyList(),
        val candidates: List<TrackedEntity> = emptyList(),
        val pending: PendingClarification? = null,
        val updatedAt: Long = 0L
    ) {
        val isEmpty: Boolean
            get() = goal.isBlank() && notes.isEmpty() && topic.isBlank() && lastIntent.isBlank() &&
                entities.isEmpty() && candidates.isEmpty() && pending == null
    }

    @Volatile
    var state: State = State()
        private set

    fun now(): Long = clock()

    @Synchronized
    fun beginGoal(goal: String, slots: Map<String, String> = emptyMap()) {
        state = state.copy(goal = goal, status = "planning", slots = slots, updatedAt = clock())
    }

    @Synchronized
    fun updateStatus(status: String) {
        state = state.copy(status = status)
    }

    @Synchronized
    fun addNote(note: String) {
        if (note.isBlank()) return
        state = state.copy(notes = (state.notes + note.trim()).takeLast(maxNotes), updatedAt = clock())
    }

    // Same entity (type + name, or same task id) is refreshed rather than duplicated.
    @Synchronized
    fun track(entity: TrackedEntity) {
        val taskId = entity.attributes["task_id"]
        val others = state.entities.filterNot {
            (it.type == entity.type && it.name.equals(entity.name, ignoreCase = true)) ||
                (taskId != null && it.attributes["task_id"] == taskId)
        }
        state = state.copy(entities = (others + entity).sortedBy { it.timestamp }.takeLast(MAX_ENTITIES), updatedAt = clock())
    }

    // A fresh explicit entity for a type supersedes older context for that type. This is used for
    // locations so a current request for Singapore can never be shadowed by stale Bangalore state.
    @Synchronized
    fun clear(type: EntityType) {
        state = state.copy(entities = state.entities.filterNot { it.type == type }, updatedAt = clock())
    }

    @Synchronized
    fun setCandidates(items: List<TrackedEntity>, topic: String) {
        state = state.copy(candidates = items.take(MAX_CANDIDATES), topic = topic.ifBlank { state.topic }, updatedAt = clock())
    }

    @Synchronized
    fun recordTurn(intent: String, args: Map<String, String> = emptyMap()) {
        state = state.copy(lastIntent = intent, lastArgs = args, updatedAt = clock())
    }

    @Synchronized
    fun setTopic(topic: String) {
        if (topic.isNotBlank()) state = state.copy(topic = topic, updatedAt = clock())
    }

    @Synchronized
    fun setPending(pending: PendingClarification?) {
        state = state.copy(pending = pending?.copy(askedAt = clock()), updatedAt = clock())
    }

    // Drops what is too old to be what the user means now.
    @Synchronized
    fun expireStale() {
        val now = clock()
        val s = state
        if (s.updatedAt > 0 && now - s.updatedAt > SESSION_TTL_MS) {
            state = State()
            return
        }
        state = s.copy(
            entities = s.entities.filter { now - it.timestamp <= ENTITY_TTL_MS },
            candidates = s.candidates.filter { now - it.timestamp <= ENTITY_TTL_MS },
            pending = s.pending?.takeIf { now - it.askedAt <= PENDING_TTL_MS }
        )
    }

    // The entity the user most likely means. Two different candidates mentioned in the same turn
    // with similar confidence are ambiguous, so the caller asks instead of guessing.
    fun lookup(vararg types: EntityType): EntityLookup {
        val now = clock()
        val ranked = state.entities
            .filter { it.type in types }
            .map { it to it.relevance(now) }
            .filter { it.second >= MIN_RELEVANCE }
            .sortedByDescending { it.second }
        val top = ranked.firstOrNull()?.first ?: return EntityLookup.None
        val rival = ranked.getOrNull(1)?.first
        return if (rival != null && !rival.name.equals(top.name, ignoreCase = true) &&
            Math.abs(top.timestamp - rival.timestamp) <= SAME_TURN_MS &&
            Math.abs(top.confidence - rival.confidence) < AMBIGUITY_MARGIN
        ) EntityLookup.Ambiguous(listOf(top, rival)) else EntityLookup.Found(top)
    }

    @Synchronized
    fun clear() {
        state = State()
    }

    // Compact summary for prompts: only live, relevant items, never the raw conversation.
    fun contextBlock(): String {
        val s = state
        if (s.isEmpty) return ""
        val now = clock()
        return buildString {
            if (s.goal.isNotBlank()) append("Goal: ${s.goal} (${s.status})\n")
            s.slots.forEach { (k, v) -> append("$k: $v\n") }
            if (s.topic.isNotBlank()) append("Topic: ${s.topic}\n")
            if (s.lastIntent.isNotBlank()) {
                append("Last request: ${s.lastIntent}")
                if (s.lastArgs.isNotEmpty()) append(" ${s.lastArgs.entries.joinToString(", ") { "${it.key}=${it.value}" }}")
                append("\n")
            }
            val live = s.entities.filter { it.relevance(now) >= MIN_RELEVANCE }
                .sortedByDescending { it.relevance(now) }.take(CONTEXT_ENTITIES)
            if (live.isNotEmpty()) append("Mentioned: ${live.joinToString("; ") { describe(it) }}\n")
            if (s.candidates.isNotEmpty()) {
                append("Last results: ${s.candidates.take(3).mapIndexed { i, c -> "${i + 1}. ${c.name}" }.joinToString("; ")}\n")
            }
            s.pending?.let { append("Waiting for the user to answer: ${it.question}\n") }
            s.notes.forEach { append("Note (this conversation only): $it\n") }
        }.trim()
    }

    private fun describe(e: TrackedEntity): String {
        val extra = listOfNotNull(e.attributes["date"]?.takeIf { it.isNotBlank() }, e.attributes["time"]?.takeIf { it.isNotBlank() })
        return "${e.type.name.lowercase(Locale.US)} ${e.name}" + if (extra.isEmpty()) "" else " (${extra.joinToString(" ")})"
    }

    companion object {
        const val MAX_ENTITIES = 12
        const val MAX_CANDIDATES = 8
        const val CONTEXT_ENTITIES = 5
        const val MIN_RELEVANCE = 0.3
        const val AMBIGUITY_MARGIN = 0.1
        const val SAME_TURN_MS = 3_000L
        const val ENTITY_TTL_MS = 30 * 60 * 1000L
        const val PENDING_TTL_MS = 5 * 60 * 1000L
        const val SESSION_TTL_MS = 2 * 60 * 60 * 1000L
    }
}
