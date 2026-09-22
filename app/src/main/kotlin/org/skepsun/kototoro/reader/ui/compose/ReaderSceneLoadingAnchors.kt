package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import org.skepsun.kototoro.reader.core.PageId
import org.skepsun.kototoro.reader.render.compose.ComposeScenePrimaryScrollState
import org.skepsun.kototoro.reader.render.compose.ComposeSceneScrollState
import kotlin.math.abs

/**
 * Chapter-wide aspect ratio estimation for pages that have not decoded yet.
 *
 * Manga pages within a chapter share their aspect ratio closely, so the running average of the
 * decoded pages predicts the real size of pages that are still loading: that is what lets the
 * loading placeholder geometry match the real image size before it decodes. Proposals are only
 * made once the average moves materially, so the placeholder geometry churn stays bounded while
 * the chapter converges (a handful of relayouts per chapter at most).
 */
internal class ReaderChapterRatioEstimator(
    private val minSamples: Int = 2,
    private val relayoutThreshold: Float = 0.1f,
) {
    private var ratioSum = 0f
    private var sampleCount = 0
    private var appliedRatio = 1f

    /** Ratio currently applied to the still-unknown pages (1f until a proposal converges). */
    val currentRatio: Float get() = appliedRatio

    /** Ratio to apply to unknown pages once enough pages have decoded to trust it, else null. */
    val convergedRatio: Float? get() = appliedRatio.takeIf { sampleCount >= minSamples }

    /**
     * Records one decoded page's intrinsic ratio and returns the ratio worth applying to the
     * still-unknown pages of the chapter, or null while there are not enough samples or the
     * running average has not moved enough to justify a scene relayout.
     */
    fun onDecoded(width: Int, height: Int): Float? {
        if (width <= 0 || height <= 0) return null
        ratioSum += width.toFloat() / height.toFloat()
        sampleCount++
        if (sampleCount < minSamples) return null
        val average = ratioSum / sampleCount
        if (average <= 0f || !average.isFinite()) return null
        if (abs(average - appliedRatio) <= appliedRatio * relayoutThreshold) return null
        appliedRatio = average
        return average
    }
}

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
    val items: List<Triple<PageId, Float, Float>>,
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
