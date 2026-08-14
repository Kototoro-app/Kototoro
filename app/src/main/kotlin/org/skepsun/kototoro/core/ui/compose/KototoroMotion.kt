package org.skepsun.kototoro.core.ui.compose

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.ui.unit.Dp

/**
 * Project-level motion specs for shared components and chrome.
 *
 * Duration-named specs preserve current timings while stopping bare literals from spreading
 * into business pages (interface-style-system.md §4.8). The semantic mapping (enter/exit/
 * expand/select/loading) lands together with the official `MotionScheme` once Material3 1.5.0
 * is available on the project's build baseline; until then every spec here is a
 * behavior-preserving move of an existing value.
 *
 * Float-typed `Fade*` values are for `fadeIn`/`fadeOut`/`animateFloatAsState`. The generic
 * [tweenLinear] and [tweenEaseOut] helpers cover specs whose type parameter is provided by the
 * call site (route slides use `IntOffset`, horizontal expand/shrink uses `IntSize`, Dp
 * animations use `Dp`).
 *
 * Business pages must reference these specs instead of constructing their own `tween`/`spring`.
 * All specs respect the system animator scale through Compose's animation core, and pages that
 * additionally disable animation (readers, reduced visual effects) still bypass them entirely.
 */
object KototoroMotion {

    // Fade-only durations.
    val Fade80: FiniteAnimationSpec<Float> = tween(80)
    val Fade140: FiniteAnimationSpec<Float> = tween(140)
    val Fade180: FiniteAnimationSpec<Float> = tween(180)
    val Fade200: FiniteAnimationSpec<Float> = tween(200)
    val Fade220: FiniteAnimationSpec<Float> = tween(220)
    val Fade260: FiniteAnimationSpec<Float> = tween(260)
    val Fade320: FiniteAnimationSpec<Float> = tween(320)
    val Fade140Delayed160: FiniteAnimationSpec<Float> = tween(140, delayMillis = 160)

    // FastOutSlowInEasing float specs.
    val Ease90: FiniteAnimationSpec<Float> = tween(90, easing = FastOutSlowInEasing)
    val Ease220: FiniteAnimationSpec<Float> = tween(220, easing = FastOutSlowInEasing)

    // Generic helpers for call-site-typed specs (IntOffset / IntSize / Dp / ...).
    fun <T> tweenLinear(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = LinearEasing)

    fun <T> tweenEaseOut(durationMillis: Int): FiniteAnimationSpec<T> =
        tween(durationMillis, easing = FastOutSlowInEasing)

    // Springs.
    val ThumbSpring = spring<Dp>(dampingRatio = 0.72f, stiffness = 520f)
    val PullSettleSpring = spring<Float>(dampingRatio = 0.78f, stiffness = 420f)
    val RailEntrySpring = spring<Float>(dampingRatio = 0.82f, stiffness = 420f)
    val VerticalRailEntrySpring = spring<Float>(dampingRatio = 0.84f, stiffness = 420f)

    // Task-specific specs.
    val PullRefresh: FiniteAnimationSpec<Float> = tween(180)
    val PageZoom: FiniteAnimationSpec<Float> = tween(220)
}
