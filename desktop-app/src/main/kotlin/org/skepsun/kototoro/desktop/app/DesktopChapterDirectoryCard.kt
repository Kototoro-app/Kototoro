package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.ui.chapters.*
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** One projection for reader and details cards, over the existing history/bookmark/download stores. */
internal fun desktopChapterCardData(
    chapter: SourceChapter,
    current: SourceChapter?,
    bookmarked: Set<Long>,
    downloaded: List<SourceChapter>,
) = ChapterCardData(
    title = desktopChapterTitle(chapter),
    description = listOfNotNull(chapter.numberLabel()?.let { "#$it" },
        chapter.scanlator?.takeIf(String::isNotBlank) ?: chapter.branch?.takeIf(String::isNotBlank)).joinToString(" • "),
    isCurrent = current != null && DesktopDownloadStore.sameChapter(chapter, current),
    isUnread = current == null || current.branch != chapter.branch ||
        chapterIsAfter(chapter.number, chapter.volume, current.number, current.volume),
    isBookmarked = chapter.id in bookmarked,
    isDownloaded = downloaded.any { DesktopDownloadStore.sameChapter(it, chapter) },
)

/**
 * Shared Android cards with the desktop's own actions supplied as a slot. Android reaches read/unread/delete
 * through the chapter long-press sheet; a desktop mouse has no long-press, so they sit behind an overflow menu.
 */
@Composable
internal fun DesktopChapterDirectoryCard(
    chapter: SourceChapter,
    data: ChapterCardData,
    isGrid: Boolean,
    enabled: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier,
    onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    onMarkRead: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null,
    onDeleteDownload: (() -> Unit)? = null,
) {
    val menu = listOfNotNull(
        onMarkRead?.let { Triple("details-mark-read", desktopText("details.markRead"), it) },
        onMarkUnread?.let { Triple("details-mark-unread", desktopText("details.markUnread"), it) },
        onDeleteDownload?.let { Triple("details-delete-download", desktopText("details.deleteDownload"), it) },
    )
    val actions: (@Composable RowScope.() -> Unit)? = if (menu.isEmpty() && onDownload == null) null else {
        {
            if (menu.isNotEmpty()) {
                Box {
                    var open by remember { mutableStateOf(false) }
                    IconButton({ open = true }, enabled = enabled,
                        modifier = Modifier.testTag("chapter-menu:${chapter.id}")) {
                        Icon(ReaderPanelIcons.MoreVert, desktopText("details.chapterActions"))
                    }
                    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                        menu.forEach { (tag, label, action) ->
                            DropdownMenuItem({ Text(label) }, {
                                open = false
                                action()
                            }, modifier = Modifier.testTag("$tag:${chapter.id}"))
                        }
                    }
                }
            }
            onDownload?.let { download ->
                IconButton(download, enabled = enabled, modifier = Modifier.testTag("download-chapter:${chapter.id}")) {
                    Icon(ReaderPanelIcons.Download, AndroidStrings["download"])
                }
            }
        }
    }
    if (isGrid) {
        ChapterGridCard(data, isSelected, onClick, onLongClick, modifier = modifier, enabled = enabled, actions = actions)
    } else {
        ChapterListCard(data, isSelected, onClick, onLongClick, modifier = modifier, enabled = enabled, actions = actions)
    }
}
