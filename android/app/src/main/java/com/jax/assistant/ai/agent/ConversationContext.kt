package com.jax.assistant.ai.agent

import com.jax.assistant.ai.agent.tools.ReminderParser
import com.jax.assistant.ai.agent.tools.WebSearchTool
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

// Deterministic, conservative entity extraction from the user's own words. Each pattern carries
// a confidence reflecting how reliable it is; nothing here calls a model.
object EntityExtractor {
    private val travelPlace = Regex(
        "\\b(?:trip to|travel(?:l?ing)? to|flights? to|fly to|weather (?:in|for|at)|hotels? (?:in|near)|" +
            "restaurants? (?:in|near)|directions to|things to do in)\\s+(?:the\\s+)?([\\p{L}][\\p{L}'\\-]*(?:\\s+[\\p{L}][\\p{L}'\\-]*){0,2})",
        RegexOption.IGNORE_CASE
    )
    private val properPlace = Regex("\\b(?:in|at|near|visit|visiting)\\s+([A-Z][\\p{L}'\\-]+(?:\\s+[A-Z][\\p{L}'\\-]+){0,2})")
    private val person = Regex("\\b(?:call|email|text|message|meet|meeting with|with|tell|ask)\\s+([A-Z][\\p{L}'\\-]+)")
    private val url = Regex("https?://[^\\s)\"']+")
    private val project = Regex("\\bproject\\s+([A-Z][\\p{L}\\d\\-]+)")
    private val document = Regex("\\b([\\w\\-]+\\.(?:pdf|docx?|xlsx?|pptx?|txt|csv))\\b", RegexOption.IGNORE_CASE)

    private val boundaryWords = setOf(
        "tomorrow", "today", "tonight", "next", "this", "on", "at", "for", "and", "then", "with", "in", "by", "from",
        "me", "my", "the", "it", "there", "please", "now", "week", "weekend", "morning", "evening", "afternoon", "near"
    )
    private val notNames = setOf(
        "I", "The", "My", "Me", "It", "Him", "Her", "Them", "Maps", "Google", "JAX", "AM", "PM",
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday",
        "January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"
    )

    fun extract(text: String, nowMillis: Long, today: LocalDate): List<TrackedEntity> {
        val out = mutableListOf<TrackedEntity>()
        fun add(type: EntityType, name: String, confidence: Double, attributes: Map<String, String> = emptyMap()) {
            if (name.isNotBlank()) out += TrackedEntity(type, name, "user", nowMillis, confidence, attributes)
        }

        travelPlace.findAll(text).forEach { add(EntityType.PLACE, cleanPlace(it.groupValues[1]), TRAVEL_CONFIDENCE) }
        properPlace.findAll(text).forEach { m ->
            val name = cleanPlace(m.groupValues[1])
            if (name.split(' ').first() !in notNames && out.none { it.name.equals(name, ignoreCase = true) }) {
                add(EntityType.PLACE, name, PROPER_NOUN_CONFIDENCE)
            }
        }
        person.findAll(text).map { it.groupValues[1] }.filter { it !in notNames }
            .forEach { add(EntityType.PERSON, it, PROPER_NOUN_CONFIDENCE) }
        url.findAll(text).forEach { add(EntityType.WEBSITE, it.value, EXACT_CONFIDENCE, mapOf("url" to it.value)) }
        project.findAll(text).forEach { add(EntityType.PROJECT, it.groupValues[1], PROPER_NOUN_CONFIDENCE) }
        document.findAll(text).forEach { add(EntityType.DOCUMENT, it.groupValues[1], EXACT_CONFIDENCE) }

        val lower = text.lowercase(Locale.US)
        ReminderParser.extractDate(lower, today)?.first?.let { add(EntityType.DATE, it.toString(), EXACT_CONFIDENCE) }
        ReminderParser.findTime(lower)?.let { add(EntityType.TIME, it.first.toString(), EXACT_CONFIDENCE) }
        return out
    }

