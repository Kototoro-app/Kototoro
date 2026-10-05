package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.reader.novel.compose.*
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChapterPanelHeader
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.readerChapterPanelSubtitleText
import org.skepsun.kototoro.reader.ui.compose.design.readerPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderPanelSurfaceMode

/** The shared chapter directory over the existing scroll scene; selection stays in the original controller. */
@Composable
internal fun DesktopNovelChaptersPanel(
    controller: DesktopController,
    state: DesktopAppState,
    enabled: Boolean,
    onDismiss: () -> Unit,
) {
    val chapters = state.content?.chapters.orEmpty()
    val currentIndex = chapters.indexOfFirst { it.id == state.novel?.chapter?.id }
    val directory = remember(chapters) {
        chapters.map {
            NovelChapterDirectoryEntry(
                id = it.id,
                title = it.title,
                volume = it.volume,
                groupTitle = it.branch?.takeIf(String::isNotBlank).orEmpty(),
                searchAliases = listOfNotNull(it.branch, it.scanlator),
            )
        }
    }
    val labels = remember {
        NovelChapterDirectoryLabels(AndroidStrings["search_chapters"], AndroidStrings["clear"],
            AndroidStrings["reverse_order"], AndroidStrings["unnamed_chapter"])
    }
    val volumeTitle: (Int) -> String = remember { { AndroidStrings["volume_"].format(it) } }
    var locateRequest by remember { mutableIntStateOf(0) }
    val theme = state.novelSettings.theme
    val base = MaterialTheme.colorScheme
    val colors = remember(theme) {
        NovelReaderChromeColors(Color(theme.background), Color(theme.text), Color(theme.muted))
    }
    val scheme = remember(base, colors) { novelChromeColorScheme(base, colors) }
    MaterialTheme(colorScheme = scheme) {
        ReaderPanelHost(
            colors = readerPanelColors(colors.background, colors.content, colors.secondary, colors.content,
                isDark = theme == DesktopNovelTheme.DARK),
            surfaceMode = ReaderPanelSurfaceMode.Opaque,
            onDismissRequest = onDismiss,
            openExpanded = true,
            modifier = Modifier.testTag("novel-chapters-panel"),
            header = {
                ReaderChapterPanelHeader(
                    title = state.content?.title.orEmpty(),
                    subtitle = readerChapterPanelSubtitleText(listOf(
                        chapters.getOrNull(currentIndex)?.title,
                        if (currentIndex >= 0) "${currentIndex + 1}/${chapters.size}" else null,
                    )),
                    actions = {
                        NovelChapterLocateButton(AndroidStrings["novel_chapters_locate_current"], currentIndex >= 0) {
                            locateRequest++
                        }
                    },
                )
                ReaderPanelTabBar(listOf(AndroidStrings["chapters"]), selectedIndex = 0, onSelected = {})
            },
        ) { dragModifier ->
            BoxWithConstraints(Modifier.fillMaxSize()) {
                NovelChapterDirectoryContent(
                    chapters = directory,
                    labels = labels,
                    volumeTitle = volumeTitle,
                    currentIndex = currentIndex,
                    onChapterSelected = { index ->
                        if (enabled) {
                            if (index != currentIndex) controller.read(chapters[index])
                            onDismiss()
                        }
                    },
                    locateRequest = locateRequest,
                    dragModifier = dragModifier,
                    enabled = enabled,
                    modifier = Modifier.widthIn(max = if (maxWidth >= 720.dp) 680.dp else maxWidth)
                        .fillMaxHeight().align(Alignment.TopCenter),
                )
            }
        }
    }
}
