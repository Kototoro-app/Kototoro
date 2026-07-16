package org.skepsun.kototoro.core.prefs

import androidx.annotation.DrawableRes
import androidx.annotation.IdRes
import androidx.annotation.Keep
import androidx.annotation.StringRes
import org.skepsun.kototoro.R

const val MAX_MAIN_NAV_ITEM_COUNT = 5

internal fun Iterable<NavItem>.limitMainNavigationItems(): List<NavItem> =
	distinct().take(MAX_MAIN_NAV_ITEM_COUNT)

@Keep
enum class NavItem(
	@IdRes val id: Int,
	@StringRes val title: Int,
	@DrawableRes val icon: Int,
) {

	HOME(R.id.nav_home, R.string.home, R.drawable.ic_home_selector),
	HISTORY(R.id.nav_history, R.string.history, R.drawable.ic_history_selector),
	FAVORITES(R.id.nav_favorites, R.string.favourites, R.drawable.ic_favourites_selector),
	LOCAL(R.id.nav_local, R.string.on_device, R.drawable.ic_storage_selector),
	EXPLORE(R.id.nav_explore, R.string.explore, R.drawable.ic_explore_selector),
	DISCOVER(R.id.nav_discover, R.string.discover, R.drawable.ic_discover_selector),
	SUGGESTIONS(R.id.nav_suggestions, R.string.suggestions, R.drawable.ic_suggestion_selector),
	FEED(R.id.nav_feed, R.string.feed, R.drawable.ic_feed_selector),
	UPDATED(R.id.nav_updated, R.string.updated, R.drawable.ic_updated_selector),
	BOOKMARKS(R.id.nav_bookmarks, R.string.bookmarks, R.drawable.ic_bookmark_selector),
	;

	fun isAvailable(settings: AppSettings): Boolean = when (this) {
		SUGGESTIONS -> settings.isSuggestionsEnabled
		UPDATED, FEED -> settings.isTrackerEnabled
		else -> true
	}
}
