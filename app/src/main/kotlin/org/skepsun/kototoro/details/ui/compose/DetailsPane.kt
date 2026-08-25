package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.shape.RoundedCornerShape
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalDensity

import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.CompactTopBarHorizontalPadding
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassBottomBarContainer
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.LocalGlassPrefs
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.details.ui.model.ContentBranch
import org.skepsun.kototoro.details.ui.model.DetailsChapterSourceTab
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.pager.bookmarks.BookmarksViewModel
import org.skepsun.kototoro.details.ui.pager.pages.PagesViewModel
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.reader.ui.PageSaveHelper

internal const val DETAILS_PANE_PRESS_FEEDBACK_ENABLED = false

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DetailsPaneContent(
    detailsPaneState: DetailsPaneState,
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    branches: List<ContentBranch>,
    isLoading: Boolean,
    viewModel: DetailsViewModel,
    pagesViewModel: PagesViewModel,
    bookmarksViewModel: BookmarksViewModel,
    settings: AppSettings,
    appRouter: AppRouter,
    pageSaveHelper: PageSaveHelper,
    metadataChapterTabs: List<DetailsChapterSourceTab>,
    readingChapterTabs: List<DetailsChapterSourceTab>,
    onSelectMetadataChapterTab: (DetailsChapterSourceTab) -> Unit,
    onSelectReadingChapterTab: (DetailsChapterSourceTab) -> Unit,
    selectedTabId: Int,
    availableTabIds: List<Int>,
    isSheetFullyExpanded: Boolean,
    sheetExpansionProgressProvider: () -> Float,
    isChapterSearchAvailable: Boolean,
    isChaptersReversed: Boolean,
    isChaptersInGridView: Boolean,
    isHideReadChapters: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    isDownloadedOnly: Boolean,
    isDownloadedFilterVisible: Boolean,
    pageGridSizeValue: Float,
    pageThumbnailAspectRatio: Float,
    isPageThumbnailsFitPreview: Boolean,
    onChapterQueryChange: (String) -> Unit,
    onChapterSearchToggle: () -> Unit,
    onToggleChaptersReversed: () -> Unit,
    onToggleChaptersGrid: () -> Unit,
    onToggleHideReadChapters: () -> Unit,
    onToggleMergeRepeatedChapters: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onPageGridSizeChange: (Float) -> Unit,
    onPageThumbnailAspectRatioChange: (Float) -> Unit,
    onTogglePageThumbnailsFitPreview: () -> Unit,
    showCollapsedHandle: Boolean,
    isModernDetailsDockEnabled: Boolean,
    isModernDockCompact: Boolean,
    onSelectedTabIdChange: (Int) -> Unit,
    onActionClick: (DetailsAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chapterQuery = detailsPaneState.chapterQuery
    val isChapterSearchVisible = detailsPaneState.isChapterSearchVisible
    val sheetExpansionProgress = if (showCollapsedHandle) {
        if (detailsPaneState.anchor == CompactDetailsPaneAnchor.Collapsed) 0f else 1f
    } else {
        sheetExpansionProgressProvider()
    }
    val paneOpacityProgress = easedOpacityProgress(sheetExpansionProgress)
    val density = LocalDensity.current
    val actionsExpansionProgress = sheetExpansionProgress
    val statusBarTopPadding = WindowInsets.statusBarsIgnoringVisibility.asPaddingValues().calculateTopPadding()
    val modernDragHandleRevealProgress = modernDockDragHandleRevealProgress(
        isModernDockEnabled = isModernDetailsDockEnabled,
        paneOpacityProgress = paneOpacityProgress,
    )
    val modernPanelTopPadding = if (showCollapsedHandle) {
        modernDockActionsTopPadding(
            handleTopInset = statusBarTopPadding,
            handleRevealProgress = modernDragHandleRevealProgress,
        ) + modernDockDragHandleHeight(modernDragHandleRevealProgress) +
            modernDockDragHandleGap(modernDragHandleRevealProgress) +
            (if (detailsPaneState.isGridSizeControlsVisible) {
                GridSizeControlsHeight
            } else {
                ModernDetailsDockChromeHeight
            }) +
            ModernDetailsDockExpandedPanelGap
    } else {
        76.dp
    }
    val useCompactPaneSurfaceTint = showCollapsedHandle
    val paneShape = RoundedRectangle(28.dp)
    val paneGlassStyle = if (useCompactPaneSurfaceTint || !showCollapsedHandle) {
        GlassDefaults.prominentStyle()
    } else {
        GlassDefaults.regularStyle()
    }
    val bottomBarGlassPrefs = rememberDetailsBottomBarGlassPrefs()
    val actionsRow: @Composable (Modifier) -> Unit = { actionsModifier ->
        DetailsPaneActionsRow(
            modifier = actionsModifier,
            detailsPaneState = detailsPaneState,
            isModernDockEnabled = isModernDetailsDockEnabled,
            isModernDockCompact = isModernDockCompact,
            selectedTabId = resolveDetailsTabSelection(selectedTabId, availableTabIds),
            isSheetFullyExpanded = isSheetFullyExpanded,
            sheetExpansionProgress = actionsExpansionProgress,
            isChapterSearchAvailable = isChapterSearchAvailable,
            isChaptersReversed = isChaptersReversed,
            isChaptersInGridView = isChaptersInGridView,
            isHideReadChapters = isHideReadChapters,
            isMergeRepeatedChapters = isMergeRepeatedChapters,
            showMergeRepeatedChapters = showMergeRepeatedChapters,
            isDownloadedOnly = isDownloadedOnly,
            isDownloadedFilterVisible = isDownloadedFilterVisible,
            pageGridSizeValue = pageGridSizeValue,
            pageThumbnailAspectRatio = pageThumbnailAspectRatio,
            isPageThumbnailsFitPreview = isPageThumbnailsFitPreview,
            onChapterSearchToggle = onChapterSearchToggle,
            onToggleChaptersReversed = onToggleChaptersReversed,
            onToggleChaptersGrid = onToggleChaptersGrid,
            onToggleHideReadChapters = onToggleHideReadChapters,
            onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
            onToggleDownloadedOnly = onToggleDownloadedOnly,
            onPageGridSizeChange = onPageGridSizeChange,
            onPageThumbnailAspectRatioChange = onPageThumbnailAspectRatioChange,
            onTogglePageThumbnailsFitPreview = onTogglePageThumbnailsFitPreview,
            showCollapsedHandle = showCollapsedHandle,
            handleTopInset = statusBarTopPadding,
            contentType = contentType,
            historyInfo = historyInfo,
            branches = branches,
            isLoading = isLoading,
            onActionClick = onActionClick,
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(modifier),
        contentAlignment = Alignment.TopCenter,
    ) {
        CompositionLocalProvider(LocalGlassPrefs provides bottomBarGlassPrefs) {
            GlassSurface(
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (isModernDetailsDockEnabled) {
                            Modifier
                                .padding(top = modernPanelTopPadding)
                                .graphicsLayer {
                                    val actualPaneOpacityProgress = easedOpacityProgress(
                                        sheetExpansionProgressProvider(),
                                    )
                                    val modernPanelRevealProgress = if (!showCollapsedHandle) {
                                        1f
                                    } else {
                                        ((actualPaneOpacityProgress - 0.04f) / 0.28f).coerceIn(0f, 1f)
                                    }
                                    alpha = modernPanelRevealProgress
                                    translationY = with(density) {
                                        (18.dp * (1f - modernPanelRevealProgress)).toPx()
                                    }
                                }
                        } else {
                            Modifier
                        },
                    ),
                shape = paneShape,
                style = paneGlassStyle,
                dialogSurface = LocalInterfaceStyle.current != InterfaceStyle.IOS,
                componentRole = GlassComponentRole.BottomPanel,
                pressFeedbackEnabled = DETAILS_PANE_PRESS_FEEDBACK_ENABLED,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth(),
                    ) {
                        if (!isModernDetailsDockEnabled) {
                            actionsRow(Modifier)
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f),
                        ) {
                            ChaptersPagesTabsContent(
                                viewModel = viewModel,
                                pagesViewModel = pagesViewModel,
                                bookmarksViewModel = bookmarksViewModel,
                                settings = settings,
                                appRouter = appRouter,
                                pageSaveHelper = pageSaveHelper,
                                metadataChapterTabs = metadataChapterTabs,
                                readingChapterTabs = readingChapterTabs,
                                onSelectMetadataChapterTab = onSelectMetadataChapterTab,
                                onSelectReadingChapterTab = onSelectReadingChapterTab,
                                isMergeRepeatedChapters = isMergeRepeatedChapters,
                                selectedTabId = resolveDetailsTabSelection(selectedTabId, availableTabIds),
                                showTabStrip = false,
                                isSheetFullyExpanded = isSheetFullyExpanded,
                                isChapterListScrollEnabled = true,
                                handleSelectionBackPressInternally = !showCollapsedHandle,
                                detailsPaneState = if (showCollapsedHandle) detailsPaneState else null,
                                pageThumbnailAspectRatio = pageThumbnailAspectRatio,
                                chapterQuery = chapterQuery,
                                isChapterSearchVisible = isChapterSearchVisible,
                                onChapterQueryChange = onChapterQueryChange,
                                onChapterSelectionStateChange = detailsPaneState::onChapterSelectionStateChanged,
                                onSelectedTabIdChange = { tabId ->
                                    val resolvedTab = resolveDetailsTabSelection(tabId, availableTabIds)
                                    onSelectedTabIdChange(resolvedTab)
                                },
                            )
                        }
                    }
                }
            }
            if (isModernDetailsDockEnabled) {
                actionsRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(
                            horizontal = CompactTopBarHorizontalPadding - DetailsDockContentHorizontalPadding,
                        )
                        .graphicsLayer {
                            scaleY = 0.98f + (
                                0.02f * easedOpacityProgress(sheetExpansionProgressProvider())
                            )
                        }
                        .zIndex(1f),
                )
            }
        }
    }
}

@Composable
internal fun DetailsDockContainer(
    modernStyle: Boolean,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (modernStyle) {
        if (LocalInterfaceStyle.current == InterfaceStyle.IOS) {
            GlassBottomBarContainer(modifier = modifier) {
                content()
            }
        } else {
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(32.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                contentColor = MaterialTheme.colorScheme.onSurface,
                tonalElevation = 0.dp,
                shadowElevation = 6.dp,
            ) {
                content()
            }
        }
    } else {
        Box(modifier = modifier) {
            content()
        }
    }
}
