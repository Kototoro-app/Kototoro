package org.skepsun.kototoro.reader.core

import kotlin.math.abs

/**
 * Configuration parameters for the predictive prefetch resolver.
 *
 * @property staticAheadFraction Multiplier of viewport height to prefetch ahead when idle (e.g. 1.0 = 1 viewport).
 * @property staticBehindFraction Multiplier of viewport height to retain/prefetch behind when idle (e.g. 0.5 = 0.5 viewport).
 * @property lookaheadHorizonSeconds Time window in seconds used to project future viewport position from [ViewportMotion.velocityY].
 * @property maxLookaheadExtraPx Maximum additional lookahead distance in pixels capped to prevent unbounded prefetching on violent flings.
 */
data class ReaderPredictionConfig(
    val staticAheadFraction: Float = 2.5f,
    val staticBehindFraction: Float = 1.0f,
    val lookaheadHorizonSeconds: Float = 0.6f,
    val maxLookaheadExtraPx: Float = 6000f,
)

/**
 * Pure prediction engine computing optimal prefetch priorities from viewport geometry and motion telemetry.
 *
 * Conforms to ADR 0002 Principle:
 * "ReaderCore owns semantics and prediction; ImagePipeline owns execution and resource scheduling."
 */
class ReaderPrediction(
    private val config: ReaderPredictionConfig = ReaderPredictionConfig(),
) {

    /**
     * Resolves an ordered list of [PrefetchRequest] based on the current [viewport] and [motion].
     */
    fun predict(
        scene: VerticalReaderScene,
        viewport: ReaderViewport,
        motion: ViewportMotion = ViewportMotion.Idle,
    ): List<PrefetchRequest> {
        val vpTop = viewport.bounds.top
        val vpHeight = viewport.bounds.height
        val vpWidth = viewport.bounds.width
        val vpBottom = viewport.bounds.bottom

        if (vpHeight <= 0f || vpWidth <= 0f) return emptyList()

        // 1. Resolve immediate visible pages
        val immediateFrame = scene.resolve(viewport)
        val immediatePageIds = immediateFrame.visibleNodes.map { it.pageId }.toSet()

        val requests = mutableListOf<PrefetchRequest>()

        // Add IMMEDIATE priority for all visible nodes
        for (node in immediateFrame.visibleNodes) {
            requests.add(
                PrefetchRequest(
                    pageId = node.pageId,
                    priority = PrefetchPriority.IMMEDIATE,
                    predictedVisibleRegion = node.visibleRegion,
                ),
            )
        }

        // 2. Compute directional lookahead expansion
        val velocityY = motion.velocityY
        val dynamicExtraPx = (abs(velocityY) * config.lookaheadHorizonSeconds).coerceAtMost(config.maxLookaheadExtraPx)

        val aheadExtra = if (velocityY >= 0) dynamicExtraPx else 0f
        val behindExtra = if (velocityY < 0) dynamicExtraPx else 0f

        val highAheadPx = vpHeight * config.staticAheadFraction + aheadExtra
        val highBehindPx = vpHeight * config.staticBehindFraction + behindExtra

        // HIGH priority window
        val highWindow = ReaderViewport(
            FloatRect.fromLtwh(
                left = 0f,
                top = (vpTop - highBehindPx).coerceAtLeast(0f),
                width = vpWidth,
                height = vpHeight + highBehindPx + highAheadPx,
            ),
        )
        val highFrame = scene.resolve(highWindow)

        for (node in highFrame.visibleNodes) {
            if (node.pageId !in immediatePageIds) {
                requests.add(
                    PrefetchRequest(
                        pageId = node.pageId,
                        priority = PrefetchPriority.HIGH,
                        predictedVisibleRegion = node.visibleRegion,
                    ),
                )
            }
        }

        // 3. MEDIUM priority window (extended lookahead ahead of scroll direction)
        val mediumAheadPx = highAheadPx + vpHeight * 1.5f
        val mediumWindow = ReaderViewport(
            FloatRect.fromLtwh(
                left = 0f,
                top = (vpTop - highBehindPx).coerceAtLeast(0f),
                width = vpWidth,
                height = vpHeight + highBehindPx + mediumAheadPx,
            ),
        )
        val mediumFrame = scene.resolve(mediumWindow)
        val existingIds = requests.map { it.pageId }.toSet()

        for (node in mediumFrame.visibleNodes) {
            if (node.pageId !in existingIds) {
                requests.add(
                    PrefetchRequest(
                        pageId = node.pageId,
                        priority = PrefetchPriority.MEDIUM,
                        predictedVisibleRegion = node.visibleRegion,
                    ),
                )
            }
        }

        return requests
    }
}
