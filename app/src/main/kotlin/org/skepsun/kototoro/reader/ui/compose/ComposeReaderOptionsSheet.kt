package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderImageComparisonPreview
import org.skepsun.kototoro.reader.ui.colorfilter.rememberReaderColorCorrectionLabels
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionValueRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.forEInk
import org.skepsun.kototoro.reader.ui.compose.design.mangaReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.rememberReaderPanelSurfaceMode

// The panel's state, quick layer and layout / display pages are shared in core-ui (`ComposeReaderOptions.kt`) with
// the Windows reader; Android adds its strings, the translation tab and the appearance preview.

@Composable
internal fun ComposeReaderOptionsPanel(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationAvailable: Boolean,
    translationActive: Boolean,
    eInkMode: Boolean,
    translationTaskPanelContent: @Composable () -> Unit = {},
) {
    if (!state.visible) return
    val colors = mangaReaderPanelColors(state.background, isSystemInDarkTheme(), MaterialTheme.colorScheme)
    val strings = rememberReaderOptionsStrings()
    ReaderOptionsPanelHost(
        colors = if (eInkMode) colors.forEInk() else colors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = callbacks.onDismiss,
        quickLayer = {
            ReaderMangaQuickLayer(
                state = state,
                callbacks = callbacks,
                strings = strings,
                features = ReaderOptionsFeatures(),
                actions = mangaQuickActions(translationAvailable, translationActive),
                actionIcon = { painterResource(MangaQuickActionId.valueOf(it.id).iconResId) },
                actionLabel = { stringResource(MangaQuickActionId.valueOf(it.id).labelResId) },
                onAction = { action ->
                    val handler = when (MangaQuickActionId.valueOf(action.id)) {
                        MangaQuickActionId.CHAPTERS -> callbacks.onPages
                        MangaQuickActionId.BOOKMARK -> callbacks.onBookmark
                        MangaQuickActionId.SAVE_PAGE -> callbacks.onSavePage
                        MangaQuickActionId.CROP_NOTE -> callbacks.onCropNote
                        MangaQuickActionId.AUTO_SCROLL -> callbacks.onAutoScroll
                        MangaQuickActionId.ROTATE -> callbacks.onRotate
                        MangaQuickActionId.DOWNLOAD -> callbacks.onDownload
                        MangaQuickActionId.BROWSER -> callbacks.onOpenBrowser
                        MangaQuickActionId.TRANSLATE -> callbacks.onTranslation
                    }
                    callbacks.onDismiss()
                    handler()
                },
            )
        },
        details = { dragModifier ->
            ReaderOptionsDetailTabs(
                tabs = listOf(
                    ReaderOptionsTab(stringResource(R.string.reader_more_tab_layout)) {
                        ReaderLayoutOptionsPage(state, callbacks, strings)
                    },
                    ReaderOptionsTab(stringResource(R.string.reader_more_tab_display)) {
                        ReaderDisplayOptionsPage(state, callbacks, strings) {
                            ReaderImageComparisonPreview(
                                originalPreviewModel = state.appearancePreviewOriginalUri,
                                processedPreviewModel = state.appearancePreviewProcessedUri,
                                colorFilter = state.colorFilter,
                                isLoading = state.appearancePreviewLoading,
                                modifier = Modifier.padding(8.dp),
                            )
                        }
                    },
                    ReaderOptionsTab(stringResource(R.string.reader_more_tab_translation)) {
                        ReaderTranslationOptionsPage(state, callbacks, translationTaskPanelContent)
                    },
                ),
                settingsDescription = stringResource(R.string.settings),
                onOpenSettings = { callbacks.onDismiss(); callbacks.onOpenSettings() },
                dragModifier = dragModifier,
            )
        },
    )
}

/** Android's resources for the shared reader options. */
@Composable
internal fun rememberReaderOptionsStrings() = ReaderOptionsStrings(
    modeStandard = stringResource(R.string.standard),
    modeRightToLeft = stringResource(R.string.right_to_left),
    modeVertical = stringResource(R.string.vertical),
    modeWebtoon = stringResource(R.string.webtoon),
    modeContinuousHorizontal = stringResource(R.string.continuous_horizontal),
    tabLayout = stringResource(R.string.reader_more_tab_layout),
    tabDisplay = stringResource(R.string.reader_more_tab_display),
    settings = stringResource(R.string.settings),
    continuousHorizontalReversed = stringResource(R.string.continuous_horizontal_reversed),
    pagesAnimation = stringResource(R.string.pages_animation),
    animations = stringArrayResource(R.array.reader_animation).toList(),
    scaleMode = stringResource(R.string.scale_mode),
    zoomModes = stringArrayResource(R.array.zoom_modes).toList(),
    sectionTwoPages = stringResource(R.string.reader_panel_section_two_pages),
    doublePageLandscape = stringResource(R.string.double_page_landscape),
    doublePageFoldable = stringResource(R.string.double_page_foldable),
    doublePageCoverPage = stringResource(R.string.double_page_cover_page),
    twoPageScrollSensitivity = stringResource(R.string.two_page_scroll_sensitivity),
    cropPages = stringResource(R.string.crop_pages),
    splitDoublePages = stringResource(R.string.split_double_pages),
    fullscreenMode = stringResource(R.string.fullscreen_mode),
    showPagesNumbers = stringResource(R.string.show_pages_numbers),
    chapterTitleAtBottom = stringResource(R.string.reader_chapter_title_at_bottom),
    sectionRenderer = stringResource(R.string.reader_panel_section_renderer),
    sceneRendererWebtoon = stringResource(R.string.reader_scene_renderer_webtoon),
    sceneRendererPaged = stringResource(R.string.reader_scene_renderer_paged),
    sectionPerformance = stringResource(R.string.reader_panel_section_performance),
    optimize = stringResource(R.string.reader_optimize),
    reducePagePreloading = stringResource(R.string.reader_reduce_page_preloading),
    background = stringResource(R.string.background),
    backgrounds = stringArrayResource(R.array.reader_backgrounds).toList(),
    save = stringResource(R.string.save),
    globally = stringResource(R.string.globally),
    thisManga = stringResource(R.string.this_manga),
    superResolution = stringResource(R.string.reader_super_resolution),
    imageServer = stringResource(R.string.image_server),
    automatic = stringResource(R.string.automatic),
    colorCorrection = rememberReaderColorCorrectionLabels(),
)
private enum class TranslationPageTab {
    SETTINGS,
    LOGS,
}

