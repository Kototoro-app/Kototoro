package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.parsers.model.Content
import java.util.Locale

/**
 * The community rating of a work and the reader's own (plan §7.2). Draws nothing while the community is off, so details
 * pages of readers who never opted in are unchanged. [container] wraps the row in the page's own panel style.
 */
@Composable
fun CommunityRatingSection(
    content: Content,
    trackerRefs: List<CommunityExternalRef>,
    container: @Composable (@Composable () -> Unit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel = hiltViewModel<CommunityRatingViewModel>(key = "community-rating-${content.id}")
    LaunchedEffect(content, trackerRefs) { viewModel.bind(content, trackerRefs) }
    val state = viewModel.state.collectAsStateWithLifecycle().value ?: return
    var showDialog by rememberSaveable { mutableStateOf(false) }

    container {
        Row(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp)
                .testTag("community-rating"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.community_rating_title),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rating = state.rating
                Text(
                    text = when {
                        state.isLoading -> stringResource(R.string.loading_)
                        rating == null -> stringResource(R.string.community_rating_none)
                        else -> stringResource(R.string.community_rating_summary, formatScore(rating.score), rating.count)
                    },
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.testTag("community-rating-score"),
                )
                refusalText(state.refusal)?.let { text ->
                    Text(
                        text = text,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            when {
                state.isPublishing -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                state.refusal == null && !state.isLoading -> FilledTonalButton(
                    onClick = { showDialog = true },
                    modifier = Modifier.testTag("community-rating-rate"),
                ) {
                    Text(
                        state.myStars?.let { stringResource(R.string.community_rating_mine, it) }
                            ?: stringResource(R.string.community_rating_rate),
                    )
                }
            }
        }
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
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (row in listOf(1..5, 6..10)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (stars in row) {
                            FilterChip(
                                selected = selected == stars,
                                onClick = { selected = stars },
                                label = { Text(stars.toString()) },
                                modifier = Modifier.testTag("community-rating-star-$stars"),
                            )
                        }
                    }
                }
                Text(
                    text = stringResource(R.string.community_rating_public_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

@Composable
private fun refusalText(refusal: CommunityRatingRefusal?): String? = when (refusal) {
    CommunityRatingRefusal.NSFW -> stringResource(R.string.community_rating_nsfw_off)
    CommunityRatingRefusal.NO_IDENTITY -> stringResource(R.string.community_rating_no_identity)
    CommunityRatingRefusal.NO_KEYS -> stringResource(R.string.community_rating_no_keys)
    CommunityRatingRefusal.DISABLED, null -> null
}

private fun formatScore(score: Double) = String.format(Locale.ROOT, "%.1f", score)
