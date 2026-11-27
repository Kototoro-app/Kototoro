package org.skepsun.kototoro.explore.ui.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.skepsun.kototoro.explore.data.DiscoveryFeedRow
import org.skepsun.kototoro.main.ui.compose.LayeredTopBarOverrideState
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedAction
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedSwitcherItem
import org.skepsun.kototoro.main.ui.compose.TopBarSegmentedSwitcherState
import org.skepsun.kototoro.main.ui.compose.overrideStateEquivalent

class DiscoveryFeedUiTest {

    @Test
    fun `suggestDiscoveryFeedDisplayName combines source with saved filter or query`() {
        assertEquals(
            "MangaDex",
            suggestDiscoveryFeedDisplayName("MangaDex", savedFilterName = null, query = null),
        )
        assertEquals(
            "MangaDex · Top Action",
            suggestDiscoveryFeedDisplayName("MangaDex", savedFilterName = "Top Action", query = "ignored"),
        )
        assertEquals(
            "MangaDex · isekai",
            suggestDiscoveryFeedDisplayName("MangaDex", savedFilterName = null, query = " isekai "),
        )
        assertEquals(
            "isekai",
            suggestDiscoveryFeedDisplayName("  ", savedFilterName = null, query = "isekai"),
        )
    }

    @Test
    fun `buildDiscoveryFeedRowSubtitle omits redundant source name and formats filter or query`() {
        val defaultRowSameTitle = DiscoveryFeedRow(
            id = "1",
            sourceName = "MANGADEX",
            displayName = "MangaDex",
        )
        assertNull(
            buildDiscoveryFeedRowSubtitle(
                sourceTitle = "MangaDex",
                row = defaultRowSameTitle,
                savedFilterBadge = "Saved filter",
            ),
        )

        val customNamedRow = DiscoveryFeedRow(
            id = "2",
            sourceName = "MANGADEX",
            savedFilterId = 7,
            displayName = "Weekly Picks",
        )
        assertEquals(
            "MangaDex · Saved filter",
            buildDiscoveryFeedRowSubtitle(
                sourceTitle = "MangaDex",
                row = customNamedRow,
                savedFilterBadge = "Saved filter",
            ),
        )

        val queryRow = DiscoveryFeedRow(
            id = "3",
            sourceName = "MANGADEX",
            query = "comedy",
            displayName = "MangaDex",
        )
        assertEquals(
            "\"comedy\"",
            buildDiscoveryFeedRowSubtitle(
                sourceTitle = "MangaDex",
                row = queryRow,
                savedFilterBadge = "Saved filter",
            ),
        )
    }

    @Test
    fun `overrideStateEquivalent treats TopBarSegmentedSwitcherState with new lambdas as equivalent`() {
        val items = listOf(
            TopBarSegmentedSwitcherItem("SOURCES", "Sources"),
            TopBarSegmentedSwitcherItem("DISCOVERY", "Discover"),
        )
        val first = LayeredTopBarOverrideState(
            segmentedSwitcherState = TopBarSegmentedSwitcherState(
                items = items,
                selectedItemId = "DISCOVERY",
                onItemSelected = { _ -> },
                hideSourceFilters = true,
                leadingExtraActions = listOf(
                    TopBarSegmentedAction(
                        id = "add",
                        iconRes = 1,
                        contentDescriptionRes = 2,
                        onClick = {},
                    ),
                ),
            ),
            keepTopBarPinned = true,
        )
        val second = LayeredTopBarOverrideState(
            segmentedSwitcherState = TopBarSegmentedSwitcherState(
                items = items,
                selectedItemId = "DISCOVERY",
                onItemSelected = { _ -> },
                hideSourceFilters = true,
                leadingExtraActions = listOf(
                    TopBarSegmentedAction(
                        id = "add",
                        iconRes = 1,
                        contentDescriptionRes = 2,
                        onClick = {},
                    ),
                ),
            ),
            keepTopBarPinned = true,
        )
        val differentSelection = first.copy(
            segmentedSwitcherState = first.segmentedSwitcherState?.copy(selectedItemId = "SOURCES"),
        )
        val differentPinned = first.copy(keepTopBarPinned = false)

        assertTrue(overrideStateEquivalent(first, second))
        assertFalse(overrideStateEquivalent(first, differentSelection))
        assertFalse(overrideStateEquivalent(first, differentPinned))
    }
}
