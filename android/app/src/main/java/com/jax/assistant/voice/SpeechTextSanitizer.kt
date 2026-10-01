package com.jax.assistant.voice

/** Converts assistant Markdown into natural speech without reading formatting markers aloud. */
object SpeechTextSanitizer {
    fun sanitize(markdown: String): String {
        var text = markdown.replace("\\r\\n", "\\n")
        text = text.replace(Regex("(?s)```(?:[A-Za-z0-9_+-]+)?\\s*(.*?)```"), "$1")
        text = text.replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        text = text.replace(Regex("\\[([^]]+)]\\((?:[^)]+)\\)"), "$1")
        text = text.replace(Regex("`([^`]+)`"), "$1")
        text = text.replace(Regex("(?m)^\\s*[-+*]\\s+"), "")
        text = text.replace(Regex("(?m)^\\s*\\d+[.)]\\s+"), "")
        text = text.replace(Regex("\\*{1,3}|_{1,3}|~{2}"), "")
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
