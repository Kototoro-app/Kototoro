package org.skepsun.kototoro.explore.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.R
import org.skepsun.kototoro.explore.ui.DiscoveryFeedBulkAddPlan
import org.skepsun.kototoro.explore.ui.DiscoveryFeedBulkAddResult
import org.skepsun.kototoro.explore.ui.DiscoveryFeedUiState
import org.skepsun.kototoro.list.ui.compose.SelectionAction
import org.skepsun.kototoro.main.ui.compose.explorePagerUserScrollEnabled

class DiscoveryFeedSelectionUiTest {

    @Test
    fun `selection bar shows only while selecting with select all and add to library`() {
        assertNull(discoveryFeedSelectionTopBarState(DiscoveryFeedUiState(), {}, {}))

        val state = discoveryFeedSelectionTopBarState(
            uiState = DiscoveryFeedUiState(selectedIds = setOf(1L, 2L), selectionRowId = "row"),
            onClearSelection = {},
            onActionClick = {},
        )!!
        assertEquals(2, state.selectedCount)
        assertFalse(state.isSingleSelection)
        assertEquals(setOf(SelectionAction.SELECT_ALL, SelectionAction.FAVOURITE), state.supportedActions)
        assertFalse(state.includeContextualActions)
    }

    @Test
    fun `pager swiping is off while either page is selecting`() {
        assertTrue(explorePagerUserScrollEnabled(sourceSelectionActive = false, feedSelectionActive = false))
        assertFalse(explorePagerUserScrollEnabled(sourceSelectionActive = false, feedSelectionActive = true))
        assertFalse(explorePagerUserScrollEnabled(sourceSelectionActive = true, feedSelectionActive = false))
    }

    @Test
    fun `bulk add messages report added and skipped counts`() {
        assertEquals(
            DiscoveryFeedMessage(R.string.discovery_feed_bulk_all_in_library, listOf(3)),
            discoveryFeedBulkAddPlanMessage(DiscoveryFeedBulkAddPlan(toAdd = emptyList(), alreadyInLibrary = 3)),
        )
        assertNull(discoveryFeedBulkAddResultMessage(DiscoveryFeedBulkAddResult(added = 0, skipped = 2)))
        assertEquals(
            DiscoveryFeedMessage(R.string.discovery_feed_bulk_added, listOf(4)),
            discoveryFeedBulkAddResultMessage(DiscoveryFeedBulkAddResult(added = 4, skipped = 0)),
        )
        assertEquals(
            DiscoveryFeedMessage(R.string.discovery_feed_bulk_added_skipped, listOf(4, 2)),
            discoveryFeedBulkAddResultMessage(DiscoveryFeedBulkAddResult(added = 4, skipped = 2)),
        )
    }
}
