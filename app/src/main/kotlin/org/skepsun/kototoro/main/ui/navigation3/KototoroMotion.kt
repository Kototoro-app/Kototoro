package org.skepsun.kototoro.main.ui.navigation3

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.navigation3.scene.Scene
import org.skepsun.kototoro.main.ui.compose.MainNavigationMotion

/**
 * Semantic motion presets for the Navigation 3 cutover (see
 * docs/architecture/navigation3-motion-system.md).
 *
 * Each navigation relation maps to one [KototoroMotion]; the mapping lives in
 * [KototoroMotionCatalog.forRelation] and the concrete transforms in
 * [KototoroMotionCatalog.preset]. A preset exposes the forward, pop and
 * predictive-pop `ContentTransform` triple that is attached either globally on
 * the `NavDisplay` (FadeThrough) or per scene through `NavDisplay.TransitionKey`
 * metadata.
 */
internal enum class KototoroMotion {
    FadeThrough,
    Hierarchical,
    HeroExpand,
    ZAxisLayer,
    ImmersiveFade,
}

internal class KototoroMotionPreset(
    val enter: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform,
    val pop: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform,
    val predictivePop: (AnimatedContentTransitionScope<Scene<*>>, Int) -> ContentTransform,
)

internal object KototoroMotionCatalog {

    /**
     * Navigation-relation -> motion language. Tab and Space switches are the
     * plane default (FadeThrough); the immersive destinations override it.
     */
    fun forRelation(prevTop: MainNavKey?, top: MainNavKey): KototoroMotion = when (top) {
        is ContentListNavKey -> KototoroMotion.Hierarchical
        is DetailsNavKey -> KototoroMotion.HeroExpand
        is SearchNavKey -> KototoroMotion.ZAxisLayer
        else -> KototoroMotion.FadeThrough
    }

    fun preset(motion: KototoroMotion): KototoroMotionPreset = when (motion) {
        KototoroMotion.FadeThrough -> fadeThrough
        KototoroMotion.Hierarchical -> hierarchical
        KototoroMotion.HeroExpand -> heroExpand
        KototoroMotion.ZAxisLayer -> zAxisLayer
        KototoroMotion.ImmersiveFade -> immersiveFade
    }

