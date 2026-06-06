package org.skepsun.kototoro.main.ui.compose

import androidx.annotation.IdRes
import androidx.lifecycle.SavedStateHandle
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.skepsun.kototoro.R

object AppRouteNames {
    const val HOME = "home"
    const val DISCOVER = "discover"
    const val HISTORY = "history"
    const val FAVORITES = "favorites"
    const val ENTITY_ORGANIZE = "entity_organize"
    const val EXPLORE = "explore"
    const val FEED = "feed"
    const val LOCAL = "local"
    const val SUGGESTIONS = "suggestions"
    const val BOOKMARKS = "bookmarks"
    const val UPDATED = "updated"
    const val SEARCH = "search"
    const val DETAILS = "details"
}

internal const val RETURN_HOME_ON_BACK_KEY = "return_home_on_back"
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
@SerialName(AppRouteNames.HOME)
data object HomeRoute

@Serializable
@SerialName(AppRouteNames.DISCOVER)
data object DiscoverRoute

@Serializable
@SerialName(AppRouteNames.HISTORY)
data object HistoryRoute

@Serializable
@SerialName(AppRouteNames.FAVORITES)
data object FavoritesRoute

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
@SerialName(AppRouteNames.EXPLORE)
data object ExploreRoute

@Serializable
@SerialName(AppRouteNames.FEED)
data object FeedRoute

@Serializable
@SerialName(AppRouteNames.LOCAL)
data object LocalRoute

@Serializable
@SerialName(AppRouteNames.SUGGESTIONS)
data object SuggestionsRoute

@Serializable
@SerialName(AppRouteNames.BOOKMARKS)
data object BookmarksRoute

@Serializable
@SerialName(AppRouteNames.UPDATED)
data object UpdatedRoute

@Serializable
@SerialName(AppRouteNames.DETAILS)
data object DetailsRoute

fun routeForBottomNavItem(@IdRes itemId: Int): Any = when (itemId) {
    R.id.nav_home -> HomeRoute
    R.id.nav_history -> HistoryRoute
    R.id.nav_favorites -> FavoritesRoute
    R.id.nav_explore -> ExploreRoute
    R.id.nav_discover -> DiscoverRoute
    R.id.nav_feed -> FeedRoute
    R.id.nav_local -> LocalRoute
    R.id.nav_suggestions -> SuggestionsRoute
    R.id.nav_bookmarks -> BookmarksRoute
    R.id.nav_updated -> UpdatedRoute
    else -> HomeRoute
}
