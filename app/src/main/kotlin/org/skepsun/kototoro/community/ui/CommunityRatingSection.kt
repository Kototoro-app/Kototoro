package org.skepsun.kototoro.community.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.parsers.model.Content

/**
 * The community rating of a work and the reader's own (plan §7.2), drawn by the shared [CommunityRatingCard]. Draws
 * nothing while the community is off, so details pages of readers who never opted in are unchanged. [container] wraps
 * the card in the page's own panel style.
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
    var showComments by rememberSaveable { mutableStateOf(false) }

    CommunityRatingCard(
        state = state,
        onRate = viewModel::rate,
        onWithdraw = viewModel::withdraw,
        onOpenComments = { showComments = true },
        onSearchTitle = onSearchTitle,
        container = container,
        modifier = modifier,
        railPadding = railPadding,
    )

    if (showComments) {
        CommunityCommentsSheet(content = content, trackerRefs = trackerRefs, onDismiss = { showComments = false })
    }
}
