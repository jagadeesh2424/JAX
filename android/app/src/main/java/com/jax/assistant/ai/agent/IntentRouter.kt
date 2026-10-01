package com.jax.assistant.ai.agent

import com.jax.assistant.ai.agent.tools.ReminderParser
import com.jax.assistant.ai.agent.tools.TaskParser
import com.jax.assistant.config.AppConfig
import com.jax.assistant.device.DeviceCommand
import com.jax.assistant.device.DeviceCommandParser
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Locale

// Same normalization for typed and spoken input: trim, collapse whitespace, drop a leading wake phrase.
object InputNormalizer {
    fun normalize(raw: String): String {
        val collapsed = raw.replace(Regex("\\s+"), " ").trim()
        val lower = collapsed.lowercase(Locale.US)
        for (phrase in AppConfig.WAKE_PHRASES) {
            if (lower.startsWith("$phrase ") || lower.startsWith("$phrase,") || lower == phrase) {
                val rest = collapsed.substring(phrase.length).trimStart(' ', ',', '.', '!', '?')
                return rest.ifBlank { collapsed }
            }
        }
        return collapsed
    }
}

// Tools that need the network: a direct call to one of these is depth 1, not 0.
private val NETWORK_TOOLS = setOf("get_weather", "web_search", "web_fetch")

// `name` and `intent` are the labels written to the request log. `depth` is the execution level:
// 0 deterministic local, 1 single network tool, 2 one Gemini reasoning call, 3 planner/agent.
sealed class Route(val name: String, val intent: String, val depth: Int) {
    // High-confidence deterministic intent: one tool call, no model involved.
    class Direct(val tool: String, val args: JSONObject, intent: String) :
        Route("DIRECT_TOOL", intent, if (tool in NETWORK_TOOLS) 1 else 0)
    object Capabilities : Route("DIRECT", "CAPABILITIES", 0)
    class Memory(val reply: String) : Route("MEMORY", "MEMORY_FACT", 0)
    // Required details are missing: ask instead of guessing. `pending` is completed next turn.
    class Clarify(val question: String, val pending: PendingClarification?, intent: String) : Route("CLARIFY", intent, 0)
    // Live information: bounded search + fetch, then one Gemini call to write a sourced answer.
    class Research(val queries: List<String>) : Route("RESEARCH", "WEB_RESEARCH", 2)
    // Continue an interrupted planned run, skipping anything already done.
    object Resume : Route("RESUME", "RESUME_RUN", 3)
    // Genuinely multi-step or needs reasoning before acting: planner -> tools -> verification -> answer.
    object Plan : Route("PLANNER", "COMPLEX_AGENT", 3)
    // One action the model must interpret (e.g. "delete my dentist task"): ReAct loop.
    object Agent : Route("AGENT", "TOOL_REQUEST", 3)
    // General or ambiguous: one model call.
    object Chat : Route("GEMINI", "GENERAL", 2)
}

// Rule-based and deterministic, so simple requests stay fast and never cost a model call.
object FastIntentRouter {

    private val capabilityQuestion = Regex(
        "^(?:what can you do|what are your (?:capabilities|skills|features)|what capabilities do you have|help|what do you do)$"
    )

    // Planning language about trips, events or preparation.
    private val planningRequest = Regex(
        "^(?:please\\s+)?(?:plan|prepare|organi[sz]e|arrange|help me plan)\\b.*\\b(?:trip|travel|weekend|itinerary|vacation|holiday|day out|visit|event|party|everything)\\b"
    )

    // Verbs that start an independent sub-request in "X and Y" / "X, then Y" sentences.
    private val clauseVerb = Regex(
        "^(?:then\\s+)?(?:find|check|create|add|book|search|look up|look at|get|plan|remind|schedule|tell me|let me know|open|navigate|set|save|compare|decide|choose|pick)\\b"
    )
    private val clauseSeparator = Regex("\\s*(?:,\\s*)?\\b(?:and|then)\\b\\s*")

    private val agentVerbs = Regex(
        "\\b(create|add|make|remind|schedule|set|complete|finish|delete|remove|cancel|mark|open|launch|call|dial|navigate|link)\\b"
    )
    private val agentPhrases = listOf("search my", "find my", "organize my", "save this", "remember this", "remember that", "update my profile")

    private val resumeRequest = Regex(
        "^(?:please\\s+)?(?:resume|continue|carry on|pick up where (?:you|we) left off)(?: (?:it|that|the (?:last|previous|interrupted) (?:task|request|run|plan)))?$"
    )

