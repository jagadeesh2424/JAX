package com.jax.assistant.ai.agent

import com.jax.assistant.ai.AIException
import com.jax.assistant.ai.ModelMessage
import com.jax.assistant.ai.ModelResponse
import com.jax.assistant.ai.ModelToolSpec
import com.jax.assistant.ai.TextStreamSink
import com.jax.assistant.ai.TokenUsage
import com.jax.assistant.ai.UsageRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.UUID
import kotlin.coroutines.EmptyCoroutineContext

// Lifecycle of a persisted agent run. Verification happens inside each tool call (ToolExecutor),
// so it is not a separate persisted phase.
object RunStatus {
    const val CREATED = "CREATED"
    const val PLANNING = "PLANNING"
    const val RUNNING = "RUNNING"
    const val WAITING = "WAITING"          // waiting for the user to approve a HIGH-risk action
    const val COMPLETED = "COMPLETED"
    const val COMPLETED_WITH_ERRORS = "COMPLETED_WITH_ERRORS"
    const val FAILED = "FAILED"
    const val CANCELLED = "CANCELLED"      // the request's coroutine was cancelled (e.g. app closed)
    const val INTERRUPTED = "INTERRUPTED"  // found active after a process death
}

data class ResumableRun(val runId: String, val goal: String, val planJson: String)

// Durable record of agent/planner runs (implemented by AgentRunRepository over Room).
interface AgentRunStore {
    suspend fun startRun(runId: String, goal: String, maxSteps: Int)
    suspend fun checkpoint(runId: String, step: Int, state: String, toolsUsed: List<String>)
    suspend fun savePlan(runId: String, planJson: String, currentStep: Int)
    suspend fun saveEvents(runId: String, events: List<AgentEvent>)
    suspend fun finishRun(runId: String, status: String, reply: String, toolsUsed: List<String>)
    suspend fun updateStatus(runId: String, status: String)
    suspend fun latestResumableRun(): ResumableRun?
}

// Structured, content-free trace of one request. Never contains the user's text.
class RequestTrace(val requestId: String = UUID.randomUUID().toString().take(8)) {
    private val startedAt = System.nanoTime()
    var route: String = ""
    var intent: String = ""
    var depth: Int = 0
    var success: Boolean = true
    var errorType: String = ""
    // A degraded path was used (planner -> agent loop, weather -> browser, research without Gemini).
    var fallback: Boolean = false
    var resolvedReference: String = ""
    val tools = mutableListOf<String>()
    private val verifications = mutableListOf<VerificationStatus>()
    var geminiCalls = 0
        private set
    var embeddingCalls = 0
        private set
    var geminiLatencyMs = 0L
        private set
    var toolExecutionMs = 0L
        private set
    var totalLatencyMs = 0L
        private set
    var promptTokens = 0
        private set
    var outputTokens = 0
        private set

    @Synchronized
    fun recordUsage(usage: TokenUsage) {
        promptTokens += usage.promptTokens
        outputTokens += usage.outputTokens
    }

    suspend fun <T> gemini(block: suspend () -> T): T {
        geminiCalls++
        val t0 = System.nanoTime()
        try {
            return block()
        } finally {
            geminiLatencyMs += (System.nanoTime() - t0) / 1_000_000
        }
    }

    suspend fun <T> embedding(block: suspend () -> T): T {
        embeddingCalls++
        return block()
    }

    // "tool:VERIFIED|UNVERIFIED|FAILED" when it ran; otherwise why it did not (DECLINED, DENIED...).
    fun recordTool(outcome: ToolOutcome) {
        val state = if (outcome.executed) outcome.verificationStatus.name else outcome.status.name
        tools += "${outcome.toolName}:$state"
        if (outcome.executed) verifications += outcome.verificationStatus
        toolExecutionMs += outcome.durationMs
    }

    // Worst verification across the tools that ran: FAILED > UNVERIFIED > VERIFIED; NONE if no tool ran.
    val verificationStatus: String
        get() = when {
            verifications.isEmpty() -> "NONE"
            VerificationStatus.FAILED in verifications -> VerificationStatus.FAILED.name
            VerificationStatus.UNVERIFIED in verifications -> VerificationStatus.UNVERIFIED.name
            else -> VerificationStatus.VERIFIED.name
        }

    fun fail(errorType: String) {
        success = false
        if (this.errorType.isBlank()) this.errorType = errorType
    }

    fun finish() {
        totalLatencyMs = (System.nanoTime() - startedAt) / 1_000_000
    }

