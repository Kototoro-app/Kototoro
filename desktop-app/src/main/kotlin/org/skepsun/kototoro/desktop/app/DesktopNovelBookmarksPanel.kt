package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.novel.compose.*
import org.skepsun.kototoro.bookmarks.domain.parseNovelBookmarkPreview
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterPanelHeader
import org.skepsun.kototoro.reader.ui.compose.design.readerPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode
import java.text.DateFormat
import java.util.Date

/** Bookmark persistence and navigation belong to the host; card and search presentation are shared. */
@Composable
internal fun DesktopNovelBookmarksPanel(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val chapters = state.content?.chapters.orEmpty().associateBy { it.id }
    val previews = remember(state.bookmarks) { state.bookmarks.associate { it.pageId to parseNovelBookmarkPreview(it.preview) } }
    val bookmarks = state.bookmarks.filter {
        previews[it.pageId].orEmpty().contains(query, ignoreCase = true) ||
            chapters[it.chapterId]?.title.orEmpty().contains(query, ignoreCase = true)
    }.sortedByDescending { it.createdAt }
    val dateFormat = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT) }
    val theme = state.novelSettings.theme
    val colors = remember(theme) {
        NovelReaderChromeColors(Color(theme.background), Color(theme.text), Color(theme.muted))
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, colors) { novelChromeColorScheme(base, colors) }
    MaterialTheme(colorScheme = scheme) {
        ReaderPanelHost(
            colors = readerPanelColors(colors.background, colors.content, colors.secondary, colors.content,
                isDark = theme == DesktopNovelTheme.DARK),
            surfaceMode = ReaderPanelSurfaceMode.Opaque,
            onDismissRequest = onDismiss,
            openExpanded = true,
            modifier = Modifier.testTag("novel-bookmarks-panel"),
            header = {
                ReaderChapterPanelHeader(title = AndroidStrings["bookmarks"], subtitle = state.content?.title.orEmpty())
            },
        ) { dragModifier ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.align(Alignment.TopCenter).widthIn(max = 680.dp)
                        .fillMaxSize().padding(horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    SharedNovelReaderSearchField(query, { query = it }, AndroidStrings["search"],
                        modifier = Modifier.fillMaxWidth().then(dragModifier),
                        clearContentDescription = AndroidStrings["clear"])
                    if (bookmarks.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(AndroidStrings[if (query.isBlank()) "no_bookmarks_yet" else "nothing_found"],
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else LazyColumn(Modifier.fillMaxSize().testTag("novel-bookmarks-list"),
                        contentPadding = PaddingValues(vertical = 8.dp)) {
                        items(bookmarks, key = { it.pageId }) { bookmark ->
                            NovelBookmarkCardContent(
                                preview = previews[bookmark.pageId].orEmpty(),
                                positionText = AndroidStrings["bookmark_position"].format(bookmark.page + 1),
                                chapterName = chapters[bookmark.chapterId]?.title,
                                dateText = dateFormat.format(Date(bookmark.createdAt)),
                                deleteDescription = AndroidStrings["delete"],
                                onOpen = { controller.openNovelBookmark(bookmark); onDismiss() },
                                onDelete = { controller.removeNovelBookmark(bookmark) },
                                enabled = enabled,
                                modifier = Modifier.testTag("novel-bookmark:${bookmark.pageId}"),
                            )
                        }
                    }
                }
            }
        }
    }
}
