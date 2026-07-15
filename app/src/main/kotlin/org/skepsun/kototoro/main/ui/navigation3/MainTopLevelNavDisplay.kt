package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.NavDisplay
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainTopLevelNavDisplay(
    navState: MainNavState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScopeOverride: AnimatedVisibilityScope? = null,
    renderEntry: @Composable (TopLevelNavKey) -> Unit,
) {
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "MainTopLevelNavDisplay requires a ViewModelStoreOwner"
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    val currentRenderEntry = rememberUpdatedState(renderEntry)
    val currentAnimatedVisibilityScope = rememberUpdatedState(animatedVisibilityScopeOverride)
    // Decorate each top-level stack independently so switching tabs does not clear its state.
    val decoratedEntriesByTopLevel: Map<TopLevelNavKey, List<NavEntry<TopLevelNavKey>>> = allTopLevelNavKeys.associateWith { key ->
        val backStack: List<TopLevelNavKey> = navState.stackFor(key).mapNotNull { it as? TopLevelNavKey }
        val entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<TopLevelNavKey>(saveableStateHolder),
            rememberViewModelStoreNavEntryDecorator<TopLevelNavKey>(viewModelStoreOwner),
        )
        rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = entryDecorators,
            entryProvider = { entryKey ->
                topLevelNavEntry(
                    key = entryKey,
                    animatedVisibilityScopeOverride = { currentAnimatedVisibilityScope.value },
                    renderEntry = { key -> currentRenderEntry.value(key) },
                )
            },
        )
    }
    val sceneStrategies: List<SceneStrategy<TopLevelNavKey>> = listOf(
        remember { SinglePaneSceneStrategy<TopLevelNavKey>() },
    )
    NavDisplay(
        entries = decoratedEntriesByTopLevel.getValue(navState.selectedTopLevel),
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        sceneStrategies = sceneStrategies,
        sceneDecoratorStrategies = emptyList<SceneDecoratorStrategy<TopLevelNavKey>>(),
        sharedTransitionScope = sharedTransitionScope,
        onBack = { navState.pop() },
    )
}

private fun topLevelNavEntry(
    key: TopLevelNavKey,
    animatedVisibilityScopeOverride: () -> AnimatedVisibilityScope? = { null },
    renderEntry: @Composable (TopLevelNavKey) -> Unit = {},
): NavEntry<TopLevelNavKey> {
    return NavEntry(key = key) { entryKey ->
        CompositionLocalProvider(
            LocalNavAnimatedVisibilityScope provides
                (animatedVisibilityScopeOverride() ?: LocalNavAnimatedContentScope.current),
        ) {
            renderEntry(entryKey)
        }
    }
}