@Composable
private fun ReaderTranslationOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationTaskPanelContent: @Composable () -> Unit,
) {
    var selectedTab by rememberSaveable { mutableStateOf(TranslationPageTab.SETTINGS) }

    Column(
        modifier = Modifier.fillMaxSize(),
    ) {
        ReaderChoiceChips(
            options = listOf(
                stringResource(R.string.reader_translation_tab_settings),
                stringResource(R.string.reader_translation_tab_logs),
            ),
            selectedIndex = selectedTab.ordinal,
            onSelected = { selectedTab = TranslationPageTab.entries[it] },
            height = 40.dp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )

        when (selectedTab) {
            TranslationPageTab.SETTINGS -> {
                ReaderTranslationSettingsContent(
                    state = state,
                    callbacks = callbacks,
                    onViewLogs = { selectedTab = TranslationPageTab.LOGS },
                )
            }
            TranslationPageTab.LOGS -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    translationTaskPanelContent()
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderTranslationSettingsContent(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    onViewLogs: () -> Unit,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        callbacks.onDismiss()
        action()
    }
    val sourceLabels = stringArrayResource(R.array.reader_translation_source_languages)
    val sourceValues = stringArrayResource(R.array.values_reader_translation_source_languages)
    val targetLabels = stringArrayResource(R.array.reader_translation_target_languages)
    val targetValues = stringArrayResource(R.array.values_reader_translation_target_languages)
    fun selectedLabel(values: Array<String>, labels: Array<String>, selected: String): String {
        val index = values.indexOf(selected)
        return labels.getOrElse(index) { selected }
    }

    ReaderOptionsPageList {
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_translation_show_translated),
                    checked = state.translationEnabled && state.translationShowTranslated,
                    enabled = state.translationEnabled,
                    onCheckedChange = callbacks.onTranslationShowTranslatedChanged,
                )
                ReaderOptionDivider()
                ReaderOptionValueRow(
                    label = stringResource(R.string.reader_translation_source_lang),
                    value = selectedLabel(sourceValues, sourceLabels, state.translationSourceLanguage),
                    onClick = callbacks.onTranslationLanguageActions,
                )
                ReaderOptionDivider()
                ReaderOptionValueRow(
                    label = stringResource(R.string.reader_translation_target_lang),
                    value = selectedLabel(targetValues, targetLabels, state.translationTargetLanguage),
                    onClick = callbacks.onTranslationLanguageActions,
                )
                ReaderOptionDivider()
                ReaderOptionSection(
                    title = stringResource(R.string.reader_translation_ocr_mode),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    ReaderChoiceChips(
                        options = listOf(
                            stringResource(R.string.reader_translation_ocr_mode_basic),
                            stringResource(R.string.reader_translation_ocr_mode_advanced),
                        ),
                        selectedIndex = ReaderOcrMode.entries.indexOf(state.translationOcrMode),
                        onSelected = { callbacks.onTranslationOcrModeChanged(ReaderOcrMode.entries[it]) },
                        height = 44.dp,
                        // Chips sit inside a card here, so unselected chips take the panel colour.
                        unselectedColor = currentReaderPanelColors().container,
                    )
                }
            }
        }
        item {
            OptionsActionGrid {
                OptionAction(R.drawable.ic_translate, R.string.reader_translation_action, dismissThen(callbacks.onTranslation))
                OptionAction(R.drawable.ic_language, R.string.reader_translation_quick_actions, callbacks.onTranslationLanguageActions)
                OptionAction(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_page, callbacks.onRetranslatePage)
                OptionAction(R.drawable.ic_retry, R.string.reader_translation_retry_failed_pages, callbacks.onRetryFailedTranslations)
                OptionAction(R.drawable.ic_retry, R.string.reader_translation_retranslate_current_chapter, callbacks.onRetranslateChapter)
                OptionAction(R.drawable.ic_settings, R.string.reader_translation_action_settings, dismissThen(callbacks.onTranslationSettings))
            }
        }
        item {
            ReaderOptionDivider()
        }
        item {
            Surface(
                onClick = onViewLogs,
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_list_detailed),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp),
                    )
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.reader_translation_task_panel_title),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.reader_translation_view_logs_summary),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(
                        painter = painterResource(R.drawable.ic_arrow_forward),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OptionsActionGrid(
    content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit,
) {
    FlowRow(
        maxItemsInEachRow = 2,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(10.dp),
        content = content,
    )
}

@Composable
private fun androidx.compose.foundation.layout.FlowRowScope.OptionAction(
    icon: Int,
    label: Int,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier
            .weight(1f)
            .height(52.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                painterResource(icon),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp),
            )
            Text(
                stringResource(label),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 10.dp),
            )
        }
    }
}
