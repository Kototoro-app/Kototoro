package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.extensions.SelectedSourcesNsfwAction
import org.skepsun.kototoro.core.extensions.SourceNsfwOverrideSet
import org.skepsun.kototoro.core.source.SourceEcosystem
import org.skepsun.kototoro.core.source.SourceListing
import org.skepsun.kototoro.core.source.SourceRef

/**
 * Android's per-source switch (`SourceEntity.enabled`) in the Windows state: [DesktopAppState.sources] keeps every
 * installed source so stored works still resolve their titles, while browsing, search, recommendations and migration
 * ask [DesktopAppState.enabledSources].
 */
class DesktopSourceSwitchTest {
    private fun listing(name: String, contentType: String = "MANGA") =
        SourceListing(SourceRef(name, "zh", contentType), name.uppercase(), SourceEcosystem.MIHON, true)

    private val alpha = listing("alpha")
    private val beta = listing("beta")
    private val gamma = listing("gamma")
    private val hentai = listing("hentai", "HENTAI_MANGA")

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

    @Test
    fun `the nsfw chip reads the effective flag of the selection`() {
        val sources = listOf(alpha, hentai, beta)
        // Nothing adult picked: marking is the only thing the chip can mean.
        assertEquals(SelectedSourcesNsfwAction.SET_NSFW,
            selectedSourcesNsfwAction(setOf("alpha", "beta"), sources, SourceNsfwOverrideSet()))
        // Everything adult: the chip offers to clear the flag instead.
        assertEquals(SelectedSourcesNsfwAction.SET_SFW,
            selectedSourcesNsfwAction(setOf("hentai"), sources, SourceNsfwOverrideSet()))
        // A mixed selection asks which way to go.
        assertEquals(SelectedSourcesNsfwAction.CHOOSE,
            selectedSourcesNsfwAction(setOf("alpha", "hentai"), sources, SourceNsfwOverrideSet()))
        assertEquals(SelectedSourcesNsfwAction.NONE,
            selectedSourcesNsfwAction(emptySet(), sources, SourceNsfwOverrideSet()))
    }

    @Test
    fun `a forced flag counts on the side the override put it`() {
        val sources = listOf(alpha, hentai, beta)
        // Both rows are adult as far as the content type says, but alpha was forced SFW, so the pair is still mixed.
        assertEquals(SelectedSourcesNsfwAction.CHOOSE, selectedSourcesNsfwAction(setOf("alpha", "hentai"), sources,
            SourceNsfwOverrideSet(sfw = setOf("alpha"))))
        // A manga forced adult joins the adult row, so the chip clears the flag for both.
        assertEquals(SelectedSourcesNsfwAction.SET_SFW, selectedSourcesNsfwAction(setOf("alpha", "hentai"), sources,
            SourceNsfwOverrideSet(nsfw = setOf("alpha"))))
        // And the other way around: an adult row forced SFW joins the plain one, so the chip marks both.
        assertEquals(SelectedSourcesNsfwAction.SET_NSFW, selectedSourcesNsfwAction(setOf("alpha", "hentai"), sources,
            SourceNsfwOverrideSet(sfw = setOf("hentai"))))
    }

    @Test
    fun `each nsfw action names android's string`() {
        assertEquals("unified_sources_mark_nsfw", selectedSourcesNsfwLabel(SelectedSourcesNsfwAction.SET_NSFW))
        assertEquals("unified_sources_unmark_nsfw", selectedSourcesNsfwLabel(SelectedSourcesNsfwAction.SET_SFW))
        assertEquals("unified_sources_set_nsfw", selectedSourcesNsfwLabel(SelectedSourcesNsfwAction.CHOOSE))
        assertEquals("unified_sources_mark_nsfw", selectedSourcesNsfwLabel(SelectedSourcesNsfwAction.NONE))
    }
}
