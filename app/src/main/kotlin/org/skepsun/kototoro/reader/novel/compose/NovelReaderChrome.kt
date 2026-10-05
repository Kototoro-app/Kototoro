package org.skepsun.kototoro.reader.novel.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.novel.NovelReaderSettings
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.ReadingMode
import org.skepsun.kototoro.reader.novel.annotation.NovelMarkingEntity
import org.skepsun.kototoro.reader.novel.novelReaderPalette
import org.skepsun.kototoro.reader.novel.tts.TtsState
import org.skepsun.kototoro.reader.ui.compose.design.ReaderFloatingControlButton

internal data class NovelReaderChromeCallbacks(
    val onNavigateBack: () -> Unit = {},
    val onProgressSelected: (Int) -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onSettingsChanged: (NovelReaderSettings) -> Unit = {},
    val onChapterSelected: (Int) -> Unit = {},
    val onSearchResultSelected: (NovelMarkingTarget) -> Unit = {},
    val onDismissSettings: () -> Unit = {},
    val onDismissChapters: () -> Unit = {},
    val onDismissTools: () -> Unit = {},
    val onShowSettings: () -> Unit = {},
    val onShowChapters: () -> Unit = {},
    val onShowReplaceRules: () -> Unit = {},
    val onShowMarkings: () -> Unit = {},
    val onDismissMarkings: () -> Unit = {},
    val onEditMarkingNote: (NovelMarkingEntity) -> Unit = {},
    val onDeleteMarking: (NovelMarkingEntity) -> Unit = {},
    val onJumpToMarking: (NovelMarkingEntity) -> Unit = {},
    val onOpenBookmark: (org.skepsun.kototoro.bookmarks.domain.Bookmark) -> Unit = {},
    val onDeleteBookmark: (org.skepsun.kototoro.bookmarks.domain.Bookmark) -> Unit = {},
    val onToggleTranslation: () -> Unit = {},
    val onToggleReplaceRules: () -> Unit = {},
    val onDismissReplaceRules: () -> Unit = {},
    val onReplaceRuleToggle: (ReplaceRule, Boolean) -> Unit = { _, _ -> },
    val onBookmark: () -> Unit = {},
    val onTts: () -> Unit = {},
    val onClearTranslationCache: () -> Unit = {},
    val onTtsPrevious: () -> Unit = {},
    val onTtsPlayPause: () -> Unit = {},
    val onTtsNext: () -> Unit = {},
    val onTtsVoice: () -> Unit = {},
    val onTtsClose: () -> Unit = {},
)

@Composable
private fun NovelFloatingControlsContent(
    state: NovelComposeReaderUiState,
    controls: List<ReaderControl>,
    showLabels: Boolean,
    callbacks: NovelReaderChromeCallbacks,
) {
    NovelReaderFloatingControls(showLabels) {
        controls.forEach { control ->
            NovelFloatingControlButton(control, state, callbacks, showLabels)
        }
    }
}

@Composable
private fun NovelFloatingControlButton(
    control: ReaderControl,
    state: NovelComposeReaderUiState,
    callbacks: NovelReaderChromeCallbacks,
    showLabel: Boolean,
) {
    val icon = when (control) {
        ReaderControl.BOOKMARK -> if (state.isCurrentPageBookmarked) {
            R.drawable.ic_bookmark_added
        } else R.drawable.ic_bookmark
        ReaderControl.TRANSLATE -> R.drawable.ic_translate
        else -> return
    }
    val label = when (control) {
        ReaderControl.BOOKMARK -> if (state.isCurrentPageBookmarked) R.string.bookmark_remove else R.string.bookmark_add
        ReaderControl.TRANSLATE -> R.string.novel_translate
    }
    val onClick = when (control) {
        ReaderControl.BOOKMARK -> callbacks.onBookmark
        ReaderControl.TRANSLATE -> callbacks.onToggleTranslation
    }
    val active = control == ReaderControl.TRANSLATE && state.settings?.isTranslationEnabled == true
    ReaderFloatingControlButton(
        icon = painterResource(icon),
        contentDescription = stringResource(label),
        label = stringResource(label),
        active = active,
        showLabel = showLabel,
        onClick = onClick,
        contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
    )
}

