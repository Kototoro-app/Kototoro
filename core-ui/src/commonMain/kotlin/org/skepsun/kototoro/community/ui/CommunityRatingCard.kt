package org.skepsun.kototoro.community.ui

import androidx.compose.runtime.Immutable
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import org.skepsun.kototoro.core.ui.compose.DetailsSectionHeader
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.community.domain.CommunitySimilarWork
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons
import kotlin.math.ceil

data class CommunityRatingUiState(
    /** The work's keys are still being worked out (tracker id searches can take seconds). */
    val isLoading: Boolean,
    val rating: CommunityRatingSummary?,
    val myStars: Int?,
    /** Comments the index counted; the comment list itself reads the relays. */
    val comments: Int,
    /** What readers who rated this work also rated highly (the index's similar works). */
    val similar: List<CommunitySimilarWork>,
    /** Why the reader cannot rate this work, or null when they can. */
    val refusal: CommunityRatingRefusal?,
    val isPublishing: Boolean,
    /** Readers who rated it after the index snapshot was built; the score above does not include them yet. */
    val freshRatings: Int = 0,
    /** Whether the reader's own rating is one of [freshRatings]. */
    val myRatingFresh: Boolean = false,
)

/**
 * The community rating of a work and the reader's own (plan §7.2), with the rail of works its raters also liked.
 * [container] wraps the card in the page's own panel style; the host owns the comment list [onOpenComments] opens,
 * and [onOpenWork] finds a similar work, which the index knows by its group id and title only, in the reader's library
 * or sources.
 */
@Composable
fun CommunityRatingCard(
    state: CommunityRatingUiState,
    onRate: (Int) -> Unit,
    onWithdraw: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenWork: (id: String, title: String) -> Unit,
    container: @Composable (@Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    railPadding: Dp = 0.dp,
    /** Inside [container]; a host that draws no container passes its page padding instead. */
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
    /** A long press on a similar work: where else it can be looked for. */
    onShowSources: ((id: String, title: String) -> Unit)? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val canRate = state.refusal == null && !state.isLoading

    container {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(contentPadding)
                .testTag("community-rating"),
        ) {
            DetailsSectionHeader(title = uiString("community_rating_title")) {
                // The index's count lags by up to a run; the list reads the relays and shows what exists now.
                CommentsPill(count = state.comments, onClick = onOpenComments)
            }
            ScoreOverview(
                rating = state.rating,
                isLoading = state.isLoading,
                modifier = Modifier.padding(top = 12.dp),
            )
            freshRatingsText(state)?.let { text ->
                // The index runs every six hours; without this a reader's own rating looks lost until it does.
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).testTag("community-rating-fresh"),
                )
            }
            HorizontalDivider(
                modifier = Modifier.padding(vertical = 12.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            )
            MyRatingRow(
                myStars = state.myStars,
                canRate = canRate,
                isPublishing = state.isPublishing,
                onRate = { showDialog = true },
            )
            communityRefusalText(state.refusal)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }

    val similar = state.similar.filter { !it.title.isNullOrBlank() }
    if (similar.isNotEmpty()) {
        CommunityWorksRail(
            title = uiString("community_similar_title"),
            cards = similar.map { work ->
                CommunityWorkCard(
                    work.id,
                    work.title.orEmpty(),
                    // Too few shared raters yet: the index related them by the tags their raters' sources agree on.
                    if (work.coRaters > 0) uiString("community_similar_supporting", work.coRaters)
                    else uiString("community_similar_tags", work.sharedTags),
                    // Its score is how alike the two are (0–1), not a rating; a star beside it would read as one.
                    score = null,
                )
            },
            onOpen = { onOpenWork(it.key, it.title) },
            onLongOpen = onShowSources?.let { show -> { show(it.key, it.title) } },
            horizontalPadding = railPadding,
            modifier = Modifier.padding(vertical = 6.dp),
            testTag = "community-similar",
        )
    }

    if (showDialog) {
        CommunityRatingDialog(
            current = state.myStars,
            onRate = { stars ->
                showDialog = false
                onRate(stars)
            },
            onWithdraw = {
                showDialog = false
                onWithdraw()
            },
            onDismiss = { showDialog = false },
        )
    }
}

