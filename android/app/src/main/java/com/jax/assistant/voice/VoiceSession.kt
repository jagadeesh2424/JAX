package com.jax.assistant.voice

import android.speech.SpeechRecognizer

// Per-turn voice latency: speech_start -> transcription -> response -> tts_start -> tts_complete.
// Routing/tool/Gemini time is inside "response" and itemised by the pipeline's [Request] log line.
class VoiceLatencyTracker(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {

    enum class Mark(val label: String) {
        SPEECH_START("speech_start"),
        TRANSCRIPTION("transcription"),
        RESPONSE("response"),
        TTS_START("tts_start"),
        TTS_COMPLETE("tts_complete")
    }

    private val marks = LinkedHashMap<Mark, Long>()

    @Synchronized
    fun begin() {
        marks.clear()
        marks[Mark.SPEECH_START] = clock()
    }

    // Ignored unless a voice turn is in progress, so typed turns are never measured.
    @Synchronized
    fun mark(mark: Mark) {
        if (Mark.SPEECH_START in marks && mark !in marks) marks[mark] = clock()
    }

    @Synchronized
    fun totalMs(): Long? {
        val start = marks[Mark.SPEECH_START] ?: return null
        val end = marks[Mark.TTS_COMPLETE] ?: return null
        return end - start
    }

    // e.g. "[Voice] transcription=820ms response=1450ms tts_start=120ms tts_complete=2300ms total=4690ms"
    @Synchronized
    fun summary(): String? {
        val start = marks[Mark.SPEECH_START] ?: return null
        var previous = start
        val parts = Mark.values().drop(1).mapNotNull { mark ->
            marks[mark]?.let { at -> "${mark.label}=${at - previous}ms".also { previous = at } }
        }
        return "[Voice] " + parts.joinToString(" ") + " total=${previous - start}ms"
    }

    @Synchronized
    fun reset() = marks.clear()
}

// What to do after a SpeechRecognizer error: retry network problems once using on-device
// recognition, retry a busy recognizer once, and otherwise report instead of looping.
object VoiceRecovery {
    enum class Action { RETRY, RETRY_OFFLINE, REPORT }

    const val MAX_RETRIES = 1

    fun onRecognizerError(error: Int, retriesSoFar: Int): Action = when {
        retriesSoFar >= MAX_RETRIES -> Action.REPORT
        error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ||
            error == SpeechRecognizer.ERROR_SERVER -> Action.RETRY_OFFLINE
        error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> Action.RETRY
        else -> Action.REPORT
    }
}

// Chooses the voice input path. Gemini Live needs conversation mode and a Firebase sign-in; after
// a Live failure the device recognizer is used for a cool-down period instead of retrying Live.
object VoiceModeSelector {
    enum class Mode { LIVE, DEVICE }

    const val LIVE_RETRY_COOLDOWN_MS = 5 * 60 * 1000L

    fun select(conversationMode: Boolean, signedIn: Boolean, liveFailedAt: Long, nowMillis: Long): Mode =
        if (conversationMode && signedIn && (liveFailedAt == 0L || nowMillis - liveFailedAt >= LIVE_RETRY_COOLDOWN_MS)) Mode.LIVE
        else Mode.DEVICE
}
