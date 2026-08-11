package com.jax.assistant.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

class VoiceManager(
    private val context: Context,
    private val onSpeechResult: (String) -> Unit,
    private val onError: (String) -> Unit = {},
    private val onListeningStateChanged: (Boolean) -> Unit = {},
    private val onSpeakingStateChanged: (Boolean) -> Unit = {}
) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = TextToSpeech(context, this)
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        if (SpeechRecognizer.isRecognitionAvailable(context)) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle?) {
                        onListeningStateChanged(false)
                        val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        if (!matches.isNullOrEmpty()) {
                            onSpeechResult(matches[0])
                        }
                    }
                    override fun onReadyForSpeech(params: Bundle?) { onListeningStateChanged(true) }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() { onListeningStateChanged(false) }
                    override fun onError(error: Int) {
                        onListeningStateChanged(false)
                        onError(describeError(error))
                    }
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) { onSpeakingStateChanged(true) }
                override fun onDone(utteranceId: String?) { onSpeakingStateChanged(false) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { onSpeakingStateChanged(false) }
            })
        }
    }

    fun startListening() {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            onError("Speech recognition is not available on this device.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }
        recognizer.startListening(intent)
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
        onListeningStateChanged(false)
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "jax_tts_id")
    }

    fun stopSpeaking() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        speechRecognizer?.destroy()
    }

    private fun describeError(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error."
        SpeechRecognizer.ERROR_CLIENT -> "Voice client error."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission denied."
        SpeechRecognizer.ERROR_NETWORK -> "Network error during recognition."
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timed out."
        SpeechRecognizer.ERROR_NO_MATCH -> "Didn't catch that. Please try again."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer is busy."
        SpeechRecognizer.ERROR_SERVER -> "Recognition server error."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected."
        else -> "Speech recognition error ($error)."
    }

    companion object {
        private val WAKE_PHRASES = com.jax.assistant.config.AppConfig.WAKE_PHRASES

        /** Removes a leading wake phrase (e.g. "Hey JAX, add a task" -> "add a task"). */
        fun stripWakePhrase(text: String): String {
            val trimmed = text.trim()
            val lower = trimmed.lowercase(Locale.US)
            for (phrase in WAKE_PHRASES) {
                if (lower.startsWith(phrase)) {
                    val remainder = trimmed.substring(phrase.length).trimStart(' ', ',', '.', '!', '?')
                    return remainder.ifBlank { trimmed }
                }
            }
            return trimmed
        }
    }
}