@Composable
internal fun NovelReaderTopChrome(
    state: NovelComposeReaderUiState,
    callbacks: NovelReaderChromeCallbacks,
    animationsEnabled: Boolean = true,
) {
    NovelReaderTopChromeContent(
        state = state.chromeState(),
        colors = chromeColors(state),
        labels = chromeLabels(),
        actions = callbacks.chromeActions(),
        animationsEnabled = animationsEnabled,
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun NovelReaderBottomChrome(
    state: NovelComposeReaderUiState,
    callbacks: NovelReaderChromeCallbacks,
    controls: Set<ReaderControl>,
    showFloatingControlLabels: Boolean,
    animationsEnabled: Boolean = true,
) {
    val toolsPanelVisible = state.toolsSheetVisible || state.ttsControlsVisible
    val panelVisible = state.chromePanelVisible()
    val floatingControls = ReaderControl.NOVEL_FLOATING
        .filter(controls::contains)
        .take(ReaderControl.MAX_FLOATING_CONTROLS)
    // Settings and chapters handle back in their own sheet hosts.
    BackHandler(enabled = panelVisible && !state.settingsSheetVisible && !state.chaptersSheetVisible) {
        when {
            state.replaceRulesSheetVisible -> callbacks.onDismissReplaceRules()
            state.markingsSheetVisible -> callbacks.onDismissMarkings()
            toolsPanelVisible -> callbacks.onDismissTools()
        }
    }
    val floatingContent: (@Composable () -> Unit)? = if (floatingControls.isEmpty()) null else {
        { NovelFloatingControlsContent(state, floatingControls, showFloatingControlLabels, callbacks) }
    }
    NovelReaderBottomChromeContent(
        state = state.chromeState(),
        colors = chromeColors(state),
        labels = chromeLabels(),
        actions = callbacks.chromeActions(),
        isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS,
        animationsEnabled = animationsEnabled,
        floatingControls = floatingContent,
        navigationBarBottomInset = WindowInsets.navigationBarsIgnoringVisibility
            .asPaddingValues().calculateBottomPadding(),
    )

    if (state.replaceRulesSheetVisible) {
        ComposeNovelReplaceRulesSheet(
            rules = state.replaceRules,
            disabledRuleIds = state.disabledReplaceRuleIds,
            scopeName = state.workTitle,
            origin = state.replaceRulesOrigin,
            onDismiss = callbacks.onDismissReplaceRules,
            onToggle = callbacks.onReplaceRuleToggle,
        )
    }
    if (state.markingsSheetVisible) {
        ComposeNovelMarkingsSheet(
            bookmarks = state.novelBookmarks,
            markings = state.novelMarkings,
            chapters = state.chapters,
            bookTitle = state.workTitle,
            themePreset = state.settings?.themePreset ?: NovelReaderThemePreset.PAPER,
            onDismiss = callbacks.onDismissMarkings,
            onEditNote = callbacks.onEditMarkingNote,
            onDelete = callbacks.onDeleteMarking,
            onDeleteBookmark = callbacks.onDeleteBookmark,
            onOpenBookmark = callbacks.onOpenBookmark,
            onJumpToMarking = callbacks.onJumpToMarking,
        )
    }
    if (toolsPanelVisible) {
        ModalBottomSheet(onDismissRequest = callbacks.onDismissTools) {
            NovelToolsPanel(state, callbacks)
        }
    }
}

private fun NovelComposeReaderUiState.chromePanelVisible(): Boolean =
    settingsSheetVisible || replaceRulesSheetVisible || markingsSheetVisible || chaptersSheetVisible ||
        toolsSheetVisible || ttsControlsVisible

private fun NovelComposeReaderUiState.chromeState() = NovelReaderChromeState(
    workTitle = workTitle,
    chapterTitle = chapterTitle,
    controlsVisible = controlsVisible,
    progressValue = progressValue,
    progressMax = progressMax,
    progressLabel = progressLabel,
    previousEnabled = currentChapterIndex > 0,
    nextEnabled = currentChapterIndex < chapters.lastIndex,
    chapterTitleAtBottom = settings?.chapterTitleAtBottom == true,
    panelVisible = chromePanelVisible(),
    showReadingStatus = settings?.showReadingStatus == true && settings.readingMode != ReadingMode.PAGED,
    transparentReadingStatus = settings?.isReadingStatusTransparent == true,
    statusHorizontalPadding = (settings?.marginHorizontal ?: 0).dp,
)

private fun NovelReaderChromeCallbacks.chromeActions() = NovelReaderChromeActions(
    onBack = onNavigateBack,
    onChapters = onShowChapters,
    onOptions = onShowSettings,
    onProgressSelected = onProgressSelected,
    onPreviousChapter = onPreviousChapter,
    onNextChapter = onNextChapter,
)

@Composable
private fun chromeColors(state: NovelComposeReaderUiState) = novelReaderPalette(
    state.settings?.themePreset ?: NovelReaderThemePreset.PAPER,
    isSystemInDarkTheme(),
).chromeColors()

@Composable
private fun chromeLabels() = NovelReaderChromeLabels(
    back = stringResource(R.string.back),
    options = stringResource(R.string.options),
    previous = stringResource(R.string.prev_chapter),
    next = stringResource(R.string.next_chapter),
)

@Composable
private fun NovelToolsPanel(
    state: NovelComposeReaderUiState,
    callbacks: NovelReaderChromeCallbacks,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            NovelToolButton(R.drawable.ic_translate, R.string.reader_translation_action, callbacks.onToggleTranslation)
            NovelToolButton(R.drawable.ic_bookmark, R.string.bookmark_add, callbacks.onBookmark)
            NovelToolButton(R.drawable.ic_voice_input, R.string.tts_settings_title, callbacks.onTts)
            NovelToolButton(R.drawable.ic_delete, R.string.clear_translation_cache, callbacks.onClearTranslationCache)
        }
        if (state.ttsControlsVisible) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f))
            Row(
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                IconButton(onClick = callbacks.onTtsPrevious) {
                    Icon(painterResource(R.drawable.ic_prev), stringResource(R.string.prev_page))
                }
                IconButton(onClick = callbacks.onTtsPlayPause) {
                    Icon(
                        painterResource(
                            if (state.ttsState == TtsState.PLAYING) R.drawable.ic_pause else R.drawable.ic_play,
                        ),
                        stringResource(if (state.ttsState == TtsState.PLAYING) R.string.pause else R.string.play),
                    )
                }
                IconButton(onClick = callbacks.onTtsNext) {
                    Icon(painterResource(R.drawable.ic_next), stringResource(R.string.next))
                }
                IconButton(onClick = callbacks.onTtsVoice) {
                    Icon(painterResource(R.drawable.ic_voice_input), stringResource(R.string.tts_settings_title))
                }
                IconButton(onClick = callbacks.onTtsClose) {
                    Icon(painterResource(R.drawable.ic_tts_close), stringResource(R.string.close))
                }
            }
        }
    }
}

@Composable
private fun RowScope.NovelToolButton(icon: Int, label: Int, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp),
        modifier = Modifier.weight(1f),
    ) {
        Icon(painterResource(icon), contentDescription = stringResource(label), modifier = Modifier.size(18.dp))
    }
}
