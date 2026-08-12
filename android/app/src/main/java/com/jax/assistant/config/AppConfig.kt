package com.jax.assistant.config

import java.util.Calendar

/**
 * Central configuration for JAX.
 *
 * This is the one place to tweak JAX's behavior. Change a value here, rebuild the app,
 * and it takes effect everywhere. Grouped by area so you can find things quickly.
 */
object AppConfig {

    // ---------------------------------------------------------------------
    // AI
    // ---------------------------------------------------------------------
    /** Default Gemini model used until you pick another one in Settings. */
    const val DEFAULT_MODEL = "gemini-2.0-flash"

    /** How many recent chat messages are sent to the AI as rolling context. */
    const val CONVERSATION_CONTEXT_TURNS = 8

    // ---------------------------------------------------------------------
    // Voice
    // ---------------------------------------------------------------------
    /** Spoken prefixes stripped before sending to JAX (e.g. "Hey JAX, add a task"). Keep lower-case. */
    val WAKE_PHRASES = listOf("hey jax", "hi jax", "okay jax", "ok jax", "hey jacks", "jax")

    // ---------------------------------------------------------------------
    // Daily briefing notification
    // ---------------------------------------------------------------------
    const val DAILY_BRIEFING_HOUR = 9        // 0-23 (local time)
    const val DAILY_BRIEFING_MINUTE = 0
    const val DAILY_CHANNEL_ID = "jax_daily_briefing_channel"

    // ---------------------------------------------------------------------
    // Weekly executive review notification
    // ---------------------------------------------------------------------
    const val WEEKLY_REVIEW_DAY = Calendar.SUNDAY   // Calendar.SUNDAY .. Calendar.SATURDAY
    const val WEEKLY_REVIEW_HOUR = 18               // 0-23 (local time)
    const val WEEKLY_REVIEW_MINUTE = 0
    const val WEEKLY_CHANNEL_ID = "jax_weekly_review_channel"

    // ---------------------------------------------------------------------
    // Database
    // ---------------------------------------------------------------------
    const val DATABASE_NAME = "jax_room_db"
}
