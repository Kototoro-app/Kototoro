package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.novel.compose.NovelReaderBottomChromeContent
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeActions
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeColors
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeLabels
import org.skepsun.kototoro.reader.novel.compose.NovelReaderChromeState
import org.skepsun.kototoro.reader.novel.compose.NovelReaderTopChromeContent
import org.skepsun.kototoro.reader.novel.compose.novelChromeColorScheme
import org.skepsun.kototoro.reader.novel.compose.NovelReaderFloatingControls
import org.skepsun.kototoro.reader.ui.compose.design.ReaderFloatingControlButton
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/** Android's novel chrome over the scroll reader; paragraphs are the engine's progress units. */
@Composable
internal fun BoxScope.DesktopNovelReaderChrome(
    novel: DesktopNovel,
    theme: DesktopNovelTheme,
    title: String,
    controlsVisible: Boolean,
    panelVisible: Boolean,
    previousEnabled: Boolean,
    nextEnabled: Boolean,
    bookmarked: Boolean,
    onToggleBookmark: () -> Unit,
    onBookmarks: () -> Unit,
    actions: NovelReaderChromeActions,
    onShowControls: () -> Unit,
) {
    val colors = remember(theme) {
        NovelReaderChromeColors(Color(theme.background), Color(theme.text), Color(theme.muted))
    }
    val base = MaterialTheme.colorScheme
    val scheme = remember(base, colors) { novelChromeColorScheme(base, colors) }
    val labels = remember {
        NovelReaderChromeLabels(AndroidStrings["back"], AndroidStrings["options"],
            AndroidStrings["prev_chapter"], AndroidStrings["next_chapter"])
    }
    val range = if (novel.firstVisible == novel.lastVisible) "${novel.firstVisible + 1}"
        else "${novel.firstVisible + 1}–${novel.lastVisible + 1}"
    val state = NovelReaderChromeState(
        workTitle = title,
        chapterTitle = novel.chapter.title ?: "第 ${novel.chapter.number} 章",
        controlsVisible = controlsVisible,
        progressValue = novel.firstVisible.toFloat(),
        progressMax = novel.blocks.lastIndex.toFloat(),
        progressLabel = "$range / ${novel.blocks.size} · ${((novel.lastVisible + 1) * 100) / novel.blocks.size}%",
        previousEnabled = previousEnabled,
        nextEnabled = nextEnabled,
        panelVisible = panelVisible,
        showReadingStatus = true,
        statusHorizontalPadding = 28.dp,
    )
    MaterialTheme(colorScheme = scheme) {
        NovelReaderTopChromeContent(state, colors, labels, actions, modifier = Modifier.align(Alignment.TopCenter))
        NovelReaderBottomChromeContent(
            state, colors, labels, actions,
            modifier = Modifier.align(Alignment.BottomCenter),
            onShowControls = onShowControls,
            floatingControls = {
                NovelReaderFloatingControls(showLabels = false) {
                    val label = AndroidStrings[if (bookmarked) "bookmark_remove" else "bookmark_add"]
                    ReaderFloatingControlButton(
                        icon = rememberVectorPainter(
                            if (bookmarked) ReaderPanelIcons.BookmarkAdded else ReaderPanelIcons.Bookmark,
                        ),
                        contentDescription = label,
                        label = label,
                        active = bookmarked,
                        showLabel = false,
                        onClick = onToggleBookmark,
                        onLongClick = onBookmarks,
                        modifier = Modifier.testTag("novel-bookmark-toggle"),
                    )
                }
            },
        )
    }
}