@Composable
private fun CommentsPill(count: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.testTag("community-comments-open"),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(ReaderPanelIcons.Comment, contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = if (count > 0) uiString("community_comments_count", count) else uiString("community_comments"),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

/** The big score with its stars and count, and how the ratings spread over one to five stars. */
@Composable
private fun ScoreOverview(rating: CommunityRatingSummary?, isLoading: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(
            modifier = Modifier.widthIn(min = 92.dp).testTag("community-rating-score"),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                // The mean readers gave. The Bayesian score is for ordering charts: with few ratings it sits near the
                // medium's mean, so a single 10 would read as 9.8.
                text = rating?.let { formatCommunityScore(it.mean) } ?: "–",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = if (rating == null) colors.onSurfaceVariant else colors.onSurface,
            )
            StarBar(value = (rating?.mean ?: 0.0) / 2, starSize = 14.dp)
            Text(
                text = when {
                    isLoading -> uiString("loading_")
                    rating == null -> uiString("community_rating_none")
                    else -> uiString("community_rating_count", rating.count)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        if (rating == null) {
            Text(
                text = if (isLoading) "" else uiString("community_rating_be_first"),
                style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
        } else {
            Distribution(
                buckets = CommunityHighlights.starBuckets(rating.distribution),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Distribution(buckets: List<Int>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val total = buckets.sum().coerceAtLeast(1)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (star in 5 downTo 1) {
            val share = buckets[star - 1].toFloat() / total
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = star.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.width(12.dp),
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(colors.surfaceContainerHighest),
                ) {
                    if (share > 0f) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(share)
                                .fillMaxHeight()
                                .clip(CircleShape)
                                .background(communityStarColor()),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MyRatingRow(myStars: Int?, canRate: Boolean, isPublishing: Boolean, onRate: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = canRate && !isPublishing, onClick = onRate),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = uiString("community_rating_your"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                StarBar(value = (myStars ?: 0) / 2.0, starSize = 20.dp)
                myStars?.let { stars ->
                    Text(
                        text = "$stars / 10 · ${uiString(ratingLevel(stars))}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        when {
            isPublishing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            canRate -> FilledTonalButton(onClick = onRate, modifier = Modifier.testTag("community-rating-rate")) {
                Text(if (myStars != null) uiString("edit") else uiString("community_rating_rate"))
            }
        }
    }
}

/** Five stars filled to [value] (0–5, fractions allowed), for display. */
@Composable
fun StarBar(value: Double, starSize: Dp, modifier: Modifier = Modifier) {
    val filled = communityStarColor()
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier) {
        for (index in 0 until 5) {
            val fraction = (value - index).coerceIn(0.0, 1.0).toFloat()
            Box(modifier = Modifier.size(starSize)) {
                Icon(SettingsMaterialIcons.Star, contentDescription = null, tint = empty, modifier = Modifier.size(starSize))
                if (fraction > 0f) {
                    Icon(
                        SettingsMaterialIcons.Star,
                        contentDescription = null,
                        tint = filled,
                        modifier = Modifier
                            .size(starSize)
                            .drawWithContent { clipRect(right = size.width * fraction) { this@drawWithContent.drawContent() } },
                    )
                }
            }
        }
    }
}

/**
 * Five large stars for choosing 1–10: the left half of a star gives half of it. A tap or a drag across the row picks the
 * score, the way most rating widgets work.
 */
@Composable
private fun StarPicker(value: Int?, onChange: (Int) -> Unit, modifier: Modifier = Modifier) {
    val starSize = 44.dp
    val description = value?.let { "$it / 10" }.orEmpty()
    Row(
        modifier = modifier
            .semantics { contentDescription = description }
            .pointerInput(Unit) {
                fun at(x: Float) = ceil(x / size.width * 10).toInt().coerceIn(1, 10)
                detectTapGestures { onChange(at(it.x)) }
            }
            .pointerInput(Unit) {
                fun at(x: Float) = ceil(x / size.width * 10).toInt().coerceIn(1, 10)
                detectHorizontalDragGestures { change, _ -> onChange(at(change.position.x)) }
            },
    ) {
        for (index in 0 until 5) {
            Box(modifier = Modifier.testTag("community-rating-star-${index * 2 + 2}")) {
                StarBar(value = ((value ?: 0) / 2.0 - index).coerceIn(0.0, 1.0), starSize = starSize, modifier = Modifier.width(starSize))
            }
        }
    }
}

@Composable
private fun CommunityRatingDialog(
    current: Int?,
    onRate: (Int) -> Unit,
    onWithdraw: () -> Unit,
    /** Off when the rating is not going to the community, whose publicity the note is about. */
    showPublicNote: Boolean = true,
    onDismiss: () -> Unit,
) {
    var selected by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiString("community_rating_dialog_title")) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StarPicker(value = selected, onChange = { selected = it })
                Text(
                    text = selected?.let { "$it / 10 · ${uiString(ratingLevel(it))}" } ?: uiString("community_rating_tap_stars"),
                    style = if (selected == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                if (showPublicNote) {
                    Text(
                        text = uiString("community_rating_public_note"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onRate) },
                // The same score again is allowed: it republishes a rating whose earlier copy never reached a relay.
                enabled = selected != null,
                modifier = Modifier.testTag("community-rating-confirm"),
            ) { Text(uiString("ok")) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onWithdraw, modifier = Modifier.testTag("community-rating-withdraw")) {
                        Text(uiString("community_rating_withdraw"))
                    }
                }
                TextButton(onClick = onDismiss) { Text(uiString("cancel")) }
            }
        },
    )
}

@Composable
private fun freshRatingsText(state: CommunityRatingUiState): String? = when {
    state.myRatingFresh && state.freshRatings <= 1 -> uiString("community_rating_fresh_mine")
    state.myRatingFresh -> uiString("community_rating_fresh_with_mine", state.freshRatings)
    state.freshRatings > 0 -> uiString("community_rating_fresh", state.freshRatings)
    else -> null
}

private fun ratingLevel(stars: Int): String = when ((stars + 1) / 2) {
    1 -> "community_rating_level_1"
    2 -> "community_rating_level_2"
    3 -> "community_rating_level_3"
    4 -> "community_rating_level_4"
    else -> "community_rating_level_5"
}

@Composable
fun communityRefusalText(refusal: CommunityRatingRefusal?): String? = when (refusal) {
    CommunityRatingRefusal.NSFW -> uiString("community_rating_nsfw_off")
    CommunityRatingRefusal.NO_IDENTITY -> uiString("community_rating_no_identity")
    CommunityRatingRefusal.NO_KEYS -> uiString("community_rating_no_keys")
    CommunityRatingRefusal.DISABLED, null -> null
}

/** A score the reader does not set: the source's own rating or a tracker's average, shown as a row under the overview. */
@Immutable
data class DetailsScoreRow(
    val key: String,
    val label: String,
    val value: String,
    val icon: (@Composable () -> Unit)? = null,
)

/** Somewhere the reader's one rating is sent: the community or a bound tracker. All are on until the reader says not. */
@Immutable
data class RatingSyncTarget(
    val key: String,
    val label: String,
    val selected: Boolean,
)

/**
 * The details page's single rating section: the community's score and spread when the community is on ([community]
 * non-null), the other scores the work has ([scores]), then the reader's own rating, set once and sent to every
 * selected [syncTargets] entry. The host converts the 1–10 score to each tracker's scale and draws no panel around
 * it; [onRate] and [onWithdraw] go to the selected targets only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DetailsRatingSection(
    community: CommunityRatingUiState?,
    scores: List<DetailsScoreRow>,
    myRating: Int?,
    isPublishing: Boolean,
    syncTargets: List<RatingSyncTarget>,
    onToggleSyncTarget: (String) -> Unit,
    onRate: (Int) -> Unit,
    onWithdraw: () -> Unit,
    onOpenComments: () -> Unit,
    onOpenWork: (id: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    railPadding: Dp = 0.dp,
    onShowSources: ((id: String, title: String) -> Unit)? = null,
    communityTargetKey: String? = null,
) {
    var showDialog by rememberSaveable { mutableStateOf(false) }
    val anySelected = syncTargets.any { it.selected }
    val communityBlocked = community != null && (community.refusal != null || community.isLoading)
    val canRate = syncTargets.isNotEmpty() && anySelected && !(communityBlocked && syncTargets.all { it.key == communityTargetKey })

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(contentPadding)
            .testTag("details-rating"),
    ) {
        DetailsSectionHeader(title = uiString("rating")) {
            if (community != null) CommentsPill(count = community.comments, onClick = onOpenComments)
        }
        if (community != null) {
            ScoreOverview(
                rating = community.rating,
                isLoading = community.isLoading,
                modifier = Modifier.padding(top = 8.dp),
            )
            freshRatingsText(community)?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp).testTag("community-rating-fresh"),
                )
            }
        }
        if (scores.isNotEmpty()) {
            Column(modifier = Modifier.padding(top = if (community != null) 8.dp else 0.dp)) {
                scores.forEach { row ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .testTag("details-rating-score:${row.key}"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.icon?.invoke()
                        Text(
                            text = row.label,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text = row.value,
                            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
        HorizontalDivider(
            modifier = Modifier.padding(vertical = 12.dp),
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
        )
        MyRatingRow(
            myStars = myRating,
            canRate = canRate,
            isPublishing = isPublishing,
            onRate = { showDialog = true },
        )
        if (syncTargets.isEmpty()) {
            Text(
                text = uiString("details_rating_unavailable"),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        } else {
            FlowRow(
                modifier = Modifier.padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = uiString("details_rating_sync_to"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                syncTargets.forEach { target ->
                    FilterChip(
                        selected = target.selected,
                        onClick = { onToggleSyncTarget(target.key) },
                        label = { Text(target.label) },
                        modifier = Modifier.testTag("details-rating-sync:${target.key}"),
                    )
                }
            }
        }
        community?.let { communityRefusalText(it.refusal) }?.let { text ->
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }

    val similar = community?.similar.orEmpty().filter { !it.title.isNullOrBlank() }
    if (similar.isNotEmpty()) {
        CommunityWorksRail(
            title = uiString("community_similar_title"),
            cards = similar.map { work ->
                CommunityWorkCard(
                    work.id,
                    work.title.orEmpty(),
                    if (work.coRaters > 0) uiString("community_similar_supporting", work.coRaters)
                    else uiString("community_similar_tags", work.sharedTags),
                    score = null,
                )
            },
            onOpen = { onOpenWork(it.key, it.title) },
            onLongOpen = onShowSources?.let { show -> { show(it.key, it.title) } },
            horizontalPadding = railPadding,
            modifier = Modifier.padding(vertical = 6.dp),
            testTag = "community-similar",
        )
    }

    if (showDialog) {
        CommunityRatingDialog(
            current = myRating,
            onRate = { stars ->
                showDialog = false
                onRate(stars)
            },
            onWithdraw = {
                showDialog = false
                onWithdraw()
            },
            onDismiss = { showDialog = false },
            showPublicNote = syncTargets.any { it.selected && it.key == communityTargetKey },
        )
    }
}
