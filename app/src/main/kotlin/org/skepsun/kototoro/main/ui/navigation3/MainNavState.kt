package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.rememberNavBackStack

@Stable
class MainNavState internal constructor(
    private val readSelectedTopLevel: () -> TopLevelNavKey,
    private val writeSelectedTopLevel: (TopLevelNavKey) -> Unit,
    private val stacks: Map<TopLevelNavKey, NavBackStack<MainNavKey>>,
) {
    val selectedTopLevel: TopLevelNavKey
        get() = readSelectedTopLevel()

    fun navigateTopLevel(key: TopLevelNavKey) {
        writeSelectedTopLevel(key)
    }

    fun replaceCurrentStack(keys: List<MainNavKey>) {
        replaceStack(selectedTopLevel, keys)
    }

    fun replaceStack(key: TopLevelNavKey, keys: List<MainNavKey>) {
        val stack = stackFor(key)
        stack.clear()
        stack.addAll(keys)
    }

    fun stacksSnapshot(): Map<TopLevelNavKey, List<MainNavKey>> = stacks.mapValues { (_, stack) -> stack.toList() }

    fun isInitialState(initialTopLevel: TopLevelNavKey): Boolean {
        return selectedTopLevel == initialTopLevel && stacks.all { (key, stack) -> stack.toList() == listOf(key) }
    }

    fun push(key: MainNavKey) {
        val stack = currentStack()
        if (stack.lastOrNull() != key) {
            stack.add(key)
        }
    }

    fun pop(): Boolean {
        val stack = currentStack()
        if (stack.size <= 1) {
            return false
        }
        stack.removeAt(stack.lastIndex)
        return true
    }

    fun stackFor(key: TopLevelNavKey): NavBackStack<MainNavKey> = stacks.getValue(key)

    fun currentStack(): NavBackStack<MainNavKey> = stacks.getValue(selectedTopLevel)
}

internal fun encodeTopLevelNavKey(key: TopLevelNavKey): String = when (key) {
    HomeNavKey -> "home"
    HistoryNavKey -> "history"
    FavoritesNavKey -> "favorites"
    ExploreNavKey -> "explore"
    DiscoverNavKey -> "discover"
    FeedNavKey -> "feed"
    LocalNavKey -> "local"
    SuggestionsNavKey -> "suggestions"
    BookmarksNavKey -> "bookmarks"
    UpdatedNavKey -> "updated"
}

internal fun decodeTopLevelNavKey(value: String): TopLevelNavKey? = when (value) {
    "home" -> HomeNavKey
    "history" -> HistoryNavKey
    "favorites" -> FavoritesNavKey
    "explore" -> ExploreNavKey
    "discover" -> DiscoverNavKey
    "feed" -> FeedNavKey
    "local" -> LocalNavKey
    "suggestions" -> SuggestionsNavKey
    "bookmarks" -> BookmarksNavKey
    "updated" -> UpdatedNavKey
    else -> null
}

private val topLevelNavKeyStateSaver = Saver<MutableState<TopLevelNavKey>, String>(
    save = { state -> encodeTopLevelNavKey(state.value) },
    restore = { value -> mutableStateOf(decodeTopLevelNavKey(value) ?: HomeNavKey) },
)

@Composable
fun rememberMainNavState(
    initialTopLevel: TopLevelNavKey,
): MainNavState {
    val selectedTopLevelState = rememberSaveable(saver = topLevelNavKeyStateSaver) {
        mutableStateOf(initialTopLevel)
    }
    val homeStack = rememberTopLevelNavBackStack(HomeNavKey)
    val historyStack = rememberTopLevelNavBackStack(HistoryNavKey)
    val favoritesStack = rememberTopLevelNavBackStack(FavoritesNavKey)
    val exploreStack = rememberTopLevelNavBackStack(ExploreNavKey)
    val discoverStack = rememberTopLevelNavBackStack(DiscoverNavKey)
    val feedStack = rememberTopLevelNavBackStack(FeedNavKey)
    val localStack = rememberTopLevelNavBackStack(LocalNavKey)
    val suggestionsStack = rememberTopLevelNavBackStack(SuggestionsNavKey)
    val bookmarksStack = rememberTopLevelNavBackStack(BookmarksNavKey)
    val updatedStack = rememberTopLevelNavBackStack(UpdatedNavKey)

    return remember(
        homeStack,
        historyStack,
        favoritesStack,
        exploreStack,
        discoverStack,
        feedStack,
        localStack,
        suggestionsStack,
        bookmarksStack,
        updatedStack,
    ) {
        MainNavState(
            readSelectedTopLevel = { selectedTopLevelState.value },
            writeSelectedTopLevel = { selectedTopLevelState.value = it },
            stacks = mapOf(
                HomeNavKey to homeStack,
                HistoryNavKey to historyStack,
                FavoritesNavKey to favoritesStack,
                ExploreNavKey to exploreStack,
                DiscoverNavKey to discoverStack,
                FeedNavKey to feedStack,
                LocalNavKey to localStack,
                SuggestionsNavKey to suggestionsStack,
                BookmarksNavKey to bookmarksStack,
                UpdatedNavKey to updatedStack,
            ),
        )
    }
}

@Composable
private fun rememberTopLevelNavBackStack(
    key: TopLevelNavKey,
): NavBackStack<MainNavKey> {
    @Suppress("UNCHECKED_CAST")
    return rememberNavBackStack(key) as NavBackStack<MainNavKey>
}
