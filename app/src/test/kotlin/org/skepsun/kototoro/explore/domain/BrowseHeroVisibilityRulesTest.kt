package org.skepsun.kototoro.explore.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The browse page reserved the hero band even when the hero had nothing to paint, leaving a dead band
 * of empty space above the source list (most visible offline, where recommendations never arrive).
 */
class BrowseHeroVisibilityRulesTest {

    @Test
    fun `reserves space for the items it has`() {
        assertTrue(
            shouldReserveBrowseHeroSpace(
                trackingRecommendationsEnabled = true,
                heroItemCount = 6,
                isDiscoverLoading = false,
            ),
        )
    }

    @Test
    fun `reserves space while the skeleton is up`() {
        assertTrue(
            shouldReserveBrowseHeroSpace(
                trackingRecommendationsEnabled = true,
                heroItemCount = 0,
                isDiscoverLoading = true,
            ),
        )
    }

    @Test
    fun `an empty hero reserves nothing`() {
        assertFalse(
            shouldReserveBrowseHeroSpace(
                trackingRecommendationsEnabled = true,
                heroItemCount = 0,
                isDiscoverLoading = false,
            ),
        )
    }

    @Test
    fun `tracking recommendations off means no hero at all`() {
        assertFalse(
            shouldReserveBrowseHeroSpace(
                trackingRecommendationsEnabled = false,
                heroItemCount = 6,
                isDiscoverLoading = true,
            ),
        )
    }

    @Test
    fun `the block paints nothing when it has nothing to paint`() {
        assertFalse(browseHeroHasContent(heroItemCount = 0, isDiscoverLoading = false))
        assertTrue(browseHeroHasContent(heroItemCount = 3, isDiscoverLoading = false))
        assertTrue(browseHeroHasContent(heroItemCount = 0, isDiscoverLoading = true))
    }
}
