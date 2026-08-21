package com.jax.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Generates text embeddings via Gemini text-embedding-004 (REST). Best-effort: returns null
// on missing key / network error so callers fall back to lexical search.
class EmbeddingService(private val apiKeyProvider: () -> String) {

    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank() || text.isBlank()) return@withContext null
        try {
            val url = URL("$ENDPOINT?key=$key")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 15000
                readTimeout = 15000
            }
            val body = JSONObject().apply {
                put("model", "models/$MODEL")
                put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text))))
            }
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) return@withContext null
            val resp = conn.inputStream.bufferedReader().use { it.readText() }
            val values = JSONObject(resp).optJSONObject("embedding")?.optJSONArray("values")
                ?: return@withContext null
            FloatArray(values.length()) { i -> values.getDouble(i).toFloat() }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val MODEL = "text-embedding-004"
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/models/text-embedding-004:embedContent"
    }
}
