package com.jax.assistant.ai.agent

import org.json.JSONArray
import org.json.JSONObject

enum class StepStatus { PENDING, RUNNING, COMPLETED, FAILED, SKIPPED }

class PlanStep(
    val id: String,
    val tool: String,
    val input: JSONObject,
    val dependsOn: List<String> = emptyList(),
    var status: StepStatus = StepStatus.PENDING,
    var result: String? = null,
    var error: String? = null,
    var retryCount: Int = 0,
    // Set once the step ran: VERIFIED, UNVERIFIED (ran, not independently confirmed) or FAILED.
    var verification: VerificationStatus? = null
) {
    fun skip(reason: String) {
        status = StepStatus.SKIPPED
        error = reason
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("tool", tool)
        .put("input", input)
        .put("dependsOn", JSONArray(dependsOn))
        .put("status", status.name)
        .put("verification", verification?.name ?: JSONObject.NULL)
        .put("result", result ?: JSONObject.NULL)
        .put("error", error ?: JSONObject.NULL)
        .put("retryCount", retryCount)
}

class AgentPlan(
    val goal: String,
    val steps: List<PlanStep>,
    val slots: Map<String, String> = emptyMap()
) {
    val isFinished: Boolean get() = steps.none { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }

    fun step(id: String): PlanStep? = steps.firstOrNull { it.id == id }

    fun toJson(): JSONObject = JSONObject()
        .put("goal", goal)
        .put("slots", JSONObject(slots))
        .put("steps", JSONArray().apply { steps.forEach { put(it.toJson()) } })

    // One line per step, used in prompts and to tell the user where an interrupted run stopped.
    fun summary(): String = steps.joinToString("\n") { s ->
        val state = if (s.status == StepStatus.COMPLETED && s.verification != null) "${s.status}/${s.verification}" else s.status.name
        "${s.id}. ${s.tool} ${s.input} -> $state" +
            (s.result?.let { " | $it" } ?: "") + (s.error?.let { " | error: $it" } ?: "")
    }

    // Marks pending steps that can never run, so execution cannot wait on them forever:
    // a dependency id that is not in the plan, or membership in a dependency cycle.
    fun markUnresolvableSteps() {
        val byId = steps.associateBy { it.id }
        steps.filter { it.status == StepStatus.PENDING }.forEach { step ->
            step.dependsOn.firstOrNull { it !in byId }?.let { step.skip("missing dependency \"$it\"") }
        }

        val visiting = HashSet<String>()
        val done = HashSet<String>()
        val cycles = LinkedHashMap<String, List<String>>()
        fun visit(id: String, path: List<String>) {
            if (id in done) return
            if (id in visiting) {
                val cycle = path.subList(path.indexOf(id), path.size) + id
                cycle.dropLast(1).forEach { cycles.putIfAbsent(it, cycle) }
                return
            }
            visiting += id
            byId[id]?.dependsOn?.filter { it in byId }?.forEach { visit(it, path + id) }
            visiting -= id
            done += id
        }
        steps.forEach { visit(it.id, emptyList()) }
        cycles.forEach { (id, cycle) ->
            byId[id]?.takeIf { it.status == StepStatus.PENDING }
                ?.skip("circular dependency (${cycle.joinToString(" -> ")})")
        }
    }

    companion object {
        fun fromJson(json: JSONObject): AgentPlan {
            val slots = json.optJSONObject("slots")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
                ?: emptyMap()
            val arr = json.optJSONArray("steps") ?: JSONArray()
            val steps = (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.map { s ->
                PlanStep(
                    id = s.optString("id"),
                    tool = s.optString("tool"),
                    input = s.optJSONObject("input") ?: JSONObject(),
                    dependsOn = s.optJSONArray("dependsOn")?.let { d -> (0 until d.length()).map { d.optString(it) } } ?: emptyList(),
                    status = runCatching { StepStatus.valueOf(s.optString("status")) }.getOrDefault(StepStatus.PENDING),
                    result = s.optString("result").takeIf { s.has("result") && !s.isNull("result") },
                    error = s.optString("error").takeIf { s.has("error") && !s.isNull("error") },
                    retryCount = s.optInt("retryCount", 0),
                    verification = runCatching { VerificationStatus.valueOf(s.optString("verification")) }.getOrNull()
                )
            }
            return AgentPlan(json.optString("goal"), steps, slots)
        }
    }
}

// Builds the planning prompt and turns the model's JSON into a validated plan. The model only
// proposes steps; unknown tools are rejected here, and execution/permissions stay deterministic.
class Planner(private val registry: ToolRegistry, private val maxSteps: Int = MAX_STEPS) {

    fun buildPrompt(userInput: String, context: String, today: String): String = """
        You are the planner for J.A.X. (Jagadeesh Agent X). Break the request into the MINIMUM tool steps.

        TODAY: $today
        CONTEXT:
        $context

        TOOLS (READ tools return data to you; ACTION tools change something for the user):
        ${registry.catalogText()}

        RULES:
        - Respond with exactly ONE JSON object and nothing else:
          {"goal":"...","slots":{"key":"value"},"steps":[{"id":"1","tool":"<tool name>","input":{...},"dependsOn":[]}]}
        - Use only the tools listed. At most $maxSteps steps. Never invent ids or data.
        - Only include steps that need a tool. Knowledge, advice and itineraries are written by you in the
          final answer later, so they need no step.
        - "dependsOn" lists step ids that must succeed first (e.g. create a task only after checking weather).
        - Dates must be YYYY-MM-DD and times HH:mm, resolved against TODAY.
        - "slots" holds the key facts of the goal (e.g. destination, date).

        USER REQUEST: "$userInput"
    """.trimIndent()

    // Returns null when the model's output is not a usable plan (the caller falls back).
    // Dependencies are kept even when they point forward: execution resolves the order.
    fun parse(raw: String): AgentPlan? {
        val json = extractJson(raw) ?: return null
        val arr = json.optJSONArray("steps") ?: return null
        val usedIds = HashSet<String>()
        val steps = (0 until minOf(arr.length(), maxSteps)).mapNotNull { i ->
            val s = arr.optJSONObject(i) ?: return@mapNotNull null
            val rawId = s.optString("id").trim()
            val duplicateId = rawId.isNotEmpty() && rawId in usedIds
            val id = when {
                rawId.isEmpty() -> "auto-${i + 1}"
                duplicateId -> "$rawId#${i + 1}"
                else -> rawId
            }
            usedIds += id
            val dependsOn = s.optJSONArray("dependsOn")
                ?.let { d -> (0 until d.length()).map { d.optString(it).trim() } }
                ?.filter { it.isNotEmpty() }
                ?.distinct()
                ?: emptyList()
            val tool = s.optString("tool").trim()
            PlanStep(id, tool, s.optJSONObject("input") ?: JSONObject(), dependsOn).apply {
                when {
                    duplicateId -> skip("invalid step id: \"$rawId\" is already used by another step")
                    registry.get(tool) == null -> skip("unknown tool \"$tool\"")
                }
            }
        }
        val slots = json.optJSONObject("slots")?.let { o -> o.keys().asSequence().associateWith { o.optString(it) } }
            ?: emptyMap()
        return AgentPlan(json.optString("goal").ifBlank { "Complete the request" }, steps, slots)
            .apply { markUnresolvableSteps() }
    }

    fun buildSynthesisPrompt(userInput: String, context: String, plan: AgentPlan): String = """
        You are J.A.X. (Jagadeesh Agent X). Write the final answer to the user's request using the
        tool results below plus your own knowledge where no tool applies.

        CONTEXT:
        $context

        GOAL: ${plan.goal}
        STEP RESULTS:
        ${if (plan.steps.isEmpty()) "(no tool steps were needed)" else plan.summary()}

        RULES:
        - COMPLETED/VERIFIED: independently confirmed; you may say it was done.
        - COMPLETED/UNVERIFIED: the request was accepted but could not be confirmed; say exactly that and
          never claim it definitely happened.
        - FAILED or SKIPPED: say it did not happen and why.
        - Be concise and practical. Plain text, no JSON.

        USER REQUEST: "$userInput"
    """.trimIndent()

    private fun extractJson(raw: String): JSONObject? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end > start) JSONObject(raw.substring(start, end + 1)) else null
    } catch (e: Exception) {
        null
    }

    companion object {
        const val MAX_STEPS = 6
    }
}

