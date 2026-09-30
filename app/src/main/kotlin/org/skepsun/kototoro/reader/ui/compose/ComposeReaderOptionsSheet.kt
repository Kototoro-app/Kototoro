package org.skepsun.kototoro.reader.ui.compose

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.IconButton
import androidx.compose.ui.draw.clip
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.design.ReaderIconChoiceBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickActionGrid
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSliderRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.forEInk
import org.skepsun.kototoro.reader.ui.compose.design.mangaReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.panel.ReaderOptionsPanelHost
import org.skepsun.kototoro.reader.ui.compose.panel.rememberReaderPanelSurfaceMode

@Immutable
internal data class ComposeReaderOptionsState(
    val visible: Boolean = false,
    val mode: ReaderMode = ReaderMode.STANDARD,
    val continuousHorizontalReversed: Boolean = false,
    /** Scene renderer for the webtoon mode; off by default. */
    val webtoonSceneReader: Boolean = false,
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

private enum class ReaderOptionsTab(val labelResId: Int) {
    LAYOUT(R.string.reader_more_tab_layout),
    DISPLAY(R.string.reader_more_tab_display),
    TRANSLATION(R.string.reader_more_tab_translation),
}

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
    ReaderOptionsPanelHost(
        colors = if (eInkMode) colors.forEInk() else colors,
        surfaceMode = rememberReaderPanelSurfaceMode(eInkMode),
        onDismissRequest = callbacks.onDismiss,
        quickLayer = {
            ReaderMangaQuickLayer(state, callbacks, translationAvailable, translationActive)
        },
        details = { dragModifier ->
            ReaderMangaDetailTabs(state, callbacks, translationTaskPanelContent, dragModifier)
        },
    )
}

