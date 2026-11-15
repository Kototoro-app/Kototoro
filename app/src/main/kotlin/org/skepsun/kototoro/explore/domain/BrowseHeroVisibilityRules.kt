package org.skepsun.kototoro.explore.domain

/**
 * Whether the browse page should reserve the tall hero band above the source list.
 *
 * The hero is an overlay: the list starts with a spacer as tall as the hero so the first row scrolls
 * out from under it. When the hero has nothing to draw — recommendations enabled but no items came
 * back, e.g. offline — that reservation is just a dead band of empty space at the top of the page,
 * so the space must only be reserved when the hero will actually paint something.
 */
fun shouldReserveBrowseHeroSpace(
    trackingRecommendationsEnabled: Boolean,
    heroItemCount: Int,
    isDiscoverLoading: Boolean,
): Boolean = trackingRecommendationsEnabled && (heroItemCount > 0 || isDiscoverLoading)

/**
 * Whether the hero block has anything to paint at all. The skeleton counts as content: it is the
 * page saying the data is on its way.
 */
fun browseHeroHasContent(heroItemCount: Int, isDiscoverLoading: Boolean): Boolean =
    heroItemCount > 0 || isDiscoverLoading
