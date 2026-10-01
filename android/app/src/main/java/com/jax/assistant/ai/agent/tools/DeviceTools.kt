package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.Verification
import com.jax.assistant.device.DeviceActionResult
import com.jax.assistant.device.DeviceCommand
import com.jax.assistant.device.DeviceController
import org.json.JSONObject

// Device tools wrap the existing DeviceController (Android intents). Every direct command
// from the fast router and every agent/planner step reaches the device through these.
// Verification can only confirm that Android accepted the launch, not what the user did next.
abstract class DeviceTool(protected val device: DeviceController) : JaxTool {
    override val isDestructive = false
    override val risk = ToolRisk.LAUNCH

    protected fun launch(command: DeviceCommand): ToolResult {
        val outcome: DeviceActionResult = device.run(command)
        return if (outcome.launched) {
            ToolResult.ok(outcome.message, JSONObject().put(LAUNCHED, true))
        } else {
            ToolResult.error(outcome.message)
        }
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification =
        if (result.data?.optBoolean(LAUNCHED) == true) Verification.verified("Android accepted the launch intent")
        else Verification.failed("the target app did not open")

    private companion object {
        const val LAUNCHED = "launched"
    }
}

class SetAlarmTool(device: DeviceController) : DeviceTool(device) {
    override val name = "set_alarm"
    override val description = "Set a device alarm at a specific 24-hour time."
    override val parameters = listOf(
        ToolParam("hour", "number", "Hour of day, 0-23", true),
        ToolParam("minute", "number", "Minute, 0-59"),
        ToolParam("label", "string", "Optional alarm label")
    )
    override val risk = ToolRisk.LOW_WRITE

    override suspend fun execute(args: JSONObject): ToolResult {
        val hour = args.optInt("hour", -1)
        if (hour !in 0..23) return ToolResult.error("hour must be between 0 and 23")
        val minute = args.optInt("minute", 0).coerceIn(0, 59)
        val label = args.optString("label", "").ifBlank { null }
        return launch(DeviceCommand.SetAlarm(hour, minute, label))
    }
}

class SetTimerTool(device: DeviceController) : DeviceTool(device) {
    override val name = "set_timer"
    override val description = "Start a device countdown timer for a number of seconds."
    override val parameters = listOf(
        ToolParam("seconds", "number", "Duration in seconds", true),
        ToolParam("label", "string", "Optional timer label")
    )
    override val risk = ToolRisk.LOW_WRITE

    override suspend fun execute(args: JSONObject): ToolResult {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) return ToolResult.error("seconds must be greater than 0")
        val label = args.optString("label", "").ifBlank { null }
        return launch(DeviceCommand.SetTimer(seconds, label))
    }
}

class OpenAppTool(device: DeviceController) : DeviceTool(device) {
    override val name = "open_app"
    override val description = "Open an installed app by name."
    override val parameters = listOf(ToolParam("name", "string", "The app name to open", true))

    override suspend fun execute(args: JSONObject): ToolResult =
        launch(DeviceCommand.OpenApp(args.optString("name").trim()))
}

class OpenWebSearchTool(device: DeviceController) : DeviceTool(device) {
    override val name = "open_web_search"
    override val description =
        "Open a web search in the browser for the user to read. It does NOT return results to J.A.X."
    override val parameters = listOf(ToolParam("query", "string", "The search query", true))

    override suspend fun execute(args: JSONObject): ToolResult =
        launch(DeviceCommand.WebSearch(args.optString("query").trim()))
}

class NavigateTool(device: DeviceController) : DeviceTool(device) {
    override val name = "navigate"
    override val description = "Open Google Maps directions to a destination."
    override val parameters = listOf(ToolParam("destination", "string", "Where to navigate to", true))

    override suspend fun execute(args: JSONObject): ToolResult =
        launch(DeviceCommand.Navigate(args.optString("destination").trim()))
}

class DialTool(device: DeviceController) : DeviceTool(device) {
    override val name = "dial"
    override val description = "Open the phone dialer with a number (the user still presses call)."
    override val parameters = listOf(ToolParam("number", "string", "The phone number", true))

    override suspend fun execute(args: JSONObject): ToolResult =
        launch(DeviceCommand.Dial(args.optString("number").trim()))
}

class OpenCameraTool(device: DeviceController) : DeviceTool(device) {
    override val name = "open_camera"
    override val description = "Open the camera app."
    override val parameters = emptyList<ToolParam>()

    override suspend fun execute(args: JSONObject): ToolResult = launch(DeviceCommand.OpenCamera)
}

class OpenSettingsTool(device: DeviceController) : DeviceTool(device) {
    override val name = "open_settings"
    override val description = "Open the Android system settings."
    override val parameters = emptyList<ToolParam>()

    override suspend fun execute(args: JSONObject): ToolResult = launch(DeviceCommand.OpenSettings)
}
