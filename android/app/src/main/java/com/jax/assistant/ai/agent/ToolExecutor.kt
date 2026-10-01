package com.jax.assistant.ai.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

enum class ExecutionStatus { SUCCEEDED, FAILED, INVALID_INPUT, UNKNOWN_TOOL, DENIED, DECLINED }

data class ToolOutcome(
    val toolName: String,
    val status: ExecutionStatus,
    val result: ToolResult,
    val verification: Verification? = null,
    val attempts: Int = 0,
    val durationMs: Long = 0L,
    val args: JSONObject = JSONObject()
) {
    val succeeded: Boolean get() = status == ExecutionStatus.SUCCEEDED
    val executed: Boolean get() = attempts > 0

    // VERIFIED: independently confirmed. UNVERIFIED: the tool reported success but nothing could
    // confirm it. FAILED: it failed, was refused, or verification contradicted it.
    val verificationStatus: VerificationStatus
        get() = if (succeeded) verification?.status ?: VerificationStatus.UNVERIFIED else VerificationStatus.FAILED

    // Observation fed back to the model. The SUCCESS/FAILURE prefixes are part of the prompt contract.
    fun observation(): String = when (status) {
        ExecutionStatus.SUCCEEDED ->
            "SUCCESS ($verificationStatus: ${verification?.detail ?: "no independent check"}): ${result.message}" +
                (result.data?.let { " DATA: $it" } ?: "")
        ExecutionStatus.DECLINED -> "DECLINED by user"
        ExecutionStatus.DENIED -> "FAILURE: not permitted (${result.message})"
        else -> "FAILURE: ${result.message}"
    }

    // What the user is told for a single direct action; never claims success that wasn't confirmed.
    fun userMessage(): String = when (verificationStatus) {
        VerificationStatus.VERIFIED -> result.message
        VerificationStatus.UNVERIFIED -> "${result.message} (The request was accepted, but I couldn't independently verify it.)"
        VerificationStatus.FAILED -> result.message
    }

    // Result returned to the model after a native function call; verification is explicit so the
    // model can tell a confirmed action from an unconfirmed one.
    fun functionResponse(): JSONObject = JSONObject()
        .put("status", if (succeeded) "SUCCESS" else status.name)
        .put("verification", verificationStatus.name)
        .put("message", result.message)
        .apply { result.data?.let { put("data", it) } }
}

