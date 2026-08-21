package com.jax.assistant.ai

import android.content.ContentResolver
import android.net.Uri
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

// Runs only after the user selects an image through Android's system picker.
class VisionService(
    private val contentResolver: ContentResolver,
    private val apiKeyProvider: () -> String
) {
    suspend fun analyze(imageUri: Uri, modelName: String): String = withContext(Dispatchers.IO) {
        val key = apiKeyProvider().trim()
        if (key.isBlank()) return@withContext "Add a Gemini API key before analyzing an image."
        try {
            val bytes = contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
                ?: return@withContext "I couldn't read that image."
            if (bytes.size > MAX_IMAGE_BYTES) return@withContext "Choose an image smaller than 5 MB."
            val mimeType = contentResolver.getType(imageUri) ?: "image/jpeg"
            val body = JSONObject().put("contents", JSONArray().put(JSONObject().put("parts", JSONArray()
                .put(JSONObject().put("text", ANALYSIS_PROMPT))
                .put(JSONObject().put("inline_data", JSONObject()
                    .put("mime_type", mimeType)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))))))
            val connection = (URL("$BASE_URL$modelName:generateContent?key=$key").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                doOutput = true
                connectTimeout = 20_000
                readTimeout = 30_000
            }
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return@withContext "Image analysis is unavailable right now."
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            JSONObject(response)
                .optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text")
                ?.takeIf { it.isNotBlank() }
                ?: "I couldn't produce an image analysis."
        } catch (_: Exception) {
            "Image analysis failed. Check your connection and try again."
        }
    }

    private companion object {
        const val MAX_IMAGE_BYTES = 5 * 1024 * 1024
        const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
        const val ANALYSIS_PROMPT = "Describe the visible scene concisely. Identify useful objects, text, and actionable details. Do not identify people or make sensitive inferences."
    }
}