    // "What's the weather in Singapore tomorrow?", "check the weather", "will it rain tomorrow in Mysore".
    private val weatherQuery = Regex(
        "^(?:(?:what(?:'s| is| will be)|how(?:'s| is| will be)|check|get|show(?: me)?|tell me)\\s+)?(?:the\\s+)?" +
            "(?:weather|forecast|temperature)(?:\\s+forecast)?(?:\\s+(?:be\\s+)?like)?" +
            "(?:\\s+(?:in|at|for)\\s+(.+?))?(?:\\s+(today|tomorrow|tonight))?$"
    )
    private val rainQuery = Regex(
        "^(?:will it|is it going to) rain(?:\\s+(today|tomorrow|tonight))?(?:\\s+(?:in|at)\\s+(.+?))?(?:\\s+(today|tomorrow|tonight))?$"
    )

    // Live-information requests answered from the web with sources.
    private val researchRequest = Regex(
        "^(?:please\\s+)?(?:research|look up|find (?:out|information|info) (?:about|on)|" +
            "(?:what(?:'s| is| are)|tell me) the latest(?: news)?(?: on| about| in| for)?|latest news (?:on|about|in))\\s+(.+)$"
    )
    private val compareRequest = Regex("^(?:please\\s+)?compare\\s+(.+?)\\s+(?:and|with|vs\\.?|versus|to)\\s+(.+)$")
    private val versusRequest = Regex("^(.+?)\\s+(?:vs\\.?|versus)\\s+(.+)$")
    // "Find hotels in Singapore": a result list, no model call. ("find my ..." stays with the agent.)
    private val findRequest = Regex("^(?:please\\s+)?(?:find|search for|look for)\\s+(?!my\\b|directions\\b|out\\b)(.+)$")

    private val memoryQuestion = Regex(
        "\\b(?:when is|what is|what was|who is|what(?:'s| is) the|do you remember|what did i tell you|what did i decide|name of)\\b"
    )

    private val bareReminder = Regex("^(?:please\\s+)?(?:remind me|set a reminder|create a reminder|add a reminder)\\b(.*)$")
    private val reminderNeedsReasoning = Regex("\\b(decide|figure out|which|whether|best|should|suggest)\\b")
    // Relative times ("in 10 minutes") are not parsed deterministically; the agent handles them.
    private val relativeTime = Regex("\\bin\\s+(?:an?|\\d+)\\s*(?:min|minute|minutes|hour|hours|hr|hrs)\\b")

    fun route(input: String, now: LocalDateTime = LocalDateTime.now()): Route {
        val text = input.trim()
        val lower = text.lowercase(Locale.US).trimEnd('?', '.', '!')
        if (lower.isBlank()) return Route.Chat

        if (capabilityQuestion.matches(lower)) return Route.Capabilities
        if (resumeRequest.matches(lower)) return Route.Resume

        // Multi-step before single commands: "find X and create a task" must not be read as "find X".
        if (isMultiStep(lower)) return Route.Plan

        weather(text, lower)?.let { return it }

        DeviceCommandParser.parse(text)?.let { return direct(it) }

        ReminderParser.parse(text, now)?.let { r ->
            return Route.Direct(
                "create_reminder",
                JSONObject().put("title", r.title).put("date", r.date.toString()).put("time", r.time.toString()),
                "REMINDER"
            )
        }

        TaskParser.parse(text, now.toLocalDate())?.let { t ->
            return Route.Direct(
                "create_task",
                JSONObject().put("title", t.title).put("deadline", t.deadline?.toString() ?: ""),
                "TASK"
            )
        }

        reminderClarification(text, lower, now)?.let { return it }
        research(text, lower)?.let { return it }

        if (agentVerbs.containsMatchIn(lower) || agentPhrases.any { lower.contains(it) }) return Route.Agent
        return Route.Chat
    }

    // Cheap lexical gate used before durable-memory lookup; ordinary conversation does not pay
    // for semantic retrieval or embeddings.
    fun isLikelyMemoryQuery(input: String): Boolean {
        val lower = input.lowercase(Locale.US)
        return memoryQuestion.containsMatchIn(lower) ||
            lower.contains("do you remember") || lower.contains("what did i tell you")
    }

    private fun weather(text: String, lower: String): Route.Direct? {
        val (location, day) = weatherQuery.find(lower)?.let { it.groupValues[1] to it.groupValues[2] }
            ?: rainQuery.find(lower)?.let { it.groupValues[2] to it.groupValues[1].ifBlank { it.groupValues[3] } }
            ?: return null
        return Route.Direct(
            "get_weather",
            JSONObject().put("location", originalCase(text, location)).put("day", if (day == "tomorrow") "tomorrow" else "today"),
            "WEATHER"
        )
    }

