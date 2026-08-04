package com.jax.assistant.ai

import android.util.Log
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.InvalidAPIKeyException
import com.google.ai.client.generativeai.type.QuotaExceededException
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "GeminiProvider"

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

    companion object {
        const val SDK_VERSION = "com.google.ai.client.generativeai:0.9.0"
        const val REST_ENDPOINT_BASE = "https://generativelanguage.googleapis.com/v1beta"
        const val API_VERSION = "v1beta"
    }

    override suspend fun generate(prompt: String, modelId: String, apiKey: String, requireJson: Boolean): ProviderRawResult {
        val keyToUse = apiKey.trim()
        if (keyToUse.isBlank()) {
            val result = ProviderRawResult(
                isSuccess = false,
                httpStatus = 401,
                errorCode = "MISSING_API_KEY",
                errorMessage = "Gemini API Key is missing or blank."
            )
            printDiagnostics(modelId, keyToUse, result, null)
            return result
        }

        val cleanModelId = modelId.removePrefix("models/").trim()

        // First attempt via GenerativeModel SDK
        var sdkResult: ProviderRawResult? = null
        var sdkException: Exception? = null

        try {
            val model = GenerativeModel(
                modelName = cleanModelId,
                apiKey = keyToUse,
                generationConfig = if (requireJson) {
                    generationConfig { responseMimeType = "application/json" }
                } else null
            )

            val response = model.generateContent(prompt)
            val text = response.text
            if (!text.isNullOrBlank()) {
                val res = ProviderRawResult(
                    isSuccess = true,
                    text = text,
                    httpStatus = 200
                )
                printDiagnostics(cleanModelId, keyToUse, res, null)
                return res
            } else {
                sdkResult = ProviderRawResult(
                    isSuccess = false,
                    httpStatus = 200,
                    errorCode = "EMPTY_RESPONSE",
                    errorMessage = "Empty response received from model $cleanModelId."
                )
            }
        } catch (e: Exception) {
            sdkException = e
            sdkResult = parseException(e, cleanModelId)
        }

        // If SDK returned success, return it
        if (sdkResult?.isSuccess == true) {
            printDiagnostics(cleanModelId, keyToUse, sdkResult, sdkException)
            return sdkResult
        }

        // Fallback: If model is known real (e.g. gemini-2.0-flash, gemini-1.5-flash, gemini-1.5-pro) or if SDK failed, run Direct REST API request
        val directRestResult = executeDirectRest(prompt, cleanModelId, keyToUse, requireJson)
        printDiagnostics(cleanModelId, keyToUse, directRestResult, sdkException ?: directRestResult.rawException)

        return if (directRestResult.isSuccess) directRestResult else (sdkResult ?: directRestResult)
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

    private suspend fun executeDirectRest(
        prompt: String,
        modelId: String,
        apiKey: String,
        requireJson: Boolean
    ): ProviderRawResult = withContext(Dispatchers.IO) {
        val cleanModelId = modelId.removePrefix("models/").trim()
        val targetUrlStr = "$REST_ENDPOINT_BASE/models/$cleanModelId:generateContent?key=$apiKey"

        try {
            val url = URL(targetUrlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 15000

            val requestJson = JSONObject().apply {
                val contentsArr = JSONArray().apply {
                    val contentObj = JSONObject().apply {
                        val partsArr = JSONArray().apply {
                            put(JSONObject().put("text", prompt))
                        }
                        put("parts", partsArr)
                    }
                    put(contentObj)
                }
                put("contents", contentsArr)

                if (requireJson) {
                    val genConfig = JSONObject().apply {
                        put("responseMimeType", "application/json")
                    }
                    put("generationConfig", genConfig)
                }
            }

            OutputStreamWriter(conn.outputStream, "UTF-8").use { writer ->
                writer.write(requestJson.toString())
                writer.flush()
            }

            val statusCode = conn.responseCode
            val isOk = statusCode in 200..299
            val stream = if (isOk) conn.inputStream else conn.errorStream

            val responseStr = if (stream != null) {
                BufferedReader(InputStreamReader(stream, "UTF-8")).use { it.readText() }
            } else ""

            if (isOk) {
                val parsedText = parseResponseText(responseStr)
                ProviderRawResult(
                    isSuccess = true,
                    text = parsedText,
                    httpStatus = statusCode,
                    responseBody = responseStr
                )
            } else {
                val errorDetails = parseErrorResponseBody(responseStr, statusCode)
                ProviderRawResult(
                    isSuccess = false,
                    httpStatus = statusCode,
                    errorCode = errorDetails.first,
                    errorMessage = errorDetails.second,
                    responseBody = responseStr
                )
            }
        } catch (e: Exception) {
            ProviderRawResult(
                isSuccess = false,
                httpStatus = null,
                errorCode = "REST_EXCEPTION",
                errorMessage = e.localizedMessage ?: e.message ?: "REST execution failed",
                responseBody = e.toString(),
                rawException = e
            )
        }
    }

    private fun parseResponseText(jsonStr: String): String {
        return try {
            val root = JSONObject(jsonStr)
            val candidates = root.optJSONArray("candidates")
            if (candidates != null && candidates.length() > 0) {
                val content = candidates.getJSONObject(0).optJSONObject("content")
                val parts = content?.optJSONArray("parts")
                if (parts != null && parts.length() > 0) {
                    return parts.getJSONObject(0).optString("text", "")
                }
            }
            jsonStr
        } catch (_: Exception) {
            jsonStr
        }
    }

    private fun parseErrorResponseBody(jsonStr: String, statusCode: Int): Pair<String, String> {
        return try {
            val root = JSONObject(jsonStr)
            if (root.has("error")) {
                val err = root.getJSONObject("error")
                val status = err.optString("status", "HTTP_$statusCode")
                val msg = err.optString("message", "Request failed with HTTP $statusCode")
                Pair(status, msg)
            } else {
                Pair("HTTP_$statusCode", jsonStr)
            }
        } catch (_: Exception) {
            Pair("HTTP_$statusCode", if (jsonStr.isNotBlank()) jsonStr else "HTTP $statusCode error")
        }
    }

    private fun parseException(e: Exception, modelId: String): ProviderRawResult {
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

    private fun printDiagnostics(
        modelId: String,
        apiKey: String,
        result: ProviderRawResult,
        exception: Exception?
    ) {
        val cleanModel = modelId.removePrefix("models/").trim()
        val url = "$REST_ENDPOINT_BASE/models/$cleanModel:generateContent?key=${if (apiKey.length > 8) apiKey.take(6) + "..." else "INVALID"}"
        val stackTrace = exception?.stackTraceToString() ?: "No stacktrace"

        val logOutput = """
            =================== GEMINI REQUEST DIAGNOSTICS ===================
            Selected Model      : $cleanModel
            SDK Version         : $SDK_VERSION
            HTTP URL            : $url
            HTTP Method         : POST
            API Version         : $API_VERSION
            HTTP Status         : ${result.httpStatus ?: "N/A"}
            Error Code / Status : ${result.errorCode ?: "None"}
            Error Message       : ${result.errorMessage ?: "None"}
            Response Body       : ${result.responseBody ?: "N/A"}
            Exception Stacktrace: ${stackTrace.take(300)}
            ==================================================================
        """.trimIndent()

        Log.d(TAG, logOutput)
        println(logOutput)
    }
}

