package org.skepsun.kototoro.main.ui.compose

import androidx.annotation.IdRes
import org.skepsun.kototoro.R
import org.skepsun.kototoro.main.ui.navigation3.BookmarksNavKey
import org.skepsun.kototoro.main.ui.navigation3.DiscoverNavKey
import org.skepsun.kototoro.main.ui.navigation3.ExploreNavKey
import org.skepsun.kototoro.main.ui.navigation3.FavoritesNavKey
import org.skepsun.kototoro.main.ui.navigation3.FeedNavKey
import org.skepsun.kototoro.main.ui.navigation3.HistoryNavKey
import org.skepsun.kototoro.main.ui.navigation3.HomeNavKey
import org.skepsun.kototoro.main.ui.navigation3.LocalNavKey
import org.skepsun.kototoro.main.ui.navigation3.SuggestionsNavKey
import org.skepsun.kototoro.main.ui.navigation3.TopLevelNavKey
import org.skepsun.kototoro.main.ui.navigation3.UpdatedNavKey

object AppRouteNames {
    const val MAIN_SHELL = "main_shell"
}

/**
 * Prefix for the per-space liquid-glass / shell owner key shared between the
 * [MainShellScene] backdrop registration and the [KototoroApp] root backdrop
 * lookup. After the Navigation 3 cutover the shell is the only root scene per
 * space, so the owner key no longer derives from a Navigation 2 entry id.
 */
internal const val MAIN_SHELL_BACKDROP_OWNER_PREFIX = "main_shell"

fun encodeEntityOrganizeSelection(ids: Set<Long>): String {
    return ids.sorted().joinToString(separator = ",")
}

fun parseEntityOrganizeSelection(value: String): Set<Long> {
    return value
        .split(',')
        .asSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull(String::toLongOrNull)
        .toSet()
}

fun topLevelKeyForBottomNavItem(@IdRes itemId: Int): TopLevelNavKey = when (itemId) {
    R.id.nav_home -> HomeNavKey
    R.id.nav_history -> HistoryNavKey
    R.id.nav_favorites -> FavoritesNavKey
    R.id.nav_explore -> ExploreNavKey
    R.id.nav_discover -> DiscoverNavKey
    R.id.nav_feed -> FeedNavKey
    R.id.nav_local -> LocalNavKey
    R.id.nav_suggestions -> SuggestionsNavKey
    R.id.nav_bookmarks -> BookmarksNavKey
    R.id.nav_updated -> UpdatedNavKey
    else -> HomeNavKey
}

fun bottomNavItemIdForTopLevelKey(key: TopLevelNavKey): Int = when (key) {
    HomeNavKey -> R.id.nav_home
    HistoryNavKey -> R.id.nav_history
    FavoritesNavKey -> R.id.nav_favorites
    ExploreNavKey -> R.id.nav_explore
    DiscoverNavKey -> R.id.nav_discover
    FeedNavKey -> R.id.nav_feed
    LocalNavKey -> R.id.nav_local
    SuggestionsNavKey -> R.id.nav_suggestions
    BookmarksNavKey -> R.id.nav_bookmarks
    UpdatedNavKey -> R.id.nav_updated
}
