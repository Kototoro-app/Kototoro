package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.source.SourcePreferenceValue

/**
 * A stored `nav_main` may have been written with Android's destination names. The rail's vocabulary differs
 * (`FAVOURITES`/`BROWSE`/`SUBSCRIPTIONS` here), so reading such a document must translate it instead of treating it as
 * unknown and resetting the whole order.
 */
class DesktopNavigationMigrationTest {

    private fun read(raw: String): List<DesktopNavItem> = DesktopNavigationSettings.from(
        mapOf(DesktopNavigationSettings.KEY_MAIN to SourcePreferenceValue.Text(raw)),
    ).items

    @Test
    fun `android spelling of the same pages resolves to this rail's destinations`() {
        assertEquals(
            listOf(DesktopNavItem.HOME, DesktopNavItem.FAVOURITES, DesktopNavItem.BROWSE, DesktopNavItem.SUBSCRIPTIONS),
            read("HOME,FAVORITES,EXPLORE,FEED"),
        )
    }

    @Test
    fun `a mixed document keeps every known entry in its stored order`() {
        assertEquals(
            listOf(DesktopNavItem.HISTORY, DesktopNavItem.BROWSE, DesktopNavItem.UPDATED, DesktopNavItem.HOME),
            read("HISTORY,EXPLORE,UPDATED,HOME"),
        )
    }

    @Test
    fun `an alias and its own name are one destination, not two`() {
        assertEquals(listOf(DesktopNavItem.FAVOURITES), read("FAVORITES,FAVOURITES"))
        assertEquals(listOf(DesktopNavItem.BROWSE), read("explore, BROWSE"))
    }

    @Test
    fun `whitespace and casing are the writer's business`() {
        assertEquals(listOf(DesktopNavItem.HOME, DesktopNavItem.BROWSE), read(" home , explore "))
    }

    @Test
    fun `pages this rail does not have are skipped on purpose`() {
        assertNull(DesktopNavigationSettings.resolveItem("LOCAL"))
        assertNull(DesktopNavigationSettings.resolveItem("DISCOVER"))
        // Skipping them does not disturb the entries around them.
        assertEquals(listOf(DesktopNavItem.HOME, DesktopNavItem.HISTORY), read("LOCAL,HOME,DISCOVER,HISTORY"))
        // A document made only of such pages has nothing renderable, so the rail falls back to its default.
        assertEquals(defaultDesktopNavItems, read("LOCAL,DISCOVER"))
    }

    @Test
    fun `an unrecognised key never costs the rest of the document`() {
        assertEquals(
            listOf(DesktopNavItem.HOME, DesktopNavItem.SUBSCRIPTIONS, DesktopNavItem.HISTORY),
            read("HOME,WIDGET,SUBSCRIPTIONS,HISTORY"),
        )
    }
}
