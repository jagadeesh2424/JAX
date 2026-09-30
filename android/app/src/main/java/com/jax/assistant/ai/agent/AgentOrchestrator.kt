package com.jax.assistant.ai.agent

import com.jax.assistant.ai.AIException
import kotlinx.coroutines.CancellationException
import org.json.JSONObject

data class AgentResult(val reply: String, val toolsUsed: List<String>)
data class AgentCheckpoint(val step: Int, val state: String, val toolsUsed: List<String>)

// A pending tool call the user must approve before it runs (SENSITIVE/DESTRUCTIVE risk).
data class ToolConfirmation(
    val toolName: String,
    val risk: ToolRisk,
    val description: String,
    val args: JSONObject
)

// The controlled reasoning loop: plan -> call tool -> observe/verify -> repeat -> final.
// Drives the model through the existing text `generate` (AIRouter stays the brain), so the
// tool definitions can later move to native function-calling / MCP with no changes here.
class AgentOrchestrator(
    private val registry: ToolRegistry,
    private val controller: AgentController,
    private val generate: suspend (prompt: String, model: String) -> String,
    private val maxSteps: Int = 6
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
        // duplicate side effects when the planner repeats an identical action.
        val executed = HashMap<String, String>()
        // Planning & recovery state (Priority #4): an explicit ordered plan the model can
        // lay out first, plus a failure counter that triggers an explicit re-plan directive.
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
                val reply = (e as? AIException)?.error?.userFriendlyMessage
                    ?: "J.A.X. Notice: ${e.message ?: "AI request failed."}"
                return AgentResult(reply, toolsUsed)
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
                    val tool = registry.get(toolName)
                    if (tool == null) {
                        transcript.append("\nTOOL $toolName -> FAILURE: unknown tool")
                        sink.emit(AgentEvent("error", "unknown tool $toolName"))
                        return@repeat
                    }
                    sink.emit(AgentEvent("tool_call", "$toolName $args"))

                    when (controller.authorize(tool, args)) {
                        AgentController.Decision.DENY -> {
                            transcript.append("\nTOOL $toolName -> FAILURE: not permitted")
                            sink.emit(AgentEvent("tool_denied", toolName))
                            return@repeat
                        }
                        AgentController.Decision.CONFIRM -> {
                            val request = ToolConfirmation(toolName, tool.risk, tool.description, args)
                            sink.emit(AgentEvent("tool_confirm_requested", "$toolName (${tool.risk})"))
                            if (!confirm(request)) {
                                transcript.append("\nTOOL $toolName -> DECLINED by user")
                                sink.emit(AgentEvent("tool_declined", toolName))
                                return@repeat
                            }
                            sink.emit(AgentEvent("tool_confirmed", toolName))
                        }
                        AgentController.Decision.ALLOW -> { /* proceed */ }
                    }

                    // Prevent duplicate execution: an identical tool+args within one turn is
                    // a loop symptom. Reuse the prior observation instead of repeating the
                    // side effect (e.g. creating the same task twice).
                    val signature = toolSignature(toolName, args)
                    val priorObservation = executed[signature]
                    if (priorObservation != null) {
                        transcript.append("\nTOOL $toolName -> ALREADY DONE: $priorObservation")
                        sink.emit(AgentEvent("tool_result", "ALREADY DONE: $priorObservation"))
                        return@repeat
                    }

                    // Retry only transient exceptions. A returned error is a business result
                    // the planner should see and re-plan around, so it is not retried here.
                    val result = executeWithRetry(tool, args, sink)
                    toolsUsed.add(toolName)

                    // S5 verify-in-loop: report success/failure back so the planner can adapt.
                    val obs = if (result.success) {
                        "SUCCESS: ${result.message}" + (result.data?.let { " DATA: $it" } ?: "")
                    } else {
                        "FAILURE: ${result.message}"
                    }
                    // Recovery: on repeated failures, tell the planner to re-plan (different
                    // tool or finalize) instead of grinding the same failing step to maxSteps.
                    if (result.success) {
                        consecutiveFailures = 0
                        replanHint = ""
                    } else {
                        consecutiveFailures++
                        if (consecutiveFailures >= MAX_CONSECUTIVE_FAILURES) {
                            replanHint = "$consecutiveFailures recent steps failed. Reconsider your approach: choose a DIFFERENT tool or finalize with what you have. Do not repeat a call that already failed."
                            sink.emit(AgentEvent("replan", "after $consecutiveFailures consecutive failures"))
                        }
                    }
                    executed[signature] = obs
                    transcript.append("\nTOOL $toolName -> $obs")
                    sink.emit(AgentEvent("tool_result", obs))
                    onCheckpoint(
                        AgentCheckpoint(
                            step = step + 1,
                            state = "Executed $toolName; awaiting verification",
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

    // Retries only thrown (transient) exceptions; business-level ToolResult.error values
    // are returned as-is so the planner can observe and re-plan around them.
    private suspend fun executeWithRetry(
        tool: JaxTool,
        args: JSONObject,
        sink: AgentEventSink,
        maxAttempts: Int = 2
    ): ToolResult {
        var lastError = "tool threw an exception"
        repeat(maxAttempts) { attempt ->
            try {
                return tool.execute(args)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e.message ?: "tool threw an exception"
                sink.emit(AgentEvent("retry", "${tool.name} attempt ${attempt + 1} failed: $lastError"))
            }
        }
        return ToolResult.error(lastError)
    }

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
