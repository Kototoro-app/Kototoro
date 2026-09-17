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

    private var lastWindowKey: ResourceWindowKey? = null

    /**
     * Returns a new resource window only when its page range, geometry revision, viewport size, or
     * coarse motion class changed. Pixel-level scroll updates within the same window are ignored.
     */
    fun predictWindowIfChanged(
        scene: VerticalReaderScene,
        frame: ReaderFrame,
        motion: ViewportMotion = ViewportMotion.Idle,
        force: Boolean = false,
    ): ReaderResourceWindow? {
        val key = ResourceWindowKey.from(scene, frame, motion, config)
        if (!force && key == lastWindowKey) return null
        lastWindowKey = key
        return ReaderResourceWindow(predict(scene, frame, motion))
    }

    /**
     * Resolves an ordered list of [PrefetchRequest] based on the current [viewport] and [motion].
     */
    fun predict(
        scene: VerticalReaderScene,
        viewport: ReaderViewport,
        motion: ViewportMotion = ViewportMotion.Idle,
    ): List<PrefetchRequest> = predict(scene, scene.resolve(viewport), motion)

    /** Resolves prediction from an already-computed visible frame. */
    fun predict(
        scene: VerticalReaderScene,
        frame: ReaderFrame,
        motion: ViewportMotion = ViewportMotion.Idle,
    ): List<PrefetchRequest> {
        val viewport = frame.viewport
        val vpTop = viewport.bounds.top
        val vpHeight = viewport.bounds.height
        val vpWidth = viewport.bounds.width
        val vpBottom = viewport.bounds.bottom

        if (vpHeight <= 0f || vpWidth <= 0f) return emptyList()

        // 1. Resolve immediate visible pages
        val requests = mutableListOf<PrefetchRequest>()
        val requestedPageIds = HashSet<PageId>()

        // Add IMMEDIATE priority for all visible nodes
        for (node in frame.visibleNodes) {
            requests.add(
                PrefetchRequest(
                    pageId = node.pageId,
                    priority = PrefetchPriority.IMMEDIATE,
                    readiness = PrefetchReadiness.PRESENTATION_READY,
                    predictedVisibleRegion = node.visibleRegion,
                ),
            )
            requestedPageIds.add(node.pageId)
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
            if (requestedPageIds.add(node.pageId)) {
                requests.add(
                    PrefetchRequest(
                        pageId = node.pageId,
                        priority = PrefetchPriority.HIGH,
                        readiness = PrefetchReadiness.PRESENTATION_READY,
                        predictedVisibleRegion = node.visibleRegion,
                    ),
                )
            }
        }

        // 3. MEDIUM priority window (extended lookahead along scroll direction)
        val extraMedium = vpHeight * 1.5f
        val (mediumBehindPx, mediumAheadPx) = if (velocityY < 0) {
            (highBehindPx + extraMedium) to highAheadPx
        } else {
            highBehindPx to (highAheadPx + extraMedium)
        }
        val mediumWindow = ReaderViewport(
            FloatRect.fromLtwh(
                left = 0f,
                top = (vpTop - mediumBehindPx).coerceAtLeast(0f),
                width = vpWidth,
                height = vpHeight + mediumBehindPx + mediumAheadPx,
            ),
        )
        val mediumFrame = scene.resolve(mediumWindow)

        for (node in mediumFrame.visibleNodes) {
            if (requestedPageIds.add(node.pageId)) {
                requests.add(
                    PrefetchRequest(
                        pageId = node.pageId,
                        priority = PrefetchPriority.MEDIUM,
                        readiness = PrefetchReadiness.SOURCE_READY,
                        predictedVisibleRegion = node.visibleRegion,
                    ),
                )
            }
        }

        return requests
    }

    private data class ResourceWindowKey(
        val scene: VerticalReaderScene,
        val sceneRevision: Long,
        val viewportWidth: Int,
        val viewportHeight: Int,
        val firstVisiblePageId: PageId?,
        val lastVisiblePageId: PageId?,
        val motionClass: MotionClass,
        val lookaheadBucket: Int,
    ) {
        companion object {
            fun from(
                scene: VerticalReaderScene,
                frame: ReaderFrame,
                motion: ViewportMotion,
                config: ReaderPredictionConfig,
            ): ResourceWindowKey {
                val viewportHeight = frame.viewport.bounds.height
                val speed = abs(motion.velocityY)
                val dynamicExtraPx = (speed * config.lookaheadHorizonSeconds).coerceAtMost(config.maxLookaheadExtraPx)
                val lookaheadBucket = if (viewportHeight > 0f) {
                    (dynamicExtraPx / viewportHeight).toInt().coerceIn(0, 8)
                } else {
                    0
                }
                val motionClass = when {
                    speed < 1f && !motion.isDragging -> MotionClass.IDLE
                    motion.velocityY < 0f && speed >= viewportHeight * 1.5f -> MotionClass.FAST_BACKWARD
                    motion.velocityY < 0f -> MotionClass.BACKWARD
                    speed >= viewportHeight * 1.5f -> MotionClass.FAST_FORWARD
                    else -> MotionClass.FORWARD
                }
                return ResourceWindowKey(
                    scene = scene,
                    sceneRevision = scene.revision,
                    viewportWidth = frame.viewport.bounds.width.toInt(),
                    viewportHeight = viewportHeight.toInt(),
                    firstVisiblePageId = frame.progress.lowerPageId,
                    lastVisiblePageId = frame.progress.upperPageId,
                    motionClass = motionClass,
                    lookaheadBucket = lookaheadBucket,
                )
            }
        }
    }

    private enum class MotionClass {
        IDLE,
        FORWARD,
        FAST_FORWARD,
        BACKWARD,
        FAST_BACKWARD,
    }
}
