package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.ui.config.ImageServerOptions
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderColorCorrectionControls
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderImageComparisonPreview
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionValueRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSegmentedChoice

@Immutable
internal data class ComposeReaderOptionsState(
    val visible: Boolean = false,
    val mode: ReaderMode = ReaderMode.STANDARD,
    val continuousHorizontalReversed: Boolean = false,
    /** Scene renderer for the webtoon mode; on by default, matching the released behaviour. */
    val webtoonSceneReader: Boolean = true,
    /** Scene renderer for single/double page; off by default, matching the released behaviour. */
    val pagedSceneReader: Boolean = false,
    val animation: ReaderAnimation = ReaderAnimation.DEFAULT,
    val zoomMode: ZoomMode = ZoomMode.FIT_CENTER,
    val doublePage: Boolean = false,
    val doublePageFoldable: Boolean = false,
    val doublePageCover: Boolean = false,
    val splitPages: Boolean = false,
    val doublePageSensitivity: Float = 0.5f,
    val fullscreen: Boolean = true,
    val pageNumbers: Boolean = false,
    val cropPages: Boolean = false,
    val optimization: Boolean = false,
    val preloadReduction: Boolean = false,
    val chapterTitleAtBottom: Boolean = false,
    val superResolution: Boolean = false,
    val appearancePreviewOriginalUri: String? = null,
    val appearancePreviewProcessedUri: String? = null,
    val appearancePreviewLoading: Boolean = false,
    val colorFilter: ReaderColorFilter? = null,
    val background: ReaderBackground = ReaderBackground.DEFAULT,
    val imageServer: ImageServerOptions? = null,
    val translationEnabled: Boolean = false,
    val translationShowTranslated: Boolean = true,
    val translationSourceLanguage: String = "auto",
    val translationTargetLanguage: String = "zh",
    val translationOcrMode: ReaderOcrMode = ReaderOcrMode.BASIC,
)

