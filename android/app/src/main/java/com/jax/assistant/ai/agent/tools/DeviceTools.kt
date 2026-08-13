package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.device.DeviceCommand
import com.jax.assistant.device.DeviceController
import org.json.JSONObject

// Device tools wrap the existing DeviceController (Android intents). They let the agent
// combine device actions with tasks/memory in one turn (the regex fast-path in MainActivity
// still handles simple single commands before the agent is even called).

class SetAlarmTool(private val device: DeviceController) : JaxTool {
    override val name = "set_alarm"
    override val description = "Set a device alarm at a specific 24-hour time."
    override val parameters = listOf(
        ToolParam("hour", "number", "Hour of day, 0-23", true),
        ToolParam("minute", "number", "Minute, 0-59"),
        ToolParam("label", "string", "Optional alarm label")
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val hour = args.optInt("hour", -1)
        if (hour !in 0..23) return ToolResult.error("hour must be between 0 and 23")
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val label = args.optString("label", "").ifBlank { null }
        return ToolResult.ok(device.execute(DeviceCommand.SetAlarm(hour, minute, label)))
    }
}

class SetTimerTool(private val device: DeviceController) : JaxTool {
    override val name = "set_timer"
    override val description = "Start a device countdown timer for a number of seconds."
    override val parameters = listOf(
        ToolParam("seconds", "number", "Duration in seconds", true),
        ToolParam("label", "string", "Optional timer label")
    )
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) return ToolResult.error("seconds must be greater than 0")
        val label = args.optString("label", "").ifBlank { null }
        return ToolResult.ok(device.execute(DeviceCommand.SetTimer(seconds, label)))
    }
}

class OpenAppTool(private val device: DeviceController) : JaxTool {
    override val name = "open_app"
    override val description = "Open an installed app by name."
    override val parameters = listOf(ToolParam("name", "string", "The app name to open", true))
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val name = args.optString("name").trim()
        if (name.isBlank()) return ToolResult.error("name is required")
        return ToolResult.ok(device.execute(DeviceCommand.OpenApp(name)))
    }
}

class WebSearchTool(private val device: DeviceController) : JaxTool {
    override val name = "web_search"
    override val description = "Open a web search for a query."
    override val parameters = listOf(ToolParam("query", "string", "The search query", true))
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val query = args.optString("query").trim()
        if (query.isBlank()) return ToolResult.error("query is required")
        return ToolResult.ok(device.execute(DeviceCommand.WebSearch(query)))
    }
}

class NavigateTool(private val device: DeviceController) : JaxTool {
    override val name = "navigate"
    override val description = "Open maps directions to a destination."
    override val parameters = listOf(ToolParam("destination", "string", "Where to navigate to", true))
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val dest = args.optString("destination").trim()
        if (dest.isBlank()) return ToolResult.error("destination is required")
        return ToolResult.ok(device.execute(DeviceCommand.Navigate(dest)))
    }
}

class DialTool(private val device: DeviceController) : JaxTool {
    override val name = "dial"
    override val description = "Open the phone dialer with a number (does not auto-call)."
    override val parameters = listOf(ToolParam("number", "string", "The phone number", true))
    override val isDestructive = false

    override suspend fun execute(args: JSONObject): ToolResult {
        val number = args.optString("number").trim()
        if (number.isBlank()) return ToolResult.error("number is required")
        return ToolResult.ok(device.execute(DeviceCommand.Dial(number)))
    }
}
