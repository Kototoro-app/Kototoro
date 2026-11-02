package org.skepsun.kototoro.desktop.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.desktop.runtime.DesktopUpdateChannel

/**
 * Android's about screen has one update switch and one group of support links; this pins down how the Windows port
 * reads them: which repository the copy follows, who may move that switch, and which addresses the rows open.
 */
class DesktopAboutLinksTest {
    private val stable = DesktopAppVersion("2.3.1", "2.3.1", DesktopUpdateChannel.RELEASE)
    private val nightly = DesktopAppVersion("N20261024", "20261024", DesktopUpdateChannel.NIGHTLY)

    @Test
    fun `a release copy follows its own channel until the unstable switch is on`() {
        assertEquals(DesktopUpdateChannel.RELEASE, desktopUpdateChannel(stable, unstable = false))
        assertEquals(DesktopUpdateChannel.NIGHTLY, desktopUpdateChannel(stable, unstable = true))
    }

    @Test
    fun `a nightly copy stays on the nightly channel even when the switch is off`() {
        // Handing a nightly install a release package would be a downgrade, so its own channel always wins.
        assertEquals(DesktopUpdateChannel.NIGHTLY, desktopUpdateChannel(nightly, unstable = false))
        assertEquals(DesktopUpdateChannel.NIGHTLY, desktopUpdateChannel(nightly, unstable = true))
    }

    @Test
    fun `only a release copy may change the unstable switch`() {
        assertTrue(desktopUnstableEditable(stable))
        assertFalse(desktopUnstableEditable(nightly))
    }

    @Test
    fun `the support group lists Android's five rows with absolute addresses`() {
        val links = desktopAboutLinks()

        assertEquals(listOf("manual", "github", "donate", "afdian", "discord"), links.map { it.key })
        assertEquals(links.size, links.map { it.key }.toSet().size)
        links.forEach { link ->
            assertTrue(link.title.isNotBlank(), "${link.key} has no label")
            assertTrue(link.url.startsWith("https://"), "${link.key} is not an absolute https address: ${link.url}")
            assertTrue(link.openable, "${link.key} would be greyed out: ${link.url}")
        }
    }
}
