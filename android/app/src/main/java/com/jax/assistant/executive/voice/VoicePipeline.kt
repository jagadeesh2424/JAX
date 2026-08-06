package com.jax.assistant.executive.voice

import com.jax.assistant.executive.ExecutiveIntelligenceEngine
import com.jax.assistant.executive.ExecutiveProcessResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceState {
    IDLE,
    LISTENING,
    PROCESSING,
    SPEAKING,
    ERROR
}

data class VoicePipelineStatus(
    val state: VoiceState = VoiceState.IDLE,
    val lastRecognizedSpeech: String = "",
    val wakeWordDetectorActive: Boolean = true,
    val lastError: String? = null
)

class VoicePipeline(private val executiveEngine: ExecutiveIntelligenceEngine) {

    private val _status = MutableStateFlow(VoicePipelineStatus())
    val status: StateFlow<VoicePipelineStatus> = _status.asStateFlow()

    suspend fun processTranscribedSpeech(recognizedText: String): ExecutiveProcessResult {
        if (recognizedText.isBlank()) {
            _status.value = _status.value.copy(state = VoiceState.IDLE)
            return ExecutiveProcessResult(
                handledLocallyByAgent = true,
                responseText = "No speech detected.",
                agentName = "VoicePipeline"
            )
        }

        _status.value = _status.value.copy(
            state = VoiceState.PROCESSING,
            lastRecognizedSpeech = recognizedText
        )

        // Route directly through the Executive Intelligence Layer
        val result = executiveEngine.processUserPrompt(recognizedText)

        _status.value = _status.value.copy(state = VoiceState.SPEAKING)
        return result
    }

    fun onSpeechPlaybackCompleted() {
        _status.value = _status.value.copy(state = VoiceState.IDLE)
    }

    fun triggerWakeWord() {
        _status.value = _status.value.copy(state = VoiceState.LISTENING)
    }
}