    // "mysore next weekend" -> "Mysore": stop at the first word that is not part of a name.
    private fun cleanPlace(raw: String): String = raw.trim().split(Regex("\\s+"))
        .takeWhile { it.lowercase(Locale.US) !in boundaryWords }
        .joinToString(" ") { word -> word.replaceFirstChar { it.titlecase(Locale.US) } }

    private const val TRAVEL_CONFIDENCE = 0.75
    private const val PROPER_NOUN_CONFIDENCE = 0.6
    private const val EXACT_CONFIDENCE = 0.9
}

// Feeds working memory from what actually happened: the user's words and successful tool results.
object ConversationTracker {
    private const val TOOL_CONFIDENCE = 0.95

    fun observeUserText(memory: WorkingMemory, text: String, today: LocalDate) {
        EntityExtractor.extract(text, memory.now(), today).forEach(memory::track)
    }

    fun observeTool(memory: WorkingMemory, outcome: ToolOutcome) {
        if (!outcome.succeeded) return
        val now = memory.now()
        val args = outcome.args
        val data = outcome.result.data
        fun track(type: EntityType, name: String, attributes: Map<String, String> = emptyMap()) {
            if (name.isNotBlank()) memory.track(TrackedEntity(type, name.trim(), outcome.toolName, now, TOOL_CONFIDENCE, attributes))
        }
        when (outcome.toolName) {
            "get_weather" -> track(
                EntityType.PLACE,
                args.optString("location").ifBlank { data?.optString("location").orEmpty().substringBefore(",") }
            )
            "navigate" -> track(EntityType.PLACE, args.optString("destination"))
            "create_task" -> track(
                EntityType.TASK,
                args.optString("title"),
                mapOf("task_id" to data?.optString("task_id").orEmpty(), "date" to args.optString("deadline"))
            )
            "create_reminder", "reschedule_reminder" -> {
                val time = data?.optString("time").orEmpty()
                track(
                    if (time.isBlank()) EntityType.TASK else EntityType.REMINDER,
                    if (outcome.toolName == "create_reminder") args.optString("title") else data?.optString("title").orEmpty(),
                    mapOf("task_id" to data?.optString("task_id").orEmpty(), "date" to data?.optString("date").orEmpty(), "time" to time)
                )
            }
            "web_search" -> memory.setCandidates(
                WebSearchTool.parseResults(data).map {
                    TrackedEntity(EntityType.ITEM, it.title, "web_search", now, TOOL_CONFIDENCE, mapOf("url" to it.url))
                },
                args.optString("query")
            )
            "search_tasks" -> {
                val tasks = data?.optJSONArray("tasks") ?: return
                memory.setCandidates(
                    (0 until tasks.length()).mapNotNull { tasks.optJSONObject(it) }.map {
                        TrackedEntity(EntityType.ITEM, it.optString("title"), "search_tasks", now, TOOL_CONFIDENCE,
                            mapOf("task_id" to it.optString("id")))
                    },
                    "tasks"
                )
            }
            "web_fetch" -> {
                val url = args.optString("url")
                track(EntityType.WEBSITE, data?.optString("title").orEmpty().ifBlank { url }, mapOf("url" to url))
            }
            "open_app" -> track(EntityType.APP, args.optString("name"))
        }
    }
}

object Clarifications {
    // `connector` is "to" for actions ("to call John") or "about" for topics ("about the meeting").
    fun reminder(title: String, date: LocalDate?, today: LocalDate, connector: String = "to"): String = when {
        title.isBlank() && date != null -> "What time ${dayPhrase(date, today)}?"
        title.isBlank() -> "What should I remind you about, and when?"
        date != null -> "What time ${dayPhrase(date, today)} should I remind you $connector ${lowerFirst(title)}?"
        else -> "When should I remind you $connector ${lowerFirst(title)}?"
    }

    fun dayPhrase(date: LocalDate, today: LocalDate): String = when {
        date == today -> "today"
        date == today.plusDays(1) -> "tomorrow"
        date.isAfter(today) && date.isBefore(today.plusDays(7)) -> "on " + date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.US)
        else -> "on $date"
    }

    private fun lowerFirst(text: String) = text.replaceFirstChar { it.lowercase(Locale.US) }
}

