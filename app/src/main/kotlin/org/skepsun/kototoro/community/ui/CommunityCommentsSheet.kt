package org.skepsun.kototoro.community.ui

import android.text.format.DateUtils
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.skepsun.kototoro.R
import org.skepsun.kototoro.community.domain.CommunityCommentEntry
import org.skepsun.kototoro.community.domain.CommunityExternalRef
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.core.ui.compose.TvModalBottomSheet
import org.skepsun.kototoro.parsers.model.Content

/** A work's community comments: threads from the relays, a composer, and reply, report, block and delete per comment. */
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
    var reporting by remember { mutableStateOf<CommunityCommentEntry?>(null) }

    TvModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(modifier = Modifier.fillMaxHeight(0.9f).imePadding().testTag("community-comments")) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val total = state.page?.threads?.sumOf { 1 + it.replies.size } ?: 0
                Text(
                    text = if (total > 0) {
                        stringResource(R.string.community_comments_count, total)
                    } else {
                        stringResource(R.string.community_comments)
                    },
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = viewModel::refresh, enabled = !state.isLoading) {
                    Text(stringResource(R.string.community_comments_reload))
                }
            }
            if (state.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
            val threads = state.page?.threads.orEmpty()
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when {
                    threads.isNotEmpty() -> LazyColumn(
                        modifier = Modifier.fillMaxWidth().testTag("community-comments-list"),
                        contentPadding = PaddingValues(vertical = 8.dp),
                    ) {
                        threads.forEachIndexed { index, thread ->
                            if (index > 0) {
                                item(key = "divider-${thread.root.comment.id}") {
                                    HorizontalDivider(
                                        modifier = Modifier.padding(start = 68.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                    )
                                }
                            }
                            item(key = thread.root.comment.id) {
                                CommentItem(thread.root, indent = false, viewModel, onReport = { reporting = it })
                            }
                            items(thread.replies, key = { it.comment.id }) { reply ->
                                CommentItem(reply, indent = true, viewModel, onReport = { reporting = it })
                            }
                        }
                    }
                    !state.isLoading -> Column(
                        modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            painterResource(R.drawable.ic_comment),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(40.dp),
                        )
                        Text(
                            text = stringResource(
                                if (state.page?.relaysAnswered == 0) R.string.community_comments_offline else R.string.community_comments_empty,
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 12.dp),
                        )
                    }
                }
            }
            HorizontalDivider()
            Composer(state, viewModel)
        }
    }

    reporting?.let { entry ->
        ReportDialog(
            onReport = { type ->
                reporting = null
                viewModel.report(entry, type)
            },
            onDismiss = { reporting = null },
        )
    }
}

@Composable
private fun CommentItem(
    entry: CommunityCommentEntry,
    indent: Boolean,
    viewModel: CommunityCommentsViewModel,
    onReport: (CommunityCommentEntry) -> Unit,
) {
    var revealed by rememberSaveable(entry.comment.id) { mutableStateOf(!entry.comment.spoiler) }
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 68.dp else 20.dp, end = 4.dp, top = 8.dp),
    ) {
        CommentAvatar(entry, size = if (indent) 28.dp else 36.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (entry.isMine) stringResource(R.string.community_comment_mine) else entry.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (entry.isPending) {
                        stringResource(R.string.community_comment_pending)
                    } else {
                        DateUtils.getRelativeTimeSpanString(entry.comment.createdAt * 1000).toString()
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (entry.isPending) colors.primary else colors.onSurfaceVariant,
                )
            }
            if (revealed) {
                Text(
                    text = entry.comment.content,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 4.dp),
                )
            } else {
                Surface(
                    onClick = { revealed = true },
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surfaceContainerHighest,
                    modifier = Modifier.padding(top = 6.dp),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(painterResource(R.drawable.ic_eye_off), contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(stringResource(R.string.community_comment_spoiler_hidden), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            TextButton(
                onClick = { viewModel.replyTo(entry) },
                enabled = !entry.isPending,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(stringResource(R.string.community_comment_reply), style = MaterialTheme.typography.labelMedium)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(
                    painterResource(R.drawable.ic_more_vert),
                    contentDescription = stringResource(R.string.more),
                    tint = colors.onSurfaceVariant,
                )
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (entry.isMine) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.delete)) },
                        onClick = {
                            menu = false
                            viewModel.delete(entry)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.report)) },
                        onClick = {
                            menu = false
                            onReport(entry)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.community_comment_block)) },
                        onClick = {
                            menu = false
                            viewModel.block(entry)
                        },
                    )
                }
            }
        }
    }
}

