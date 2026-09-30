package com.jax.assistant.device

import java.util.Locale

/** A recognized on-device action JAX can perform without calling the AI. */
sealed class DeviceCommand {
    data class CurrentDateTime(val includeDate: Boolean, val includeTime: Boolean) : DeviceCommand()
    data class OpenApp(val appName: String) : DeviceCommand()
    data class WebSearch(val query: String) : DeviceCommand()
    data class Weather(val location: String?) : DeviceCommand()
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
        val text = raw.trim().trimEnd('?', '.', '!')
        if (text.isEmpty()) return null
        val lower = text.lowercase(Locale.US)

        // Deterministic system-clock answers. These never need Gemini or network access.
        if (lower.matches(Regex("^(?:what time is it|what is the current time|current time|tell me the time|what time now)$"))) {
            return DeviceCommand.CurrentDateTime(includeDate = false, includeTime = true)
        }
        if (lower.matches(Regex("^(?:what is today's date|what date is it|current date|today's date)$"))) {
            return DeviceCommand.CurrentDateTime(includeDate = true, includeTime = false)
        }
        if (lower.matches(Regex("^(?:what day is today|what is the date and time|what time and date is it)$"))) {
            return DeviceCommand.CurrentDateTime(includeDate = true, includeTime = true)
        }

        // Current weather is external data; open a focused search instead of asking Gemini
        // to guess a live observation. A missing location is left for the search provider.
        Regex("^(?:what(?:'s| is) the weather|how is the weather|weather|will it rain today|is it going to rain)(?:\\s+(?:in|at|for)\\s+(.+))?(?: today)?$").find(lower)?.let {
            return DeviceCommand.Weather(it.groupValues.getOrNull(1)?.takeIf { location -> location.isNotBlank() }?.let { location -> originalSegment(text, location) })
        }
        Regex("^(?:what(?:'s| is) the temperature|temperature)(?:\\s+(?:in|at|for)\\s+(.+))?(?: today)?$").find(lower)?.let {
            return DeviceCommand.Weather(it.groupValues.getOrNull(1)?.takeIf { location -> location.isNotBlank() }?.let { location -> originalSegment(text, location) })
        }

        if (lower == "open camera" || lower == "open the camera" || lower == "take a photo") {
            return DeviceCommand.OpenCamera
        }
        if (lower == "open settings" || lower == "open the settings") {
            return DeviceCommand.OpenSettings
        }

        Regex("^(?:search(?: the web)?(?: for)?|google)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.WebSearch(originalTail(text, it.groupValues[1]))
        }

        Regex("^(?:navigate to|directions to|find directions to|get directions to|show directions to|take me to|how do i get to|how can i get to|show me how to get to|navigate|directions)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.Navigate(originalTail(text, it.groupValues[1]))
        }

        Regex("^open maps (?:for|to)\\s+(.+)$").find(lower)?.let {
            return DeviceCommand.Navigate(originalTail(text, it.groupValues[1]))
        }

        Regex("^(?:open|show|find)\\s+(.+?)\\s+(?:in|on) maps$").find(lower)?.let {
            return DeviceCommand.Navigate(originalSegment(text, it.groupValues[1]))
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

    private fun originalSegment(original: String, lowerSegment: String): String {
        val normalized = original.lowercase(Locale.US)
        val idx = normalized.indexOf(lowerSegment)
        return if (idx >= 0) original.substring(idx, idx + lowerSegment.length).trim() else lowerSegment.trim()
    }
}