internal data class ComposeReaderOptionsCallbacks(
    val onDismiss: () -> Unit = {},
    val onModeChanged: (ReaderMode) -> Unit = {},
    val onContinuousHorizontalReversedChanged: (Boolean) -> Unit = {},
    val onWebtoonSceneReaderChanged: (Boolean) -> Unit = {},
    val onPagedSceneReaderChanged: (Boolean) -> Unit = {},
    val onAnimationChanged: (ReaderAnimation) -> Unit = {},
    val onZoomModeChanged: (ZoomMode) -> Unit = {},
    val onDoublePageChanged: (Boolean) -> Unit = {},
    val onDoublePageFoldableChanged: (Boolean) -> Unit = {},
    val onDoublePageCoverChanged: (Boolean) -> Unit = {},
    val onSplitPagesChanged: (Boolean) -> Unit = {},
    val onDoublePageSensitivityChanged: (Float) -> Unit = {},
    val onFullscreenChanged: (Boolean) -> Unit = {},
    val onPageNumbersChanged: (Boolean) -> Unit = {},
    val onCropPagesChanged: (Boolean) -> Unit = {},
    val onOptimizationChanged: (Boolean) -> Unit = {},
    val onPreloadReductionChanged: (Boolean) -> Unit = {},
    val onChapterTitleAtBottomChanged: (Boolean) -> Unit = {},
    val onSuperResolutionChanged: (Boolean) -> Unit = {},
    val onBackgroundChanged: (ReaderBackground) -> Unit = {},
    val onImageServerChanged: (String?) -> Unit = {},
    val onSavePage: () -> Unit = {},
    val onPreviousChapter: () -> Unit = {},
    val onNextChapter: () -> Unit = {},
    val onPages: () -> Unit = {},
    val onBookmark: () -> Unit = {},
    val onCropNote: () -> Unit = {},
    val onDownload: () -> Unit = {},
    val onRotate: () -> Unit = {},
    val onAutoScroll: () -> Unit = {},
    val onTranslation: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
    val onColorFilterChanged: (ReaderColorFilter?) -> Unit = {},
    val onSaveColorFilterForManga: (ReaderColorFilter?) -> Unit = {},
    val onSaveColorFilterGlobally: (ReaderColorFilter?) -> Unit = {},
    val onOpenBrowser: () -> Unit = {},
    val onTranslationSettings: () -> Unit = {},
    val onTranslationShowTranslatedChanged: (Boolean) -> Unit = {},
    val onTranslationOcrModeChanged: (ReaderOcrMode) -> Unit = {},
    val onTranslationLanguageActions: () -> Unit = {},
    val onRetranslatePage: () -> Unit = {},
    val onRetryFailedTranslations: () -> Unit = {},
    val onRetranslateChapter: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun ComposeReaderOptionsSheet(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    embedded: Boolean = false,
    translationTaskPanelContent: @Composable () -> Unit = {},
    headerModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    if (!state.visible) return
    val pages = listOf(
        ReaderOptionsPage(R.drawable.ic_book_page, R.string.reader_more_tab_reading),
        ReaderOptionsPage(R.drawable.ic_lightbulb, R.string.reader_more_tab_display),
        ReaderOptionsPage(R.drawable.ic_aspect_ratio, R.string.reader_more_tab_page),
        ReaderOptionsPage(R.drawable.ic_translate, R.string.reader_more_tab_translation),
        ReaderOptionsPage(R.drawable.ic_more_vert, R.string.reader_more_tab_tools),
    )
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    Surface(
        shape = if (embedded) androidx.compose.foundation.shape.RoundedCornerShape(0.dp) else MaterialTheme.shapes.large,
        color = if (embedded) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier
            .widthIn(max = 560.dp)
            .fillMaxWidth()
            .then(if (embedded) Modifier.fillMaxHeight() else Modifier.heightIn(max = 560.dp)),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(headerModifier)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                pages.forEachIndexed { index, page ->
                    ReaderOptionsTab(
                        page = page,
                        selected = pagerState.currentPage == index,
                        onClick = { scope.launch { pagerState.scrollToPage(index) } },
                        modifier = Modifier.widthIn(min = 64.dp),
                    )
                }
            }
            HorizontalPager(
                state = pagerState,
                overscrollEffect = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
            ) { page ->
                when (page) {
                    0 -> ReaderReadingOptionsPage(state, callbacks)
                    1 -> ReaderDisplayOptionsPage(state, callbacks)
                    2 -> ReaderPageOptionsPage(state, callbacks)
                    3 -> ReaderTranslationOptionsPage(state, callbacks, translationTaskPanelContent)
                    else -> ReaderToolsOptionsPage(state, callbacks)
                }
            }
        }
    }
}

@Immutable
private data class ReaderOptionsPage(
    val iconResId: Int,
    val labelResId: Int,
)

