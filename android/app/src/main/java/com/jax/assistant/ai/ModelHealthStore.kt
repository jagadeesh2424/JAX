package com.jax.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ModelHealthStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jax_model_health_db", Context.MODE_PRIVATE)

    fun isDiscoveryExpired(): Boolean {
        val lastTimestamp = prefs.getLong("discovery_timestamp_ms", 0L)
        val now = System.currentTimeMillis()
        return (now - lastTimestamp) > 86_400_000L // 24 hours
    }

    fun markDiscoveryUpdated() {
        prefs.edit().putLong("discovery_timestamp_ms", System.currentTimeMillis()).apply()
    }

    fun loadModels(): List<ModelInfo> {
        val jsonStr = prefs.getString("models_health_json", null) ?: return ModelCatalog.getFallbackModels()

        try {
            val array = JSONArray(jsonStr)
            val result = mutableListOf<ModelInfo>()

            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                if (id.isBlank()) continue

                val model = ModelInfo(
                    id = id,
                    displayName = obj.optString("displayName", id),
                    provider = obj.optString("provider", "Gemini"),
                    priority = obj.optInt("priority", ModelCatalog.calculatePriority(id, id)),
                    speedScore = obj.optInt("speedScore", 8),
                    reasoningScore = obj.optInt("reasoningScore", 8),
                    contextWindow = obj.optInt("contextWindow", 1048576),
                    supportsJson = obj.optBoolean("supportsJson", true),
                    supportsStreaming = obj.optBoolean("supportsStreaming", false),
                    supportsVision = obj.optBoolean("supportsVision", false),
                    supportedMethods = obj.optString("supportedMethods", "generateContent"),
                    exclusionReason = if (obj.has("exclusionReason") && !obj.isNull("exclusionReason")) obj.optString("exclusionReason") else null,
                    enabled = obj.optBoolean("enabled", true),
                    lastSuccess = obj.optLong("lastSuccess", 0L),
                    lastFailure = obj.optLong("lastFailure", 0L),
                    cooldownUntil = obj.optLong("cooldownUntil", 0L),
                    failureCount = obj.optInt("failureCount", 0),
                    averageLatency = obj.optLong("averageLatency", 0L)
                )
                result.add(model)
            }

            if (result.isNotEmpty()) {
                return result.sortedBy { it.priority }
            }
        } catch (_: Exception) {}

        return ModelCatalog.getFallbackModels()
    }

    fun saveModels(models: List<ModelInfo>) {
        val array = JSONArray()
        for (m in models) {
            val obj = JSONObject().apply {
                put("id", m.id)
                put("displayName", m.displayName)
                put("provider", m.provider)
                put("priority", m.priority)
                put("speedScore", m.speedScore)
                put("reasoningScore", m.reasoningScore)
                put("contextWindow", m.contextWindow)
                put("supportsJson", m.supportsJson)
                put("supportedMethods", m.supportedMethods)
                put("exclusionReason", m.exclusionReason ?: JSONObject.NULL)
                put("enabled", m.enabled)
                put("lastSuccess", m.lastSuccess)
                put("lastFailure", m.lastFailure)
                put("cooldownUntil", m.cooldownUntil)
                put("failureCount", m.failureCount)
                put("averageLatency", m.averageLatency)
            }
            array.put(obj)
        }
        prefs.edit().putString("models_health_json", array.toString()).apply()
    }
}

