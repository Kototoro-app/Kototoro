package org.skepsun.kototoro.favourites.ui.container

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.core.model.FavouriteCategory.Companion.NO_ID
import org.skepsun.kototoro.explore.ui.model.BrowseGroupTab

/**
 * Tabs and the empty state of the favourites host. "No favourites yet" must only show when
 * there is nothing to filter: a quick filter that happens to hide every work (e.g. the
 * Downloaded filter applied automatically while offline) has to keep the tabs, so the
 * page can show its quick-filter row and "nothing found / reset filter" instead.
 */
class FavoritesHostUiStateTest {

    private val categories = listOf(
        FavouriteTabModel(1L, "Reading"),
        FavouriteTabModel(2L, "Plan to read"),
    )

    @Test
    fun `no categories and no all tab is the empty library`() {
        val state = buildFavoritesHostUiState(
            categories = emptyList(),
            categoryCounts = emptyMap(),
            groupTab = BrowseGroupTab.All,
            hasSourceTags = false,
            hasQuickFilters = false,
            showAll = false,
        )

        assertTrue(state.isEmpty)
    }

    @Test
    fun `space filter hides categories without matching works`() {
        val state = buildFavoritesHostUiState(
            categories = categories,
            categoryCounts = mapOf(NO_ID to 3, 1L to 3),
            groupTab = BrowseGroupTab.Content,
            hasSourceTags = false,
            hasQuickFilters = false,
            showAll = true,
        )

        assertEquals(listOf(NO_ID, 1L), state.categories.map { it.id })
        assertFalse(state.isEmpty)
    }

    @Test
    fun `nothing in the space without quick filters is still empty`() {
        val state = buildFavoritesHostUiState(
            categories = categories,
            categoryCounts = emptyMap(),
            groupTab = BrowseGroupTab.Content,
            hasSourceTags = false,
            hasQuickFilters = false,
            showAll = true,
        )

        assertTrue(state.isEmpty)
    }

    @Test
    fun `quick filters hiding every work keep the tabs`() {
        val state = buildFavoritesHostUiState(
            categories = categories,
            categoryCounts = emptyMap(),
            groupTab = BrowseGroupTab.Content,
            hasSourceTags = false,
            hasQuickFilters = true,
            showAll = true,
        )

        assertFalse(state.isEmpty)
        assertEquals(listOf(NO_ID, 1L, 2L), state.categories.map { it.id })
    }
}
