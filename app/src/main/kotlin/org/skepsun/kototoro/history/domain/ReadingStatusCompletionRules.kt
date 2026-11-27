package org.skepsun.kototoro.history.domain

import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/**
 * Statuses the user never picked by hand, so finishing a title may upgrade them to [ScrobblingStatus.COMPLETED].
 * Everything else (ON_HOLD, DROPPED, RE_READING, COMPLETED) is a deliberate choice and must survive.
 */
private val AUTO_COMPLETABLE_STATUSES: Set<ScrobblingStatus?> = setOf(
    null,
    ScrobblingStatus.PLANNED,
    ScrobblingStatus.READING,
)

/**
 * The reading status to store after the reader reached [percent], or `null` when nothing should change.
 *
 * Pure on purpose: the repositories only talk to the database, the whole decision lives here (issue #750).
 */
fun readingStatusAfterCompletion(
    current: ScrobblingStatus?,
    percent: Float,
    enabled: Boolean,
): ScrobblingStatus? {
    if (!enabled || !ReadingProgress.isCompleted(percent)) {
        return null
    }
    return if (current in AUTO_COMPLETABLE_STATUSES) ScrobblingStatus.COMPLETED else null
}
