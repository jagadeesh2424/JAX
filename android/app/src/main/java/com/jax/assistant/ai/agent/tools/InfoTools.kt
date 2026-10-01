package com.jax.assistant.ai.agent.tools

import com.jax.assistant.ai.agent.JaxTool
import com.jax.assistant.ai.agent.ToolParam
import com.jax.assistant.ai.agent.ToolResult
import com.jax.assistant.ai.agent.ToolRisk
import com.jax.assistant.ai.agent.Verification
import com.jax.assistant.config.AppConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Current date/time from the device clock. Never asks the model.
class DateTimeTool(private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : JaxTool {
    override val name = "get_datetime"
    override val description = "Get the current date and/or time from the device clock."
    override val parameters = listOf(
        ToolParam("include_date", "boolean", "Include today's date"),
        ToolParam("include_time", "boolean", "Include the current time")
    )
    override val isDestructive = false
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JSONObject): ToolResult {
        val includeDate = args.optBoolean("include_date", false)
        val includeTime = args.optBoolean("include_time", !includeDate)
        val current = now()
        return ToolResult.ok(
            describe(current, includeDate, includeTime),
            JSONObject()
                .put("iso", current.toOffsetDateTime().toString())
                .put("date", current.toLocalDate().toString())
                .put("time", current.toLocalTime().withNano(0).toString())
        )
    }

    override suspend fun verify(args: JSONObject, result: ToolResult): Verification =
        if (result.data?.optString("date") == now().toLocalDate().toString()) Verification.verified("device clock")
        else Verification.failed("clock value is not today's date")

    companion object {
        fun describe(now: ZonedDateTime, includeDate: Boolean, includeTime: Boolean): String {
            val time = now.format(DateTimeFormatter.ofPattern("h:mm a z", Locale.getDefault()))
            val date = now.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.getDefault()))
            return when {
                includeDate && includeTime -> "It's $time on $date."
                includeDate -> "Today is $date."
                else -> "It's $time."
            }
        }
    }
}

// HTTP GET returning the body; throws on failure. A function type keeps tests trivial to fake.
typealias HttpGet = suspend (url: String) -> String

const val HTTP_USER_AGENT = "Mozilla/5.0 (Linux; Android) JAX-Assistant/1.0"
const val HTTP_MAX_CHARS = 1_500_000

// Plain HttpURLConnection GET with timeouts; throws on non-2xx so callers see a clear failure.
val DefaultHttpGet: HttpGet = { url ->
    withContext(Dispatchers.IO) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("User-Agent", HTTP_USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code !in 200..299) throw IllegalStateException("HTTP $code")
            readCapped(connection.inputStream, HTTP_MAX_CHARS)
        } finally {
            connection.disconnect()
        }
    }
}

// Reads at most maxChars so a huge or endless response cannot exhaust memory.
fun readCapped(stream: java.io.InputStream, maxChars: Int): String = stream.bufferedReader().use { reader ->
    val buffer = CharArray(8_192)
    val out = StringBuilder()
    while (out.length < maxChars) {
        val n = reader.read(buffer)
        if (n < 0) break
        out.append(buffer, 0, minOf(n, maxChars - out.length))
    }
    out.toString()
}

data class WeatherSnapshot(
    val location: String,
    val date: LocalDate,
    val summary: String,
    val currentTempC: Double?,
    val maxTempC: Double,
    val minTempC: Double,
    val rainChancePercent: Int,
    val fetchedAtMillis: Long
)

