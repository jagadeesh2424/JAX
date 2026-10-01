package com.jax.assistant.ai.agent

import com.jax.assistant.ai.AIException
import com.jax.assistant.ai.ModelFunctionResponse
import com.jax.assistant.ai.ModelMessage
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class AgentResult(val reply: String, val toolsUsed: List<String>, val plan: AgentPlan? = null)
data class AgentCheckpoint(val step: Int, val state: String, val toolsUsed: List<String>)

// A pending tool call the user must approve before it runs (HIGH-risk actions).
data class ToolConfirmation(
    val toolName: String,
    val risk: ToolRisk,
    val description: String,
    val args: JSONObject
)

// The controlled reasoning loops over the existing text `generate` (AIRouter stays the brain):
//  - run():        ReAct loop for single-intent tool requests (model picks one tool at a time).
//  - runPlanned(): plan -> execute -> observe -> verify -> continue/stop -> final answer, for
//                  genuinely multi-step requests. Costs exactly two model calls when planning succeeds.
// All tool calls go through ToolExecutor, so permissions and verification are never the model's call.
class AgentOrchestrator(
    private val registry: ToolRegistry,
    private val controller: AgentController,
    private val generate: suspend (prompt: String, model: String) -> String,
    private val maxSteps: Int = 6,
    private val executor: ToolExecutor = ToolExecutor(registry, controller),
    // Plain-text final answers (plan synthesis). `generate` may be configured for JSON output.
    private val generateAnswer: suspend (prompt: String, model: String) -> String = generate
) {

    suspend fun run(
        userInput: String,
        model: String,
        contextText: String,
        sink: AgentEventSink,
        onCheckpoint: suspend (AgentCheckpoint) -> Unit = {},
        // Consulted for CONFIRM-level tool calls. Default approves so existing callers and
        // non-interactive contexts (workers) keep working; the UI supplies a real prompt.
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): AgentResult {
        sink.emit(AgentEvent("user_input", userInput))
        val toolsUsed = mutableListOf<String>()
        val transcript = StringBuilder()
        // Per-run ledger of executed tool signatures -> observation, used to skip
        // duplicate side effects when the model repeats an identical action.
        val executed = HashMap<String, String>()
        var currentPlan: List<String> = emptyList()
        var consecutiveFailures = 0
        var replanHint = ""

        repeat(maxSteps) { step ->
            onCheckpoint(AgentCheckpoint(step + 1, "Requesting model decision", toolsUsed.toList()))
            val prompt = buildPrompt(userInput, contextText, transcript.toString(), currentPlan, replanHint)

            val raw = try {
                generate(prompt, model)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sink.emit(AgentEvent("error", e.message ?: "generate failed"))
                return AgentResult(friendlyError(e), toolsUsed)
            }

            val json = parseJson(raw) ?: return AgentResult(fallbackReply(raw), toolsUsed)

            when (json.optString("action", "final").lowercase()) {
                "plan" -> {
                    val stepsArr = json.optJSONArray("steps")
                    val steps = buildList {
                        if (stepsArr != null) for (i in 0 until stepsArr.length()) {
                            val s = stepsArr.optString(i).trim()
                            if (s.isNotEmpty()) add(s)
                        }
                    }
                    if (steps.isNotEmpty()) {
                        currentPlan = steps
                        sink.emit(AgentEvent("plan", steps.joinToString(" | ")))
                        transcript.append("\nPLAN: ${steps.joinToString("; ")}")
                        onCheckpoint(AgentCheckpoint(step + 1, "Planned ${steps.size} step(s)", toolsUsed.toList()))
                    }
                    return@repeat
                }
                "tool" -> {
                    val toolName = json.optString("tool")
                    val args = json.optJSONObject("args") ?: JSONObject()

                    // An identical tool+args within one turn is a loop symptom: reuse the prior
                    // observation instead of repeating the side effect.
                    val signature = toolSignature(toolName, args)
                    val priorObservation = executed[signature]
                    if (priorObservation != null) {
                        transcript.append("\nTOOL $toolName -> ALREADY DONE: $priorObservation")
                        sink.emit(AgentEvent("tool_result", "ALREADY DONE: $priorObservation"))
                        return@repeat
                    }

                    val outcome = executor.execute(toolName, args, userInput, sink, confirm)
                    if (outcome.executed) toolsUsed.add(toolName)
                    val obs = outcome.observation()

                    // Recovery: on repeated failures, tell the model to re-plan (different tool or
                    // finalize) instead of grinding the same failing step to maxSteps.
                    if (outcome.succeeded) {
                        consecutiveFailures = 0
                        replanHint = ""
                    } else if (outcome.status != ExecutionStatus.DECLINED) {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            replanHint = "$consecutiveFailures recent steps failed. Reconsider your approach: choose a DIFFERENT tool or finalize with what you have. Do not repeat a call that already failed."
                            sink.emit(AgentEvent("replan", "after $consecutiveFailures consecutive failures"))
                        }
                    }
                    if (outcome.executed) executed[signature] = obs
                    transcript.append("\nTOOL $toolName -> $obs")
                    onCheckpoint(
                        AgentCheckpoint(
                            step = step + 1,
                            state = "Executed $toolName: ${outcome.status}",
                            toolsUsed = toolsUsed.toList()
                        )
                    )
                }
                else -> {
                    val reply = json.optString("reply").ifBlank { "Done, Jagadeesh." }
                    sink.emit(AgentEvent("final", reply))
                    return AgentResult(reply, toolsUsed)
                }
            }
        }
        return AgentResult("I've handled what I can for now, Jagadeesh.", toolsUsed)
    }

    // Native function calling: Gemini picks tools from their declarations. Every call still goes
    // through ToolExecutor (validation, risk gate, confirmation, verification), with the same
    // duplicate guard and step limit as the JSON loop.
    suspend fun runNative(
        userInput: String,
        contextText: String,
        systemInstruction: String,
        toolModel: ToolCallingModel,
        tools: List<JaxTool>,
        sink: AgentEventSink,
        onCheckpoint: suspend (AgentCheckpoint) -> Unit = {},
        confirm: suspend (ToolConfirmation) -> Boolean = { true },
        maxSteps: Int = this.maxSteps,
        stream: Boolean = false
    ): AgentResult {
        sink.emit(AgentEvent("user_input", userInput))
        val specs = tools.map { it.toModelToolSpec() }
        val allowed = tools.map { it.name }.toSet()
        val messages = mutableListOf(ModelMessage.user("CONTEXT:\n$contextText\n\nUSER REQUEST: \"$userInput\""))
        val toolsUsed = mutableListOf<String>()
        val executed = HashMap<String, JSONObject>()

        repeat(maxSteps) { step ->
            val response = try {
                toolModel.call(systemInstruction, messages, specs, stream)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sink.emit(AgentEvent("error", e.message ?: "model call failed"))
                return AgentResult(friendlyError(e), toolsUsed)
            }
            if (response.functionCalls.isEmpty()) {
                val reply = response.text.trim().ifBlank { "Done, Jagadeesh." }
                sink.emit(AgentEvent("final", reply))
                return AgentResult(reply, toolsUsed)
            }
            messages += response.modelTurn
            val results = response.functionCalls.map { call ->
                val signature = toolSignature(call.name, call.args)
                val prior = executed[signature]
                val payload = when {
                    prior != null -> JSONObject(prior.toString()).put("note", "Already done earlier in this request; not repeated.")
                    call.name !in allowed -> JSONObject()
                        .put("status", ExecutionStatus.UNKNOWN_TOOL.name)
                        .put("message", "Tool ${call.name} is not available here.")
                    else -> {
                        val outcome = executor.execute(call.name, call.args, userInput, sink, confirm)
                        if (outcome.executed) toolsUsed.add(call.name)
                        outcome.functionResponse().also { if (outcome.executed) executed[signature] = it }
                    }
                }
                ModelFunctionResponse(call.name, payload, call.id)
            }
            messages += ModelMessage.toolResults(results)
            onCheckpoint(AgentCheckpoint(step + 1, "Executed ${results.joinToString { it.name }}", toolsUsed.toList()))
        }
        return AgentResult("I've handled what I can for now, Jagadeesh.", toolsUsed)
    }

    // UNDERSTAND -> PLAN -> EXECUTE -> OBSERVE -> VERIFY -> CONTINUE/STOP -> FINAL RESPONSE.
    // Returns null when the model could not produce a usable plan, so the caller can fall back.
    suspend fun runPlanned(
        userInput: String,
        model: String,
        contextText: String,
        today: String,
        sink: AgentEventSink,
        onPlanUpdate: suspend (AgentPlan) -> Unit = {},
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): AgentResult? {
        sink.emit(AgentEvent("user_input", userInput))
        val planner = Planner(registry)

        val rawPlan = try {
            generate(planner.buildPrompt(userInput, contextText, today), model)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sink.emit(AgentEvent("error", e.message ?: "planning failed"))
            return AgentResult(friendlyError(e), emptyList())
        }
        val plan = planner.parse(rawPlan)
        if (plan == null) {
            sink.emit(AgentEvent("error", "planner returned no usable plan"))
            return null
        }
        sink.emit(AgentEvent("plan", plan.steps.joinToString(" | ") { "${it.id}:${it.tool}" }))
        onPlanUpdate(plan)

        val toolsUsed = executePlan(plan, userInput, sink, onPlanUpdate, confirm)
        val reply = synthesize(planner, userInput, contextText, plan, model, sink)
        return AgentResult(reply, toolsUsed, plan)
    }

    // Continues a persisted plan after an interruption. RunResumer has already decided which
    // interrupted steps are safe to run again; completed steps are never repeated.
    suspend fun resumePlanned(
        plan: AgentPlan,
        userInput: String,
        model: String,
        contextText: String,
        sink: AgentEventSink,
        onPlanUpdate: suspend (AgentPlan) -> Unit = {},
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): AgentResult {
        sink.emit(AgentEvent("resume", plan.steps.joinToString(" | ") { "${it.id}:${it.status}" }))
        val toolsUsed = executePlan(plan, userInput, sink, onPlanUpdate, confirm)
        val reply = synthesize(Planner(registry), userInput, contextText, plan, model, sink)
        return AgentResult(reply, toolsUsed, plan)
    }

    private suspend fun synthesize(
        planner: Planner,
        userInput: String,
        contextText: String,
        plan: AgentPlan,
        model: String,
        sink: AgentEventSink
    ): String {
        val reply = try {
            generateAnswer(planner.buildSynthesisPrompt(userInput, contextText, plan), model).trim()
                .ifBlank { deterministicSummary(plan) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            sink.emit(AgentEvent("error", "final answer failed: ${e.message}"))
            deterministicSummary(plan)
        }
        sink.emit(AgentEvent("final", reply))
        return reply
    }

    // Dependency-aware execution: a step runs only once every step it depends on is COMPLETED,
    // whatever order the model listed them in. Steps whose dependencies FAILED or were SKIPPED
    // are skipped with the reason recorded. Each pass either runs or skips at least one step,
    // so the loop always terminates. Already COMPLETED steps are never re-run.
    suspend fun executePlan(
        plan: AgentPlan,
        userInput: String,
        sink: AgentEventSink,
        onPlanUpdate: suspend (AgentPlan) -> Unit = {},
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): List<String> {
        plan.markUnresolvableSteps()
        val toolsUsed = mutableListOf<String>()
        // Any step that actually ran (even if it failed) claims its tool+input, so an identical
        // later step can never repeat a side effect.
        val attemptedSignatures = HashMap<String, String>()
        plan.steps.filter { it.status == StepStatus.COMPLETED }
            .forEach { attemptedSignatures.putIfAbsent(toolSignature(it.tool, it.input), it.id) }
        var actionFailed = false

        repeat(plan.steps.size) {
            skipStepsWithUnsatisfiableDependencies(plan)
            val step = plan.steps.firstOrNull { s ->
                s.status == StepStatus.PENDING && s.dependsOn.all { plan.step(it)?.status == StepStatus.COMPLETED }
            } ?: return@repeat
            val tool = registry.get(step.tool)
            val duplicateOf = attemptedSignatures[toolSignature(step.tool, step.input)]
            when {
                duplicateOf != null -> step.skip("duplicate of step $duplicateOf")
                // After a failed action, stop further actions; independent lookups still run.
                actionFailed && tool != null && !tool.isReadOnly -> step.skip("stopped after an earlier action failed")
                else -> {
                    step.status = StepStatus.RUNNING
                    onPlanUpdate(plan)
                    val outcome = executor.execute(step.tool, step.input, userInput, sink, confirm)
                    if (outcome.executed) {
                        toolsUsed.add(step.tool)
                        attemptedSignatures[toolSignature(step.tool, step.input)] = step.id
                    }
                    step.retryCount = (outcome.attempts - 1).coerceAtLeast(0)
                    step.verification = outcome.verificationStatus
                    if (outcome.succeeded) {
                        step.status = StepStatus.COMPLETED
                        step.result = outcome.observation()
                    } else {
                        step.status = StepStatus.FAILED
                        step.error = outcome.result.message
                        if (tool != null && !tool.isReadOnly) actionFailed = true
                    }
                }
            }
            onPlanUpdate(plan)
        }

        skipStepsWithUnsatisfiableDependencies(plan)
        plan.steps.filter { it.status == StepStatus.PENDING }.forEach { it.skip("dependencies never completed") }
        onPlanUpdate(plan)
        return toolsUsed
    }

    // Propagates failure: a pending step whose dependency FAILED or was SKIPPED can never run.
    private fun skipStepsWithUnsatisfiableDependencies(plan: AgentPlan) {
        var changed = true
        while (changed) {
            changed = false
            plan.steps.filter { it.status == StepStatus.PENDING }.forEach { step ->
                val blocker = step.dependsOn.mapNotNull { plan.step(it) }
                    .firstOrNull { it.status == StepStatus.FAILED || it.status == StepStatus.SKIPPED }
                if (blocker != null) {
                    step.skip("dependency step ${blocker.id} ${blocker.status.name.lowercase()}" +
                        (blocker.error?.let { ": $it" } ?: ""))
                    changed = true
                }
            }
        }
    }

    // Used when the model is unavailable for the final answer: report checked outcomes only.
    private fun deterministicSummary(plan: AgentPlan): String = buildString {
        append("Here's what I could do for \"${plan.goal}\":\n")
        plan.steps.forEach { s ->
            val detail = when {
                s.status == StepStatus.COMPLETED && s.verification == VerificationStatus.UNVERIFIED ->
                    "accepted, but I couldn't independently verify it (${stepMessage(s)})"
                s.status == StepStatus.COMPLETED -> stepMessage(s)
                else -> "${s.status.name.lowercase()}${s.error?.let { " ($it)" } ?: ""}"
            }
            append("• ${s.tool}: $detail\n")
        }
        append("I couldn't reach Gemini to write a fuller answer.")
    }

    private fun stepMessage(step: PlanStep): String =
        step.result?.substringAfter("): ")?.substringBefore(" DATA:") ?: "done"

    private fun friendlyError(e: Exception): String =
        (e as? AIException)?.error?.userFriendlyMessage ?: "J.A.X. Notice: ${e.message ?: "AI request failed."}"

    // Stable signature (keys sorted) so the duplicate guard is insensitive to JSON key order.
    private fun toolSignature(toolName: String, args: JSONObject): String {
        val body = args.keys().asSequence().sorted().joinToString("|") { key -> "$key=${args.opt(key)}" }
        return "$toolName($body)"
    }

    private fun buildPrompt(
        userInput: String,
        context: String,
        transcript: String,
        plan: List<String>,
        replanHint: String
    ): String {
        val planBlock = if (plan.isEmpty()) "" else
            "CURRENT PLAN (follow in order; each step may depend on the previous):\n" +
                plan.mapIndexed { i, s -> "${i + 1}. $s" }.joinToString("\n") + "\n\n"
        val replanBlock = if (replanHint.isEmpty()) "" else "REPLAN NOTICE: $replanHint\n\n"
        return """
            You are J.A.X. (Jagadeesh Agent X), an executive AI agent. Use tools to fulfil the request.

            CONTEXT:
            $context

            AVAILABLE TOOLS:
            ${registry.catalogText()}

            ${planBlock}${replanBlock}RESPONSE RULES:
            - Respond with exactly ONE JSON object and nothing else.
            - For a multi-step request, FIRST outline a plan once: {"action":"plan","steps":["step 1","step 2"]}. Emit a plan at most once, then execute it with tools.
            - To call a tool: {"action":"tool","tool":"<name>","args":{ ... }}
            - When the request is fully handled, or it is just conversation, respond:
              {"action":"final","reply":"<concise, courteous message to the user>"}
            - To cancel/complete/delete a task, first call search_tasks to obtain its id, then call complete_task with that id. Never invent ids.
            - If a tool fails, try a different tool or approach; do not repeat a call that already failed.
            - A result marked UNVERIFIED was accepted but not independently confirmed: never tell the user it definitely happened.
            - Only use tools that are listed above.

            WORK SO FAR (tool results):
            ${if (transcript.isBlank()) "(none yet)" else transcript}

            USER REQUEST: "$userInput"
        """.trimIndent()
    }

    private fun parseJson(raw: String): JSONObject? = try {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start != -1 && end > start) JSONObject(raw.substring(start, end + 1)) else null
    } catch (e: Exception) {
        null
    }

    // If the model replied with prose instead of JSON, treat it as a conversational answer.
    private fun fallbackReply(raw: String): String = raw.trim().ifBlank { "Understood, Jagadeesh." }

    companion object {
        // Trigger an explicit re-plan directive once this many steps fail back-to-back.
        private const val MAX_CONSECUTIVE_FAILURES = 2
    }
}
