package com.jax.assistant.ai

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class ModelHealthStore(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("jax_model_health_db", Context.MODE_PRIVATE)

    fun loadModels(): List<ModelInfo> {
        val defaults = ModelCatalog.getDefaultModels().associateBy { it.id }.toMutableMap()
        val jsonStr = prefs.getString("models_health_json", null) ?: return defaults.values.toList()

        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id")
                val model = defaults[id]
                if (model != null) {
                    model.enabled = obj.optBoolean("enabled", model.enabled)
                    model.lastSuccess = obj.optLong("lastSuccess", model.lastSuccess)
                    model.lastFailure = obj.optLong("lastFailure", model.lastFailure)
                    model.cooldownUntil = obj.optLong("cooldownUntil", model.cooldownUntil)
                    model.failureCount = obj.optInt("failureCount", model.failureCount)
                    model.averageLatency = obj.optLong("averageLatency", model.averageLatency)
                }
            }
        } catch (_: Exception) {}

        return defaults.values.toList()
    }

    fun saveModels(models: List<ModelInfo>) {
        val array = JSONArray()
        for (m in models) {
            val obj = JSONObject().apply {
                put("id", m.id)
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
