package org.skepsun.kototoro.reader.core

/**
 * Priority assigned to image prefetch or decoding tasks by prediction engines.
 */
enum class PrefetchPriority {
    /** Currently visible inside the active viewport; needs immediate decode/render. */
    IMMEDIATE,

    /** High probability of intersecting viewport in the immediate lookahead window (< 300ms). */
    HIGH,

    /** Expected to become visible in medium term (300ms - 1000ms). */
    MEDIUM,

    /** Low priority prefetch or background buffer (> 1000ms). */
    LOW,
}

/**
 * Request emitted from ReaderCore / Prediction to the ImagePipeline.
 *
 * Conforms to ADR 0002 Principle:
 * "ReaderCore owns semantics and prediction; ImagePipeline owns execution and resource scheduling."
 *
 * @property pageId Identifier of the page to prefetch.
 * @property priority Scheduling priority.
 * @property predictedVisibleRegion Sub-rectangle of the page expected to become visible (useful for region/tiling).
 */
data class PrefetchRequest(
    val pageId: PageId,
    val priority: PrefetchPriority,
    val predictedVisibleRegion: FloatRect? = null,
)