@Composable
private fun ReaderOptionsTab(
    page: ReaderOptionsPage,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.82f) else Color.Transparent,
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = modifier.semantics { role = Role.Tab },
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
        ) {
            Icon(
                painter = painterResource(page.iconResId),
                contentDescription = stringResource(page.labelResId),
                modifier = Modifier.size(20.dp),
            )
            Text(
                text = stringResource(page.labelResId),
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderReadingOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    val animationLabels = stringArrayResource(R.array.reader_animation)
    OptionsPageList {
        item { OptionsPageTitle(R.string.reader_more_tab_reading) }
        item {
            ReaderSegmentedChoice(
                title = stringResource(R.string.reader_page_turning_mode),
                options = ReaderMode.entries.map { it.label() },
                selectedIndex = ReaderMode.entries.indexOf(state.mode),
                onSelected = { callbacks.onModeChanged(ReaderMode.entries[it]) },
                icon = { index ->
                    Icon(
                        painter = painterResource(ReaderMode.entries[index].iconResId()),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                stackedTitle = true,
                verticalOptions = true,
            )
        }
        if (state.mode == ReaderMode.CONTINUOUS_HORIZONTAL) {
            // Direction is a preference of its own here rather than another mode entry: the mode
            // says how pages are laid out, this says which way they are read.
            item {
                ReaderOptionGroup {
                    ReaderOptionSwitchRow(
                        label = stringResource(R.string.continuous_horizontal_reversed),
                        checked = state.continuousHorizontalReversed,
                        onCheckedChange = callbacks.onContinuousHorizontalReversedChanged,
                    )
                }
            }
        }
        item {
            // One switch per renderer family, so the experimental paged engine can be tried without
            // the webtoon renderer having to be turned off (they used to be a conjunction). The
            // labels name the family, and both stay editable whichever mode is on screen: these are
            // engine preferences, not properties of the current page layout.
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_scene_renderer_webtoon),
                    checked = state.webtoonSceneReader,
                    onCheckedChange = callbacks.onWebtoonSceneReaderChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_scene_renderer_paged),
                    checked = state.pagedSceneReader,
                    onCheckedChange = callbacks.onPagedSceneReaderChanged,
                )
            }
        }
        item {
            ReaderSegmentedChoice(
                title = stringResource(R.string.pages_animation),
                options = ReaderAnimation.entries.mapIndexed { index, animation ->
                    animationLabels.getOrElse(index) { animation.name }
                },
                selectedIndex = ReaderAnimation.entries.indexOf(state.animation),
                onSelected = { callbacks.onAnimationChanged(ReaderAnimation.entries[it]) },
                icon = { ReaderAnimationIcon(ReaderAnimation.entries[it]) },
                stackedTitle = true,
                verticalOptions = true,
            )
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.double_page_landscape),
                    checked = state.doublePage,
                    enabled = state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED,
                    onCheckedChange = callbacks.onDoublePageChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.double_page_foldable),
                    checked = state.doublePageFoldable,
                    enabled = state.doublePage,
                    onCheckedChange = callbacks.onDoublePageFoldableChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.double_page_cover_page),
                    checked = state.doublePageCover,
                    enabled = state.doublePage,
                    onCheckedChange = callbacks.onDoublePageCoverChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.fullscreen_mode),
                    checked = state.fullscreen,
                    onCheckedChange = callbacks.onFullscreenChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.show_pages_numbers),
                    checked = state.pageNumbers,
                    onCheckedChange = callbacks.onPageNumbersChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_chapter_title_at_bottom),
                    checked = state.chapterTitleAtBottom,
                    onCheckedChange = callbacks.onChapterTitleAtBottomChanged,
                )
            }
        }
    }
}