    /**
     * Phase C behavior, kept verbatim while the module is extracted; replaced by
     * the semantic presets once the mapping is applied.
     */
    val fullSlide: KototoroMotionPreset = preset(
        enter = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
            ) + fadeIn(tween(MainNavigationMotion.DetailsEnterFadeInMillis, easing = LinearEasing)) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsExitFadeOutMillis, easing = LinearEasing))
                )
        },
        pop = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing),
            ) + fadeIn(tween(MainNavigationMotion.DetailsPopEnterFadeInMillis, easing = LinearEasing)) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.DetailsRouteSlideMillis, easing = LinearEasing),
                ) + fadeOut(tween(MainNavigationMotion.DetailsPopExitFadeOutMillis, easing = LinearEasing))
                )
        },
    )

    private fun preset(
        enter: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform,
        pop: (AnimatedContentTransitionScope<Scene<*>>) -> ContentTransform,
        predictivePop: (AnimatedContentTransitionScope<Scene<*>>, Int) -> ContentTransform = { scope, _ -> pop(scope) },
    ) = KototoroMotionPreset(enter = enter, pop = pop, predictivePop = predictivePop)

    private val fade: FiniteAnimationSpec<Float> = tween(MainNavigationMotion.FadeMillis, easing = LinearEasing)

    // Sibling / space switches: crossfade with a barely visible depth hint.
    private val fadeThrough = preset(
        enter = { scope ->
            fadeIn(fade) + scaleIn(
                initialScale = MainNavigationMotion.FadeScaleIn,
                animationSpec = fade,
            ) togetherWith (
                fadeOut(fade) + scaleOut(
                    targetScale = MainNavigationMotion.FadeScaleOut,
                    animationSpec = fade,
                )
                )
        },
        pop = { scope ->
            fadeIn(fade) + scaleIn(
                initialScale = MainNavigationMotion.FadeScaleOut,
                animationSpec = fade,
            ) togetherWith (
                fadeOut(fade) + scaleOut(
                    targetScale = MainNavigationMotion.FadeScaleIn,
                    animationSpec = fade,
                )
                )
        },
    )

    // Parent-child depth: the new level slides in by a third, the current one
    // recesses slightly; not a full page turn.
    private val hierarchical = preset(
        enter = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(MainNavigationMotion.HierarchicalMillis, easing = LinearEasing),
                initialOffset = { fullWidth -> fullWidth / MainNavigationMotion.HierarchicalEnterDivisor },
            ) + fadeIn(fade) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.HierarchicalMillis, easing = LinearEasing),
                    targetOffset = { fullWidth -> -fullWidth / MainNavigationMotion.HierarchicalExitDivisor },
                ) + fadeOut(fade)
                )
        },
        pop = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(MainNavigationMotion.HierarchicalMillis, easing = LinearEasing),
                initialOffset = { fullWidth -> -fullWidth / MainNavigationMotion.HierarchicalExitDivisor },
            ) + fadeIn(fade) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.HierarchicalMillis, easing = LinearEasing),
                    targetOffset = { fullWidth -> fullWidth / MainNavigationMotion.HierarchicalEnterDivisor },
                ) + fadeOut(fade)
                )
        },
    )

    // Entity expansion: cover hero / shared bounds do the heavy lifting (they live
    // inside the content); the scene itself only applies a shallow depth + Z
    // expansion so it never fights the hero with a full page turn.
    private val heroExpand = preset(
        enter = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Left,
                animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                initialOffset = { fullWidth -> fullWidth / MainNavigationMotion.HeroEnterDepthDivisor },
            ) + scaleIn(
                initialScale = MainNavigationMotion.HeroEnterScale,
                animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
            ) + fadeIn(fade) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                    targetOffset = { fullWidth -> -fullWidth / MainNavigationMotion.HeroExitDepthDivisor },
                ) + scaleOut(
                    targetScale = MainNavigationMotion.HeroBackScale,
                    animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                ) + fadeOut(fade)
                )
        },
        pop = { scope ->
            scope.slideIntoContainer(
                towards = AnimatedContentTransitionScope.SlideDirection.Right,
                animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                initialOffset = { fullWidth -> -fullWidth / MainNavigationMotion.HeroExitDepthDivisor },
            ) + scaleIn(
                initialScale = MainNavigationMotion.HeroBackScale,
                animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
            ) + fadeIn(fade) togetherWith (
                scope.slideOutOfContainer(
                    towards = AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                    targetOffset = { fullWidth -> fullWidth / MainNavigationMotion.HeroEnterDepthDivisor },
                ) + scaleOut(
                    targetScale = MainNavigationMotion.HeroEnterScale,
                    animationSpec = tween(MainNavigationMotion.HeroMillis, easing = LinearEasing),
                ) + fadeOut(fade)
                )
        },
    )

    // Tool workspace (search): forward/back depth instead of horizontal travel.
    private val zAxisLayer = preset(
        enter = { scope ->
            fadeIn(fade) + scaleIn(
                initialScale = MainNavigationMotion.ZAxisEnterScale,
                animationSpec = tween(MainNavigationMotion.ZAxisMillis, easing = LinearEasing),
            ) togetherWith (
                fadeOut(fade) + scaleOut(
                    targetScale = MainNavigationMotion.ZAxisBackScale,
                    animationSpec = tween(MainNavigationMotion.ZAxisMillis, easing = LinearEasing),
                )
                )
        },
        pop = { scope ->
            fadeIn(fade) + scaleIn(
                initialScale = MainNavigationMotion.ZAxisBackScale,
                animationSpec = tween(MainNavigationMotion.ZAxisMillis, easing = LinearEasing),
            ) togetherWith (
                fadeOut(fade) + scaleOut(
                    targetScale = MainNavigationMotion.ZAxisEnterScale,
                    animationSpec = tween(MainNavigationMotion.ZAxisMillis, easing = LinearEasing),
                )
                )
        },
    )

    // Immersive consumption (reader / player, outside Navigation 3 today):
    // restrained fade + subtle scale; reserved for the future.
    private val immersiveFade = preset(
        enter = { scope ->
            fadeIn(fade) + scaleIn(
                initialScale = MainNavigationMotion.ImmersiveScaleIn,
                animationSpec = fade,
            ) togetherWith fadeOut(fade)
        },
        pop = { scope ->
            fadeIn(fade) togetherWith (
                fadeOut(fade) + scaleOut(
                    targetScale = MainNavigationMotion.ImmersiveScaleIn,
                    animationSpec = fade,
                )
                )
        },
    )
}
