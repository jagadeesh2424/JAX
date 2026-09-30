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
import com.google.firebase.ai.type.liveGenerationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Gemini Live audio provider: start -> connect -> audio conversation -> monitor, with automatic
 * reconnection (exponential backoff) when the server drops the session.
 */
@OptIn(PublicPreviewAPI::class)
class GeminiLiveVoiceProvider(
    private val onInputTranscript: (String) -> Unit,
    private val onOutputTranscript: (String) -> Unit,
    private val onActiveChanged: (Boolean) -> Unit,
    private val onError: (String) -> Unit,
    private val functionCallHandler: ((FunctionCallPart) -> FunctionResponsePart)? = null
) : VoiceProvider {

    enum class ConnectionState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING, ERROR }

    companion object {
        const val MODEL_NAME = "gemini-2.5-flash-native-audio-preview-12-2025"
        private const val TAG = "GeminiLiveVoice"
        const val RECONNECT_DELAY_BASE_MS = 250L
        const val RECONNECT_DELAY_MAX_MS = 10_000L
        private const val RECONNECT_BACKOFF_MULTIPLIER = 1.5
        private const val MAX_RECONNECT_ATTEMPTS = 5
        private const val SESSION_POLL_MS = 1_000L

        fun nextReconnectDelay(currentMs: Long): Long =
            (currentMs * RECONNECT_BACKOFF_MULTIPLIER).toLong().coerceIn(RECONNECT_DELAY_BASE_MS, RECONNECT_DELAY_MAX_MS)
    }

    // All mutable state below is only touched on the main thread (scope uses Dispatchers.Main).
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var session: LiveSession? = null
    private var stopped = true
    private var reconnecting = false
    private var monitorJob: Job? = null
    private var reconnectDelayMs = RECONNECT_DELAY_BASE_MS
    private var inputTranscript = StringBuilder()
    private var outputTranscript = StringBuilder()

    var state: ConnectionState = ConnectionState.DISCONNECTED
        private set

    override fun start() {
        if (!stopped) return
        if (FirebaseAuth.getInstance().currentUser == null) {
            fail("Firebase sign-in required for Gemini Live; use local voice instead.")
            return
        }
        stopped = false
        reconnectDelayMs = RECONNECT_DELAY_BASE_MS
        state = ConnectionState.CONNECTING
        scope.launch {
            try {
                connectAndStart()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.e(TAG, "Gemini Live start failed", e)
                fail(e.userMessage())
            }
        }
    }

    fun isActive(): Boolean = !stopped && session?.isClosed() == false

    @SuppressLint("MissingPermission")
    private suspend fun connectAndStart() {
        session?.let { runCatching { it.close() } }
        val liveModel = Firebase.ai(backend = GenerativeBackend.googleAI()).liveModel(
            modelName = MODEL_NAME,
            generationConfig = liveGenerationConfig {
                responseModality = ResponseModality.AUDIO
                inputAudioTranscription = AudioTranscriptionConfig()
                outputAudioTranscription = AudioTranscriptionConfig()
            }
        )
        val newSession = liveModel.connect()
        if (stopped) {
            runCatching { newSession.close() }
            return
        }
        session = newSession
        // SDK callbacks arrive on background threads; hop to main before touching state.
        newSession.startAudioConversation(
            functionCallHandler,
            { input, output ->
                val inText = input?.text?.takeIf { it.isNotBlank() }
                val outText = output?.text?.takeIf { it.isNotBlank() }
                scope.launch {
                    inText?.let { onInputTranscript(inputTranscript.append(it).toString()) }
                    outText?.let { onOutputTranscript(outputTranscript.append(it).toString()) }
                }
            },
            { goAway ->
                android.util.Log.d(TAG, "Server is closing the session: $goAway")
                scope.launch { reconnect() }
            },
            true
        )
        state = ConnectionState.CONNECTED
        reconnectDelayMs = RECONNECT_DELAY_BASE_MS
        onActiveChanged(true)

        monitorJob?.cancel()
        monitorJob = scope.launch {
            while (!stopped && session === newSession) {
                delay(SESSION_POLL_MS)
                if (newSession.isClosed()) {
                    onActiveChanged(false)
                    reconnect()
                    break
                }
            }
        }
    }

    // Retries with exponential backoff; gives up (and reports) after MAX_RECONNECT_ATTEMPTS.
    private suspend fun reconnect() {
        if (stopped || reconnecting) return
        reconnecting = true
        state = ConnectionState.RECONNECTING
        try {
            repeat(MAX_RECONNECT_ATTEMPTS) {
                delay(reconnectDelayMs)
                reconnectDelayMs = nextReconnectDelay(reconnectDelayMs)
                if (stopped) return
                try {
                    connectAndStart()
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Reconnect attempt failed", e)
                }
            }
            fail("Live voice disconnected. Check your connection and try again.")
        } finally {
            reconnecting = false
        }
    }

    // Terminal failure: reset to a restartable state and tell the user.
    private fun fail(message: String) {
        stopped = true
        session = null
        monitorJob?.cancel()
        monitorJob = null
        state = ConnectionState.ERROR
        onActiveChanged(false)
        onError(message)
    }

    override fun stop() {
        stopped = true
        reconnecting = false
        state = ConnectionState.DISCONNECTED
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
