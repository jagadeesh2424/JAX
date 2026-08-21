package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.data.UserPreferencesRepository
import org.json.JSONObject

// Maintains the always-in-context user profile (identity, role, preferences, style).
// Distinct from store_memory: profile is injected into every turn; memory is retrieved by relevance.
class UpdateProfileTool(private val prefs: UserPreferencesRepository) : JaxTool {
    override val name = "update_profile"
    override val description =
        "Save a durable fact about the user's identity, role, preferences, or how they want you to communicate. Use when the user shares something lasting about themselves or their preferences."
    override val parameters = listOf(
        ToolParam("note", "string", "A concise fact or preference to remember about the user", true)
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val note = args.optString("note").trim()
        if (note.isBlank()) return ToolResult.error("note is required")
        val current = prefs.getUserProfile()
        val updated = if (current.isBlank()) "- $note" else "$current\n- $note"
        prefs.saveUserProfile(updated)
        return ToolResult.ok("Noted about you: $note")
    }
}
