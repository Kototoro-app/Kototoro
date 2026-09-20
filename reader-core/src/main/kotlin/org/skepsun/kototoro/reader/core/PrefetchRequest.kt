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

/** How far the image pipeline should prepare a predicted page. */
enum class PrefetchReadiness {
    /** Ensure encoded/source data is locally available without decoding presentation pixels. */
    SOURCE_READY,

    /** Ensure a renderer-ready image is decoded and available. */
    PRESENTATION_READY,
}

/**
 * Request emitted from ReaderCore / Prediction to the ImagePipeline.
 *
 * Conforms to ADR 0002 Principle:
 * "ReaderCore owns semantics and prediction; ImagePipeline owns execution and resource scheduling."
 *
 * @property pageId Identifier of the page to prefetch.
 * @property priority Scheduling priority.
 * @property readiness Resource stage that must be prepared independently from scheduling urgency.
 * @property predictedVisibleRegion Sub-rectangle of the page expected to become visible (useful for region/tiling).
 */
data class PrefetchRequest(
    val pageId: PageId,
    val priority: PrefetchPriority,
    val readiness: PrefetchReadiness,
    val predictedVisibleRegion: FloatRect? = null,
)

/** Complete desired image-resource window for one scene/viewport snapshot. */
data class ReaderResourceWindow(
    val requests: List<PrefetchRequest>,
)
