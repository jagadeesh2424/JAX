package com.jax.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

object ModelCatalog {

    suspend fun discoverModels(apiKey: String): List<ModelInfo> = withContext(Dispatchers.IO) {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            return@withContext getFallbackModels()
        }

        val targetUrl = "https://generativelanguage.googleapis.com/v1beta/models?key=$keyToUse"
        try {
            val url = URL(targetUrl)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 12000

            val statusCode = conn.responseCode
            if (statusCode == 200) {
                val responseStr = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8")).use { it.readText() }
                val root = JSONObject(responseStr)
                val modelsArray = root.optJSONArray("models")

                if (modelsArray != null && modelsArray.length() > 0) {
                    val discovered = mutableListOf<ModelInfo>()

                    for (i in 0 until modelsArray.length()) {
                        val obj = modelsArray.getJSONObject(i)
                        val rawName = obj.optString("name", "")
                        val cleanId = rawName.removePrefix("models/").trim()
                        if (cleanId.isBlank()) continue

                        val displayName = obj.optString("displayName", cleanId)
                        val methodsArray = obj.optJSONArray("supportedGenerationMethods")
                        val methodsList = mutableListOf<String>()
                        var supportsGenerateContent = false

                        if (methodsArray != null) {
                            for (m in 0 until methodsArray.length()) {
                                val method = methodsArray.getString(m)
                                methodsList.add(method)
                                if (method == "generateContent") {
                                    supportsGenerateContent = true
                                }
                            }
                        }

                        val methodsStr = methodsList.joinToString(", ").ifBlank { "generateContent" }
                        val priority = calculatePriority(cleanId, displayName)

                        val model = ModelInfo(
                            id = cleanId,
                            displayName = displayName,
                            provider = "Gemini",
                            priority = priority,
                            speedScore = if (cleanId.contains("flash")) 9 else 6,
                            reasoningScore = if (cleanId.contains("pro")) 9 else 7,
                            contextWindow = obj.optInt("inputTokenLimit", 1048576),
                            supportsJson = true,
                            supportedMethods = methodsStr,
                            enabled = supportsGenerateContent,
                            exclusionReason = if (!supportsGenerateContent) "Does not support generateContent method" else null
                        )

                        discovered.add(model)
                    }

                    if (discovered.isNotEmpty()) {
                        return@withContext discovered.sortedBy { it.priority }
                    }
                }
            }
        } catch (_: Exception) {}

        getFallbackModels()
    }

    fun calculatePriority(cleanId: String, displayName: String): Int {
        val idLower = cleanId.lowercase()
        return when {
            idLower == "gemini-3.6-flash" || idLower == "gemini-3.6-flash-preview" -> 1
            idLower == "gemini-3.5-flash" || idLower == "gemini-3.5-flash-preview" -> 2
            idLower == "gemini-3.1-flash-lite" || idLower == "gemini-3.1-flash-lite-preview" -> 3
            idLower == "gemini-3-flash" || idLower == "gemini-3.0-flash" -> 4
            idLower == "gemini-2.5-flash" || idLower == "gemini-2.5-flash-lite" -> 5
            idLower == "gemini-2.0-flash" || idLower == "gemini-2.0-flash-exp" -> 6
            idLower.contains("3.6") && idLower.contains("flash") -> 1
            idLower.contains("3.5") && idLower.contains("flash") -> 2
            idLower.contains("3.1") && idLower.contains("flash") -> 3
            idLower.contains("3") && idLower.contains("flash") -> 4
            idLower.contains("2.5") && idLower.contains("flash") -> 5
            idLower.contains("2.0") && idLower.contains("flash") -> 6
            idLower.contains("2.5") && idLower.contains("pro") -> 7
            idLower.contains("1.5") && idLower.contains("flash") -> 8
            idLower.contains("1.5") && idLower.contains("pro") -> 9
            idLower.contains("flash") -> 10
            idLower.contains("pro") -> 11
            else -> 20
        }
    }

    fun getFallbackModels(): List<ModelInfo> {
        return listOf(
            ModelInfo(
                id = "gemini-2.0-flash",
                displayName = "Gemini 2.0 Flash",
                provider = "Gemini",
                priority = 6,
                speedScore = 9,
                reasoningScore = 8,
                contextWindow = 1048576,
                supportsJson = true,
                supportedMethods = "generateContent, countTokens"
            ),
            ModelInfo(
                id = "gemini-1.5-flash",
                displayName = "Gemini 1.5 Flash",
                provider = "Gemini",
                priority = 8,
                speedScore = 8,
                reasoningScore = 7,
                contextWindow = 1048576,
                supportsJson = true,
                supportedMethods = "generateContent, countTokens"
            )
        )
    }

    fun getDefaultModels(): List<ModelInfo> = getFallbackModels()
}

