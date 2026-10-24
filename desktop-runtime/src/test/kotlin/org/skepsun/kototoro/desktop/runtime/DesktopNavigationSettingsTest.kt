package org.skepsun.kototoro.desktop.runtime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.source.host.FileSourcePreferenceStore
import java.nio.file.Path

class DesktopNavigationSettingsTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `an empty store reads the rail order the shell has always drawn`() {
        val settings = DesktopNavigationSettings.from(emptyMap())
        assertEquals(
            listOf(
                DesktopNavItem.HOME,
                DesktopNavItem.FAVOURITES,
                DesktopNavItem.BROWSE,
                DesktopNavItem.HISTORY,
                DesktopNavItem.SUBSCRIPTIONS,
            ),
            settings.items,
        )
        assertFalse(settings.canAdd, "the default already fills all five slots")
        assertEquals(emptyList<DesktopNavItem>(), settings.available)
    }

    @Test
    fun `the list round trips through nav_main in Android's format`() {
        FileSourcePreferenceStore(directory.resolve("prefs")).use { store ->
            val preferences = DesktopNavigationPreferences(store.open("desktop_nav"))
            val saved = DesktopNavigationSettings(
                items = listOf(DesktopNavItem.FAVOURITES, DesktopNavItem.HOME, DesktopNavItem.HISTORY),
            )
            assertTrue(preferences.save(saved))
            assertEquals(saved, DesktopNavigationPreferences(store.open("desktop_nav")).current())
            assertEquals(saved, preferences.settings.value)
            val snapshot = store.open("desktop_nav").snapshot()
            assertEquals(setOf(DesktopNavigationSettings.KEY_MAIN), snapshot.keys)
            assertEquals(
                SourcePreferenceValue.Text("FAVOURITES,HOME,HISTORY"),
                snapshot[DesktopNavigationSettings.KEY_MAIN],
            )
        }
    }

    @Test
    fun `a hand edited document is deduplicated, capped and resolved case insensitively`() {
        val settings = DesktopNavigationSettings.from(
            mapOf(
                DesktopNavigationSettings.KEY_MAIN to SourcePreferenceValue.Text(
                    "subscriptions,home,HOME,unknown,the-more-tab,history,browse,favourites",
                ),
            ),
        )
        assertEquals(
            listOf(
                DesktopNavItem.SUBSCRIPTIONS,
                DesktopNavItem.HOME,
                DesktopNavItem.HISTORY,
                DesktopNavItem.BROWSE,
                DesktopNavItem.FAVOURITES,
            ),
            settings.items,
        )
    }

    @Test
    fun `a blank or unreadable document falls back to the default order`() {
        val values = mapOf(
            DesktopNavigationSettings.KEY_MAIN to SourcePreferenceValue.Text(" , ,unknown"),
            DesktopNavigationSettings.KEY_MAIN + "-wrong-type" to SourcePreferenceValue.Integer(3),
        )
        assertEquals(defaultDesktopNavItems, DesktopNavigationSettings.from(values).items)
        assertEquals(
            defaultDesktopNavItems,
            DesktopNavigationSettings.from(
                mapOf(DesktopNavigationSettings.KEY_MAIN to SourcePreferenceValue.Integer(3)),
            ).items,
        )
    }

    @Test
    fun `edits go through the shared rules`() {
        val settings = DesktopNavigationSettings(
            items = listOf(
                DesktopNavItem.HOME,
                DesktopNavItem.FAVOURITES,
                DesktopNavItem.BROWSE,
                DesktopNavItem.HISTORY,
            ),
        )
        assertTrue(settings.canAdd)
        assertEquals(listOf(DesktopNavItem.SUBSCRIPTIONS), settings.available)
        assertEquals(
            listOf(
                DesktopNavItem.HOME,
                DesktopNavItem.FAVOURITES,
                DesktopNavItem.BROWSE,
                DesktopNavItem.HISTORY,
                DesktopNavItem.SUBSCRIPTIONS,
            ),
            settings.add(DesktopNavItem.SUBSCRIPTIONS).items,
        )
        assertFalse(settings.add(DesktopNavItem.SUBSCRIPTIONS).canAdd)
        assertEquals(
            listOf(DesktopNavItem.HOME, DesktopNavItem.BROWSE, DesktopNavItem.HISTORY),
            settings.remove(DesktopNavItem.FAVOURITES).items,
        )
        assertEquals(
            listOf(DesktopNavItem.FAVOURITES, DesktopNavItem.HOME, DesktopNavItem.BROWSE, DesktopNavItem.HISTORY),
            settings.step(DesktopNavItem.FAVOURITES, direction = -1).items,
        )
        assertEquals(
            listOf(DesktopNavItem.HOME, DesktopNavItem.FAVOURITES, DesktopNavItem.BROWSE, DesktopNavItem.HISTORY),
            settings.step(DesktopNavItem.HOME, direction = -1).items,
        )
        assertEquals(
            listOf(DesktopNavItem.HISTORY, DesktopNavItem.HOME, DesktopNavItem.FAVOURITES, DesktopNavItem.BROWSE),
            settings.move(from = 3, to = 0).items,
        )
    }

    @Test
    fun `removing the last destination brings home back`() {
        val settings = DesktopNavigationSettings(items = listOf(DesktopNavItem.HISTORY))
        assertEquals(listOf(DesktopNavItem.HOME), settings.remove(DesktopNavItem.HISTORY).items)
    }
}