// The single deterministic path every tool call takes, whichever route proposed it (fast
// router, planner, or agent loop): validate -> memory policy -> risk gate -> execute with
// timeout and bounded retries -> independent verification.
class ToolExecutor(
    private val registry: ToolRegistry,
    private val controller: AgentController,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val maxRetries: Int = DEFAULT_MAX_RETRIES,
    private val onOutcome: (ToolOutcome) -> Unit = {}
) {

    suspend fun execute(
        toolName: String,
        args: JSONObject,
        userInput: String,
        sink: AgentEventSink,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): ToolOutcome = executeGated(toolName, args, userInput, sink, confirm).copy(args = args).also(onOutcome)

    private suspend fun executeGated(
        toolName: String,
        args: JSONObject,
        userInput: String,
        sink: AgentEventSink,
        confirm: suspend (ToolConfirmation) -> Boolean
    ): ToolOutcome {
        val tool = registry.get(toolName)
        if (tool == null) {
            sink.emit(AgentEvent("error", "unknown tool $toolName"))
            return ToolOutcome(toolName, ExecutionStatus.UNKNOWN_TOOL, ToolResult.error("unknown tool $toolName"))
        }
        sink.emit(AgentEvent("tool_call", "$toolName $args"))

        val missing = tool.parameters.filter { it.required && args.optString(it.name).isBlank() }.map { it.name }
        if (missing.isNotEmpty()) {
            return finish(sink, ToolOutcome(toolName, ExecutionStatus.INVALID_INPUT,
                ToolResult.error("missing required input: ${missing.joinToString()}")))
        }

        if (tool.writesDurableMemory && !MemoryPolicy.allowsDurableWrite(userInput)) {
            sink.emit(AgentEvent("tool_denied", "$toolName: not an explicit user fact"))
            return ToolOutcome(toolName, ExecutionStatus.DENIED,
                ToolResult.error("the user did not explicitly ask to remember this; keep it for this conversation only"))
        }

        when (controller.authorize(tool, args)) {
            AgentController.Decision.DENY -> {
                sink.emit(AgentEvent("tool_denied", toolName))
                return ToolOutcome(toolName, ExecutionStatus.DENIED, ToolResult.error("blocked by policy"))
            }
            AgentController.Decision.CONFIRM -> {
                val risk = controller.riskFor(tool, args)
                sink.emit(AgentEvent("tool_confirm_requested", "$toolName ($risk)"))
                if (!confirm(ToolConfirmation(toolName, risk, tool.description, args))) {
                    sink.emit(AgentEvent("tool_declined", toolName))
                    return ToolOutcome(toolName, ExecutionStatus.DECLINED, ToolResult.error("declined by user"))
                }
                sink.emit(AgentEvent("tool_confirmed", toolName))
            }
            AgentController.Decision.ALLOW -> Unit
        }

        val startedAt = System.nanoTime()
        var attempts = 0
        var last = ToolResult.error("not executed")
        var verification: Verification? = null
        var succeeded = false

        while (attempts <= maxRetries) {
            attempts++
            val (result, retryable) = attempt(tool, args)
            last = result
            if (result.success) {
                verification = safeVerify(tool, args, result)
                if (verification.status != VerificationStatus.FAILED) {
                    succeeded = true
                    break
                }
                last = ToolResult.error("result could not be verified: ${verification.detail}")
                // Never blindly repeat a write whose outcome is contradicted (risk of duplicates).
                if (!tool.isReadOnly) break
            } else if (!retryable) {
                break
            }
            if (attempts <= maxRetries) {
                sink.emit(AgentEvent("retry", "$toolName attempt $attempts failed: ${last.message}"))
            }
        }

        val durationMs = (System.nanoTime() - startedAt) / 1_000_000
        val status = if (succeeded) ExecutionStatus.SUCCEEDED else ExecutionStatus.FAILED
        return finish(sink, ToolOutcome(toolName, status, last, verification, attempts, durationMs))
    }

    // Returns the result and whether a failure is safe to retry. Only read-only tools are retried:
    // a write that threw or timed out may already have been saved, so repeating it could duplicate it.
    private suspend fun attempt(tool: JaxTool, args: JSONObject): Pair<ToolResult, Boolean> = try {
        val result = withTimeout(timeoutMs) { tool.execute(args) }
        result to (!result.success && tool.isReadOnly && result.retryable)
    } catch (e: TimeoutCancellationException) {
        ToolResult.error("timed out after ${timeoutMs}ms" + notRetriedSuffix(tool)) to tool.isReadOnly
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ToolResult.error((e.message ?: "tool threw an exception") + notRetriedSuffix(tool)) to tool.isReadOnly
    }

    private fun notRetriedSuffix(tool: JaxTool): String =
        if (tool.isReadOnly) "" else " (not retried, to avoid repeating an action that may already have happened)"

    private suspend fun safeVerify(tool: JaxTool, args: JSONObject, result: ToolResult): Verification = try {
        tool.verify(args, result)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Verification.unverified("verification check failed: ${e.message}")
    }

    private fun finish(sink: AgentEventSink, outcome: ToolOutcome): ToolOutcome {
        outcome.verification?.let { sink.emit(AgentEvent("verify", "${outcome.toolName}: ${it.status} ${it.detail}")) }
        sink.emit(AgentEvent("tool_result", outcome.observation()))
        return outcome
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 20_000L
        const val DEFAULT_MAX_RETRIES = 2
    }
}
