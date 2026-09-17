package org.skepsun.kototoro.reader.core

import kotlin.math.abs

/**
 * Axis projection helper for mapping physical viewport motion and bounds expansion
 * along the reading direction of a [ReaderScene].
 */
object SceneAxisProjection {

    /**
     * Resolves forward reading velocity given the physical velocity and reading direction.
     * Forward motion in reading direction is always positive.
     */
    fun forwardVelocity(motion: ViewportMotion, direction: SceneReadingDirection): Float {
        val raw = when (direction) {
            SceneReadingDirection.TOP_TO_BOTTOM -> motion.velocityY
            SceneReadingDirection.LEFT_TO_RIGHT,
            SceneReadingDirection.RIGHT_TO_LEFT -> motion.velocityX
        }
        return raw * direction.directionSign
    }

    /**
     * Expands [viewport] forward and backward along the scene's reading axis.
     *
     * @param viewport The base viewport.
     * @param direction Reading direction of the scene.
     * @param aheadPx Pixels to expand forward in the reading direction.
     * @param behindPx Pixels to expand backward against the reading direction.
     */
    fun expand(
        viewport: ReaderViewport,
        direction: SceneReadingDirection,
        aheadPx: Float,
        behindPx: Float,
    ): ReaderViewport {
        val b = viewport.bounds
        val expandedBounds = when (direction) {
            SceneReadingDirection.TOP_TO_BOTTOM -> {
                FloatRect.fromLtwh(
                    left = b.left,
                    top = (b.top - behindPx).coerceAtLeast(0f),
                    width = b.width,
                    height = b.height + behindPx + aheadPx,
                )
            }
            SceneReadingDirection.LEFT_TO_RIGHT -> {
                FloatRect.fromLtwh(
                    left = (b.left - behindPx).coerceAtLeast(0f),
                    top = b.top,
                    width = b.width + behindPx + aheadPx,
                    height = b.height,
                )
            }
            SceneReadingDirection.RIGHT_TO_LEFT -> {
                FloatRect.fromLtwh(
                    left = (b.left - aheadPx).coerceAtLeast(0f),
                    top = b.top,
                    width = b.width + aheadPx + behindPx,
                    height = b.height,
                )
            }
        }
        return ReaderViewport(expandedBounds)
    }

    /**
     * Returns the primary dimension of the viewport along the reading axis.
     */
    fun primaryDimension(bounds: FloatRect, direction: SceneReadingDirection): Float {
        return if (direction.isVertical) bounds.height else bounds.width
    }
}
