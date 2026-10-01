package com.jax.assistant.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle

/** Small, dependency-free Markdown renderer for assistant messages. */
object MarkdownRenderer {
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*)$")
    private val bullet = Regex("^\\s*[-+*]\\s+(.*)$")
    private val numbered = Regex("^\\s*\\d+[.)]\\s+(.*)$")
    private val token = Regex("(\\*\\*(.+?)\\*\\*|(?<!\\*)\\*(?!\\s)(.+?)(?<!\\s)\\*|`([^`]+)`|\\[([^]]+)]\\(([^)]+)\\))")

    fun render(markdown: String): AnnotatedString = buildAnnotatedString {
        var codeBlock = false
        val lines = markdown.replace("\\r\\n", "\\n").split('\n')
        lines.forEachIndexed { index, rawLine ->
            val line = rawLine.trimEnd()
            if (line.trimStart().startsWith("```")) {
                codeBlock = !codeBlock
            } else if (codeBlock) {
                withStyle(codeStyle) { append(line) }
            } else {
                val headingMatch = heading.matchEntire(line)
                val bulletMatch = bullet.matchEntire(line)
                val numberedMatch = numbered.matchEntire(line)
                when {
                    headingMatch != null -> withStyle(headingStyle) { appendInline(headingMatch.groupValues[1]) }
                    bulletMatch != null -> {
                        append("• ")
                        appendInline(bulletMatch.groupValues[1])
                    }
                    numberedMatch != null -> {
                        val marker = line.trimStart().substringBefore(numberedMatch.groupValues[1]).trim()
                        append("$marker ")
                        appendInline(numberedMatch.groupValues[1])
                    }
                    else -> appendInline(line)
                }
            }
            if (index < lines.lastIndex) append('\n')
        }
    }

    private fun AnnotatedString.Builder.appendInline(text: String) {
        var cursor = 0
        token.findAll(text).forEach { match ->
            if (match.range.first > cursor) append(text.substring(cursor, match.range.first))
            when {
                match.value.startsWith("**") -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(match.groupValues[2])
                }
                match.value.startsWith("*") -> withStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)) {
                    append(match.groupValues[3])
                }
                match.value.startsWith("`") -> withStyle(codeStyle) { append(match.groupValues[4]) }
                match.value.startsWith("[") -> {
                    pushStringAnnotation("URL", match.groupValues[6])
                    withStyle(SpanStyle(color = Color(0xFF7DD3FC), textDecoration = TextDecoration.Underline)) {
                        append(match.groupValues[5])
                    }
                    pop()
                }
            }
            cursor = match.range.last + 1
        }
        if (cursor < text.length) append(text.substring(cursor))
    }

    private val headingStyle = SpanStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp)
    private val codeStyle = SpanStyle(fontFamily = FontFamily.Monospace, color = Color(0xFFB7F7D8))
}
