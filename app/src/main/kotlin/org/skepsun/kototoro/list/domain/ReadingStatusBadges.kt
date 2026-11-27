package org.skepsun.kototoro.list.domain

import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/** Value of `reading_status` inside every corner badge set. */
const val CARD_BADGE_READING_STATUS = "reading_status"

/**
 * The reading status a card shows: the status the user (or a tracker) persisted wins, and a list
 * without one falls back to what the reading progress already implies.
 *
 * The fallback deliberately does not invent [ScrobblingStatus.PLANNED] or
 * [ScrobblingStatus.ON_HOLD]: without a persisted status an untouched cover carries no badge at
 * all, and a started one can only be told apart as reading or finished.
 */
fun deriveReadingStatus(
    persisted: ScrobblingStatus?,
    progress: ReadingProgress?,
): ScrobblingStatus? = persisted ?: when {
    progress == null -> null
    progress.isCompleted() -> ScrobblingStatus.COMPLETED
    progress.percent > 0f -> ScrobblingStatus.READING
    else -> null
}

/** Parses a persisted `reading_status` name, ignoring values this build does not know. */
fun String?.toReadingStatusOrNull(): ScrobblingStatus? =
    this?.let { name -> ScrobblingStatus.entries.firstOrNull { it.name == name } }
