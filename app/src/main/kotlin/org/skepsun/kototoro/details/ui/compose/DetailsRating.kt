package org.skepsun.kototoro.details.ui.compose

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.ui.CommunityCommentsSheet
import org.skepsun.kototoro.community.ui.CommunityRatingUiState
import org.skepsun.kototoro.community.ui.CommunityRatingViewModel
import org.skepsun.kototoro.community.ui.DetailsRatingSection
import org.skepsun.kototoro.community.ui.DetailsScoreRow
import org.skepsun.kototoro.community.ui.RatingSyncTarget
import org.skepsun.kototoro.community.ui.rememberCommunitySources
import org.skepsun.kototoro.core.ui.compose.DetailsSectionDivider
import org.skepsun.kototoro.core.ui.compose.rememberResolvedSourceTitle
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.details.ui.model.LinkedTrackingItemUiModel
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.util.Locale
import kotlin.math.roundToInt

private const val COMMUNITY_TARGET = "community"

/** The reader's one rating on the 1–10 scale both the community and trackers use, or null when unrated. */
internal fun detailsMyRating(communityStars: Int?, trackerRating: Float): Int? =
    communityStars ?: (trackerRating.coerceIn(0f, 1f) * 10f).roundToInt().takeIf { it > 0 }

/**
 * The details page's rating section: the community score (while the community is on), the source's and trackers'
 * scores, and the reader's one rating, which goes to the community and every bound tracker unless the reader
 * deselects one. Replaces the separate rating chip and community card. Selections last for the page visit.
 */
@Composable
internal fun DetailsRatingHost(
    content: Content,
    community: CommunityRatingUiState?,
    communityViewModel: CommunityRatingViewModel,
    trackerRefs: List<CommunityExternalRef>,
    sourceScore: String?,
    linkedTrackingItems: List<LinkedTrackingItemUiModel>,
    trackerRating: Float,
    onUpdateTrackerRating: (Float, Set<ScrobblerService>) -> Unit,
    horizontalPadding: Dp,
    onSearchTitle: (String) -> Unit,
    onOpenContent: (Content) -> Unit,
    modifier: Modifier = Modifier,
) {
    var deselected by rememberSaveable(content.id) { mutableStateOf(emptyList<String>()) }
    var showComments by rememberSaveable { mutableStateOf(false) }
    val showSources = rememberCommunitySources(onOpenContent, onSearchTitle)
    val boundTrackers = linkedTrackingItems.filter { it.hasScrobblingBinding }
    val sourceTitle = rememberResolvedSourceTitle(content.source)
    val communityLabel = stringResource(R.string.community)
    val trackerTitles = linkedTrackingItems.associate { it.service to stringResource(it.service.titleResId) }

    val scores = buildList {
        sourceScore?.let { score -> add(DetailsScoreRow(key = "source", label = sourceTitle, value = score)) }
        linkedTrackingItems.forEach { linked ->
            val score = linked.communityScore ?: return@forEach
            add(
                DetailsScoreRow(
                    key = "tracker:${linked.service.name}",
                    label = trackerTitles.getValue(linked.service),
                    value = String.format(Locale.getDefault(), "%.1f", score),
                    icon = { TrackerIcon(linked.service) },
                ),
            )
        }
    }
    val targets = buildList {
        if (community != null) {
            add(RatingSyncTarget(COMMUNITY_TARGET, communityLabel, selected = COMMUNITY_TARGET !in deselected))
        }
        boundTrackers.forEach { linked ->
            val key = linked.service.name
            add(RatingSyncTarget(key, trackerTitles.getValue(linked.service), selected = key !in deselected))
        }
    }
    if (community == null && scores.isEmpty() && targets.isEmpty()) return

    fun selectedTrackers(): Set<ScrobblerService> = boundTrackers
        .map { it.service }
        .filterTo(mutableSetOf()) { it.name !in deselected }

    Column(modifier = modifier.fillMaxWidth().padding(top = 12.dp)) {
    DetailsSectionDivider(horizontalPadding = horizontalPadding)
    DetailsRatingSection(
        community = community,
        scores = scores,
        myRating = detailsMyRating(community?.myStars, trackerRating),
        isPublishing = community?.isPublishing == true,
        syncTargets = targets,
        onToggleSyncTarget = { key ->
            deselected = if (key in deselected) deselected - key else deselected + key
        },
        onRate = { stars ->
            if (community != null && COMMUNITY_TARGET !in deselected && community.refusal == null) {
                communityViewModel.rate(stars)
            }
            selectedTrackers().takeIf { it.isNotEmpty() }?.let { onUpdateTrackerRating(stars / 10f, it) }
        },
        onWithdraw = {
            if (community != null && COMMUNITY_TARGET !in deselected) communityViewModel.withdraw()
            selectedTrackers().takeIf { it.isNotEmpty() }?.let { onUpdateTrackerRating(0f, it) }
        },
        onOpenComments = { showComments = true },
        onOpenWork = { id, title -> communityViewModel.openWork(id, title, onOpenContent, onSearchTitle) },
        contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 12.dp),
        railPadding = horizontalPadding,
        onShowSources = showSources,
        communityTargetKey = COMMUNITY_TARGET,
    )
    }

    if (showComments) {
        CommunityCommentsSheet(content = content, trackerRefs = trackerRefs, onDismiss = { showComments = false })
    }
}

@Composable
private fun TrackerIcon(service: ScrobblerService) {
    Icon(
        painter = rememberSafePainter(service.iconResId),
        contentDescription = null,
        tint = Color.Unspecified,
        modifier = Modifier.size(18.dp),
    )
}
