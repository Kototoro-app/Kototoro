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

    @Test
    fun `an unfiltered dialog returns the host list itself`() {
        val sources = listOf(alpha, beta, gamma)
        assertSame(sources, filterSourceSwitchEntries(sources, "  ", DesktopSourceEnabledFilter.ALL, setOf("beta")))
    }

    @Test
    fun `the enabled filter lists only the side of the switch it names`() {
        val sources = listOf(alpha, beta, gamma)
        val disabled = setOf("beta")
        assertEquals(sources, filterSourceSwitchEntries(sources, "", DesktopSourceEnabledFilter.ALL, disabled))
        assertEquals(
            listOf(alpha, gamma),
            filterSourceSwitchEntries(sources, "", DesktopSourceEnabledFilter.ENABLED, disabled),
        )
        assertEquals(
            listOf(beta),
            filterSourceSwitchEntries(sources, "", DesktopSourceEnabledFilter.DISABLED, disabled),
        )
    }

    @Test
    fun `the query and the filter combine with and`() {
        val sources = listOf(alpha, beta, gamma)
        val disabled = setOf("beta")
        assertEquals(
            listOf(gamma),
            filterSourceSwitchEntries(sources, "ga", DesktopSourceEnabledFilter.ENABLED, disabled),
        )
        assertEquals(
            emptyList<SourceListing>(),
            filterSourceSwitchEntries(sources, "beta", DesktopSourceEnabledFilter.ENABLED, disabled),
        )
        assertEquals(
            listOf(beta),
            filterSourceSwitchEntries(sources, " BETA ", DesktopSourceEnabledFilter.DISABLED, disabled),
        )
    }

    @Test
    fun `a tapped row joins the selection and tapping it again leaves it`() {
        val once = toggleSourceSelection(emptySet(), "alpha")
        assertEquals(setOf("alpha"), once)
        assertEquals(setOf("alpha", "beta"), toggleSourceSelection(once, "beta"))
        assertEquals(setOf("beta"), toggleSourceSelection(setOf("alpha", "beta"), "alpha"))
        assertEquals(emptySet<String>(), toggleSourceSelection(setOf("beta"), "beta"))
    }

    @Test
    fun `select all covers the visible rows and toggles back to none of them`() {
        val sources = listOf(alpha, beta, gamma)
        // The query left gamma out, so 全选 must not reach it: only the rows on screen take part.
        val visible = filterSourceSwitchEntries(sources, "a", DesktopSourceEnabledFilter.ENABLED, setOf("gamma"))
        assertEquals(listOf(alpha, beta), visible)
        val picked = toggleVisibleSourceSelection(setOf("gamma"), visible)
        assertEquals(setOf("alpha", "beta", "gamma"), picked)
        assertEquals(setOf("gamma"), toggleVisibleSourceSelection(picked, visible))
    }

    @Test
    fun `an empty list is never all selected`() {
        assertEquals(emptySet<String>(), toggleVisibleSourceSelection(emptySet(), emptyList()))
        assertEquals(false, everyVisibleSourceSelected(emptySet(), emptyList()))
    }

    @Test
    fun `the select all label flips only once every visible row is picked`() {
        val sources = listOf(alpha, beta)
        assertEquals(false, everyVisibleSourceSelected(emptySet(), sources))
        assertEquals(false, everyVisibleSourceSelected(setOf("alpha"), sources))
        assertEquals(true, everyVisibleSourceSelected(setOf("alpha", "beta"), sources))
        // A selection that also holds a row the filter hid still reads 取消全选, because 全选 only speaks for the list.
        assertEquals(true, everyVisibleSourceSelected(setOf("alpha", "beta", "gamma"), sources))
    }
}
