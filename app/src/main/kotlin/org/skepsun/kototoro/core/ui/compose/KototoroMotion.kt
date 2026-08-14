package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Project-level motion layer above the official M3 `MotionScheme`.
 *
 * Phase D of the toolchain upgrade wires `MotionScheme.expressive()` into the Material theme root.
 * Semantic fade/effects specs below are derived from the active `MaterialTheme.motionScheme`
 * (single source of truth) so shared components move with the scheme instead of redefining
 * timings. Physics springs and task choreography (route slides, pull-to-refresh, zoom) have no
 * official scheme counterpart and remain here as named, documented constants — bare literals
 * must not spread back into business pages (interface-style-system.md §4.8).
 */
object KototoroMotion {

    // ---- Scheme-derived semantic specs (single source of truth: MaterialTheme.motionScheme) ----

    @Composable
    fun fadeFast(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastEffectsSpec()

    @Composable
    fun fadeDefault(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultEffectsSpec()

    @Composable
    fun fadeSlow(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.slowEffectsSpec()

    @Composable
    fun spatialFast(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.fastSpatialSpec()

    @Composable
    fun spatialDefault(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.defaultSpatialSpec()

    @Composable
    fun spatialSlow(): FiniteAnimationSpec<Float> = MaterialTheme.motionScheme.slowSpatialSpec()

    // ---- Bespoke choreography (no official counterpart; keep named and documented) ----

    /** Reader info-bar enter: fade with a small delay, choreographed against the slide. */
    val InfoBarEnter: FiniteAnimationSpec<Float> = tween(140, delayMillis = 160)

    /** Route slides/expand-shrink use call-site-typed specs (IntOffset / IntSize / Dp). */
    fun <T> tweenLinear(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = LinearEasing)

    fun <T> tweenEaseOut(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = FastOutSlowInEasing)

    // Physics springs.
    val ThumbSpring = spring<Dp>(dampingRatio = 0.72f, stiffness = 520f)
    val PullSettleSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 420f)
    val RailEntrySpring = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)
    val VerticalRailEntrySpring = spring<Float>(dampingRatio = 0.84f, stiffness = 420f)

    // Task-specific specs.
    val PullRefresh: FiniteAnimationSpec<Float> = tween(180)
    val PageZoom: FiniteAnimationSpec<Float> = tween(220)

    /** Direct access to the active scheme for components needing all six specs. */
    @Composable
    fun scheme(): MotionScheme = MaterialTheme.motionScheme
}
