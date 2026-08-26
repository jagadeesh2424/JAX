package com.jax.assistant.voice

/** A voice transport independent of JAX's text/agent brain. */
interface VoiceProvider {
    fun start()
    fun stop()
    fun shutdown()
}