    fun toLogLine(): String =
        "[Request] requestId=$requestId intent=$intent route=$route depth=$depth geminiCallCount=$geminiCalls " +
            "toolCalls=${tools.size} toolsUsed=[${tools.joinToString(", ")}] verification=$verificationStatus " +
            "embeddingCalls=$embeddingCalls geminiLatencyMs=$geminiLatencyMs toolExecutionMs=$toolExecutionMs " +
            "tokensIn=$promptTokens tokensOut=$outputTokens " +
            "totalLatencyMs=$totalLatencyMs success=$success errorType=${errorType.ifBlank { "NONE" }} fallback=$fallback" +
            (if (resolvedReference.isNotBlank()) " reference=resolved" else "")
}

data class PipelineResult(val reply: String, val trace: RequestTrace)

// The one entry point for every typed or spoken request:
// normalize -> resolve references -> route -> (direct tool | clarify | research | chat | agent | planner)
// -> verified reply. Voice and text share it, so follow-ups work the same way in both.
class RequestPipeline(
    private val registry: ToolRegistry,
    private val controller: AgentController,
    private val runStore: AgentRunStore,
    private val workingMemory: WorkingMemory,
    // Raw model call for the agent loop and planner.
    private val modelCall: suspend (prompt: String, model: String) -> String,
    // Single-call conversational brain (exactly one model call).
    private val chat: suspend (input: String) -> String,
    private val buildContext: suspend (input: String, trace: RequestTrace) -> String,
    private val selectedModel: () -> String,
    private val now: () -> LocalDateTime = { LocalDateTime.now() },
    private val toolTimeoutMs: Long = ToolExecutor.DEFAULT_TIMEOUT_MS,
    private val log: (String) -> Unit = { android.util.Log.i(TAG, it) },
    private val metrics: RequestMetrics = RequestMetrics.shared,
    // Plain-text answers (plan synthesis, research); defaults to modelCall.
    private val answerCall: (suspend (prompt: String, model: String) -> String)? = null,
    // Native function calling (Gemini tools); when unavailable the JSON tool protocol is used.
    private val toolModel: ToolCallingModel? = null,
    // Chat turn that may propose one action; the action runs through ToolExecutor. Preferred over `chat`.
    private val chatDraft: (suspend (input: String) -> ChatDraft)? = null,
    private val systemInstruction: () -> String = { JaxPersona.systemInstruction() }
) {

    // `onPartial` receives the answer text so far while it is being generated (streaming).
    // `confirm` stays last so callers can pass it as a trailing lambda.
    suspend fun handle(
        rawInput: String,
        onPartial: ((String) -> Unit)? = null,
        confirm: suspend (ToolConfirmation) -> Boolean = { true }
    ): PipelineResult {
        val trace = RequestTrace()
        val streaming = onPartial?.let { TextStreamSink(it) } ?: EmptyCoroutineContext
        return withContext(UsageRecorder(trace::recordUsage) + streaming) { process(rawInput, confirm, trace) }
    }

    private suspend fun process(
        rawInput: String,
        confirm: suspend (ToolConfirmation) -> Boolean,
        trace: RequestTrace
    ): PipelineResult {
        val input = InputNormalizer.normalize(rawInput)
        val current = now()
        workingMemory.expireStale()
        ConversationTracker.observeUserText(workingMemory, input, current.toLocalDate())
        val resolution = ReferenceResolver.resolve(input, workingMemory, current)
        // An open question is answered (or abandoned) by this turn either way.
        if (workingMemory.state.pending != null) workingMemory.setPending(null)
        val text = resolution.text
        val route = resolution.route ?: FastIntentRouter.route(text, current)
        trace.route = route.name
        trace.intent = route.intent
        trace.depth = route.depth
        trace.resolvedReference = resolution.note
        val executor = ToolExecutor(registry, controller, timeoutMs = toolTimeoutMs, onOutcome = { outcome ->
            trace.recordTool(outcome)
            ConversationTracker.observeTool(workingMemory, outcome)
        })

        val reply = try {
            when (route) {
                is Route.Direct -> runDirect(route, text, executor, trace, confirm)
                Route.Capabilities -> registry.describeCapabilities()
                is Route.Clarify -> {
                    workingMemory.setPending(route.pending)
                    route.question
                }
                is Route.Research -> runResearch(route, text, executor, trace, confirm)
                Route.Resume -> runResume(executor, trace, confirm)
                Route.Chat -> runChat(text, executor, trace, confirm)
                Route.Agent -> runAgent(text, executor, trace, confirm, planned = false)
                Route.Plan -> runAgent(text, executor, trace, confirm, planned = true)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: AIException) {
            trace.fail("AI_" + e.error.javaClass.simpleName.uppercase())
            e.error.userFriendlyMessage
        } catch (e: Exception) {
            trace.fail(e.javaClass.simpleName)
            "Sorry, something went wrong while handling that (${e.message ?: e.javaClass.simpleName})."
        }
        workingMemory.recordTurn(route.intent, (route as? Route.Direct)?.args?.let(::stringMap) ?: emptyMap())
        if (route is Route.Research) workingMemory.setTopic(route.queries.first())
        trace.finish()
        metrics.record(trace)
        log(trace.toLogLine())
        if (metrics.count() % METRICS_LOG_EVERY == 0) log(metrics.snapshot().toLogLine())
        return PipelineResult(reply, trace)
    }

    private fun stringMap(json: JSONObject): Map<String, String> =
        json.keys().asSequence().associateWith { json.optString(it) }

    // Counts every native tool-calling round trip as a Gemini call on the trace.
    private fun counted(model: ToolCallingModel, trace: RequestTrace) = object : ToolCallingModel {
        override fun isAvailable() = model.isAvailable()
        override suspend fun call(
            systemInstruction: String,
            messages: List<ModelMessage>,
            tools: List<ModelToolSpec>,
            stream: Boolean
        ): ModelResponse = trace.gemini { model.call(systemInstruction, messages, tools, stream) }
    }

    private fun nativeTools(): ToolCallingModel? = toolModel?.takeIf { it.isAvailable() }

    // General conversation. With native tools, one streamed call answers and may use LOW/MEDIUM
    // tools; otherwise a single classifier call may propose one action. Either way every write runs
    // through ToolExecutor.
    private suspend fun runChat(
        input: String,
        executor: ToolExecutor,
        trace: RequestTrace,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): String {
        nativeTools()?.let { model ->
            // Tools whose default use is not HIGH risk (complete_task is offered for "mark done";
            // its delete mode still needs the user's confirmation through the controller).
            val chatTools = registry.all().filter { it.riskFor(JSONObject()).tier != RiskTier.HIGH }
            val sink = InMemoryEventSink(max = 30)
            val result = orchestrator(executor, trace).runNative(
                userInput = input,
                contextText = buildContext(input, trace),
                systemInstruction = systemInstruction(),
                toolModel = counted(model, trace),
                tools = chatTools,
                sink = sink,
                confirm = confirm,
                maxSteps = CHAT_MAX_STEPS,
                stream = true
            )
            // runNative turns a model failure into a friendly reply; still record it as a failure.
            if (sink.snapshot().any { it.type == "error" }) trace.fail("CHAT_MODEL_ERROR")
            return result.reply
        }
        val draftFn = chatDraft ?: return trace.gemini { chat(input) }
        val draft = trace.gemini { draftFn(input) }
        val action = draft.action ?: return draft.reply
        val outcome = executor.execute(action.tool, action.args, input, InMemoryEventSink(max = 20), confirm)
        return when {
            outcome.succeeded && outcome.verificationStatus == VerificationStatus.VERIFIED -> draft.reply
            outcome.succeeded -> "${draft.reply} (I saved it, but couldn't independently verify it.)"
            outcome.status == ExecutionStatus.DECLINED -> "Okay, I won't do that."
            outcome.status == ExecutionStatus.DENIED && action.tool == "store_memory" -> {
                // Not an explicit "remember this": keep it for this conversation only (MemoryPolicy).
                workingMemory.addNote("${action.args.optString("title")}: ${action.args.optString("details")}")
                "${draft.reply} (Noted for this conversation. Say \"remember that\" to save it permanently.)"
            }
            else -> {
                trace.fail("TOOL_" + outcome.status.name)
                "I couldn't do that: ${outcome.result.message}."
            }
        }
    }

    private suspend fun runDirect(
        route: Route.Direct,
        input: String,
        executor: ToolExecutor,
        trace: RequestTrace,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): String {
        val sink = InMemoryEventSink(max = 20)
        val outcome = executor.execute(route.tool, route.args, input, sink, confirm)
        if (outcome.succeeded) return outcome.userMessage()
        trace.fail("TOOL_" + outcome.status.name)
        return when (outcome.status) {
            ExecutionStatus.DECLINED -> "Okay, I won't do that."
            ExecutionStatus.DENIED -> "I'm not allowed to do that: ${outcome.result.message}."
            else -> directFallback(route, input, executor, sink, confirm, outcome, trace)
        }
    }

    // Graceful degradation: live weather unavailable -> open a web search the user can read.
    private suspend fun directFallback(
        route: Route.Direct,
        input: String,
        executor: ToolExecutor,
        sink: AgentEventSink,
        confirm: suspend (ToolConfirmation) -> Boolean,
        failed: ToolOutcome,
        trace: RequestTrace
    ): String {
        if (route.tool == "get_weather") {
            val place = route.args.optString("location").trim()
            val query = if (place.isBlank()) "weather today" else "weather today in $place"
            trace.fallback = true
            val search = executor.execute("open_web_search", JSONObject().put("query", query), input, sink, confirm)
            if (search.succeeded) return "I couldn't fetch live weather (${failed.result.message}), so I opened a web search instead."
        }
        return "I couldn't complete that: ${failed.result.message}."
    }

    private suspend fun runResearch(
        route: Route.Research,
        input: String,
        executor: ToolExecutor,
        trace: RequestTrace,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): String {
        val researcher = WebResearcher(executor, synthesize = { prompt -> trace.gemini { answer(prompt, selectedModel()) } })
        val result = researcher.research(input, route.queries, InMemoryEventSink(max = 50), confirm)
        if (result.failed) trace.fail("WEB_UNAVAILABLE")
        if (result.sources.isNotEmpty() && !result.synthesized) trace.fallback = true
        return result.reply
    }

    // Continues the latest interrupted planned run. Completed steps are not repeated; interrupted
    // writes are checked first (RunResumer). Risk controls apply exactly as in a new run.
    private suspend fun runResume(
        executor: ToolExecutor,
        trace: RequestTrace,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): String {
        val run = runStore.latestResumableRun() ?: return "There's no interrupted request to resume."
        val plan = runCatching { AgentPlan.fromJson(JSONObject(run.planJson)) }.getOrNull()
            ?: return "I can't safely resume \"${run.goal.take(60)}\" automatically. Please ask again."
        val notes = RunResumer.prepare(plan, registry)
        val sink = DurableEventSink()
        runStore.updateStatus(run.runId, RunStatus.RUNNING)
        val orchestrator = orchestrator(executor, trace)
        return try {
            val result = orchestrator.resumePlanned(
                plan = plan,
                userInput = run.goal,
                model = selectedModel(),
                contextText = buildContext(run.goal, trace),
                sink = sink,
                onPlanUpdate = { p -> savePlan(run.runId, p, sink) },
                confirm = trackedConfirm(run.runId, confirm)
            )
            finish(run.runId, result, sink, trace)
            (notes + result.reply).joinToString("\n\n")
        } catch (e: CancellationException) {
            cancelRun(run.runId, sink)
            throw e
        } catch (e: Exception) {
            runStore.saveEvents(run.runId, sink.drainPending())
            runStore.finishRun(run.runId, STATUS_FAILED, e.message.orEmpty(), emptyList())
            throw e
        }
    }

    private suspend fun runAgent(
        input: String,
        executor: ToolExecutor,
        trace: RequestTrace,
        confirm: suspend (ToolConfirmation) -> Boolean,
        planned: Boolean
    ): String {
        val runId = UUID.randomUUID().toString()
        val sink = DurableEventSink()
        val model = selectedModel()
        runStore.startRun(runId, input, MAX_AGENT_STEPS)
        val context = buildContext(input, trace)
        val orchestrator = orchestrator(executor, trace)
        val tracked = trackedConfirm(runId, confirm)
        val onCheckpoint: suspend (AgentCheckpoint) -> Unit = { checkpoint ->
            runStore.checkpoint(runId, checkpoint.step, checkpoint.state, checkpoint.toolsUsed)
            runStore.saveEvents(runId, sink.drainPending())
        }

        try {
            val result = if (planned) {
                runStore.updateStatus(runId, RunStatus.PLANNING)
                var executing = false
                orchestrator.runPlanned(
                    userInput = input,
                    model = model,
                    contextText = context,
                    today = now().toLocalDate().toString(),
                    sink = sink,
                    onPlanUpdate = { plan ->
                        if (!executing) {
                            executing = true
                            runStore.updateStatus(runId, RunStatus.RUNNING)
                        }
                        if (workingMemory.state.goal != plan.goal) workingMemory.beginGoal(plan.goal, plan.slots)
                        savePlan(runId, plan, sink)
                    },
                    confirm = tracked
                ) ?: run {
                    // Planner output unusable: fall back to the step-by-step agent loop.
                    trace.route = "PLANNER>AGENT"
                    trace.fallback = true
                    agentLoop(orchestrator, input, model, context, sink, onCheckpoint, tracked, trace)
                }
            } else {
                agentLoop(orchestrator, input, model, context, sink, onCheckpoint, tracked, trace)
            }

            val plan = result.plan
            if (plan != null) {
                val done = plan.steps.count { it.status == StepStatus.COMPLETED }
                workingMemory.updateStatus(if (done == plan.steps.size) "done" else "partially done ($done/${plan.steps.size} steps)")
            }
            finish(runId, result, sink, trace)
            return result.reply
        } catch (e: CancellationException) {
            cancelRun(runId, sink)
            throw e
        } catch (e: Exception) {
            runStore.saveEvents(runId, sink.drainPending())
            runStore.finishRun(runId, STATUS_FAILED, e.message.orEmpty(), emptyList())
            throw e
        }
    }

    private fun orchestrator(executor: ToolExecutor, trace: RequestTrace) = AgentOrchestrator(
        registry = registry,
        controller = controller,
        generate = { prompt, m -> trace.gemini { modelCall(prompt, m) } },
        maxSteps = MAX_AGENT_STEPS,
        executor = executor,
        generateAnswer = { prompt, m -> trace.gemini { answer(prompt, m) } }
    )

    private suspend fun answer(prompt: String, model: String): String =
        (answerCall ?: modelCall).invoke(prompt, model)

    // Native function calling when the backend supports it, otherwise the JSON tool protocol.
    private suspend fun agentLoop(
        orchestrator: AgentOrchestrator,
        input: String,
        model: String,
        context: String,
        sink: AgentEventSink,
        onCheckpoint: suspend (AgentCheckpoint) -> Unit,
        confirm: suspend (ToolConfirmation) -> Boolean,
        trace: RequestTrace
    ): AgentResult {
        val native = nativeTools() ?: return orchestrator.run(input, model, context, sink, onCheckpoint, confirm)
        return orchestrator.runNative(
            userInput = input,
            contextText = context,
            systemInstruction = systemInstruction(),
            toolModel = counted(native, trace),
            tools = registry.all(),
            sink = sink,
            onCheckpoint = onCheckpoint,
            confirm = confirm,
            stream = true
        )
    }

    private suspend fun savePlan(runId: String, plan: AgentPlan, sink: DurableEventSink) {
        val current = plan.steps.indexOfFirst { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }
        runStore.savePlan(runId, plan.toJson().toString(), if (current < 0) plan.steps.size else current + 1)
        runStore.saveEvents(runId, sink.drainPending())
    }

    // WAITING while the user decides on a HIGH-risk action. If the app dies meanwhile the run is
    // later reconciled as interrupted, and the action never ran.
    private fun trackedConfirm(
        runId: String,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): suspend (ToolConfirmation) -> Boolean = { request ->
        runStore.updateStatus(runId, RunStatus.WAITING)
        val approved = confirm(request)
        runStore.updateStatus(runId, RunStatus.RUNNING)
        approved
    }

    private suspend fun finish(runId: String, result: AgentResult, sink: DurableEventSink, trace: RequestTrace) {
        val hadErrors = sink.snapshot().any { it.type == "error" } ||
            result.plan?.steps?.any { it.status == StepStatus.FAILED } == true
        if (hadErrors) trace.fail("AGENT_STEP_FAILED")
        runStore.saveEvents(runId, sink.drainPending())
        runStore.finishRun(runId, if (hadErrors) STATUS_COMPLETED_WITH_ERRORS else STATUS_COMPLETED, result.reply, result.toolsUsed)
    }

    // Cancelled mid-run (e.g. the screen closed): keep the plan so "resume" can continue it.
    private suspend fun cancelRun(runId: String, sink: DurableEventSink) = withContext(NonCancellable) {
        runStore.saveEvents(runId, sink.drainPending())
        runStore.finishRun(runId, RunStatus.CANCELLED, "", emptyList())
    }

    companion object {
        private const val TAG = "JaxPipeline"
        private const val METRICS_LOG_EVERY = 20
        private const val CHAT_MAX_STEPS = 3
        const val MAX_AGENT_STEPS = 6
        const val STATUS_COMPLETED = RunStatus.COMPLETED
        const val STATUS_COMPLETED_WITH_ERRORS = RunStatus.COMPLETED_WITH_ERRORS
        const val STATUS_FAILED = RunStatus.FAILED
    }
}
