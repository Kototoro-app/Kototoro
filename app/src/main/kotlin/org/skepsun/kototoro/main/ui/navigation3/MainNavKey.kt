package org.skepsun.kototoro.main.ui.navigation3

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface MainNavKey : NavKey

@Serializable
sealed interface TopLevelNavKey : MainNavKey

@Serializable
data class ContentListNavKey(
    val sourceName: String,
) : MainNavKey

@Serializable
data class DetailsNavKey(
    val entityId: Long? = null,
    val requestedProjectionId: Long? = null,
) : MainNavKey

@Serializable
data object HomeNavKey : TopLevelNavKey

@Serializable
data object HistoryNavKey : TopLevelNavKey

@Serializable
data object FavoritesNavKey : TopLevelNavKey

@Serializable
data object ExploreNavKey : TopLevelNavKey

@Serializable
data object DiscoverNavKey : TopLevelNavKey

@Serializable
data object FeedNavKey : TopLevelNavKey

@Serializable
data object LocalNavKey : TopLevelNavKey

@Serializable
data object SuggestionsNavKey : TopLevelNavKey

@Serializable
data object BookmarksNavKey : TopLevelNavKey

@Serializable
data object UpdatedNavKey : TopLevelNavKey

val allTopLevelNavKeys: List<TopLevelNavKey> = listOf(
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
)