// Live weather from Open-Meteo (free, no API key). Location comes from the request; when absent
// the configured home city is used (JAX does not read device location).
class WeatherClient(
    private val http: HttpGet = DefaultHttpGet,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun fetch(location: String, dayOffset: Int): WeatherSnapshot {
        val place = location.trim().ifBlank { AppConfig.DEFAULT_WEATHER_LOCATION }
        val geo = JSONObject(http(GEOCODE_URL + URLEncoder.encode(place, "UTF-8")))
        val hit = geo.optJSONArray("results")?.optJSONObject(0)
            ?: throw IllegalArgumentException("I couldn't find a place called \"$place\".")
        val resolvedName = listOf(hit.optString("name"), hit.optString("country"))
            .filter { it.isNotBlank() }.joinToString(", ")

        val forecast = JSONObject(http(
            FORECAST_URL +
                "?latitude=${hit.getDouble("latitude")}&longitude=${hit.getDouble("longitude")}" +
                "&current=temperature_2m,weather_code" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
                "&timezone=auto&forecast_days=3"
        ))
        val daily = forecast.getJSONObject("daily")
        val index = dayOffset.coerceIn(0, daily.getJSONArray("time").length() - 1)
        val current = forecast.optJSONObject("current")
        return WeatherSnapshot(
            location = resolvedName,
            date = LocalDate.parse(daily.getJSONArray("time").getString(index)),
            summary = describeWeatherCode(daily.getJSONArray("weather_code").getInt(index)),
            currentTempC = if (index == 0 && current != null && current.has("temperature_2m"))
                current.getDouble("temperature_2m") else null,
            maxTempC = daily.getJSONArray("temperature_2m_max").getDouble(index),
            minTempC = daily.getJSONArray("temperature_2m_min").getDouble(index),
            rainChancePercent = daily.getJSONArray("precipitation_probability_max").optInt(index, 0),
            fetchedAtMillis = clock()
        )
    }

    companion object {
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search?count=1&name="
        private const val FORECAST_URL = "https://api.open-meteo.com/v1/forecast"

        // WMO weather interpretation codes, grouped.
        fun describeWeatherCode(code: Int): String = when (code) {
            0 -> "clear sky"
            1, 2 -> "partly cloudy"
            3 -> "overcast"
            45, 48 -> "fog"
            in 51..57 -> "drizzle"
            in 61..67, in 80..82 -> "rain"
            in 71..77, 85, 86 -> "snow"
            in 95..99 -> "thunderstorms"
            else -> "mixed conditions"
        }
    }
}

class WeatherTool(
    private val client: WeatherClient,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : JaxTool {
    override val name = "get_weather"
    override val description =
        "Get live weather (today or tomorrow) for a place: conditions, temperature range and chance of rain."
    override val parameters = listOf(
        ToolParam("location", "string", "City or place; empty for the user's home city"),
        ToolParam("day", "string", "\"today\" (default) or \"tomorrow\"")
    )
    override val isDestructive = false
    override val risk = ToolRisk.READ

    override suspend fun execute(args: JSONObject): ToolResult {
        val dayOffset = if (args.optString("day").trim().equals("tomorrow", ignoreCase = true)) 1 else 0
        val w = try {
            client.fetch(args.optString("location"), dayOffset)
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(e.message ?: "unknown place")
        }
        val dayLabel = if (dayOffset == 1) "Tomorrow" else "Today"
        val now = w.currentTempC?.let { " It's ${fmt(it)}°C now." } ?: ""
        val message = "$dayLabel in ${w.location}: ${w.summary}, ${fmt(w.minTempC)}–${fmt(w.maxTempC)}°C, " +
            "${w.rainChancePercent}% chance of rain.$now"
        return ToolResult.ok(
            message,
            JSONObject()
                .put("location", w.location)
                .put("date", w.date.toString())
                .put("summary", w.summary)
                .put("max_c", w.maxTempC)
                .put("min_c", w.minTempC)
                .put("rain_chance", w.rainChancePercent)
                .put("fetched_at", w.fetchedAtMillis)
        )
    }

    // "Did we receive usable current information?" — fresh fetch with real values.
    override suspend fun verify(args: JSONObject, result: ToolResult): Verification {
        val data = result.data ?: return Verification.failed("no weather data returned")
        val ageMs = clock() - data.optLong("fetched_at", 0L)
        return when {
            !data.has("max_c") || data.optString("date").isBlank() -> Verification.failed("incomplete weather data")
            ageMs > MAX_AGE_MS -> Verification.failed("weather data is stale")
            else -> Verification.verified("live Open-Meteo data for ${data.optString("date")}")
        }
    }

    private fun fmt(value: Double) = String.format(Locale.US, "%.0f", value)

    private companion object {
        const val MAX_AGE_MS = 10 * 60 * 1000L
    }
}