/** A round initial in a colour picked from the author's key, so one author looks the same in every thread. */
@Composable
private fun CommentAvatar(entry: CommunityCommentEntry, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.surfaceContainerHighest to colors.onSurface,
    )
    val (background, foreground) = if (entry.isMine) {
        colors.primary to colors.onPrimary
    } else {
        palette[(entry.comment.pubkey.hashCode() and Int.MAX_VALUE) % palette.size]
    }
    val name = if (entry.isMine) stringResource(R.string.community_comment_mine) else entry.author
    Box(
        modifier = Modifier.size(size).clip(CircleShape).background(background),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = name.trim().take(1).uppercase(),
            style = if (size > 32.dp) MaterialTheme.typography.titleSmall else MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = foreground,
        )
    }
}

@Composable
private fun Composer(state: CommunityCommentsUiState, viewModel: CommunityCommentsViewModel) {
    var text by rememberSaveable { mutableStateOf("") }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        refusalText(state.refusal)?.let { message ->
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        state.replyTo?.let { target ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = stringResource(R.string.community_comment_reply_to, target.author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.replyTo(null) }) { Text(stringResource(R.string.cancel)) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= CommunityEventSchema.MAX_COMMENT_LENGTH) text = it },
                placeholder = { Text(stringResource(R.string.community_comment_hint)) },
                enabled = !state.isPosting,
                maxLines = 5,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier.weight(1f).testTag("community-comment-input"),
            )
            Box(modifier = Modifier.padding(start = 8.dp).size(48.dp), contentAlignment = Alignment.Center) {
                if (state.isPosting) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                } else {
                    FilledIconButton(
                        onClick = {
                            viewModel.post(text, spoiler)
                            text = ""
                            spoiler = false
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.testTag("community-comment-send"),
                    ) {
                        Icon(painterResource(R.drawable.ic_send), contentDescription = stringResource(R.string.community_comment_send))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            FilterChip(
                selected = spoiler,
                onClick = { spoiler = !spoiler },
                label = { Text(stringResource(R.string.community_comment_spoiler)) },
                leadingIcon = {
                    Icon(painterResource(R.drawable.ic_eye_off), contentDescription = null, modifier = Modifier.size(16.dp))
                },
                enabled = !state.isPosting,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (state.isPosting) {
                    stringResource(R.string.community_comment_publishing)
                } else {
                    "${text.length} / ${CommunityEventSchema.MAX_COMMENT_LENGTH}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ReportDialog(onReport: (CommunityReportType) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(CommunityReportType.SPAM) }
    val labels = mapOf(
        CommunityReportType.SPAM to R.string.community_report_spam,
        CommunityReportType.ILLEGAL to R.string.community_report_illegal,
        CommunityReportType.NUDITY to R.string.community_report_nudity,
        CommunityReportType.PROFANITY to R.string.community_report_profanity,
        CommunityReportType.OTHER to R.string.community_report_other,
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.community_report_title)) },
        text = {
            Column {
                CommunityReportType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected == type, role = Role.RadioButton) { selected = type },
                    ) {
                        RadioButton(selected = selected == type, onClick = null)
                        Text(stringResource(labels.getValue(type)), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onReport(selected) }) { Text(stringResource(R.string.report)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
