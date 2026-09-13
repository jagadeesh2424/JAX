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
 * Gemini Live audio provider with enhanced reliability (Priority #6).
 * Connection lifecycle: start → connect → audio-conversation → monitor → [auto-reconnect on disconnect].
 * Network resilience: exponential backoff, session recovery, heartbeat monitoring.
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
        const val MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val TAG = "GeminiLiveVoice"
        // Exponential backoff: 250ms base, up to 10s max, for auto-reconnect.
        private const val RECONNECT_DELAY_BASE_MS = 250L
        private const val RECONNECT_DELAY_MAX_MS = 10_000L
        private const val RECONNECT_BACKOFF_MULTIPLIER = 1.5f
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: LiveSession? = null
    private var stopped = true
    private var reconnecting = false
    private var monitorJob: Job? = null
    // Connection state tracking: explicit lifecycle for debugging and conditional logic.
    private var connectionState = ConnectionState.DISCONNECTED
    private var reconnectDelayMs = RECONNECT_DELAY_BASE_MS
    private var inputTranscript = StringBuilder()
    private var outputTranscript = StringBuilder()

    // Lifecycle states for the connection.
    private enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    override fun start() {
        if (!stopped) return
        // Check Firebase auth before attempting connection.
        if (FirebaseAuth.getInstance().currentUser == null) {
            val msg = "Firebase sign-in required for Gemini Live; use local voice instead."
            android.util.Log.w(TAG, msg)
            onError(msg)
            onActiveChanged(false)
            connectionState = ConnectionState.ERROR
            return
        }
        stopped = false
        connectionState = ConnectionState.CONNECTING
        reconnectDelayMs = RECONNECT_DELAY_BASE_MS
        scope.launch {
            try {
                android.util.Log.d(TAG, "Starting Gemini Live connection...")
                connectAndStart()
            } catch (error: Exception) {
                val msg = error.userMessage()
                android.util.Log.e(TAG, "Gemini Live start failed: $msg", error)
                onActiveChanged(false)
                onError(msg)
                connectionState = ConnectionState.ERROR
            }
        }
    }

    fun isActive(): Boolean = !stopped && session?.isClosed() == false

    @SuppressLint("MissingPermission")
    private suspend fun connectAndStart() {
        session?.close()
        try {
            val generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            }

            android.util.Log.d(TAG, "Creating liveModel with $MODEL_NAME...")
            val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
                modelName = MODEL_NAME,
                generationConfig = generationConfig
            )
            android.util.Log.d(TAG, "Connecting to server...")
            val newSession = liveModel.connect()
            session = newSession
            connectionState = ConnectionState.CONNECTED
            android.util.Log.d(TAG, "Connected. Starting audio conversation...")
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
                { goAway ->
                    android.util.Log.d(TAG, "Server closed session: $goAway")
                    connectionState = ConnectionState.DISCONNECTED
                    reconnectAfterGoAway()
                },
                true
            )
            android.util.Log.d(TAG, "Audio conversation started successfully.")
            onActiveChanged(true)
            monitorJob?.cancel()
            monitorJob = scope.launch {
                while (!stopped && session === newSession) {
                    delay(1_000)
                    if (newSession.isClosed()) {
                        android.util.Log.d(TAG, "Session closed by server.")
                        connectionState = ConnectionState.DISCONNECTED
                        onActiveChanged(false)
                        reconnectAfterGoAway()
                        break
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Connection setup failed", e)
            connectionState = ConnectionState.ERROR
            throw e
        }
    }

    private fun reconnectAfterGoAway() {
        if (stopped || reconnecting) return
        reconnecting = true
        connectionState = ConnectionState.RECONNECTING
        scope.launch {
            try {
                val delayMs = reconnectDelayMs.toLong()
                android.util.Log.d(TAG, "Reconnecting after ${delayMs}ms...")
                delay(delayMs)
                // Exponential backoff for next attempt: cap at MAX.
                reconnectDelayMs = (reconnectDelayMs * RECONNECT_BACKOFF_MULTIPLIER)
                    .toLong()
                    .coerceAtMost(RECONNECT_DELAY_MAX_MS)
                if (!stopped) {
                    connectionState = ConnectionState.CONNECTING
                    connectAndStart()
                }
            } catch (error: Exception) {
                if (!stopped) {
                    onError("Live voice disconnected: ${error.userMessage()}")
                    connectionState = ConnectionState.ERROR
                }
            } finally {
                reconnecting = false
            }
        }
    }

    override fun stop() {
        stopped = true
        reconnecting = false
        connectionState = ConnectionState.DISCONNECTED
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

    private fun Exception.userMessage(): String {
        val msg = message?.lowercase() ?: ""
        return when {
            msg.contains("app check") ->
                "Firebase App Check rejected this build. Verify debug token in Firebase Console."
            msg.contains("permission") ->
                "Microphone permission required for Gemini Live."
            msg.contains("closed by server") || msg.contains("server") || msg.contains("websocket") ->
                "Server disconnected. Check Firebase auth, App Check, or network connection."
            msg.contains("auth") ->
                "Firebase authentication failed. Sign in and retry."
            msg.contains("timeout") ->
                "Connection timed out. Check network and retry."
            else -> message?.takeIf { it.isNotBlank() } ?: "Gemini Live could not start."
        }
    }
}
