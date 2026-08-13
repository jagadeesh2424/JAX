package com.jax.assistant.ai.agent

import org.json.JSONObject

data class AgentResult(val reply: String, val toolsUsed: List<String>)

// The controlled reasoning loop: plan -> call tool -> observe/verify -> repeat -> final.
// Drives the model through the existing text `generate` (AIRouter stays the brain), so the
// tool definitions can later move to native function-calling / MCP with no changes here.
class AgentOrchestrator(
    private val registry: ToolRegistry,
    private val controller: AgentController,
    private val eventSink: AgentEventSink,
    private val generate: suspend (prompt: String, model: String) -> String,
    private val maxSteps: Int = 6
) {

    suspend fun run(userInput: String, model: String, contextText: String): AgentResult {
        eventSink.emit(AgentEvent("user_input", userInput))
        val toolsUsed = mutableListOf<String>()
        val transcript = StringBuilder()

        repeat(maxSteps) {
            val prompt = buildPrompt(userInput, contextText, transcript.toString())

            val raw = try {
                generate(prompt, model)
            } catch (e: Exception) {
                eventSink.emit(AgentEvent("error", e.message ?: "generate failed"))
                return AgentResult("J.A.X. Notice: ${e.message ?: "AI request failed."}", toolsUsed)
            }

            val json = parseJson(raw) ?: return AgentResult(fallbackReply(raw), toolsUsed)

            when (json.optString("action", "final").lowercase()) {
                "tool" -> {
                    val toolName = json.optString("tool")
                    val args = json.optJSONObject("args") ?: JSONObject()
                    val tool = registry.get(toolName)
                    if (tool == null) {
                        transcript.append("\nTOOL $toolName -> FAILURE: unknown tool")
                        eventSink.emit(AgentEvent("error", "unknown tool $toolName"))
                        return@repeat
                    }
                    eventSink.emit(AgentEvent("tool_call", "$toolName $args"))

                    if (controller.authorize(tool, args) != AgentController.Decision.ALLOW) {
                        transcript.append("\nTOOL $toolName -> FAILURE: not permitted")
                        return@repeat
                    }

                    val result = try {
                        tool.execute(args)
                    } catch (e: Exception) {
                        ToolResult.error(e.message ?: "tool threw an exception")
                    }
                    toolsUsed.add(toolName)

                    // S5 verify-in-loop: report success/failure back so the planner can adapt.
                    val obs = if (result.success) {
                        "SUCCESS: ${result.message}" + (result.data?.let { " DATA: $it" } ?: "")
                    } else {
                        "FAILURE: ${result.message}"
                    }
                    transcript.append("\nTOOL $toolName -> $obs")
                    eventSink.emit(AgentEvent("tool_result", obs))
                }
                else -> {
                    val reply = json.optString("reply").ifBlank { "Done, Jagadeesh." }
                    eventSink.emit(AgentEvent("final", reply))
                    return AgentResult(reply, toolsUsed)
                }
            }
        }
        return AgentResult("I've handled what I can for now, Jagadeesh.", toolsUsed)
    }

    private fun buildPrompt(userInput: String, context: String, transcript: String): String {
        return """
            You are J.A.X. (Jagadeesh Agent X), an executive AI agent. Use tools to fulfil the request.

            CONTEXT:
            $context

            AVAILABLE TOOLS:
            ${registry.catalogText()}

            RESPONSE RULES:
            - Respond with exactly ONE JSON object and nothing else.
            - To call a tool: {"action":"tool","tool":"<name>","args":{ ... }}
            - When the request is fully handled, or it is just conversation, respond:
              {"action":"final","reply":"<concise, courteous message to the user>"}
            - To cancel/complete/delete a task, first call search_tasks to obtain its id, then call complete_task with that id. Never invent ids.
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
}
