package org.skepsun.kototoro.core.ui.downloads

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.CompactContentCoverShape
import org.skepsun.kototoro.settings.compose.SettingsMaterialIcons

/**
 * One chapter of a download queue, as the expanded row lists them.
 *
 * [number] is the chapter's own label ("12.5") and may be blank; [name] is the title the source gave it.
 */
data class DownloadQueueChapterUiModel(
    val number: String?,
    val name: String,
    val isDownloaded: Boolean,
)

/** What a queued row is doing right now. Both hosts map their own queue state onto these five values. */
enum class DownloadQueueStatus { QUEUED, DOWNLOADING, PAUSED, COMPLETE, FAILED }

/** Waiting or running: the states a pause can interrupt, and the ones a removal has to wait for. */
val DownloadQueueStatus.isActive: Boolean
    get() = this == DownloadQueueStatus.QUEUED || this == DownloadQueueStatus.DOWNLOADING

/**
 * A queue row reduced to what it draws.
 *
 * Everything the host already knows how to format stays formatted here — the status line comes from Android's
 * `R.string` (or its work/task kind) and from the desktop catalog, so this model carries text instead of making the
 * shared card guess at it. Queue identity, page progress and selection live in the host; the shared card only reads
 * them.
 *
 * @param key stable row identity across refreshes (Android's work id, the desktop's `contentId` + chapter identity).
 * @param subtitle the chapter or branch the queue is working on, when the host has one.
 * @param statusText the localized state line ("Paused", "Queued", "Cancelled").
 * @param infoText a second, quieter line: byte progress on Android, page counts on the desktop.
 * @param detailText the third line: the ETA or error text while downloading.
 * @param percent `null` while the total is unknown, which draws an indeterminate bar.
 */
data class DownloadQueueItemUiModel(
    val key: String,
    val title: String,
    val subtitle: String? = null,
    val status: DownloadQueueStatus,
    val statusText: String,
    val infoText: String? = null,
    val detailText: String? = null,
    val detailIsError: Boolean = false,
    val percent: Float? = null,
    val isSelected: Boolean = false,
    val isExpanded: Boolean = false,
    val chapters: List<DownloadQueueChapterUiModel> = emptyList(),
    val canPause: Boolean = false,
    val canResume: Boolean = false,
    val canCancel: Boolean = false,
    val canRemove: Boolean = false,
)

/**
 * One button of a queue row.
 *
 * Android's row offers what its worker can do right now (pause/resume/cancel/retry), the desktop adds "read offline"
 * and "export"; both hand the shared card a label and a callback, so a row never grows host-specific rendering.
 * [tag] is the test tag the host's UI probe looks for.
 */
class DownloadQueueAction(
    val label: String,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
    val filled: Boolean = false,
    val tag: String? = null,
)

/**
 * What a batch action can do with the rows the user selected.
 *
 * The counts answer "how many selected rows support this", which is what a desktop batch button acts on; [canPause]
 * and friends answer "do all of them", which is when Android shows the action in its selection bar. Both readings
 * come from one set of per-row flags so the two hosts cannot drift apart on which rows qualify.
 */
data class DownloadQueueBatchState(
    val selected: Int,
    val pausable: Int,
    val resumable: Int,
    val retryable: Int,
    val cancellable: Int,
    val removable: Int,
) {
    val canPause: Boolean get() = selected > 0 && pausable == selected
    val canResume: Boolean get() = selected > 0 && resumable == selected
    val canRetry: Boolean get() = selected > 0 && retryable == selected
    val canCancel: Boolean get() = selected > 0 && cancellable == selected
    val canRemove: Boolean get() = selected > 0 && removable == selected
}

/** The per-row part of a batch decision, so a host can ask about rows it has not turned into cards yet. */
data class DownloadQueueBatchItem(
    val status: DownloadQueueStatus,
    val canPause: Boolean,
    val canResume: Boolean,
    val canCancel: Boolean,
    val canRemove: Boolean,
)

fun DownloadQueueItemUiModel.toBatchItem(): DownloadQueueBatchItem = DownloadQueueBatchItem(
    status = status,
    canPause = canPause,
    canResume = canResume,
    canCancel = canCancel,
    canRemove = canRemove,
)

fun downloadQueueBatchState(items: List<DownloadQueueBatchItem>): DownloadQueueBatchState =
    DownloadQueueBatchState(
        selected = items.size,
        pausable = items.count { it.canPause },
        resumable = items.count { it.canResume },
        retryable = items.count { it.status == DownloadQueueStatus.FAILED },
        cancellable = items.count { it.canCancel },
        removable = items.count { it.canRemove },
    )

