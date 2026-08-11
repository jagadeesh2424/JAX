package com.jax.assistant.device

import java.util.Locale

/** A recognized on-device action JAX can perform without calling the AI. */
sealed class DeviceCommand {
    data class OpenApp(val appName: String) : DeviceCommand()
    data class WebSearch(val query: String) : DeviceCommand()
    data class Dial(val number: String) : DeviceCommand()
    data class Navigate(val destination: String) : DeviceCommand()
    data class SetAlarm(val hour: Int, val minute: Int, val label: String?) : DeviceCommand()
    data class SetTimer(val seconds: Int, val label: String?) : DeviceCommand()
    object OpenCamera : DeviceCommand()
    object OpenSettings : DeviceCommand()
}

/** Lightweight local parser that maps natural phrases to a [DeviceCommand], or null for chat. */
object DeviceCommandParser {

    fun parse(raw: String): DeviceCommand? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.US)

        if (lower == "open camera" || lower == "open the camera" || lower == "take a photo") {
            return DeviceCommand.OpenCamera
        }
        if (lower == "open settings" || lower == "open the settings") {
            return DeviceCommand.OpenSettings
        }

        Regex("^(?:search(?: the web)?(?: for)?|google)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.WebSearch(originalTail(text, it.groupValues[1]))
        }

        Regex("^(?:navigate to|directions to|take me to|navigate|directions)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.Navigate(originalTail(text, it.groupValues[1]))
        }

        Regex("^(?:call|dial|phone)\\s+([+\\d][\\d\\s\\-]{4,})$").find(lower)?.let {
            val number = it.groupValues[1].filter { c -> c.isDigit() || c == '+' }
            return DeviceCommand.Dial(number)
        }

        Regex("^set (?:a )?timer for\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?)$").find(lower)?.let {
            val n = it.groupValues[1].toIntOrNull() ?: return null
            val unit = it.groupValues[2]
            val seconds = when {
                unit.startsWith("sec") -> n
                unit.startsWith("min") -> n * 60
                unit.startsWith("hour") -> n * 3600
                else -> n
            }
            return DeviceCommand.SetTimer(seconds, null)
        }

        Regex("^set (?:an? )?alarm for\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?$").find(lower)?.let {
            var hour = it.groupValues[1].toIntOrNull() ?: return null
            val minute = it.groupValues[2].toIntOrNull() ?: 0
            when (it.groupValues[3]) {
                "pm" -> if (hour < 12) hour += 12
                "am" -> if (hour == 12) hour = 0
            }
            if (hour !in 0..23 || minute !in 0..59) return null
            return DeviceCommand.SetAlarm(hour, minute, null)
        }

        Regex("^(?:open|launch|start|run)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.OpenApp(originalTail(text, it.groupValues[1]))
        }

        return null
    }

    // Recover the original casing of a captured (lowercased) tail from the raw text.
    private fun originalTail(original: String, lowerTail: String): String {
        val idx = original.lowercase(Locale.US).indexOf(lowerTail)
        return if (idx >= 0) original.substring(idx).trim() else lowerTail.trim()
    }
}
