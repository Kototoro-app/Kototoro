package org.skepsun.kototoro.community.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.community.domain.CommunityRatingRefusal
import org.skepsun.kototoro.community.domain.CommunityThread
import org.skepsun.kototoro.community.nostr.CommunityEventSchema
import org.skepsun.kototoro.community.nostr.CommunityReportType
import org.skepsun.kototoro.core.ui.text.uiString
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** One comment as the list draws it; hosts project their own entries into it. */
data class CommunityCommentRow(
    val id: String,
    val pubkey: String,
    /** The author's chosen name, or a short form of their public key. */
    val author: String,
    val content: String,
    /** Seconds since the epoch, as the event carries it. */
    val createdAt: Long,
    val spoiler: Boolean,
    val isMine: Boolean,
    /** Written on this device and not yet stored by any relay. */
    val isPending: Boolean,
)

data class CommunityCommentsViewState(
    val isLoading: Boolean = true,
    val threads: List<CommunityThread<CommunityCommentRow>> = emptyList(),
    /** How many relays answered the last load; zero says nothing about what exists. Null before the first load. */
    val relaysAnswered: Int? = null,
    /** Mining, signing and handing the comment to the relays; comments take a second or two of proof of work. */
    val isPosting: Boolean = false,
    val refusal: CommunityRatingRefusal? = null,
    val replyTo: CommunityCommentRow? = null,
)

/**
 * A work's community comments: threads from the relays, a composer, and reply, report, block and delete per comment.
 * The host places it in its own sheet or dialog and formats [CommunityCommentRow.createdAt] with [formatTime].
 */
@Composable
fun CommunityCommentsContent(
    state: CommunityCommentsViewState,
    formatTime: (Long) -> String,
    onRefresh: () -> Unit,
    onReplyTo: (CommunityCommentRow?) -> Unit,
    onPost: (text: String, spoiler: Boolean) -> Unit,
    onDelete: (CommunityCommentRow) -> Unit,
    onReport: (CommunityCommentRow, CommunityReportType) -> Unit,
    onBlock: (CommunityCommentRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    var reporting by remember { mutableStateOf<CommunityCommentRow?>(null) }
    Column(modifier = modifier.testTag("community-comments")) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val total = state.threads.sumOf { 1 + it.replies.size }
            Text(
                text = if (total > 0) uiString("community_comments_count", total) else uiString("community_comments"),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onRefresh, enabled = !state.isLoading, modifier = Modifier.testTag("community-comments-reload")) {
                Text(uiString("community_comments_reload"))
            }
        }
        if (state.isLoading) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                state.threads.isNotEmpty() -> LazyColumn(
                    modifier = Modifier.fillMaxWidth().testTag("community-comments-list"),
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    state.threads.forEachIndexed { index, thread ->
                        if (index > 0) {
                            item(key = "divider-${thread.root.id}") {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 68.dp, end = 20.dp, top = 4.dp, bottom = 4.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                                )
                            }
                        }
                        item(key = thread.root.id) {
                            CommentItem(thread.root, indent = false, formatTime, onReplyTo, onDelete, { reporting = it }, onBlock)
                        }
                        items(thread.replies, key = { it.id }) { reply ->
                            CommentItem(reply, indent = true, formatTime, onReplyTo, onDelete, { reporting = it }, onBlock)
                        }
                    }
                }
                !state.isLoading -> Column(
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        ReaderPanelIcons.Comment,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(40.dp),
                    )
                    Text(
                        text = uiString(
                            if (state.relaysAnswered == 0) "community_comments_offline" else "community_comments_empty",
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 12.dp).testTag("community-comments-empty"),
                    )
                }
            }
        }
        HorizontalDivider()
        Composer(state, onReplyTo, onPost)
    }

    reporting?.let { row ->
        ReportDialog(
            onReport = { type ->
                reporting = null
                onReport(row, type)
            },
            onDismiss = { reporting = null },
        )
    }
}

