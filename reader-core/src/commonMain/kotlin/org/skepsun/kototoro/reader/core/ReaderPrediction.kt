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
 *
 * Doubles as the continuous-mode [SceneResourceWindowPlanner] strategy (improvement plan
 * 2026-09 §8.1): [plan] delegates to [predictWindowIfChanged] including its
 * suppress-unchanged-snapshot semantics.
 */
class ReaderPrediction(
    private val config: ReaderPredictionConfig = ReaderPredictionConfig(),
) : SceneResourceWindowPlanner {

    private var lastWindowKey: ResourceWindowKey? = null

    override fun plan(request: SceneResourceWindowRequest): ReaderResourceWindow? =
        predictWindowIfChanged(request.scene, request.frame, request.motion)

    /**
     * Returns a new resource window only when its page range, geometry revision, viewport size, or
     * coarse motion class changed. Pixel-level scroll updates within the same window are ignored.
     */
    fun predictWindowIfChanged(
        scene: ReaderScene,
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
        scene: ReaderScene,
        viewport: ReaderViewport,
        motion: ViewportMotion = ViewportMotion.Idle,
    ): List<PrefetchRequest> = predict(scene, scene.resolve(viewport), motion)

    /** Resolves prediction from an already-computed visible frame. */
    fun predict(
        scene: ReaderScene,
        frame: ReaderFrame,
        motion: ViewportMotion = ViewportMotion.Idle,
    ): List<PrefetchRequest> {
        val viewport = frame.viewport
        val direction = scene.readingDirection
        val primaryDim = SceneAxisProjection.primaryDimension(viewport.bounds, direction)

        if (primaryDim <= 0f || viewport.bounds.width <= 0f || viewport.bounds.height <= 0f) return emptyList()

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
        val forwardVelocity = SceneAxisProjection.forwardVelocity(motion, direction)
        val speed = abs(forwardVelocity)
        val dynamicExtraPx = (speed * config.lookaheadHorizonSeconds).coerceAtMost(config.maxLookaheadExtraPx)

        val aheadExtra = if (forwardVelocity >= 0) dynamicExtraPx else 0f
        val behindExtra = if (forwardVelocity < 0) dynamicExtraPx else 0f

        val highAheadPx = primaryDim * config.staticAheadFraction + aheadExtra
        val highBehindPx = primaryDim * config.staticBehindFraction + behindExtra

        // HIGH priority window
        val highWindow = SceneAxisProjection.expand(viewport, direction, highAheadPx, highBehindPx)
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
        val extraMedium = primaryDim * 1.5f
        val (mediumBehindPx, mediumAheadPx) = if (forwardVelocity < 0) {
            (highBehindPx + extraMedium) to highAheadPx
        } else {
            highBehindPx to (highAheadPx + extraMedium)
        }
        val mediumWindow = SceneAxisProjection.expand(viewport, direction, mediumAheadPx, mediumBehindPx)
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
        val scene: ReaderScene,
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
                scene: ReaderScene,
                frame: ReaderFrame,
                motion: ViewportMotion,
                config: ReaderPredictionConfig,
            ): ResourceWindowKey {
                val direction = scene.readingDirection
                val primaryDim = SceneAxisProjection.primaryDimension(frame.viewport.bounds, direction)
                val forwardVelocity = SceneAxisProjection.forwardVelocity(motion, direction)
                val speed = abs(forwardVelocity)
                val dynamicExtraPx = (speed * config.lookaheadHorizonSeconds).coerceAtMost(config.maxLookaheadExtraPx)
                val lookaheadBucket = if (primaryDim > 0f) {
                    (dynamicExtraPx / primaryDim).toInt().coerceIn(0, 8)
                } else {
                    0
                }
                val motionClass = when {
                    speed < 1f && !motion.isDragging -> MotionClass.IDLE
                    forwardVelocity < 0f && speed >= primaryDim * 1.5f -> MotionClass.FAST_BACKWARD
                    forwardVelocity < 0f -> MotionClass.BACKWARD
                    speed >= primaryDim * 1.5f -> MotionClass.FAST_FORWARD
                    else -> MotionClass.FORWARD
                }
                return ResourceWindowKey(
                    scene = scene,
                    sceneRevision = scene.revision,
                    viewportWidth = frame.viewport.bounds.width.toInt(),
                    viewportHeight = frame.viewport.bounds.height.toInt(),
                    firstVisiblePageId = frame.progress.firstVisiblePageId,
                    lastVisiblePageId = frame.progress.lastVisiblePageId,
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
