package org.skepsun.kototoro.main.ui.compose

import org.skepsun.kototoro.core.prefs.ExplorePage

// Explore's Sources / Discover pages sit in a HorizontalPager whose page index is the enum ordinal;
// the top bar capsule and the saved page setting both follow the pager through these rules.

internal fun explorePageAt(page: Int): ExplorePage = ExplorePage.entries.getOrElse(page) { ExplorePage.SOURCES }

/** The capsule highlights the pager's current page, so it follows a swipe past the halfway point. */
internal fun exploreSwitcherSelectedId(currentPage: Int): String = explorePageAt(currentPage).name

/** Pager page to animate to when the capsule picks [itemId], or null when the pager is already headed there. */
internal fun explorePagerTargetFor(itemId: String, targetPage: Int): Int? {
    val page = ExplorePage.entries.firstOrNull { it.name == itemId }?.ordinal ?: return null
    return page.takeIf { it != targetPage }
}

/** The page to save once the pager settles, or null when the setting already matches. */
internal fun explorePageToPersist(settledPage: Int, saved: ExplorePage): ExplorePage? =
    explorePageAt(settledPage).takeIf { it != saved }