@Composable
private fun ReaderDisplayOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    OptionsPageList {
        item { OptionsPageTitle(R.string.reader_more_tab_display) }
        item {
            ReaderBackgroundPalette(
                selected = state.background,
                onSelected = callbacks.onBackgroundChanged,
            )
        }
        item {
            ReaderOptionGroup {
                ReaderImageComparisonPreview(
                    originalPreviewModel = state.appearancePreviewOriginalUri,
                    processedPreviewModel = state.appearancePreviewProcessedUri,
                    colorFilter = state.colorFilter,
                    isLoading = state.appearancePreviewLoading,
                    modifier = Modifier.padding(8.dp),
                )
            }
        }
        item {
            ReaderColorCorrectionControls(
                colorFilter = state.colorFilter,
                isLoading = state.appearancePreviewLoading,
                onColorFilterChange = callbacks.onColorFilterChanged,
                onReset = { callbacks.onColorFilterChanged(null) },
            )
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_super_resolution),
                    checked = state.superResolution,
                    onCheckedChange = callbacks.onSuperResolutionChanged,
                )
            }
        }
        item {
            ReaderOptionGroup {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = stringResource(R.string.save),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    TextButton(onClick = { callbacks.onSaveColorFilterGlobally(state.colorFilter) }) {
                        Text(stringResource(R.string.globally))
                    }
                    TextButton(onClick = { callbacks.onSaveColorFilterForManga(state.colorFilter) }) {
                        Text(stringResource(R.string.this_manga))
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderBackgroundPalette(
    selected: ReaderBackground,
    onSelected: (ReaderBackground) -> Unit,
) {
    val labels = stringArrayResource(R.array.reader_backgrounds)
    ReaderOptionGroup {
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        ) {
            Text(
                text = stringResource(R.string.background),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
            ) {
                ReaderBackground.entries.forEachIndexed { index, background ->
                    val isSelected = background == selected
                    Surface(
                        onClick = { onSelected(background) },
                        shape = RoundedCornerShape(12.dp),
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerLow
                        },
                        contentColor = if (isSelected) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        border = BorderStroke(
                            1.dp,
                            if (isSelected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
                            } else {
                                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                            },
                        ),
                        modifier = Modifier
                            .width(72.dp)
                            .height(76.dp),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                            modifier = Modifier
                                .fillMaxHeight()
                                .padding(horizontal = 3.dp, vertical = 7.dp),
                        ) {
                            ReaderBackgroundIcon(background)
                            Text(
                                text = labels.getOrElse(index) { background.name },
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPageOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    val zoomLabels = stringArrayResource(R.array.zoom_modes)
    OptionsPageList {
        item { OptionsPageTitle(R.string.reader_more_tab_page) }
        item {
            ReaderSegmentedChoice(
                title = stringResource(R.string.scale_mode),
                options = ZoomMode.entries.mapIndexed { index, mode ->
                    zoomLabels.getOrElse(index) { mode.name }
                },
                selectedIndex = ZoomMode.entries.indexOf(state.zoomMode),
                onSelected = { callbacks.onZoomModeChanged(ZoomMode.entries[it]) },
                icon = { index ->
                    val iconResId = when (ZoomMode.entries[index]) {
                        ZoomMode.FIT_CENTER -> R.drawable.ic_fullscreen
                        ZoomMode.FIT_HEIGHT -> R.drawable.ic_swap_vert
                        ZoomMode.FIT_WIDTH -> R.drawable.ic_move_horizontal
                        ZoomMode.KEEP_START -> R.drawable.ic_size_large
                    }
                    Icon(
                        painter = painterResource(iconResId),
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                },
                stackedTitle = true,
                verticalOptions = true,
            )
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.crop_pages),
                    checked = state.cropPages,
                    onCheckedChange = callbacks.onCropPagesChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.split_double_pages),
                    checked = state.splitPages,
                    onCheckedChange = callbacks.onSplitPagesChanged,
                )
                if (state.doublePage) {
                    ReaderOptionDivider()
                    ReaderDoublePageSensitivity(state, callbacks)
                }
            }
        }
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_optimize),
                    checked = state.optimization,
                    onCheckedChange = callbacks.onOptimizationChanged,
                )
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_reduce_page_preloading),
                    checked = state.preloadReduction,
                    onCheckedChange = callbacks.onPreloadReductionChanged,
                )
            }
        }
    }
}

