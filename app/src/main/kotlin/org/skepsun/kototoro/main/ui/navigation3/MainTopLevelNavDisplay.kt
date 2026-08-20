package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
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
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneStrategy
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.ui.LocalNavAnimatedContentScope
import androidx.navigation3.ui.NavDisplay
import org.skepsun.kototoro.core.prefs.ListToDetailsTransition
import org.skepsun.kototoro.core.ui.compose.LocalNavAnimatedVisibilityScope

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
 * Scene motion is owned by [KototoroMotionCatalog]: the global [NavDisplay]
 * defaults (FadeThrough for tab / space switches) and the per-entry
 * [NavDisplay.TransitionKey] metadata overrides (content list / details /
 * search). See docs/architecture/navigation3-motion-system.md.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun MainTopLevelNavDisplay(
    navState: MainNavState,
    modifier: Modifier = Modifier,
    sharedTransitionScope: SharedTransitionScope? = null,
    detailsTransitionStyle: ListToDetailsTransition = ListToDetailsTransition.HERO_EXPAND,
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
                // The navigation relation is (previous top of stack, new top of stack).
                val prev = backStack.takeWhile { it != entryKey }.lastOrNull()
                navEntry(
                    key = entryKey,
                    prev = prev,
                    detailsTransitionStyle = detailsTransitionStyle,
                    renderEntry = { key -> currentRenderEntry.value(key) },
                )
            },
        )
    }
    val sceneStrategies: List<SceneStrategy<MainNavKey>> = listOf(
        remember { SinglePaneSceneStrategy<MainNavKey>() },
    )
    // Global default: sibling / space switches cross-fade. Immersive destinations
    // override it through per-entry metadata below.
    val fadeThrough = remember { KototoroMotionCatalog.preset(KototoroMotion.FadeThrough) }
    NavDisplay(
        entries = decoratedEntriesByTopLevel.getValue(navState.selectedTopLevel),
        modifier = modifier,
        contentAlignment = Alignment.TopStart,
        sceneStrategies = sceneStrategies,
        sceneDecoratorStrategies = emptyList<SceneDecoratorStrategy<MainNavKey>>(),
        sharedTransitionScope = sharedTransitionScope,
        transitionSpec = { fadeThrough.enter(this) },
        popTransitionSpec = { fadeThrough.pop(this) },
        predictivePopTransitionSpec = { progress -> fadeThrough.predictivePop(this, progress) },
        onBack = { navState.pop() },
    )
}

private fun navEntry(
    key: MainNavKey,
    prev: MainNavKey?,
    detailsTransitionStyle: ListToDetailsTransition,
    renderEntry: @Composable (MainNavKey) -> Unit = {},
): NavEntry<MainNavKey> {
    return NavEntry(
        key = key,
        metadata = transitionMetadata(key, prev, detailsTransitionStyle),
    ) { entryKey ->
        CompositionLocalProvider(
            LocalNavAnimatedVisibilityScope provides LocalNavAnimatedContentScope.current,
        ) {
            renderEntry(entryKey)
        }
    }
}

/**
 * Navigation-relation -> scene metadata. Top-level / sibling entries fade through
 * (also the NavDisplay default); source lists use Hierarchical; search uses the
 * Z-axis workspace; details use HeroExpand by default, or the selectable legacy
 * full-width page turn when the user opts into it.
 */
private fun transitionMetadata(
    key: MainNavKey,
    prev: MainNavKey?,
    detailsTransitionStyle: ListToDetailsTransition,
): Map<String, Any> {
    val preset = if (key is DetailsNavKey && detailsTransitionStyle == ListToDetailsTransition.LEGACY_SLIDE) {
        KototoroMotionCatalog.legacySlide
    } else {
        KototoroMotionCatalog.preset(KototoroMotionCatalog.forRelation(prev, key))
    }
    return metadata {
        put(NavDisplay.TransitionKey, preset.enter)
        put(NavDisplay.PopTransitionKey, preset.pop)
        put(NavDisplay.PredictivePopTransitionKey, preset.predictivePop)
    }
}
