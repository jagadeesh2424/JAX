package com.jax.assistant.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.generationConfig
import org.json.JSONObject

data class ProviderRawResult(
    val isSuccess: Boolean,
    val text: String? = null,
    val httpStatus: Int? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val responseBody: String? = null,
    val rawException: Exception? = null
)

interface AIProvider {
    val providerName: String
    suspend fun generate(prompt: String, modelId: String, apiKey: String, requireJson: Boolean): ProviderRawResult
    suspend fun testConnection(modelId: String, apiKey: String): TestConnectionResult
}

class GeminiProvider : AIProvider {
    override val providerName: String = "Gemini"

    override suspend fun generate(prompt: String, modelId: String, apiKey: String, requireJson: Boolean): ProviderRawResult {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            return ProviderRawResult(
                isSuccess = false,
                httpStatus = 401,
                errorCode = "MISSING_API_KEY",
                errorMessage = "Gemini API Key is missing or blank."
            )
        }

        return try {
            val model = GenerativeModel(
                modelName = modelId,
                apiKey = keyToUse,
                generationConfig = if (requireJson) {
                    generationConfig { responseMimeType = "application/json" }
                } else null
            )

            val response = model.generateContent(prompt)
            val text = response.text
            if (text.isNullOrBlank()) {
                ProviderRawResult(
                    isSuccess = false,
                    httpStatus = 200,
                    errorCode = "EMPTY_RESPONSE",
                    errorMessage = "Empty response received from model $modelId."
                )
            } else {
                ProviderRawResult(
                    isSuccess = true,
                    text = text,
                    httpStatus = 200
                )
            }
        } catch (e: Exception) {
            parseException(e)
        }
    }

    override suspend fun testConnection(modelId: String, apiKey: String): TestConnectionResult {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            return TestConnectionResult(false, "API Key is missing. Please enter your Gemini API Key in Settings ⚙️.")
        }

        val res = generate("Respond with 'OK' to verify connection.", modelId, keyToUse, requireJson = false)
        return if (res.isSuccess && !res.text.isNullOrBlank()) {
            TestConnectionResult(true, "Model '$modelId' connected successfully!")
        } else {
            val msg = res.errorMessage ?: "Unknown provider error"
            TestConnectionResult(false, "Model '$modelId' test failed: $msg")
        }
    }

    private fun parseException(e: Exception): ProviderRawResult {
        val fullMessage = buildString {
            append(e.message ?: "")
            append(" ")
            append(e.localizedMessage ?: "")
            append(" ")
            append(e.cause?.message ?: "")
            append(" ")
            append(e.toString())
        }

        var httpStatus: Int? = null
        var errorCode: String? = null
        var jsonErrorMessage: String? = null
        var responseBody: String? = null

        val jsonStart = fullMessage.indexOf('{')
        val jsonEnd = fullMessage.lastIndexOf('}')
        if (jsonStart != -1 && jsonEnd > jsonStart) {
            val candidateJson = fullMessage.substring(jsonStart, jsonEnd + 1)
            try {
                val jsonObj = JSONObject(candidateJson)
                if (jsonObj.has("error")) {
                    val errorObj = jsonObj.getJSONObject("error")
                    val code = errorObj.optInt("code", 0)
                    if (code != 0) httpStatus = code
                    errorCode = errorObj.optString("status", null) ?: errorObj.optString("code", null)
                    jsonErrorMessage = errorObj.optString("message", null)
                    responseBody = candidateJson
                }
            } catch (_: Exception) {}
        }

        if (httpStatus == null) {
            val statusRegex = Regex("""\b(4\d{2}|5\d{2})\b""")
            val match = statusRegex.find(fullMessage)
            if (match != null) {
                httpStatus = match.value.toIntOrNull()
            }
        }

        if (e is InvalidAPIKeyException) {
            httpStatus = 401
            errorCode = "INVALID_API_KEY"
        } else if (e is QuotaExceededException) {
            httpStatus = 429
            errorCode = "QUOTA_EXCEEDED"
        }

        val extractedMsg = jsonErrorMessage ?: e.localizedMessage ?: e.message ?: "Unknown error"

        return ProviderRawResult(
            isSuccess = false,
            httpStatus = httpStatus,
            errorCode = errorCode,
            errorMessage = extractedMsg,
            responseBody = responseBody ?: fullMessage,
            rawException = e
        )
    }
}
