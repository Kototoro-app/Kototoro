package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.render.compose.ComposeScenePrimaryScrollState
import org.skepsun.kototoro.reader.render.compose.ComposeSceneScrollState

/** Viewport-lengths of look-ahead on each side when anchoring the loading overlays. */
internal const val LOADING_OVERLAY_MARGIN_VIEWPORTS = 3f

/**
 * Loading overlays anchored to the draw-phase placeholder geometry.
 *
 * [items] positions are computed against [anchorScroll] (a composition-time snapshot of the
 * scroll offset, taken without observing it so composition is not subscribed to per-frame
 * scroll) and then track scroll purely at the graphics-layer phase — see
 * [loadingOverlayHorizontalAnchor] / [loadingOverlayVerticalAnchor].
 */
internal data class AnchoredLoadingOverlays(
    val anchorScroll: Float,
    val items: List<AnchoredLoadingOverlay>,
)

/**
 * One loading/error overlay of [pageId]; a page longer than the viewport carries one per viewport
 * extent (see resolvePlaceholderIndicatorCenters), told apart by [slot].
 */
internal data class AnchoredLoadingOverlay(
    val pageId: PageId,
    val slot: Int,
    val centerX: Float,
    val centerY: Float,
)

/**
 * Tracks the primary horizontal scroll at the layer phase.
 *
 * The renderer scrolls in the Draw Phase reading [ComposeScenePrimaryScrollState.offset]; this
 * modifier re-applies on every offset write without recomposition or layout, in the same frame
 * the renderer reads the offset, so the overlay stays exactly on the placeholder rect. Without
 * it, the overlay position rides the composition pipeline and lags the draw-phase placeholder
 * whenever recomposition misses a frame deadline during fast scrolling.
 */
internal fun Modifier.loadingOverlayHorizontalAnchor(
    anchorScroll: Float,
    scrollState: ComposeScenePrimaryScrollState,
): Modifier = graphicsLayer {
    translationX = anchorScroll - scrollState.offset
}

/** Vertical counterpart of [loadingOverlayHorizontalAnchor] for the webtoon (strip) scene. */
internal fun Modifier.loadingOverlayVerticalAnchor(
    anchorScroll: Float,
    scrollState: ComposeSceneScrollState,
): Modifier = graphicsLayer {
    translationY = anchorScroll - scrollState.scrollY
}
