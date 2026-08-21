package com.jax.assistant.data

import android.content.Context
import android.content.SharedPreferences
import com.jax.assistant.config.AppConfig

// Owns all persisted user settings (API key, selected model, developer mode).
class UserPreferencesRepository(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("jax_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String =
        prefs.getString("gemini_api_key", null) ?: System.getenv("GEMINI_API_KEY") ?: ""

    fun saveApiKey(key: String) {
        prefs.edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun getSelectedModel(): String =
        prefs.getString("selected_ai_model", AppConfig.DEFAULT_MODEL) ?: AppConfig.DEFAULT_MODEL

    fun saveSelectedModel(modelName: String) {
        prefs.edit().putString("selected_ai_model", modelName.trim()).apply()
    }

    fun isDeveloperMode(): Boolean = prefs.getBoolean("developer_mode", false)

    fun setDeveloperMode(enabled: Boolean) {
        prefs.edit().putBoolean("developer_mode", enabled).apply()
    }

    // Freeform user profile (identity, role, preferences) injected into every agent turn.
    fun getUserProfile(): String = prefs.getString("user_profile", "") ?: ""

    fun saveUserProfile(profile: String) {
        prefs.edit().putString("user_profile", profile.trim()).apply()
    }

    fun isDailyAutomationEnabled(): Boolean = prefs.getBoolean("daily_automation_enabled", true)

    fun setDailyAutomationEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("daily_automation_enabled", enabled).apply()
    }

    fun isTaskContextAwarenessEnabled(): Boolean = prefs.getBoolean("task_context_awareness_enabled", true)

    fun setTaskContextAwarenessEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("task_context_awareness_enabled", enabled).apply()
    }

    fun isVoiceResponsesEnabled(): Boolean = prefs.getBoolean("voice_responses_enabled", true)

    fun setVoiceResponsesEnabled(enabled: Boolean) {
        prefs.edit().putBoolean("voice_responses_enabled", enabled).apply()
    }

    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