// `text` is what gets routed; `route`, when set, bypasses the router (the reference already
// determined the action). `note` records what was resolved, for the log.
data class Resolution(val text: String, val route: Route? = null, val note: String = "")

// Resolves follow-ups against working memory before routing, so "there", "it", "the second one"
// or "how about tomorrow?" never need a model call. When the referent is missing or ambiguous it
// asks instead of guessing.
object ReferenceResolver {
    private val editIt = Regex(
        "^(?:please\\s+)?(?:(?:no|actually|oh),?\\s+)?(?:make it|change it to|move it to|set it (?:to|for)|reschedule it(?: to| for)?|push it to)\\s+(.+)$"
    )
    private val completeIt = Regex(
        "^(?:please\\s+)?(?:(?:mark|set) (?:it|that|this) (?:as )?(?:done|complete|completed|finished)|(?:complete|finish) (?:it|that|this))$"
    )
    private val deleteIt = Regex("^(?:please\\s+)?(?:delete|remove|cancel) (?:it|that|this)(?: (?:task|reminder))?$")
    private val ordinal = Regex(
        "\\b(?:the\\s+)?(first|second|third|fourth|fifth|1st|2nd|3rd|4th|5th|last)\\s+(?:one|result|option|link|hotel|place|item|task|site|page)\\b"
    )
    private val refine = Regex("^(?:and\\s+)?which (?:ones?|of (?:them|these|those))\\s+(?:are\\s+|is\\s+)?(.+)$")
    private val dayFollowUp = Regex("^(?:and\\s+|what about\\s+|how about\\s+)?(today|tomorrow)$")
    private val placeFollowUp = Regex("^(?:and\\s+|what about\\s+|how about\\s+)(?:in\\s+)?([\\p{L}][\\p{L}'\\- ]{1,40})$")
    private val there = Regex("(?<!\\b(?:is|are|was|were|be)\\s)\\bthere\\b(?!\\s+(?:is|are|was|were|be|will)\\b)")
    private val locationContext = Regex(
        "\\b(weather|forecast|temperature|rain|go|get|going|navigate|drive|directions|take me|hotels?|restaurants?|stay|travel|fly|flights?|things to do|reach)\\b"
    )
    private val movement = Regex("\\b(?:go|get|going|navigate|drive|take me|directions|travel|fly|flights?|reach)$")
    private val taskAction = Regex("^(?:mark|complete|finish|delete|remove|cancel)\\b")
    private val fillers = setOf("ok", "okay", "yes", "sure", "please", "yeah", "reminder")

    private const val MAX_ANSWER_WORDS = 8

    fun resolve(input: String, memory: WorkingMemory, now: LocalDateTime): Resolution {
        val base = input.trim()
        val lower = base.lowercase(Locale.US).trimEnd('?', '.', '!')
        memory.state.pending?.let { pending -> answerPending(base, lower, pending, now)?.let { return it } }
        return editReference(lower, memory, now)
            ?: taskReference(lower, memory)
            ?: ordinalReference(base, memory)
            ?: refineReference(base, lower, memory.state)
            ?: followUp(lower, memory.state)
            ?: placeReference(base, lower, memory)
            ?: Resolution(base)
    }

    private fun clarify(question: String, intent: String, note: String, text: String) =
        Resolution(text, Route.Clarify(question, null, intent), note)

