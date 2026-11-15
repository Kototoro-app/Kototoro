package org.skepsun.kototoro.details.ui.compose

/**
 * Whether the metadata source and the reading source of a work are the same plain source.
 *
 * Until a work is bound to a tracker the metadata simply comes from the source it is read from, so the pill under the
 * title printed that source twice. Merged, the source is shown once; the metadata half stays reachable as a compact
 * link control because the metadata sheet has no other way in. A tracker on either side is a real choice and keeps both
 * names.
 */
internal fun sourceSummaryIsMerged(
    metadataSourceName: String?,
    metadataIsTracker: Boolean,
    readingSourceName: String?,
    readingIsTracker: Boolean,
): Boolean = !metadataIsTracker &&
    !readingIsTracker &&
    metadataSourceName != null &&
    metadataSourceName == readingSourceName