@Composable
private fun ReaderDoublePageSensitivity(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.two_page_scroll_sensitivity),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${(state.doublePageSensitivity * 100).toInt()}%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = state.doublePageSensitivity,
            onValueChange = callbacks.onDoublePageSensitivityChanged,
            valueRange = 0f..1f,
        )
    }
}

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
        ReaderSegmentedChoice(
            options = listOf(
                stringResource(R.string.reader_translation_tab_settings),
                stringResource(R.string.reader_translation_tab_logs),
            ),
            selectedIndex = selectedTab.ordinal,
            onSelected = { selectedTab = TranslationPageTab.entries[it] },
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
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

    OptionsPageList {
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
                ReaderSegmentedChoice(
                    title = stringResource(R.string.reader_translation_ocr_mode),
                    options = listOf(
                        stringResource(R.string.reader_translation_ocr_mode_basic),
                        stringResource(R.string.reader_translation_ocr_mode_advanced),
                    ),
                    selectedIndex = ReaderOcrMode.entries.indexOf(state.translationOcrMode),
                    onSelected = { callbacks.onTranslationOcrModeChanged(ReaderOcrMode.entries[it]) },
                    stackedTitle = true,
                    // Keep the option cards clear of the parent MD3 group's rounded clip.
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
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
private fun ReaderToolsOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    fun dismissThen(action: () -> Unit): () -> Unit = {
        callbacks.onDismiss()
        action()
    }
    OptionsPageList {
        item { OptionsPageTitle(R.string.reader_more_tab_tools) }
        item {
            ReaderOptionGroup {
                ReaderToolActionRow(
                    icon = R.drawable.ic_grid,
                    title = stringResource(R.string.chapters_and_pages),
                    onClick = dismissThen(callbacks.onPages),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_prev,
                    title = stringResource(R.string.prev_chapter),
                    onClick = dismissThen(callbacks.onPreviousChapter),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_next,
                    title = stringResource(R.string.next_chapter),
                    onClick = dismissThen(callbacks.onNextChapter),
                )
            }
        }
        item {
            ReaderOptionGroup {
                ReaderToolActionRow(
                    icon = R.drawable.ic_save,
                    title = stringResource(R.string.save_page),
                    onClick = dismissThen(callbacks.onSavePage),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_crop,
                    title = stringResource(R.string.crop_and_annotate),
                    onClick = dismissThen(callbacks.onCropNote),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_bookmark,
                    title = stringResource(R.string.bookmark_add),
                    onClick = dismissThen(callbacks.onBookmark),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_download,
                    title = stringResource(R.string.download),
                    onClick = dismissThen(callbacks.onDownload),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_screen_rotation,
                    title = stringResource(R.string.rotate_screen),
                    onClick = dismissThen(callbacks.onRotate),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_timer,
                    title = stringResource(R.string.automatic_scroll),
                    onClick = dismissThen(callbacks.onAutoScroll),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_web,
                    title = stringResource(R.string.open_in_browser),
                    onClick = dismissThen(callbacks.onOpenBrowser),
                )
                ReaderOptionDivider()
                ReaderToolActionRow(
                    icon = R.drawable.ic_settings,
                    title = stringResource(R.string.settings),
                    onClick = dismissThen(callbacks.onOpenSettings),
                )
            }
        }
        item {
            state.imageServer?.let { imageServer ->
                val automatic = stringResource(R.string.automatic)
                val labels = imageServer.entries.map { it.label ?: automatic }
                val selected = imageServer.entries.indexOfFirst {
                    it.value == imageServer.selectedValue
                }.coerceAtLeast(0)
                ReaderOptionGroup {
                    SelectRow(
                        title = stringResource(R.string.image_server),
                        selected = labels.getOrElse(selected) { automatic },
                        options = labels,
                        onSelected = { callbacks.onImageServerChanged(imageServer.entries[it].value) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OptionsPageTitle(resId: Int) {
    Text(
        text = stringResource(resId),
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
    )
}

@Composable
private fun ReaderToolActionRow(
    icon: Int,
    title: String,
    supporting: String? = null,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.72f),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(1.dp),
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                supporting?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
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

@Composable
private fun OptionsPageList(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp, vertical = 8.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
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

@Composable
private fun SelectRow(
    title: String,
    selected: String,
    options: List<String>,
    onSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        ReaderOptionValueRow(
            label = title,
            value = selected,
            onClick = { expanded = true },
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { index, option ->
                DropdownMenuItem(text = { Text(option) }, onClick = { expanded = false; onSelected(index) })
            }
        }
    }
}

@Composable
private fun ReaderMode.label(): String = stringResource(
    when (this) {
        ReaderMode.STANDARD -> R.string.standard
        ReaderMode.REVERSED -> R.string.right_to_left
        ReaderMode.VERTICAL -> R.string.vertical
        ReaderMode.WEBTOON -> R.string.webtoon
        ReaderMode.CONTINUOUS_HORIZONTAL -> R.string.continuous_horizontal
    },
)

private fun ReaderMode.iconResId(): Int = when (this) {
    ReaderMode.STANDARD -> R.drawable.ic_reader_ltr
    ReaderMode.REVERSED -> R.drawable.ic_reader_rtl
    ReaderMode.VERTICAL -> R.drawable.ic_reader_vertical
    ReaderMode.WEBTOON -> R.drawable.ic_gesture_vertical
    ReaderMode.CONTINUOUS_HORIZONTAL -> R.drawable.ic_move_horizontal
}

@Composable
internal fun ReaderAnimationIcon(animation: ReaderAnimation) {
    val color = androidx.compose.material3.LocalContentColor.current
    Canvas(modifier = Modifier.size(20.dp)) {
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round)
        val left = size.width * 0.18f
        val top = size.height * 0.16f
        val right = size.width * 0.82f
        val bottom = size.height * 0.84f
        when (animation) {
            ReaderAnimation.NONE -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(right - left, bottom - top),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                drawLine(
                    color = color,
                    start = Offset(left, bottom),
                    end = Offset(right, top),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
            ReaderAnimation.DEFAULT -> {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(left, top),
                    size = Size(size.width * 0.46f, bottom - top),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                    style = stroke,
                )
                val arrowEnd = Offset(right, size.height * 0.5f)
                drawLine(
                    color,
                    Offset(size.width * 0.62f, size.height * 0.5f),
                    arrowEnd,
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.72f, size.height * 0.4f),
                    arrowEnd,
                    stroke.width,
                    StrokeCap.Round,
                )
                drawLine(
                    color,
                    Offset(size.width * 0.72f, size.height * 0.6f),
                    arrowEnd,
                    stroke.width,
                    StrokeCap.Round,
                )
            }
            ReaderAnimation.ADVANCED -> {
                repeat(3) { index ->
                    val offset = index * size.width * 0.12f
                    drawRoundRect(
                        color = color,
                        topLeft = Offset(left + offset, top + offset * 0.35f),
                        size = Size(size.width * 0.44f, size.height * 0.58f),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                        style = stroke,
                    )
                }
            }
            ReaderAnimation.SIMULATION -> {
                val path = Path().apply {
                    moveTo(left, top)
                    lineTo(size.width * 0.56f, top)
                    cubicTo(right, size.height * 0.28f, right, size.height * 0.7f, size.width * 0.58f, bottom)
                    lineTo(left, bottom)
                    close()
                }
                drawPath(path, color, style = stroke)
                drawLine(
                    color = color,
                    start = Offset(size.width * 0.58f, bottom),
                    end = Offset(right, size.height * 0.68f),
                    strokeWidth = stroke.width,
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
private fun ReaderBackgroundIcon(background: ReaderBackground) {
    val colors = MaterialTheme.colorScheme
    val outline = androidx.compose.material3.LocalContentColor.current
    Canvas(modifier = Modifier.size(24.dp)) {
        val radius = size.minDimension * 0.38f
        val glyphTopLeft = Offset(
            x = center.x - radius,
            y = center.y - radius,
        )
        val glyphSize = Size(radius * 2f, radius * 2f)
        when (background) {
            ReaderBackground.DEFAULT -> {
                drawArc(
                    color = colors.surface,
                    startAngle = -90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = glyphTopLeft,
                    size = glyphSize,
                )
                drawArc(
                    color = colors.onSurface,
                    startAngle = 90f,
                    sweepAngle = 180f,
                    useCenter = true,
                    topLeft = glyphTopLeft,
                    size = glyphSize,
                )
            }
            ReaderBackground.LIGHT -> drawCircle(colors.surfaceBright, radius)
            ReaderBackground.DARK -> drawCircle(colors.surfaceDim, radius)
            ReaderBackground.WHITE -> drawCircle(Color.White, radius)
            ReaderBackground.BLACK -> drawCircle(Color.Black, radius)
            ReaderBackground.AUTO -> {
                drawCircle(colors.primaryContainer, radius)
                drawCircle(colors.primary, radius * 0.42f)
            }
        }
        drawCircle(outline, radius, center, style = Stroke(width = 1.5.dp.toPx()))
    }
}