// Prepares an interrupted plan for resumption. A step left RUNNING may or may not have taken
// effect: reads are simply repeated; a write is repeated only when the tool can confirm it did
// not happen, marked done when it did, and otherwise skipped so it is never duplicated.
object RunResumer {
    suspend fun prepare(plan: AgentPlan, registry: ToolRegistry): List<String> {
        val notes = mutableListOf<String>()
        plan.steps.filter { it.status == StepStatus.RUNNING }.forEach { step ->
            val tool = registry.get(step.tool)
            when {
                tool == null -> step.skip("tool \"${step.tool}\" is no longer available")
                tool.isReadOnly -> step.status = StepStatus.PENDING
                else -> when (checkDone(tool, step)) {
                    true -> {
                        step.status = StepStatus.COMPLETED
                        step.verification = VerificationStatus.VERIFIED
                        step.result = "already done before the interruption (confirmed by read-back)"
                        notes += "Step ${step.id} (${step.tool}) had already completed, so I didn't repeat it."
                    }
                    false -> step.status = StepStatus.PENDING
                    null -> {
                        step.skip("interrupted mid-action; not repeated to avoid a duplicate")
                        notes += "Step ${step.id} (${step.tool}) was interrupted mid-action and I couldn't confirm " +
                            "whether it happened, so I didn't repeat it. Please check it."
                    }
                }
            }
        }
        return notes
    }

    private suspend fun checkDone(tool: JaxTool, step: PlanStep): Boolean? = try {
        tool.alreadyDone(step.input)
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }
}
