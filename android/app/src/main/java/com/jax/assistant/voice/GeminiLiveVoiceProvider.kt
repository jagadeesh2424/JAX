package com.jax.assistant.voice

import android.annotation.SuppressLint
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.AudioTranscriptionConfig
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.LiveSession
import com.google.firebase.ai.type.PublicPreviewAPI
import com.google.firebase.ai.type.ResponseModality
import com.google.firebase.ai.type.Transcription
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gemini Live audio provider. It deliberately does not call JaxRepository: Live
 * is a separate conversational transport and the existing text/agent architecture
 * remains the source of truth for text turns and tools.
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveVoiceProvider(
    private val onInputTranscript: (String) -> Unit,
    private val onOutputTranscript: (String) -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val functionCallHandler: ((FunctionCallPart) -> FunctionResponsePart)? = null
) : VoiceProvider {

    companion object {
        // Current Gemini Developer API native-audio Live model documented by Firebase.
        const val MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: LiveSession? = null
    private var stopped = true
    private var reconnecting = false
    private var monitorJob: Job? = null
    private var inputTranscript = StringBuilder()
    private var outputTranscript = StringBuilder()

    override fun start() {
        if (!stopped) return
        if (FirebaseAuth.getInstance().currentUser == null) {
            onError("Sign in with Google before starting Gemini Live voice.")
            onActiveChanged(false)
            return
        }
        stopped = false
        scope.launch {
            try {
                connectAndStart()
            } catch (error: Exception) {
                onActiveChanged(false)
                onError(error.userMessage())
            }
        }
    }

    fun isActive(): Boolean = !stopped && session?.isClosed() == false

    @SuppressLint("MissingPermission")
    private suspend fun connectAndStart() {
        session?.close()
        val generationConfig = liveGenerationConfig {
            responseModality = ResponseModality.AUDIO
            inputAudioTranscription = AudioTranscriptionConfig()
            outputAudioTranscription = AudioTranscriptionConfig()
        }

        val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = MODEL_NAME,
            generationConfig = generationConfig
        )
        val newSession = liveModel.connect()
        session = newSession
        newSession.startAudioConversation(
            functionCallHandler,
            { input, output ->
                input?.text?.takeIf { it.isNotBlank() }?.let {
                    inputTranscript.append(it)
                    onInputTranscript(inputTranscript.toString())
                }
                output?.text?.takeIf { it.isNotBlank() }?.let {
                    outputTranscript.append(it)
                    onOutputTranscript(outputTranscript.toString())
                }
            },
            { reconnectAfterGoAway() },
            true
        )
        onActiveChanged(true)
        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (!stopped && session === newSession) {
                delay(1_000)
                if (newSession.isClosed()) {
                    onActiveChanged(false)
                    reconnectAfterGoAway()
                    break
                }
            }
        }
    }

    private fun reconnectAfterGoAway() {
        if (stopped || reconnecting) return
        reconnecting = true
        scope.launch {
            try {
                delay(250)
                if (!stopped) connectAndStart()
            } catch (error: Exception) {
                if (!stopped) onError("Live voice disconnected: ${error.userMessage()}")
            } finally {
                reconnecting = false
            }
        }
    }

    override fun stop() {
        stopped = true
        reconnecting = false
        monitorJob?.cancel()
        monitorJob = null
        session?.let {
            runCatching { it.stopAudioConversation() }
            scope.launch { runCatching { it.close() } }
        }
        session = null
        inputTranscript = StringBuilder()
        outputTranscript = StringBuilder()
        onActiveChanged(false)
    }

    override fun shutdown() {
        stop()
        scope.cancel()
    }

    private fun Exception.userMessage(): String = when {
        message?.contains("App Check", ignoreCase = true) == true ->
            "Firebase App Check rejected this debug build. Register the debug token in Firebase Console."
        message?.contains("permission", ignoreCase = true) == true ->
            "Microphone permission is required for Gemini Live."
        else -> message?.takeIf { it.isNotBlank() } ?: "Gemini Live could not start."
    }
}