    // The user is answering a question JAX asked (e.g. "What time tomorrow?" -> "6 PM").
    private fun answerPending(base: String, lower: String, pending: PendingClarification, now: LocalDateTime): Resolution? {
        if (pending.intent != "REMINDER") return null
        val words = lower.split(Regex("\\s+")).size
        val time = ReminderParser.findTime(lower)
        val withoutTime = time?.let { lower.removeRange(it.second) } ?: lower
        val (date, rest) = ReminderParser.extractDate(withoutTime, now.toLocalDate()) ?: return null
        if ((time == null && date == null) || words > MAX_ANSWER_WORDS) return null

        val leftover = ReminderParser.extractTitle(rest.replace(Regex("^\\s*(?:remind me|at|on)\\b"), " "))
        val title = pending.slots["title"].orEmpty().ifBlank {
            if (leftover.lowercase(Locale.US) in fillers) "" else ReminderParser.restoreCasing(base, leftover)
        }
        val day = date ?: pending.slots["date"]?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        if (time == null) {
            val connector = pending.slots["connector"].orEmpty().ifBlank { "to" }
            val question = Clarifications.reminder(title, day, now.toLocalDate(), connector)
            val slots = mapOf("title" to title, "date" to (day?.toString() ?: ""), "connector" to connector)
            return Resolution(base, Route.Clarify(question, PendingClarification("REMINDER", slots, question), "REMINDER"), "reminder still needs a time")
        }
        val resolvedDay = day ?: if (LocalDateTime.of(now.toLocalDate(), time.first).isAfter(now)) now.toLocalDate() else now.toLocalDate().plusDays(1)
        val args = JSONObject()
            .put("title", title.ifBlank { "Reminder" })
            .put("date", resolvedDay.toString())
            .put("time", time.first.toString())
        return Resolution(base, Route.Direct("create_reminder", args, "REMINDER"), "answered pending reminder")
    }

    // "Make it 6 PM" -> the most recent reminder/task.
    private fun editReference(lower: String, memory: WorkingMemory, now: LocalDateTime): Resolution? {
        val match = editIt.find(lower) ?: return null
        val target = when (val found = memory.lookup(EntityType.REMINDER, EntityType.TASK)) {
            is EntityLookup.Found -> found.entity
            is EntityLookup.Ambiguous -> return clarify(whichOf(found.options), "REMINDER", "ambiguous reminder", lower)
            EntityLookup.None -> return clarify("Which reminder or task should I change?", "REMINDER", "no reminder in context", lower)
        }
        val taskId = target.attributes["task_id"].orEmpty()
        if (taskId.isBlank()) return clarify("Which reminder or task should I change?", "REMINDER", "reminder without id", lower)
        val rest = match.groupValues[1]
        val time = ReminderParser.findTime(rest)
        val date = ReminderParser.extractDate(time?.let { rest.removeRange(it.second) } ?: rest, now.toLocalDate())?.first
        if (time == null && date == null) {
            return clarify("What time or day should I move \"${target.name}\" to?", "REMINDER", "edit without a time", lower)
        }
        val args = JSONObject()
            .put("task_id", taskId)
            .put("date", date?.toString() ?: "")
            .put("time", time?.first?.toString() ?: "")
        return Resolution(lower, Route.Direct("reschedule_reminder", args, "REMINDER_UPDATE"), "it -> ${target.name}")
    }

    // "Mark it done" / "delete it" -> the most recent task/reminder.
    private fun taskReference(lower: String, memory: WorkingMemory): Resolution? {
        val remove = deleteIt.matches(lower)
        if (!remove && !completeIt.matches(lower)) return null
        val target = when (val found = memory.lookup(EntityType.TASK, EntityType.REMINDER)) {
            is EntityLookup.Found -> found.entity
            is EntityLookup.Ambiguous -> return clarify(whichOf(found.options), "TASK", "ambiguous task", lower)
            EntityLookup.None -> return clarify("Which task do you mean?", "TASK", "no task in context", lower)
        }
        val taskId = target.attributes["task_id"].orEmpty()
        if (taskId.isBlank()) return clarify("Which task do you mean?", "TASK", "task without id", lower)
        return Resolution(lower, completeTask(taskId, remove), "it -> ${target.name}")
    }