fun downloadQueueBatchStateOf(items: List<DownloadQueueItemUiModel>): DownloadQueueBatchState =
    downloadQueueBatchState(items.map { it.toBatchItem() })

/**
 * Android's queue card: cover, title, the state line, progress and the actions the worker allows right now.
 *
 * The desktop passes [leading] (its selection checkbox) and no [cover] because its queue stores page artifacts
 * rather than a content image; everything else — spacing, the chevron that opens the chapter list, the progress bar,
 * the action row — is the same code, so a change to one host's queue row is a change to both.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun DownloadQueueCard(
    item: DownloadQueueItemUiModel,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onToggleExpanded: (() -> Unit)? = null,
    actions: List<DownloadQueueAction> = emptyList(),
    leading: (@Composable () -> Unit)? = null,
    cover: (@Composable () -> Unit)? = null,
) {
    val interactive = onClick != null || onLongClick != null
    // Only a row that handles clicks becomes a click target: a disabled clickable would still merge its children's
    // semantics, which hides the per-row tags a host's UI probe looks for.
    val clickModifier = if (interactive) {
        Modifier.combinedClickable(onClick = { onClick?.invoke() }, onLongClick = onLongClick)
    } else {
        Modifier
    }
    Card(
        modifier = modifier
            .fillMaxWidth()
            .testTag("download:${item.key}")
            .then(clickModifier),
        colors = CardDefaults.cardColors(
            containerColor = if (item.isSelected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(bottom = 12.dp)) {
            Row(modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp)) {
                if (leading != null) {
                    leading()
                    Spacer(modifier = Modifier.width(8.dp))
                }
                if (cover != null) {
                    Column(modifier = Modifier.size(64.dp).clip(CompactContentCoverShape)) { cover() }
                    Spacer(modifier = Modifier.width(16.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        if (item.chapters.isNotEmpty() && onToggleExpanded != null) {
                            IconButton(
                                onClick = onToggleExpanded,
                                modifier = Modifier.size(32.dp).testTag("download-expand:${item.key}"),
                            ) {
                                Icon(
                                    imageVector = if (item.isExpanded) SettingsMaterialIcons.KeyboardArrowUp
                                    else SettingsMaterialIcons.KeyboardArrowDown,
                                    contentDescription = null,
                                )
                            }
                        }
                    }
                    item.subtitle?.takeIf { it.isNotBlank() }?.let { subtitle ->
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Text(
                        text = item.statusText,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.testTag("download-status:${item.key}"),
                    )
                    item.infoText?.takeIf { it.isNotBlank() }?.let { info ->
                        Text(
                            text = info,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    item.detailText?.takeIf { it.isNotBlank() }?.let { detail ->
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.detailIsError) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                item.percent?.let { progress ->
                    Text(
                        text = "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.align(Alignment.Bottom),
                    )
                }
            }
            if (item.status == DownloadQueueStatus.DOWNLOADING) {
                val barModifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, top = 12.dp, end = 12.dp)
                val progress = item.percent
                if (progress == null) {
                    LinearProgressIndicator(modifier = barModifier)
                } else {
                    LinearProgressIndicator(progress = { progress }, modifier = barModifier)
                }
            }
            if (item.isExpanded && item.chapters.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                        .heightIn(max = 240.dp)
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp)),
                ) {
                    items(item.chapters) { chapter ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            chapter.number?.takeIf { it.isNotBlank() }?.let { number ->
                                Text(
                                    text = number,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.width(32.dp),
                                )
                            }
                            Text(
                                text = chapter.name,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (chapter.isDownloaded) {
                                Icon(
                                    imageVector = SettingsMaterialIcons.Check,
                                    contentDescription = null,
                                    modifier = Modifier.size(16.dp),
                                    tint = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }
            if (actions.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (action in actions) {
                        val actionModifier = Modifier.padding(start = 8.dp)
                            .let { base -> action.tag?.let { base.testTag(it) } ?: base }
                        if (action.filled) {
                            Button(
                                onClick = action.onClick,
                                enabled = action.enabled,
                                modifier = actionModifier,
                            ) { Text(action.label) }
                        } else {
                            OutlinedButton(
                                onClick = action.onClick,
                                enabled = action.enabled,
                                modifier = actionModifier,
                            ) { Text(action.label) }
                        }
                    }
                }
            }
        }
    }
}
