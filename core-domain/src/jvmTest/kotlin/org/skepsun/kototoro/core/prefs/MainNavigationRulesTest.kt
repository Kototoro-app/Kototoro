package org.skepsun.kototoro.core.prefs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainNavigationRulesTest {

    private val defaults = listOf("HOME", "HISTORY", "FAVOURITES", "EXPLORE", "FEED")

    @Test
    fun `the stored list is deduplicated and capped`() {
        assertEquals(
            listOf("HOME", "HISTORY", "FAVOURITES", "EXPLORE", "FEED"),
            MainNavigationRules.limit(listOf("HOME", "HISTORY", "HOME", "FAVOURITES", "EXPLORE", "FEED", "LOCAL")),
        )
        assertEquals(5, MainNavigationRules.MAX_ITEMS)
    }

    @Test
    fun `moving shifts the items in between like a drag`() {
        val items = listOf("HOME", "HISTORY", "FAVOURITES", "EXPLORE")
        assertEquals(
            listOf("HOME", "FAVOURITES", "EXPLORE", "HISTORY"),
            MainNavigationRules.move(items, from = 1, to = 3),
        )
        assertEquals(
            listOf("EXPLORE", "HOME", "HISTORY", "FAVOURITES"),
            MainNavigationRules.move(items, from = 3, to = 0),
        )
        assertEquals(items, MainNavigationRules.move(items, from = 2, to = 2))
        assertEquals(items, MainNavigationRules.move(items, from = 9, to = 0))
    }

    @Test
    fun `one step stops at either end`() {
        val items = listOf("HOME", "HISTORY", "FAVOURITES")
        assertEquals(
            listOf("HISTORY", "HOME", "FAVOURITES"),
            MainNavigationRules.step(items, "HOME", direction = 1),
        )
        assertEquals(
            listOf("HOME", "FAVOURITES", "HISTORY"),
            MainNavigationRules.step(items, "HISTORY", direction = 1),
        )
        assertEquals(items, MainNavigationRules.step(items, "HOME", direction = -1))
        assertEquals(items, MainNavigationRules.step(items, "MISSING", direction = 1))
    }

    @Test
    fun `adding respects the cap and the existing entries`() {
        val full = listOf("HOME", "HISTORY", "FAVOURITES", "EXPLORE", "FEED")
        assertEquals(full, MainNavigationRules.add(full, "LOCAL"))
        assertEquals(full, MainNavigationRules.add(full, "HOME"))
        val partial = full.take(4)
        assertEquals(partial + "LOCAL", MainNavigationRules.add(partial, "LOCAL"))
        assertTrue(MainNavigationRules.canAdd(partial))
        assertFalse(MainNavigationRules.canAdd(full))
    }

    @Test
    fun `removing the last item falls back to the default one`() {
        assertEquals(
            listOf("HISTORY", "FAVOURITES"),
            MainNavigationRules.remove(listOf("HOME", "HISTORY", "FAVOURITES"), "HOME", fallback = "HOME"),
        )
        assertEquals(
            listOf("HOME"),
            MainNavigationRules.remove(listOf("LOCAL"), "LOCAL", fallback = "HOME"),
        )
    }

    @Test
    fun `the picker offers what is not configured and not excluded`() {
        val all = listOf("HOME", "HISTORY", "FAVOURITES", "LOCAL", "EXPLORE", "DISCOVER", "FEED")
        assertEquals(
            listOf("LOCAL", "EXPLORE", "FEED"),
            MainNavigationRules.available(
                all,
                configured = listOf("HOME", "HISTORY", "FAVOURITES"),
                excluded = listOf("DISCOVER"),
            ),
        )
    }

    @Test
    fun `a stored string keeps its order and drops what it cannot resolve`() {
        val known = setOf("HOME", "HISTORY", "FAVOURITES", "LOCAL", "EXPLORE", "FEED", "DISCOVER")
        fun parse(raw: String?) = MainNavigationRules.parse(
            raw = raw,
            resolve = { key -> key.takeIf { it in known } },
            fallback = defaults,
            excluded = listOf("DISCOVER"),
        )
        assertEquals(
            listOf("FEED", "HOME", "LOCAL"),
            parse("FEED, HOME,DOWNLOADS?,LOCAL"),
        )
        assertEquals(defaults, parse("DISCOVER"))
        assertEquals(defaults, parse(""))
        assertEquals(defaults, parse(null))
        assertEquals(listOf("HOME", "HISTORY"), parse("HOME,HISTORY,HOME"))
        assertEquals(
            "FEED,HOME,LOCAL",
            MainNavigationRules.format(parse("FEED,HOME,LOCAL")),
        )
    }
}
