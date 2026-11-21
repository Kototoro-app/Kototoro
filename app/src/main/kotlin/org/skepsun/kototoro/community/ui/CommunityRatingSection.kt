package org.skepsun.kototoro.community.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityHighlights
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityRatingSummary
import org.skepsun.kototoro.parsers.model.Content
import kotlin.math.ceil

/**
 * The community rating of a work and the reader's own (plan §7.2). Draws nothing while the community is off, so details
 * pages of readers who never opted in are unchanged. [container] wraps the card in the page's own panel style.
 */
@Composable
fun CommunityRatingSection(
    content: Content,
    trackerRefs: List<CommunityExternalRef>,
    container: @Composable (@Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
    railPadding: Dp = 0.dp,
    onSearchTitle: (String) -> Unit = {},
) {
    val viewModel = hiltViewModel<CommunityRatingViewModel>(key = "community-rating-${content.id}")
    LaunchedEffect(content, trackerRefs) { viewModel.bind(content, trackerRefs) }
    val state = viewModel.state.collectAsStateWithLifecycle().value ?: return
    var showDialog by rememberSaveable { mutableStateOf(false) }
    var showComments by rememberSaveable { mutableStateOf(false) }
    val canRate = state.refusal == null && !state.isLoading

    container {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .testTag("community-rating"),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.community_rating_title),
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                // The index's count lags by up to a run; the sheet reads the relays and shows what exists now.
                CommentsPill(count = state.comments, onClick = { showComments = true })
            }
            ScoreOverview(
                rating = state.rating,
                isLoading = state.isLoading,
                modifier = Modifier.padding(top = 12.dp),
            )
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
            refusalText(state.refusal)?.let { text ->
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
            title = stringResource(R.string.community_similar_title),
            cards = similar.map { work ->
                CommunityWorkCard(
                    work.id,
                    work.title.orEmpty(),
                    stringResource(R.string.community_similar_supporting, work.coRaters),
                    score = work.score,
                )
            },
            onOpen = { onSearchTitle(it.title) },
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
                viewModel.rate(stars)
            },
            onWithdraw = {
                showDialog = false
                viewModel.withdraw()
            },
            onDismiss = { showDialog = false },
        )
    }

    if (showComments) {
        CommunityCommentsSheet(content = content, trackerRefs = trackerRefs, onDismiss = { showComments = false })
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
            Icon(painterResource(R.drawable.ic_comment), contentDescription = null, modifier = Modifier.size(16.dp))
            Text(
                text = if (count > 0) {
                    stringResource(R.string.community_comments_count, count)
                } else {
                    stringResource(R.string.community_comments)
                },
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
                text = rating?.let { formatCommunityScore(it.score) } ?: "–",
                style = MaterialTheme.typography.displaySmall.copy(fontWeight = FontWeight.Bold),
                color = if (rating == null) colors.onSurfaceVariant else colors.onSurface,
            )
            StarBar(value = (rating?.score ?: 0.0) / 2, starSize = 14.dp)
            Text(
                text = when {
                    isLoading -> stringResource(R.string.loading_)
                    rating == null -> stringResource(R.string.community_rating_none)
                    else -> stringResource(R.string.community_rating_count, rating.count)
                },
                style = MaterialTheme.typography.labelSmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(modifier = Modifier.width(16.dp))
        if (rating == null) {
            Text(
                text = if (isLoading) "" else stringResource(R.string.community_rating_be_first),
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
                text = stringResource(R.string.community_rating_your),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                StarBar(value = (myStars ?: 0) / 2.0, starSize = 20.dp)
                myStars?.let { stars ->
                    Text(
                        text = "$stars / 10 · ${stringResource(ratingLevel(stars))}",
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
            }
        }
        when {
            isPublishing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            canRate -> FilledTonalButton(onClick = onRate, modifier = Modifier.testTag("community-rating-rate")) {
                Text(
                    myStars?.let { stringResource(R.string.edit) } ?: stringResource(R.string.community_rating_rate),
                )
            }
        }
    }
}

/** Five stars filled to [value] (0–5, fractions allowed), for display. */
@Composable
internal fun StarBar(value: Double, starSize: Dp, modifier: Modifier = Modifier) {
    val filled = communityStarColor()
    val empty = MaterialTheme.colorScheme.outlineVariant
    Row(modifier = modifier) {
        for (index in 0 until 5) {
            val fraction = (value - index).coerceIn(0.0, 1.0).toFloat()
            Box(modifier = Modifier.size(starSize)) {
                Icon(Icons.Filled.Star, contentDescription = null, tint = empty, modifier = Modifier.size(starSize))
                if (fraction > 0f) {
                    Icon(
                        Icons.Filled.Star,
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
    onDismiss: () -> Unit,
) {
    var selected by remember(current) { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.community_rating_dialog_title)) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                StarPicker(value = selected, onChange = { selected = it })
                Text(
                    text = selected?.let { "$it / 10 · ${stringResource(ratingLevel(it))}" }
                        ?: stringResource(R.string.community_rating_tap_stars),
                    style = if (selected == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium,
                    color = if (selected == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.community_rating_public_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { selected?.let(onRate) },
                // The same score again is allowed: it republishes a rating whose earlier copy never reached a relay.
                enabled = selected != null,
                modifier = Modifier.testTag("community-rating-confirm"),
            ) { Text(stringResource(android.R.string.ok)) }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    TextButton(onClick = onWithdraw, modifier = Modifier.testTag("community-rating-withdraw")) {
                        Text(stringResource(R.string.community_rating_withdraw))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
            }
        },
    )
}

private fun ratingLevel(stars: Int): Int = when ((stars + 1) / 2) {
    1 -> R.string.community_rating_level_1
    2 -> R.string.community_rating_level_2
    3 -> R.string.community_rating_level_3
    4 -> R.string.community_rating_level_4
    else -> R.string.community_rating_level_5
}

@Composable
internal fun refusalText(refusal: CommunityRatingRefusal?): String? = when (refusal) {
    CommunityRatingRefusal.NSFW -> stringResource(R.string.community_rating_nsfw_off)
    CommunityRatingRefusal.NO_IDENTITY -> stringResource(R.string.community_rating_no_identity)
    CommunityRatingRefusal.NO_KEYS -> stringResource(R.string.community_rating_no_keys)
    CommunityRatingRefusal.DISABLED, null -> null
}
