package org.skepsun.kototoro.core.ui.theme

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Motion personality used across chrome, lists, sheets and transitions.
 *
 * The UI settings only expose the overall interface style; [MotionStyle] is derived
 * from it (Material styles -> [MotionStyle.MATERIAL], iOS -> [MotionStyle.IOS]) but is
 * kept as an independent knob so a theme could pair e.g. expressive Material motion
 * with a glass surface without changing the settings model.
 */
enum class MotionStyle {
    /** Stronger damping, restrained overscroll, tonal surfaces, emphasized tween. */
    MATERIAL,

    /** Long natural inertia, rubber-band overscroll, scroll-linked chrome. */
    IOS,
}

/**
 * How the scrolling content interacts with the floating chrome surfaces.
 *
 * Mirrors the "two surface languages" split: Material styles stay on stable tonal
 * surfaces ([SurfaceStyle.MATERIAL]), while iOS uses a liquid-glass backdrop that
 * participates in scroll feedback ([SurfaceStyle.BACKDROP]).
 */
enum class SurfaceStyle {
    /** Stable tonal/elevated surfaces; glass/backdrop is never used. */
    MATERIAL,

    /** Liquid-glass backdrop participates in scroll feedback via overlap progress. */
    BACKDROP,
}

/**
 * How far the list content has passed underneath the floating top chrome.
 *
 * `0f` = list at its top (nothing under the bar yet), `1f` = content has scrolled at
 * least one top-bar height past the bar. This is derived purely from scroll *position*
 * (overlap), never from velocity — matching the iOS design rule that the backdrop must
 * not wobble with fling speed.
 */
val LocalChromeScrollOverlap = compositionLocalOf { 0f }

val LocalMotionStyle = staticCompositionLocalOf { MotionStyle.MATERIAL }
val LocalSurfaceStyle = staticCompositionLocalOf { SurfaceStyle.MATERIAL }