@Composable
private fun CommentItem(
    row: CommunityCommentRow,
    indent: Boolean,
    formatTime: (Long) -> String,
    onReplyTo: (CommunityCommentRow?) -> Unit,
    onDelete: (CommunityCommentRow) -> Unit,
    onReport: (CommunityCommentRow) -> Unit,
    onBlock: (CommunityCommentRow) -> Unit,
) {
    var revealed by rememberSaveable(row.id) { mutableStateOf(!row.spoiler) }
    var menu by remember { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = if (indent) 68.dp else 20.dp, end = 4.dp, top = 8.dp)
            .testTag("community-comment:${row.id}"),
    ) {
        CommentAvatar(row, size = if (indent) 28.dp else 36.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (row.isMine) uiString("community_comment_mine") else row.author,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Text(
                    text = if (row.isPending) uiString("community_comment_pending") else formatTime(row.createdAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (row.isPending) colors.primary else colors.onSurfaceVariant,
                )
            }
            if (revealed) {
                Text(
                    text = row.content,
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
                        Icon(ReaderPanelIcons.EyeOff, contentDescription = null, modifier = Modifier.size(16.dp))
                        Text(uiString("community_comment_spoiler_hidden"), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            TextButton(
                onClick = { onReplyTo(row) },
                enabled = !row.isPending,
                contentPadding = PaddingValues(horizontal = 0.dp),
                modifier = Modifier.heightIn(min = 36.dp),
            ) {
                Text(uiString("community_comment_reply"), style = MaterialTheme.typography.labelMedium)
            }
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("community-comment-menu")) {
                Icon(ReaderPanelIcons.MoreVert, contentDescription = uiString("more"), tint = colors.onSurfaceVariant)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                if (row.isMine) {
                    DropdownMenuItem(
                        text = { Text(uiString("delete")) },
                        onClick = {
                            menu = false
                            onDelete(row)
                        },
                    )
                } else {
                    DropdownMenuItem(
                        text = { Text(uiString("report")) },
                        onClick = {
                            menu = false
                            onReport(row)
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(uiString("community_comment_block")) },
                        onClick = {
                            menu = false
                            onBlock(row)
                        },
                    )
                }
            }
        }
    }
}

/** A round initial in a colour picked from the author's key, so one author looks the same in every thread. */
@Composable
private fun CommentAvatar(row: CommunityCommentRow, size: Dp) {
    val colors = MaterialTheme.colorScheme
    val palette = listOf(
        colors.primaryContainer to colors.onPrimaryContainer,
        colors.secondaryContainer to colors.onSecondaryContainer,
        colors.tertiaryContainer to colors.onTertiaryContainer,
        colors.surfaceContainerHighest to colors.onSurface,
    )
    val (background, foreground) = if (row.isMine) {
        colors.primary to colors.onPrimary
    } else {
        palette[(row.pubkey.hashCode() and Int.MAX_VALUE) % palette.size]
    }
    val name = if (row.isMine) uiString("community_comment_mine") else row.author
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
private fun Composer(
    state: CommunityCommentsViewState,
    onReplyTo: (CommunityCommentRow?) -> Unit,
    onPost: (String, Boolean) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf("") }
    var spoiler by rememberSaveable { mutableStateOf(false) }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        communityRefusalText(state.refusal)?.let { message ->
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.testTag("community-comment-refusal"),
            )
            return@Column
        }
        state.replyTo?.let { target ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                Text(
                    text = uiString("community_comment_reply_to", target.author),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onReplyTo(null) }) { Text(uiString("cancel")) }
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            OutlinedTextField(
                value = text,
                onValueChange = { if (it.length <= CommunityEventSchema.MAX_COMMENT_LENGTH) text = it },
                placeholder = { Text(uiString("community_comment_hint")) },
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
                            onPost(text, spoiler)
                            text = ""
                            spoiler = false
                        },
                        enabled = text.isNotBlank(),
                        modifier = Modifier.testTag("community-comment-send"),
                    ) {
                        Icon(ReaderPanelIcons.Send, contentDescription = uiString("community_comment_send"))
                    }
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
            FilterChip(
                selected = spoiler,
                onClick = { spoiler = !spoiler },
                label = { Text(uiString("community_comment_spoiler")) },
                leadingIcon = { Icon(ReaderPanelIcons.EyeOff, contentDescription = null, modifier = Modifier.size(16.dp)) },
                enabled = !state.isPosting,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = if (state.isPosting) {
                    uiString("community_comment_publishing")
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(uiString("community_report_title")) },
        text = {
            Column {
                CommunityReportType.entries.forEach { type ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().selectable(selected == type, role = Role.RadioButton) { selected = type },
                    ) {
                        RadioButton(selected = selected == type, onClick = null)
                        Text(uiString(reportLabel(type)), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onReport(selected) }, modifier = Modifier.testTag("community-report-confirm")) {
                Text(uiString("report"))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(uiString("cancel")) } },
    )
}

private fun reportLabel(type: CommunityReportType): String = when (type) {
    CommunityReportType.SPAM -> "community_report_spam"
    CommunityReportType.ILLEGAL -> "community_report_illegal"
    CommunityReportType.NUDITY -> "community_report_nudity"
    CommunityReportType.PROFANITY -> "community_report_profanity"
    CommunityReportType.OTHER -> "community_report_other"
}
