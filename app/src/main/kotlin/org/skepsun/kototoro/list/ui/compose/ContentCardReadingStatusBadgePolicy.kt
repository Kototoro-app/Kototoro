package org.skepsun.kototoro.list.ui.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.CardProgressStyle
import org.skepsun.kototoro.list.domain.CARD_BADGE_READING_STATUS
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblingStatus

/** The four corners of a cover a card can draw badges in. */
enum class ContentCardCorner { TOP_START, TOP_END, BOTTOM_START, BOTTOM_END }

/** Maps the layout [Alignment] of a corner badge row onto [ContentCardCorner]. */
fun Alignment.toContentCardCorner(): ContentCardCorner? = when (this) {
    Alignment.TopStart -> ContentCardCorner.TOP_START
    Alignment.TopEnd -> ContentCardCorner.TOP_END
    Alignment.BottomStart -> ContentCardCorner.BOTTOM_START
    Alignment.BottomEnd -> ContentCardCorner.BOTTOM_END
    else -> null
}

/**
 * Pure decision: whether the reading-status corner badge is drawn in [corner] of a card.
 *
 * Rules, in order:
 * 1. no status, or [ScrobblingStatus.PLANNED], draws nothing — "plan to read" is the default of
 *    every untouched work and would only add noise;
 * 2. the badge is drawn only in a corner whose configuration contains `reading_status`;
 * 3. with [CardProgressStyle.CIRCULAR_BADGE] the ring already says "finished" with its check mark,
 *    so [ScrobblingStatus.COMPLETED] is dropped; the bottom bar draws no such mark and keeps it;
 * 4. the ring is anchored in the bottom-end corner and the badge never overlaps it, so with
 *    [CardProgressStyle.CIRCULAR_BADGE] the bottom-end corner draws no reading status at all.
 *    Skipping beats relocating the badge to another corner: the badge stays in the corner the user
 *    chose or does not appear, and the ring keeps owning the one corner it already explains.
 */
fun shouldDrawReadingStatusBadge(
    status: ScrobblingStatus?,
    progressStyle: CardProgressStyle,
    corner: ContentCardCorner,
    cornerBadges: Set<String>,
): Boolean {
    if (status == null || status == ScrobblingStatus.PLANNED) return false
    if (CARD_BADGE_READING_STATUS !in cornerBadges) return false
    if (progressStyle != CardProgressStyle.CIRCULAR_BADGE) return true
    if (status == ScrobblingStatus.COMPLETED) return false
    return corner != ContentCardCorner.BOTTOM_END
}

/** The icon of a reading-status corner badge, mirroring the reading-status filter icons. */
val ScrobblingStatus.cardBadgeIconResId: Int
    get() = when (this) {
        ScrobblingStatus.PLANNED -> R.drawable.ic_bookmark
        ScrobblingStatus.READING -> R.drawable.ic_read
        ScrobblingStatus.RE_READING -> R.drawable.ic_history
        ScrobblingStatus.COMPLETED -> R.drawable.ic_state_finished
        ScrobblingStatus.ON_HOLD -> R.drawable.ic_action_pause
        ScrobblingStatus.DROPPED -> R.drawable.ic_state_abandoned
    }

/** The accessible name of a reading-status corner badge. */
val ScrobblingStatus.cardBadgeTitleResId: Int
    get() = when (this) {
        ScrobblingStatus.PLANNED -> R.string.reading_status_planned
        ScrobblingStatus.READING -> R.string.reading_status_reading
        ScrobblingStatus.RE_READING -> R.string.reading_status_re_reading
        ScrobblingStatus.COMPLETED -> R.string.reading_status_completed
        ScrobblingStatus.ON_HOLD -> R.string.reading_status_on_hold
        ScrobblingStatus.DROPPED -> R.string.reading_status_dropped
    }

/** Android host of the shared badge: resolves the icon and label for [status]. */
@Composable
fun ContentCardReadingStatusBadge(
    status: ScrobblingStatus,
    metrics: ContentCardBadgeMetrics = ContentCardBadgeMetrics(),
) {
    ContentCardReadingStatusBadge(
        icon = painterResource(status.cardBadgeIconResId),
        contentDescription = stringResource(status.cardBadgeTitleResId),
        metrics = metrics,
    )
}
