package org.skepsun.kototoro.main.ui.compose

import androidx.annotation.IdRes
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
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
    const val ENTITY_ORGANIZE = "entity_organize"
    const val SEARCH = "search"
    const val CONTENT_LIST = "content_list"
    const val DETAILS = "details"
}

internal const val ENTITY_ORGANIZE_RESULT_REFRESH_KEY = "entity_organize_result_refresh"
internal const val ENTITY_ORGANIZE_RESULT_MESSAGE_KEY = "entity_organize_result_message"

internal fun consumeEntityOrganizeRefreshResult(savedStateHandle: SavedStateHandle): Boolean {
    val shouldRefresh = savedStateHandle.get<Boolean>(ENTITY_ORGANIZE_RESULT_REFRESH_KEY) == true
    if (shouldRefresh) {
        savedStateHandle[ENTITY_ORGANIZE_RESULT_REFRESH_KEY] = false
    }
    return shouldRefresh
}

internal fun consumeEntityOrganizeMessageResult(savedStateHandle: SavedStateHandle): String? {
    val message = savedStateHandle.get<String>(ENTITY_ORGANIZE_RESULT_MESSAGE_KEY)
        ?.takeIf { it.isNotBlank() }
    if (message != null) {
        savedStateHandle[ENTITY_ORGANIZE_RESULT_MESSAGE_KEY] = null
    }
    return message
}

@Serializable
@SerialName(AppRouteNames.MAIN_SHELL)
data object MainShellRoute

@Serializable
@SerialName(AppRouteNames.ENTITY_ORGANIZE)
data class EntityOrganizeRoute(
    val selectedContentIds: String = "",
)

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

@Serializable
@SerialName(AppRouteNames.CONTENT_LIST)
data class ContentListRoute(
    val sourceName: String,
)

@Serializable
@SerialName(AppRouteNames.DETAILS)
data object DetailsRoute

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

fun routeForTopLevelKey(key: TopLevelNavKey): Any = when (key) {
    HomeNavKey,
    HistoryNavKey,
    FavoritesNavKey,
    ExploreNavKey,
    DiscoverNavKey,
    FeedNavKey,
    LocalNavKey,
    SuggestionsNavKey,
    BookmarksNavKey,
    UpdatedNavKey,
    -> MainShellRoute
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
