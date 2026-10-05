package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.prefs.ReaderAnimation
import org.skepsun.kototoro.core.prefs.ReaderBackground
import org.skepsun.kototoro.core.prefs.ReaderMode
import org.skepsun.kototoro.core.prefs.ReaderOcrMode
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.reader.core.ZoomMode
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderColorCorrectionControls
import org.skepsun.kototoro.reader.ui.colorfilter.ReaderColorCorrectionLabels
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChoiceChips
import org.skepsun.kototoro.reader.ui.compose.design.ReaderIconChoiceBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionDivider
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionGroup
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSection
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionSwitchRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderOptionValueRow
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelTabBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickActionGrid
import org.skepsun.kototoro.reader.ui.compose.design.ReaderSliderRow
import org.skepsun.kototoro.reader.ui.compose.design.currentReaderPanelColors
import org.skepsun.kototoro.reader.ui.config.ImageServerOptions

@Immutable
data class ComposeReaderOptionsState(
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

data class ComposeReaderOptionsCallbacks(
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

/**
 * Which of Android's reader options a host offers. Android offers all of them; another host leaves out the ones it
 * has no engine for, and the panel hides those rows.
 */
@Immutable
data class ReaderOptionsFeatures(
    val modes: List<ReaderMode> = ReaderMode.entries,
    val doublePageFoldable: Boolean = true,
    val doublePageSensitivity: Boolean = true,
    val splitPages: Boolean = true,
    val fullscreen: Boolean = true,
    val chapterTitleAtBottom: Boolean = true,
    val sceneRenderers: Boolean = true,
    val performance: Boolean = true,
    /** Hosts that apply colour correction permanently right away have no save row. */
    val saveColorFilter: Boolean = true,
    val saveColorFilterForManga: Boolean = true,
    val superResolution: Boolean = true,
)

/** The reader options' text, named after Android's string resources (the Windows host reads the same resources). */
@Immutable
data class ReaderOptionsStrings(
    val modeStandard: String,
    val modeRightToLeft: String,
    val modeVertical: String,
    val modeWebtoon: String,
    val modeContinuousHorizontal: String,
    val tabLayout: String,
    val tabDisplay: String,
    val settings: String,
    val continuousHorizontalReversed: String,
    val pagesAnimation: String,
    val animations: List<String>,
    val scaleMode: String,
    val zoomModes: List<String>,
    val sectionTwoPages: String,
    val doublePageLandscape: String,
    val doublePageFoldable: String,
    val doublePageCoverPage: String,
    val twoPageScrollSensitivity: String,
    val cropPages: String,
    val splitDoublePages: String,
    val fullscreenMode: String,
    val showPagesNumbers: String,
    val chapterTitleAtBottom: String,
    val sectionRenderer: String,
    val sceneRendererWebtoon: String,
    val sceneRendererPaged: String,
    val sectionPerformance: String,
    val optimize: String,
    val reducePagePreloading: String,
    val background: String,
    val backgrounds: List<String>,
    val save: String,
    val globally: String,
    val thisManga: String,
    val superResolution: String,
    val imageServer: String,
    val automatic: String,
    val colorCorrection: ReaderColorCorrectionLabels,
) {
    fun mode(mode: ReaderMode): String = when (mode) {
        ReaderMode.STANDARD -> modeStandard
        ReaderMode.REVERSED -> modeRightToLeft
        ReaderMode.VERTICAL -> modeVertical
        ReaderMode.WEBTOON -> modeWebtoon
        ReaderMode.CONTINUOUS_HORIZONTAL -> modeContinuousHorizontal
    }
}

/** One page of the detail tabs. */
@Immutable
class ReaderOptionsTab(val label: String, val content: @Composable () -> Unit)

/** The quick layer: the reading-mode bar and the quick actions. */
@Composable
fun ReaderMangaQuickLayer(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    strings: ReaderOptionsStrings,
    features: ReaderOptionsFeatures,
    actions: List<ReaderQuickAction>,
    actionIcon: @Composable (ReaderQuickAction) -> Painter,
    actionLabel: @Composable (ReaderQuickAction) -> String,
    onAction: (ReaderQuickAction) -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
    ) {
        val modes = features.modes
        ReaderIconChoiceBar(
            options = modes.map(strings::mode),
            selectedIndex = modes.indexOf(state.mode),
            onSelected = { callbacks.onModeChanged(modes[it]) },
            icon = { index ->
                Icon(
                    imageVector = modes[index].icon(),
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            },
            modifier = Modifier.testTag("reader-options-modes"),
        )
        ReaderQuickActionGrid(
            actions = actions,
            onClick = onAction,
            icon = actionIcon,
            label = actionLabel,
        )
    }
}

/** The detail tabs under the quick layer, with an optional host settings button. */
@Composable
fun ReaderOptionsDetailTabs(
    tabs: List<ReaderOptionsTab>,
    settingsDescription: String,
    onOpenSettings: (() -> Unit)?,
    dragModifier: Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { tabs.size })
    val scope = rememberCoroutineScope()
    val settingsButton: (@Composable () -> Unit)? = if (onOpenSettings == null) null else {
        {
            IconButton(onClick = onOpenSettings, modifier = Modifier.testTag("reader-options-settings")) {
                Icon(imageVector = ReaderPanelIcons.Settings, contentDescription = settingsDescription)
            }
        }
    }
    Column(modifier = Modifier.fillMaxSize()) {
        ReaderPanelTabBar(
            labels = tabs.map { it.label },
            selectedIndex = pagerState.currentPage,
            onSelected = { scope.launch { pagerState.animateScrollToPage(it) } },
            modifier = dragModifier,
            trailing = settingsButton,
        )
        HorizontalPager(
            state = pagerState,
            overscrollEffect = null,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        ) { page ->
            tabs[page].content()
        }
    }
}

@Composable
fun ReaderLayoutOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    strings: ReaderOptionsStrings,
    features: ReaderOptionsFeatures = ReaderOptionsFeatures(),
) {
    ReaderOptionsPageList {
        if (state.mode == ReaderMode.CONTINUOUS_HORIZONTAL) {
            // Direction is a preference of its own rather than another mode entry: the mode says
            // how pages are laid out, this says which way they are read.
            item {
                ReaderOptionGroup {
                    ReaderOptionSwitchRow(
                        label = strings.continuousHorizontalReversed,
                        checked = state.continuousHorizontalReversed,
                        onCheckedChange = callbacks.onContinuousHorizontalReversedChanged,
                    )
                }
            }
        }
        item {
            ReaderOptionSection(strings.pagesAnimation) {
                ReaderChoiceChips(
                    options = ReaderAnimation.entries.mapIndexed { index, animation ->
                        strings.animations.getOrElse(index) { animation.name }
                    },
                    selectedIndex = ReaderAnimation.entries.indexOf(state.animation),
                    onSelected = { callbacks.onAnimationChanged(ReaderAnimation.entries[it]) },
                    icon = { ReaderAnimationIcon(ReaderAnimation.entries[it]) },
                    modifier = Modifier.testTag("reader-options-animation"),
                )
            }
        }
        item {
            ReaderOptionSection(strings.scaleMode) {
                ReaderChoiceChips(
                    options = ZoomMode.entries.mapIndexed { index, mode -> strings.zoomModes.getOrElse(index) { mode.name } },
                    selectedIndex = ZoomMode.entries.indexOf(state.zoomMode),
                    onSelected = { callbacks.onZoomModeChanged(ZoomMode.entries[it]) },
                    icon = { index ->
                        Icon(
                            imageVector = ZoomMode.entries[index].icon(),
                            contentDescription = null,
                            modifier = Modifier.size(20.dp),
                        )
                    },
                    modifier = Modifier.testTag("reader-options-zoom"),
                )
            }
        }
        item {
            ReaderOptionGroup(title = strings.sectionTwoPages) {
                ReaderOptionSwitchRow(
                    label = strings.doublePageLandscape,
                    checked = state.doublePage,
                    enabled = state.mode == ReaderMode.STANDARD || state.mode == ReaderMode.REVERSED,
                    onCheckedChange = callbacks.onDoublePageChanged,
                    modifier = Modifier.testTag("reader-options-double-page"),
                )
                if (features.doublePageFoldable) {
                    ReaderOptionDivider()
                    ReaderOptionSwitchRow(
                        label = strings.doublePageFoldable,
                        checked = state.doublePageFoldable,
                        enabled = state.doublePage,
                        onCheckedChange = callbacks.onDoublePageFoldableChanged,
                    )
                }
                ReaderOptionDivider()
                ReaderOptionSwitchRow(
                    label = strings.doublePageCoverPage,
                    checked = state.doublePageCover,
                    enabled = state.doublePage,
                    onCheckedChange = callbacks.onDoublePageCoverChanged,
                    modifier = Modifier.testTag("reader-options-double-cover"),
                )
                if (state.doublePage && features.doublePageSensitivity) {
                    // Only has an effect while landscape double pages are on.
                    ReaderOptionDivider()
                    ReaderSliderRow(
                        label = strings.twoPageScrollSensitivity,
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
                    label = strings.cropPages,
                    checked = state.cropPages,
                    onCheckedChange = callbacks.onCropPagesChanged,
                    modifier = Modifier.testTag("reader-options-crop"),
                )
                if (features.splitPages) {
                    ReaderOptionDivider()
                    ReaderOptionSwitchRow(
                        label = strings.splitDoublePages,
                        checked = state.splitPages,
                        onCheckedChange = callbacks.onSplitPagesChanged,
                    )
                }
            }
        }
        item {
            ReaderOptionGroup {
                if (features.fullscreen) {
                    ReaderOptionSwitchRow(
                        label = strings.fullscreenMode,
                        checked = state.fullscreen,
                        onCheckedChange = callbacks.onFullscreenChanged,
                        modifier = Modifier.testTag("reader-options-fullscreen"),
                    )
                    ReaderOptionDivider()
                }
                ReaderOptionSwitchRow(
                    label = strings.showPagesNumbers,
                    checked = state.pageNumbers,
                    onCheckedChange = callbacks.onPageNumbersChanged,
                    modifier = Modifier.testTag("reader-options-page-numbers"),
                )
                if (features.chapterTitleAtBottom) {
                    ReaderOptionDivider()
                    ReaderOptionSwitchRow(
                        label = strings.chapterTitleAtBottom,
                        checked = state.chapterTitleAtBottom,
                        onCheckedChange = callbacks.onChapterTitleAtBottomChanged,
                    )
                }
            }
        }
        if (features.sceneRenderers) {
            item {
                // One switch per renderer family, so the experimental paged engine can be tried
                // without turning the webtoon renderer off. Engine preferences, editable in any mode.
                ReaderOptionGroup(title = strings.sectionRenderer) {
                    ReaderOptionSwitchRow(
                        label = strings.sceneRendererWebtoon,
                        checked = state.webtoonSceneReader,
                        onCheckedChange = callbacks.onWebtoonSceneReaderChanged,
                    )
                    ReaderOptionDivider()
                    ReaderOptionSwitchRow(
                        label = strings.sceneRendererPaged,
                        checked = state.pagedSceneReader,
                        onCheckedChange = callbacks.onPagedSceneReaderChanged,
                    )
                }
            }
        }
        if (features.performance) {
            item {
                ReaderOptionGroup(title = strings.sectionPerformance) {
                    ReaderOptionSwitchRow(
                        label = strings.optimize,
                        checked = state.optimization,
                        onCheckedChange = callbacks.onOptimizationChanged,
                    )
                    ReaderOptionDivider()
                    ReaderOptionSwitchRow(
                        label = strings.reducePagePreloading,
                        checked = state.preloadReduction,
                        onCheckedChange = callbacks.onPreloadReductionChanged,
                    )
                }
            }
        }
    }
}

/**
 * Background, colour correction (with an optional before/after [appearancePreview]), saving the filter, super
 * resolution and the image server.
 */
@Composable
fun ReaderDisplayOptionsPage(
    state: ComposeReaderOptionsState,
    callbacks: ComposeReaderOptionsCallbacks,
    strings: ReaderOptionsStrings,
    features: ReaderOptionsFeatures = ReaderOptionsFeatures(),
    appearancePreview: (@Composable () -> Unit)? = null,
) {
    ReaderOptionsPageList {
        item {
            ReaderOptionSection(strings.background) {
                ReaderBackgroundSwatches(
                    selected = state.background,
                    labels = strings.backgrounds,
                    onSelected = callbacks.onBackgroundChanged,
                )
            }
        }
        if (appearancePreview != null) {
            item {
                ReaderOptionGroup {
                    appearancePreview()
                }
            }
        }
        item {
            ReaderColorCorrectionControls(
                colorFilter = state.colorFilter,
                isLoading = state.appearancePreviewLoading,
                onColorFilterChange = callbacks.onColorFilterChanged,
                onReset = { callbacks.onColorFilterChanged(null) },
                labels = strings.colorCorrection,
            )
        }
        if (features.saveColorFilter) item {
            ReaderOptionGroup {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 52.dp)
                        .padding(horizontal = 4.dp),
                ) {
                    Text(
                        text = strings.save,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.weight(1f).padding(start = 12.dp),
                    )
                    TextButton(
                        onClick = { callbacks.onSaveColorFilterGlobally(state.colorFilter) },
                        modifier = Modifier.testTag("reader-options-color-save-global"),
                    ) {
                        Text(strings.globally)
                    }
                    if (features.saveColorFilterForManga) {
                        TextButton(onClick = { callbacks.onSaveColorFilterForManga(state.colorFilter) }) {
                            Text(strings.thisManga)
                        }
                    }
                }
            }
        }
        if (features.superResolution) {
            item {
                ReaderOptionGroup {
                    ReaderOptionSwitchRow(
                        label = strings.superResolution,
                        checked = state.superResolution,
                        onCheckedChange = callbacks.onSuperResolutionChanged,
                        modifier = Modifier.testTag("reader-options-super-resolution"),
                    )
                }
            }
        }
        state.imageServer?.let { imageServer ->
            item {
                val labels = imageServer.entries.map { it.label ?: strings.automatic }
                val selected = imageServer.entries.indexOfFirst { it.value == imageServer.selectedValue }.coerceAtLeast(0)
                ReaderOptionGroup {
                    SelectRow(
                        title = strings.imageServer,
                        selected = labels.getOrElse(selected) { strings.automatic },
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
fun ReaderBackgroundSwatches(
    selected: ReaderBackground,
    labels: List<String>,
    onSelected: (ReaderBackground) -> Unit,
) {
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
                    .testTag("reader-options-background:${background.name}")
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

@Composable
fun ReaderOptionsPageList(
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
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

fun ReaderMode.icon(): ImageVector = when (this) {
    ReaderMode.STANDARD -> ReaderPanelIcons.ReaderLtr
    ReaderMode.REVERSED -> ReaderPanelIcons.ReaderRtl
    ReaderMode.VERTICAL -> ReaderPanelIcons.ReaderVertical
    ReaderMode.WEBTOON -> ReaderPanelIcons.GestureVertical
    ReaderMode.CONTINUOUS_HORIZONTAL -> ReaderPanelIcons.MoveHorizontal
}

fun ZoomMode.icon(): ImageVector = when (this) {
    ZoomMode.FIT_CENTER -> ReaderPanelIcons.Fullscreen
    ZoomMode.FIT_HEIGHT -> ReaderPanelIcons.SwapVert
    ZoomMode.FIT_WIDTH -> ReaderPanelIcons.MoveHorizontal
    ZoomMode.KEEP_START -> ReaderPanelIcons.SizeLarge
}

@Composable
fun ReaderAnimationIcon(animation: ReaderAnimation) {
    val color = LocalContentColor.current
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
fun ReaderBackgroundIcon(background: ReaderBackground) {
    val colors = MaterialTheme.colorScheme
    val outline = LocalContentColor.current
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

/** Panel colours for the manga reader, following its page background (as [ReaderBackground] is light or dark). */
@Composable
fun rememberMangaReaderPanelColors(background: ReaderBackground) =
    org.skepsun.kototoro.reader.ui.compose.design.mangaReaderPanelColors(
        background,
        isSystemInDarkTheme(),
        MaterialTheme.colorScheme,
    )
