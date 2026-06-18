package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
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
    val backStack = navState.currentStack().mapNotNull { it as? TopLevelNavKey }
    val entryDecorators: List<NavEntryDecorator<TopLevelNavKey>> = buildList {
        add(rememberSaveableStateHolderNavEntryDecorator(saveableStateHolder))
        add(rememberViewModelStoreNavEntryDecorator(viewModelStoreOwner))
    }
    val sceneStrategies: List<SceneStrategy<TopLevelNavKey>> = listOf(
        remember { SinglePaneSceneStrategy<TopLevelNavKey>() },
    )
    NavDisplay(
        backStack = backStack,
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        entryDecorators = entryDecorators,
        sceneStrategies = sceneStrategies,
        sceneDecoratorStrategies = emptyList<SceneDecoratorStrategy<TopLevelNavKey>>(),
        sharedTransitionScope = sharedTransitionScope,
        onBack = { navState.pop() },
        entryProvider = { key -> topLevelNavEntry(key, animatedVisibilityScopeOverride, renderEntry) },
    )
}

private fun topLevelNavEntry(
    key: TopLevelNavKey,
    animatedVisibilityScopeOverride: AnimatedVisibilityScope? = null,
    renderEntry: @Composable (TopLevelNavKey) -> Unit = {},
): NavEntry<TopLevelNavKey> {
    return NavEntry(key = key) { entryKey ->
        CompositionLocalProvider(
            LocalNavAnimatedVisibilityScope provides (animatedVisibilityScopeOverride ?: LocalNavAnimatedContentScope.current),
        ) {
            renderEntry(entryKey)
        }
    }
}
