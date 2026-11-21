package org.skepsun.kototoro.community.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.community.domain.CommunityCommentEntry
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.domain.CommunityThread
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.parsers.model.Content

/** A work's community comments in a bottom sheet; the list and composer are the shared [CommunityCommentsContent]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommunityCommentsSheet(
    content: Content,
    trackerRefs: List<CommunityExternalRef>,
    onDismiss: () -> Unit,
) {
    val viewModel = hiltViewModel<CommunityCommentsViewModel>(key = "community-comments-${content.id}")
    LaunchedEffect(content, trackerRefs) { viewModel.bind(content, trackerRefs) }
    val state = viewModel.state.collectAsStateWithLifecycle().value
    val entries = remember(state.page) {
        state.page?.threads.orEmpty().flatMap { listOf(it.root) + it.replies }.associateBy { it.comment.id }
    }
    val view = remember(state) {
        CommunityCommentsViewState(
            isLoading = state.isLoading,
            threads = state.page?.threads.orEmpty().map { thread ->
                CommunityThread(thread.root.toRow(), thread.replies.map { it.toRow() })
            },
            relaysAnswered = state.page?.relaysAnswered,
            isPosting = state.isPosting,
            refusal = state.refusal,
            replyTo = state.replyTo?.toRow(),
        )
    }

    TvModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        CommunityCommentsContent(
            state = view,
            formatTime = { DateUtils.getRelativeTimeSpanString(it * 1000).toString() },
            onRefresh = viewModel::refresh,
            onReplyTo = { row -> viewModel.replyTo(row?.let { entries[it.id] }) },
            onPost = viewModel::post,
            onDelete = { row -> entries[row.id]?.let(viewModel::delete) },
            onReport = { row, type -> entries[row.id]?.let { viewModel.report(it, type) } },
            onBlock = { row -> entries[row.id]?.let(viewModel::block) },
            modifier = Modifier.fillMaxHeight(0.9f).imePadding(),
        )
    }
}

private fun CommunityCommentEntry.toRow() = CommunityCommentRow(
    id = comment.id,
    pubkey = comment.pubkey,
    author = author,
    content = comment.content,
    createdAt = comment.createdAt,
    spoiler = comment.spoiler,
    isMine = isMine,
    isPending = isPending,
)
