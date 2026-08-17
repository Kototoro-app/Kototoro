package org.skepsun.kototoro.core.network.webview

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PageScriptInjectionStateTest {

    @Test
    fun `injects once per main document navigation`() {
        val state = PageScriptInjectionState()

        state.onPageStarted("https://example.org/challenge")
        assertTrue(state.shouldInject("https://example.org/challenge"))
        assertFalse(state.shouldInject("https://example.org/challenge"))

        state.onPageStarted("https://example.org/home")
        assertTrue(state.shouldInject("https://example.org/home"))
        assertFalse(state.shouldInject("https://example.org/home"))
    }

    @Test
    fun `stale page finish cannot consume newer navigation injection`() {
        val state = PageScriptInjectionState()

        state.onPageStarted("https://example.org/challenge")
        state.onPageStarted("https://example.org/home")

        assertFalse(state.shouldInject("https://example.org/challenge"))
        assertTrue(state.shouldInject("https://example.org/home"))
    }
}
