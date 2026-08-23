package org.skepsun.kototoro.tracker.domain

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Unified post-commit event for SourceTracker website sync (plan T4B.1).
 *
 * Produced by repositories *after* a local DB write commits successfully and consumed by
 * [SourceTrackerSyncManager]. Four kinds cover the reading and favorite axes; every event
 * carries at least a source-local [contentId] and the owning [sourceKey]
 * (`ContentSource.name`), which together identify the remote work for Phase 5+.
 */
sealed interface SourceTrackerEvent {

    /** Source-local content id (manga_id), as produced by the originating source. */
    val contentId: Long

    /** Canonical source key (`ContentSource.name`) that owns [contentId]. */
    val sourceKey: String

    /** Absolute content URL when known — used to match the remote work in Phase 5+. */
    val contentUrl: String?

    /** Reading-state update: progress moved forward. */
    data class Read(
        override val contentId: Long,
        override val sourceKey: String,
        val percent: Float,
        override val contentUrl: String? = null,
    ) : SourceTrackerEvent

    /** Reading-state update: progress cleared / marked unread. */
    data class Unread(
        override val contentId: Long,
        override val sourceKey: String,
        override val contentUrl: String? = null,
    ) : SourceTrackerEvent

    /**
     * Favorite-state update. [added] = true records a favourite; [added] = false records a
     * partial (category-level) removal where the work is still favorited elsewhere.
     */
    data class Favorite(
        override val contentId: Long,
        override val sourceKey: String,
        val added: Boolean,
        override val contentUrl: String? = null,
    ) : SourceTrackerEvent

    /** Favorite-state update: work is no longer a favourite anywhere. */
    data class Unfavorite(
        override val contentId: Long,
        override val sourceKey: String,
        override val contentUrl: String? = null,
    ) : SourceTrackerEvent
}

/**
 * Publishes [SourceTrackerEvent]s into a hot, ordered stream that consumers may subscribe to.
 *
 * [emit] is intentionally non-suspending: repositories call it right after a committed DB
 * write and must never block on backpressure.
 */
interface SourceTrackerEventEmitter {

    /** Non-suspending publish; never throws and never blocks the caller. */
    fun emit(event: SourceTrackerEvent)

    /** Hot stream of all emitted events. */
    val events: SharedFlow<SourceTrackerEvent>
}

/**
 * Process-wide shared event bus (a Kotlin object — a single instance by construction).
 *
 * The buffer is bounded with [BufferOverflow.DROP_OLDEST]: under burst pressure the oldest
 * un-processed events are dropped instead of blocking the DB write path, consistent with the
 * fold-latest semantics of the consumer (only the newest state per axis survives anyway).
 */
object SourceTrackerEventBus : SourceTrackerEventEmitter {

    /** Bounded extra buffer for slow consumers before [BufferOverflow.DROP_OLDEST] kicks in. */
    const val BUFFER_CAPACITY = 256

    private val _events = MutableSharedFlow<SourceTrackerEvent>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    override val events: SharedFlow<SourceTrackerEvent> = _events.asSharedFlow()

    override fun emit(event: SourceTrackerEvent) {
        _events.tryEmit(event)
    }
}
