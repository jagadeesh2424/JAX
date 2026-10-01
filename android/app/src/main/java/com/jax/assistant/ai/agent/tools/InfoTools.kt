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
    val fetchedAtMillis: Long,
    val city: String = location.substringBefore(",").trim(),
    val region: String = "",
    val country: String = ""
)

// Live weather from Open-Meteo (free, no API key). Location comes from the request; when absent
// the configured home city is used (JAX does not read device location).
class WeatherClient(
    private val http: HttpGet = DefaultHttpGet,
    private val clock: () -> Long = { System.currentTimeMillis() }
) {
    suspend fun fetch(location: String, dayOffset: Int): WeatherSnapshot {
        val place = location.trim().ifBlank { AppConfig.DEFAULT_WEATHER_LOCATION }
        val hit = resolveLocation(place)
            ?: throw IllegalArgumentException("I couldn't find a place called \"$place\".")
        val city = hit.optString("name").trim()
        val region = hit.optString("admin1").trim()
        val country = hit.optString("country").trim()
        val resolvedName = listOf(city, region, country)
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
            fetchedAtMillis = clock(),
            city = city,
            region = region,
            country = country
        )
    }

    private suspend fun resolveLocation(place: String): JSONObject? {
        val alias = knownAlias(place)
        val first = geocode(alias?.query ?: place, alias?.countryCode)
        if (alias == null || first == null || isIndia(first)) return first

        // Open-Meteo should honor countryCode, but keep a validation guard because a provider
        // response must never silently turn Bangalore into a same-named city in another country.
        val corrected = geocode("Bengaluru", "IN")
        return corrected?.takeIf(::isIndia)
    }

    private suspend fun geocode(query: String, countryCode: String?): JSONObject? {
        val params = buildString {
            append(GEOCODE_URL)
            append(URLEncoder.encode(query, "UTF-8"))
            countryCode?.let { append("&countryCode=").append(it) }
        }
        val results = JSONObject(http(params)).optJSONArray("results") ?: return null
        if (results.length() == 0) return null
        val preferredCountry = countryCode?.uppercase(Locale.US)
        return (0 until results.length())
            .mapNotNull { results.optJSONObject(it) }
            .filter { preferredCountry == null || it.optString("country_code").equals(preferredCountry, true) ||
                (preferredCountry == "IN" && it.optString("country").equals("India", true)) }
            .maxByOrNull { score(it, query, preferredCountry) }
            ?: results.optJSONObject(0)
    }

    private fun score(hit: JSONObject, query: String, preferredCountry: String?): Int {
        val name = hit.optString("name").trim()
        val normalizedName = normalize(name)
        val normalizedQuery = normalize(query)
        var score = 0
        if (normalizedName == normalizedQuery) score += 1_000
        if (preferredCountry != null && hit.optString("country_code").equals(preferredCountry, true)) score += 500
        if (preferredCountry == "IN" && hit.optString("country").equals("India", true)) score += 400
        if (hit.optString("admin1").equals("Karnataka", true)) score += 250
        score += (hit.optDouble("population", 0.0) / 1_000_000.0).toInt().coerceAtMost(100)
        return score
    }

    private fun knownAlias(place: String): LocationAlias? = when (normalize(place)) {
        "bangalore", "bengaluru" -> LocationAlias("Bengaluru", "IN")
        else -> null
    }

    private fun isIndia(hit: JSONObject): Boolean =
        hit.optString("country_code").equals("IN", true) || hit.optString("country").equals("India", true)

    private fun normalize(value: String): String =
        value.lowercase(Locale.US).replace(Regex("[^a-z0-9]"), "")

    private data class LocationAlias(val query: String, val countryCode: String)

    companion object {
        private const val GEOCODE_URL = "https://geocoding-api.open-meteo.com/v1/search?count=10&name="
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
                .put("city", w.city)
                .put("region", w.region)
                .put("country", w.country)
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