    private fun research(text: String, lower: String): Route? {
        compareRequest.find(lower)?.let { m ->
            return Route.Research(listOf(originalCase(text, m.groupValues[1]), originalCase(text, m.groupValues[2])))
        }
        researchRequest.find(lower)?.let { m ->
            if (m.groupValues[1].startsWith("my ")) return null
            val topic = originalCase(text, m.groupValues[1])
            return Route.Research(listOf(if (lower.contains("latest")) "latest $topic" else topic))
        }
        versusRequest.find(lower)?.let { m ->
            return Route.Research(listOf(originalCase(text, m.groupValues[1]), originalCase(text, m.groupValues[2])))
        }
        findRequest.find(lower)?.let { m ->
            val query = originalCase(text, m.groupValues[1])
            return Route.Direct("web_search", JSONObject().put("query", query), "WEB_SEARCH")
        }
        return null
    }

    // "Remind me tomorrow" or "Remind me to call John": a time (or a day) is missing, so ask.
    private fun reminderClarification(text: String, lower: String, now: LocalDateTime): Route.Clarify? {
        val body = bareReminder.find(lower)?.groupValues?.get(1)?.trim() ?: return null
        if (reminderNeedsReasoning.containsMatchIn(body) || relativeTime.containsMatchIn(body)) return null
        if (ReminderParser.findTime(body) != null) return null
        val (date, rest) = ReminderParser.extractDate(body, now.toLocalDate()) ?: return null
        val connector = if (Regex("^\\s*(?:for|about|of)\\b").containsMatchIn(rest)) "about" else "to"
        val parsedTitle = ReminderParser.extractTitle(rest.replace(Regex("^\\s*(?:for|about|of)\\b"), " "))
        val title = if (parsedTitle == "Reminder") "" else ReminderParser.restoreCasing(text.trim(), parsedTitle)
        val question = Clarifications.reminder(title, date, now.toLocalDate(), connector)
        val slots = mapOf("title" to title, "date" to (date?.toString() ?: ""), "connector" to connector)
        return Route.Clarify(question, PendingClarification("REMINDER", slots, question), "REMINDER")
    }

    private fun originalCase(text: String, lowerPart: String): String {
        val part = lowerPart.trim()
        if (part.isEmpty()) return ""
        val start = text.lowercase(Locale.US).indexOf(part)
        return if (start >= 0) text.substring(start, start + part.length) else part
    }

    fun isMultiStep(lower: String): Boolean {
        if (planningRequest.containsMatchIn(lower)) return true
        val clauses = lower.split(clauseSeparator).map { it.trim() }.filter { it.isNotEmpty() }
        return clauses.size >= 2 && clauses.count { clauseVerb.containsMatchIn(it) } >= 2
    }

    private fun direct(command: DeviceCommand): Route.Direct = when (command) {
        is DeviceCommand.CurrentDateTime -> Route.Direct(
            "get_datetime",
            JSONObject().put("include_date", command.includeDate).put("include_time", command.includeTime),
            if (command.includeDate && !command.includeTime) "DATE" else "TIME"
        )
        is DeviceCommand.Weather -> Route.Direct(
            "get_weather", JSONObject().put("location", command.location ?: ""), "WEATHER"
        )
        is DeviceCommand.Navigate -> Route.Direct("navigate", JSONObject().put("destination", command.destination), "NAVIGATION")
        is DeviceCommand.WebSearch -> Route.Direct("web_search", JSONObject().put("query", command.query), "WEB_SEARCH")
        is DeviceCommand.OpenApp -> Route.Direct("open_app", JSONObject().put("name", command.appName), "OPEN_APP")
        is DeviceCommand.Dial -> Route.Direct("dial", JSONObject().put("number", command.number), "DIAL")
        is DeviceCommand.SetAlarm -> Route.Direct(
            "set_alarm",
            JSONObject().put("hour", command.hour).put("minute", command.minute).put("label", command.label ?: ""),
            "ALARM"
        )
        is DeviceCommand.SetTimer -> Route.Direct(
            "set_timer", JSONObject().put("seconds", command.seconds).put("label", command.label ?: ""), "TIMER"
        )
        DeviceCommand.OpenCamera -> Route.Direct("open_camera", JSONObject(), "CAMERA")
        DeviceCommand.OpenSettings -> Route.Direct("open_settings", JSONObject(), "SETTINGS")
    }
}
