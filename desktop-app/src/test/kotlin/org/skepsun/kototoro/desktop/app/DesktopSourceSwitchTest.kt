package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef

/**
 * Android's per-source switch (`SourceEntity.enabled`) in the Windows state: [DesktopAppState.sources] keeps every
 * installed source so stored works still resolve their titles, while browsing, search, recommendations and migration
 * ask [DesktopAppState.enabledSources].
 */
class DesktopSourceSwitchTest {
    private fun listing(name: String) =
        SourceListing(SourceRef(name, "zh", "MANGA"), name.uppercase(), SourceEcosystem.MIHON, true)

    private val alpha = listing("alpha")
    private val beta = listing("beta")
    private val gamma = listing("gamma")

    @Test
    fun `nothing disabled keeps the host's own list untouched`() {
        val state = DesktopAppState(sources = listOf(alpha, beta, gamma))
        assertSame(state.sources, state.enabledSources)
    }

    @Test
    fun `a disabled source leaves the enabled list and keeps its place when switched back on`() {
        val off = DesktopAppState(sources = listOf(alpha, beta, gamma), disabledSources = setOf("beta"))
        assertEquals(listOf(alpha, gamma), off.enabledSources)
        assertEquals(listOf(alpha, beta, gamma), off.sources)
        val on = off.copy(disabledSources = emptySet())
        assertEquals(listOf(alpha, beta, gamma), on.enabledSources)
    }

    @Test
    fun `every source can be switched off without dropping the installed list`() {
        val state = DesktopAppState(sources = listOf(alpha, beta), disabledSources = setOf("alpha", "beta"))
        assertEquals(emptyList<SourceListing>(), state.enabledSources)
        assertEquals(2, state.sources.size)
    }

    @Test
    fun `names that no longer exist are simply ignored`() {
        val state = DesktopAppState(sources = listOf(alpha), disabledSources = setOf("gone"))
        assertEquals(listOf(alpha), state.enabledSources)
    }
}
