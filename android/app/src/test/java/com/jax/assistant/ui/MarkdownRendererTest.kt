package com.jax.assistant.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownRendererTest {
    @Test
    fun assistantMarkdownIsDisplayedWithoutControlMarkers() {
        val rendered = MarkdownRenderer.render("### **Agentic AI**\n- Fast *and* useful\n1. `code`\n[Docs](https://example.com)")

        assertTrue(rendered.text.contains("Agentic AI"))
        assertTrue(rendered.text.contains("• Fast and useful"))
        assertTrue(rendered.text.contains("1. code"))
        assertTrue(rendered.text.contains("Docs"))
        assertFalse(rendered.text.contains("**"))
        assertFalse(rendered.text.contains("`"))
        assertFalse(rendered.text.contains("###"))
    }
}