@Composable
private fun ReaderMangaQuickLayer(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationAvailable: Boolean,
    translationActive: Boolean,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        ReaderIconChoiceBar(
            options = ReaderMode.entries.map { it.label() },
            selectedIndex = ReaderMode.entries.indexOf(state.mode),
            onSelected = { callbacks.onModeChanged(ReaderMode.entries[it]) },
            icon = { index ->
                Icon(
                    painter = painterResource(ReaderMode.entries[index].iconResId()),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
        )
        ReaderQuickActionGrid(
            actions = mangaQuickActions(translationAvailable, translationActive),
            onClick = { action ->
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
    }
}

@Composable
private fun ReaderMangaDetailTabs(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    translationTaskPanelContent: @Composable () -> Unit,
    dragModifier: Modifier,
) {
    val tabs = ReaderOptionsTab.entries
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    Column(modifier = Modifier.fillMaxSize()) {
        ReaderPanelTabBar(
            labels = tabs.map { stringResource(it.labelResId) },
            selectedIndex = pagerState.currentPage,
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = dragModifier,
            trailing = {
                IconButton(onClick = { callbacks.onDismiss(); callbacks.onOpenSettings() }) {
                    Icon(
                        painter = painterResource(R.drawable.ic_settings),
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            },
        )
        HorizontalPager(
            state = pagerState,
            overscrollEffect = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            when (tabs[page]) {
                ReaderOptionsTab.LAYOUT -> ReaderLayoutOptionsPage(state, callbacks)
                ReaderOptionsTab.DISPLAY -> ReaderDisplayOptionsPage(state, callbacks)
                ReaderOptionsTab.TRANSLATION -> ReaderTranslationOptionsPage(state, callbacks, translationTaskPanelContent)
            }
        }
    }
}

@Composable
private fun ReaderLayoutOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    val animationLabels = stringArrayResource(R.array.reader_animation)
    val zoomLabels = stringArrayResource(R.array.zoom_modes)
    OptionsPageList {
        if (state.mode == ReaderMode.CONTINUOUS_HORIZONTAL) {
            // Direction is a preference of its own rather than another mode entry: the mode says
            // how pages are laid out, this says which way they are read.
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
            ReaderOptionSection(stringResource(R.string.pages_animation)) {
                ReaderChoiceChips(
                    options = ReaderAnimation.entries.mapIndexed { index, animation ->
                        animationLabels.getOrElse(index) { animation.name }
                    },
                    selectedIndex = ReaderAnimation.entries.indexOf(state.animation),
                    onSelected = { callbacks.onAnimationChanged(ReaderAnimation.entries[it]) },
                    icon = { ReaderAnimationIcon(ReaderAnimation.entries[it]) },
                )
            }
        }
        item {
            ReaderOptionSection(stringResource(R.string.scale_mode)) {
                ReaderChoiceChips(
                    options = ZoomMode.entries.mapIndexed { index, mode -> zoomLabels.getOrElse(index) { mode.name } },
                    selectedIndex = ZoomMode.entries.indexOf(state.zoomMode),
                    onSelected = { callbacks.onZoomModeChanged(ZoomMode.entries[it]) },
                    icon = { index ->
                        Icon(
                            painter = painterResource(ZoomMode.entries[index].iconResId()),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                )
            }
        }
        item {
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_two_pages)) {
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
                if (state.doublePage) {
                    // Only has an effect while landscape double pages are on.
                    ReaderOptionDivider()
                    ReaderSliderRow(
                        label = stringResource(R.string.two_page_scroll_sensitivity),
                        valueLabel = "${(state.doublePageSensitivity * 100).toInt()}%",
                        value = state.doublePageSensitivity,
                        valueRange = 0f..1f,
                        onValueChange = callbacks.onDoublePageSensitivityChanged,
                    )
                }
            }
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
            }
        }
        item {
            ReaderOptionGroup {
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
        item {
            // One switch per renderer family, so the experimental paged engine can be tried
            // without turning the webtoon renderer off. Engine preferences, editable in any mode.
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_renderer)) {
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
            ReaderOptionGroup(title = stringResource(R.string.reader_panel_section_performance)) {
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

private fun ZoomMode.iconResId(): Int = when (this) {
    ZoomMode.FIT_CENTER -> R.drawable.ic_fullscreen
    ZoomMode.FIT_HEIGHT -> R.drawable.ic_swap_vert
    ZoomMode.FIT_WIDTH -> R.drawable.ic_move_horizontal
    ZoomMode.KEEP_START -> R.drawable.ic_size_large
}

@Composable
private fun ReaderDisplayOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
) {
    OptionsPageList {
        item {
            ReaderOptionSection(stringResource(R.string.background)) {
                ReaderBackgroundSwatches(selected = state.background, onSelected = callbacks.onBackgroundChanged)
            }
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
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
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
        item {
            ReaderOptionGroup {
                ReaderOptionSwitchRow(
                    label = stringResource(R.string.reader_super_resolution),
                    checked = state.superResolution,
                    onCheckedChange = callbacks.onSuperResolutionChanged,
                )
            }
        }
        state.imageServer?.let { imageServer ->
            item {
                val automatic = stringResource(R.string.automatic)
                val labels = imageServer.entries.map { it.label ?: automatic }
                val selected = imageServer.entries.indexOfFirst { it.value == imageServer.selectedValue }.coerceAtLeast(0)
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReaderBackgroundSwatches(
    selected: ReaderBackground,
    onSelected: (ReaderBackground) -> Unit,
) {
    val labels = stringArrayResource(R.array.reader_backgrounds)
    val colors = currentReaderPanelColors()
    // Wraps instead of scrolling, so no swatch is cut off at the edge.
    FlowRow(
        maxItemsInEachRow = 3,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReaderBackground.entries.forEachIndexed { index, background ->
            val isSelected = background == selected
            val shape = RoundedCornerShape(16.dp)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .weight(1f)
                    .clip(shape)
                    .background(if (isSelected) colors.selectedContainer else colors.card)
                    .border(1.dp, if (isSelected) colors.accent else Color.Transparent, shape)
                    .tvFocusable(shape = shape, addFocusTarget = false)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = { onSelected(background) })
                    .padding(horizontal = 10.dp, vertical = 10.dp),
            ) {
                ReaderBackgroundIcon(background)
                Text(
                    text = labels.getOrElse(index) { background.name },
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) colors.content else colors.contentSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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

@Composable
private fun OptionsPageList(
    content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 12.dp),
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
            ReaderBackground.LIGHT -> drawCircle(Color(0xFFF1F0F4), radius)
            ReaderBackground.DARK -> drawCircle(Color(0xFF2A292E), radius)
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