    // "Book the second one" -> the second item of the last result list.
    private fun ordinalReference(base: String, memory: WorkingMemory): Resolution? {
        val lowerBase = base.lowercase(Locale.US)
        val match = ordinal.find(lowerBase) ?: return null
        val candidates = memory.state.candidates
        if (candidates.isEmpty()) {
            return clarify("Which list do you mean? I don't have any recent results to choose from.", "SELECTION", "no candidates", base)
        }
        val index = when (match.groupValues[1]) {
            "first", "1st" -> 0
            "second", "2nd" -> 1
            "third", "3rd" -> 2
            "fourth", "4th" -> 3
            "fifth", "5th" -> 4
            else -> candidates.size - 1
        }
        val chosen = candidates.getOrNull(index)
            ?: return clarify("I only have ${candidates.size} result(s). Which one do you mean?", "SELECTION", "ordinal out of range", base)
        memory.track(chosen.copy(timestamp = memory.now(), source = "selection"))

        val taskId = chosen.attributes["task_id"]
        if (taskId != null && taskAction.containsMatchIn(lowerBase)) {
            val remove = lowerBase.startsWith("delete") || lowerBase.startsWith("remove") || lowerBase.startsWith("cancel")
            return Resolution(base, completeTask(taskId, remove), "ordinal -> ${chosen.name}")
        }
        val label = "\"${chosen.name}\"" + (chosen.attributes["url"]?.let { " ($it)" } ?: "")
        val rewritten = base.substring(0, match.range.first) + label + base.substring(match.range.last + 1)
        return Resolution(rewritten, Route.Agent, "ordinal -> ${chosen.name}")
    }

    // "Which ones are near Bugis?" -> narrows the last search topic.
    private fun refineReference(base: String, lower: String, state: WorkingMemory.State): Resolution? {
        val match = refine.find(lower) ?: return null
        if (state.topic.isBlank() || state.topic == "tasks") return null
        val constraint = match.groupValues[1].trim()
        val start = base.lowercase(Locale.US).indexOf(constraint)
        val cased = if (start >= 0) base.substring(start, start + constraint.length) else constraint
        val query = "${state.topic} $cased"
        return Resolution(query, Route.Direct("web_search", JSONObject().put("query", query), "WEB_SEARCH"), "ones -> ${state.topic}")
    }

    // "How about tomorrow?" / "What about Singapore?" right after a weather answer.
    private fun followUp(lower: String, state: WorkingMemory.State): Resolution? {
        if (state.lastIntent != "WEATHER") return null
        dayFollowUp.find(lower)?.let { m ->
            val args = JSONObject().put("location", state.lastArgs["location"].orEmpty()).put("day", m.groupValues[1])
            return Resolution(lower, Route.Direct("get_weather", args, "WEATHER"), "weather follow-up (day)")
        }
        placeFollowUp.find(lower)?.let { m ->
            val place = m.groupValues[1].trim()
            val first = place.split(' ').first()
            if (first in setOf("the", "it", "that", "rain", "wind", "temperature", "today", "tomorrow")) return null
            val cased = place.split(Regex("\\s+")).joinToString(" ") { w -> w.replaceFirstChar { it.titlecase(Locale.US) } }
            val args = JSONObject().put("location", cased).put("day", state.lastArgs["day"].orEmpty().ifBlank { "today" })
            return Resolution(lower, Route.Direct("get_weather", args, "WEATHER"), "weather follow-up (place)")
        }
        return null
    }

    // "Check the weather there" -> "Check the weather in Mysore".
    private fun placeReference(base: String, lower: String, memory: WorkingMemory): Resolution? {
        val match = there.find(base.lowercase(Locale.US)) ?: return null
        if (!locationContext.containsMatchIn(lower)) return null
        val place = when (val found = memory.lookup(EntityType.PLACE)) {
            is EntityLookup.Found -> found.entity.name
            is EntityLookup.Ambiguous -> return clarify(whichOf(found.options), "PLACE", "ambiguous place", base)
            EntityLookup.None -> return clarify("Which place do you mean?", "PLACE", "no place in context", base)
        }
        val before = base.substring(0, match.range.first).trimEnd()
        val preposition = if (movement.containsMatchIn(before.lowercase(Locale.US))) "to" else "in"
        val rewritten = "$before $preposition $place${base.substring(match.range.last + 1)}".trim()
        return Resolution(rewritten, null, "there -> $place")
    }

    private fun completeTask(taskId: String, remove: Boolean) = Route.Direct(
        "complete_task",
        JSONObject().put("id", taskId).put("remove", remove),
        if (remove) "TASK_DELETE" else "TASK_COMPLETE"
    )

    private fun whichOf(options: List<TrackedEntity>): String =
        "Do you mean ${options.joinToString(" or ") { "\"${it.name}\"" }}?"
}
