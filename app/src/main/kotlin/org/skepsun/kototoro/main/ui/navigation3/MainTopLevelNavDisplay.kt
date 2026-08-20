package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.metadata
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope
import org.skepsun.kototoro.main.ui.compose.MainNavigationMotion

/**
 * Renders the selected top-level tab's full back stack with a single [NavDisplay].
 *
 * The innermost entry of the selected tab's stack is shown as the current scene:
 * a [TopLevelNavKey] renders the tab content, while a [DetailsNavKey],
 * [ContentListNavKey] or [SearchNavKey] on top renders the immersive destination
 * (details / source list / search) over the tab.
 *
 * Navigation-3 shared-element semantics are used for the hero transition: every
 * entry's content receives `LocalNavAnimatedVisibilityScope` = the NavDisplay's
 * inner `AnimatedContent` scope (`LocalNavAnimatedContentScope`), so both the
 * leaving list scene and the entering details scene register their shared bounds
 * against the same active scene transition and the cover hero animates.
 *
 * The immersive destinations (content list / details) keep the original v2
 * horizontal-slide motion through per-entry transition metadata; search keeps
 * the default cross-fade.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainTopLevelNavDisplay(
    navState: MainNavState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    renderEntry: @Composable (MainNavKey) -> Unit,
) {
    val viewModelStoreOwner = checkNotNull(LocalViewModelStoreOwner.current) {
        "MainTopLevelNavDisplay requires a ViewModelStoreOwner"
    }
    val saveableStateHolder = rememberSaveableStateHolder()
    val currentRenderEntry = rememberUpdatedState(renderEntry)
    // Decorate each top-level stack independently so switching tabs does not clear its state.
    val decoratedEntriesByTopLevel: Map<TopLevelNavKey, List<NavEntry<MainNavKey>>> = allTopLevelNavKeys.associateWith { key ->
        val backStack: List<MainNavKey> = navState.stackFor(key).toList()
        val entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator<MainNavKey>(saveableStateHolder),
            rememberViewModelStoreNavEntryDecorator<MainNavKey>(viewModelStoreOwner),
        )
        rememberDecoratedNavEntries(
            backStack = backStack,
            entryDecorators = entryDecorators,
            entryProvider = { entryKey ->
                navEntry(
                    key = entryKey,
                    renderEntry = { key -> currentRenderEntry.value(key) },
                )
            },
        )
    }
    val sceneStrategies: List<SceneStrategy<MainNavKey>> = listOf(
        remember { SinglePaneSceneStrategy<MainNavKey>() },
    )
    NavDisplay(
        entries = decoratedEntriesByTopLevel.getValue(navState.selectedTopLevel),
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        sceneStrategies = sceneStrategies,
        sceneDecoratorStrategies = emptyList<SceneDecoratorStrategy<MainNavKey>>(),
        sharedTransitionScope = sharedTransitionScope,
        onBack = { navState.pop() },
    )
}

private fun navEntry(
    key: MainNavKey,
    renderEntry: @Composable (MainNavKey) -> Unit = {},
): NavEntry<MainNavKey> {
    return NavEntry(
        key = key,
        metadata = immersiveTransitionMetadata(key),
    ) { entryKey ->
        CompositionLocalProvider(
            LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
        ) {
            renderEntry(entryKey)
        }
    }
}

/**
 * Restores the v2 horizontal-slide motion for the immersive destinations
 * (content list, details); search and top-level entries keep the default fade.
 */
private fun immersiveTransitionMetadata(key: MainNavKey): Map<String, Any> {
    if (key !is ContentListNavKey && key !is DetailsNavKey) {
        return emptyMap()
    }
    return metadata {
        put(NavDisplay.TransitionKey, immersiveEnterTransform)
        put(NavDisplay.PopTransitionKey, immersivePopTransform)
        put(NavDisplay.PredictivePopTransitionKey, predictivePopTransform)
    }
}

private val immersiveEnterTransform: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform = { scope ->
    scope.slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Left,
        animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
    ) + fadeIn(tween(MainNavigationMotion.DetailsEnterFadeInMillis, easing = LinearEasing)) togetherWith (
        scope.slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Left,
            animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
        ) + fadeOut(tween(MainNavigationMotion.DetailsExitFadeOutMillis, easing = LinearEasing))
        )
}

private val immersivePopTransform: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform = { scope ->
    scope.slideIntoContainer(
        towards = AnimatedContentTransitionScope.SlideDirection.Right,
        animationSpec = tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing),
    ) + fadeIn(tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing)) togetherWith (
        scope.slideOutOfContainer(
            towards = AnimatedContentTransitionScope.SlideDirection.Right,
            animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
        ) + fadeOut(tween(MainNavigationMotion.DetailsPopExitFadeOutMillis, easing = LinearEasing))
        )
}

private val predictivePopTransform:
    (AnimatedContentTransitionScope<Scene<*>>, Int) -> ContentTransform = { scope, _ ->
    immersivePopTransform(scope)
}
