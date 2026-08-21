package org.skepsun.kototoro.details.ui.compose


import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.KototoroMotion
import org.skepsun.kototoro.core.ui.compose.KototoroSlider
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.rememberGlassPrefsOrFallback
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.details.ui.model.ContentBranch
import org.skepsun.kototoro.details.ui.model.HistoryInfo
import org.skepsun.kototoro.details.ui.compose.state.CompactDetailsPaneAnchor
import org.skepsun.kototoro.details.ui.compose.state.rememberDetailsPaneFlingBehavior
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneTopBarMode
import org.skepsun.kototoro.details.ui.compose.state.DetailsPaneState
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

@Composable
internal fun DetailsPaneActionsRow(
    modifier: Modifier = Modifier,
    detailsPaneState: DetailsPaneState,
    isModernDockEnabled: Boolean,
    isModernDockCompact: Boolean,
    selectedTabId: Int,
    isSheetFullyExpanded: Boolean,
    sheetExpansionProgress: Float,
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
    handleTopInset: androidx.compose.ui.unit.Dp,
    contentType: ContentType?,
    historyInfo: HistoryInfo,
    branches: List<ContentBranch>,
    isLoading: Boolean,
    onActionClick: (DetailsAction) -> Unit,
) {
    val isChapterSearchVisible = detailsPaneState.isChapterSearchVisible
    val chapterSelectionState = detailsPaneState.chapterSelectionState
    val paneOpacityProgress = easedOpacityProgress(sheetExpansionProgress)
    val showPagesTab = contentType != ContentType.VIDEO &&
        contentType != ContentType.HENTAI_VIDEO &&
        contentType != ContentType.NOVEL &&
        contentType != ContentType.HENTAI_NOVEL
    val showBookmarksTab = contentType != ContentType.VIDEO &&
        contentType != ContentType.HENTAI_VIDEO
    val compactModernDock = isModernDockEnabled && isModernDockCompact
    val showAllDockTabs = !compactModernDock
    val modernDragHandleRevealProgress = modernDockDragHandleRevealProgress(
        isModernDockEnabled = isModernDockEnabled,
        paneOpacityProgress = paneOpacityProgress,
    )
    val paneFlingBehavior = rememberDetailsPaneFlingBehavior(detailsPaneState)
    val shouldShowPaneDragHandle = showCollapsedHandle && modernDragHandleRevealProgress > 0.01f
    val dragHandleAlpha by animateFloatAsState(
        targetValue = if (
            isModernDockEnabled && detailsPaneState.anchor == CompactDetailsPaneAnchor.Collapsed
        ) {
            0f
        } else {
            lerpFloat(0.68f, 1f, paneOpacityProgress) * modernDragHandleRevealProgress
        },
        animationSpec = KototoroMotion.fadeDefault(),
        label = "detailsPaneDragHandleAlpha",
    )
    val dockItemEnter = fadeIn(KototoroMotion.fadeDefault()) + expandHorizontally(
        animationSpec = tween(ModernDetailsDockAnimationDurationMillis, easing = FastOutSlowInEasing),
        expandFrom = Alignment.Start,
    )
    val dockItemExit = fadeOut(KototoroMotion.fadeFast()) + shrinkHorizontally(
        animationSpec = KototoroMotion.tweenEaseOut(320),
        shrinkTowards = Alignment.Start,
    )
    val modernDockDragModifier = if (isModernDockEnabled) {
        Modifier.anchoredDraggable(
            state = detailsPaneState.anchoredState,
            orientation = Orientation.Vertical,
            enabled = detailsPaneState.isPaneTopBarDragEnabled,
            flingBehavior = paneFlingBehavior,
        )
    } else {
        Modifier
    }

    LaunchedEffect(selectedTabId, isSheetFullyExpanded) {
        detailsPaneState.syncTopBarContext(
            selectedTabId = selectedTabId,
            chaptersTabId = DETAILS_TAB_CHAPTERS,
            isSheetFullyExpanded = isSheetFullyExpanded,
        )
    }
    val topBarMode = detailsPaneState.topBarMode(
        selectedTabId = selectedTabId,
        chaptersTabId = DETAILS_TAB_CHAPTERS,
        isCompactLayout = showCollapsedHandle,
    )

    Column(
        modifier = Modifier
            .then(modifier)
            .fillMaxWidth()
            .then(
                if (isModernDockEnabled) {
                    Modifier
                } else {
                    Modifier.anchoredDraggable(
                        state = detailsPaneState.anchoredState,
                        orientation = Orientation.Vertical,
                        enabled = detailsPaneState.isPaneTopBarDragEnabled,
                        flingBehavior = paneFlingBehavior,
                    )
                },
            )
            .padding(
                start = DetailsDockContentHorizontalPadding,
                end = DetailsDockContentHorizontalPadding,
                top = if (showCollapsedHandle) {
                    modernDockActionsTopPadding(
                        handleTopInset = handleTopInset,
                        handleRevealProgress = modernDragHandleRevealProgress,
                    )
                } else {
                    7.dp
                },
                bottom = 2.dp,
            ),
        verticalArrangement = Arrangement.spacedBy(
            if (showCollapsedHandle) {
                modernDockDragHandleGap(modernDragHandleRevealProgress)
            } else {
                4.dp
            },
        ),
        horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally,
    ) {
        if (shouldShowPaneDragHandle) {
            Box(
                modifier = Modifier
                    .height(modernDockDragHandleHeight(modernDragHandleRevealProgress))
                    .then(
                        if (isModernDockEnabled) {
                            Modifier
                                .width(64.dp)
                                .then(
                                    if (detailsPaneState.anchor == CompactDetailsPaneAnchor.Collapsed) {
                                        Modifier
                                    } else {
                                        Modifier.clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            detailsPaneState.animateTo(
                                                when (detailsPaneState.anchor) {
                                                    CompactDetailsPaneAnchor.Collapsed -> CompactDetailsPaneAnchor.Hovered
                                                    CompactDetailsPaneAnchor.Hovered -> CompactDetailsPaneAnchor.Full
                                                    CompactDetailsPaneAnchor.Full -> CompactDetailsPaneAnchor.Collapsed
                                                },
                                            )
                                        }
                                    },
                                )
                                .anchoredDraggable(
                                    state = detailsPaneState.anchoredState,
                                    orientation = Orientation.Vertical,
                                    enabled = detailsPaneState.isPaneTopBarDragEnabled,
                                    flingBehavior = paneFlingBehavior,
                                )
                        } else {
                            Modifier.fillMaxWidth()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                DetailsPaneDragHandle(
                    modifier = Modifier
                        .alpha(dragHandleAlpha),
                )
            }
        }
        when (topBarMode) {
            DetailsPaneTopBarMode.ChapterSelection -> {
                ChapterSelectionTopBar(
                    state = chapterSelectionState ?: return@Column,
                    modernStyle = isModernDockEnabled,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            DetailsPaneTopBarMode.GridSizeControls -> {
                PageGridSizeControlsRow(
                    sizeValue = pageGridSizeValue,
                    aspectRatio = pageThumbnailAspectRatio,
                    onSizeValueChange = onPageGridSizeChange,
                    onAspectRatioChange = onPageThumbnailAspectRatioChange,
                    onBackClick = detailsPaneState::hideGridSizeControls,
                    modifier = Modifier.fillMaxWidth(),
                )
                return@Column
            }

            else -> Unit
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (isModernDockEnabled) {
                        Modifier
                    } else {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                        )
                    },
                ),
        ) {
            BoxWithConstraints(
                modifier = Modifier.fillMaxWidth(),
            ) {
                val visibleDockTabCount = 1 +
                    (if (showPagesTab) 1 else 0) +
                    (if (showBookmarksTab) 1 else 0)
                val tabsDockPadding = if (isModernDockEnabled) 10.dp else 4.dp
                val dockGap = if (isModernDockEnabled) 8.dp else 4.dp
                val tabButtonWidth = if (isModernDockEnabled) ModernDetailsDockTabButtonWidth else 52.dp
                val tabSpacing = if (isModernDockEnabled) ModernDetailsDockTabSpacing else 0.dp
                val expandedTabsDockWidth = tabsDockPadding +
                    (tabButtonWidth * visibleDockTabCount) +
                    (tabSpacing * (visibleDockTabCount - 1))
                val isDualPaneChapterTools = !showCollapsedHandle &&
                    topBarMode == DetailsPaneTopBarMode.ExpandedChapterTools
                val minimumTabsDockWidth = tabsDockPadding + tabButtonWidth
                val desiredTabsDockWidth = if (showAllDockTabs) {
                    expandedTabsDockWidth
                } else {
                    minimumTabsDockWidth
                }
                val primaryDockMinWidth = if (isDualPaneChapterTools) {
                    DualPaneDetailsPrimaryDockMinWidth
                } else {
                    ModernDetailsDockCompactPrimaryWidth
                }
                val tabsDockTargetWidth = desiredTabsDockWidth.coerceAtMost(
                    (maxWidth - primaryDockMinWidth - dockGap).coerceAtLeast(minimumTabsDockWidth),
                )
                val tabsDockWidth by animateDpAsState(
                    targetValue = tabsDockTargetWidth,
                    animationSpec = tween(
                        durationMillis = ModernDetailsDockAnimationDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "detailsTabsDockWidth",
                )
                val expandedPrimaryDockWidth = (maxWidth - tabsDockTargetWidth - dockGap).coerceAtLeast(0.dp)
                val primaryDockWidth by animateDpAsState(
                    targetValue = when {
                        compactModernDock -> ModernDetailsDockCompactPrimaryWidth
                        isModernDockEnabled &&
                            topBarMode == DetailsPaneTopBarMode.ExpandedChapterTools &&
                            !isDualPaneChapterTools -> {
                            ModernDetailsDockToolsWidth
                        }
                        isModernDockEnabled && topBarMode == DetailsPaneTopBarMode.ExpandedGridTools -> {
                            ModernDetailsDockToolsWidth
                        }
                        else -> expandedPrimaryDockWidth
                    },
                    animationSpec = tween(
                        durationMillis = ModernDetailsDockAnimationDurationMillis,
                        easing = FastOutSlowInEasing,
                    ),
                    label = "detailsPrimaryDockWidth",
                )
                val tabsScrollState = rememberScrollState()
                val selectedDockTabIndex = when (selectedTabId) {
                    DETAILS_TAB_PAGES -> if (showPagesTab) 1 else 0
                    DETAILS_TAB_BOOKMARKS -> visibleDockTabCount - 1
                    else -> 0
                }
                val tabScrollStepPx = with(LocalDensity.current) {
                    (tabButtonWidth + tabSpacing).roundToPx()
                }
                LaunchedEffect(selectedDockTabIndex, tabsScrollState.maxValue, tabScrollStepPx) {
                    tabsScrollState.animateScrollTo(
                        (selectedDockTabIndex * tabScrollStepPx).coerceAtMost(tabsScrollState.maxValue),
                    )
                }

                DetailsDockContainer(
                    modernStyle = isModernDockEnabled,
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .then(modernDockDragModifier)
                        .width(tabsDockWidth),
                ) {
                    Row(
                        modifier = Modifier
                            .horizontalScroll(tabsScrollState)
                            .padding(
                                horizontal = if (isModernDockEnabled) 5.dp else 2.dp,
                                vertical = if (isModernDockEnabled) 5.dp else 0.dp,
                            ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AnimatedVisibility(
                            visible = true,
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_list,
                                contentDescription = stringResource(R.string.chapters),
                                isSelected = selectedTabId == DETAILS_TAB_CHAPTERS,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = if (isModernDockEnabled && showAllDockTabs && (showPagesTab || showBookmarksTab)) {
                                    2.dp
                                } else {
                                    0.dp
                                },
                                onClick = { onActionClick(DetailsAction.ToggleList) },
                            )
                        }
                        AnimatedVisibility(
                            visible = showPagesTab,
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_grid,
                                contentDescription = stringResource(R.string.pages),
                                isSelected = selectedTabId == DETAILS_TAB_PAGES,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = if (isModernDockEnabled && showAllDockTabs && showBookmarksTab) {
                                    2.dp
                                } else {
                                    0.dp
                                },
                                onClick = { onActionClick(DetailsAction.ToggleGrid) },
                            )
                        }
                        AnimatedVisibility(
                            visible = showBookmarksTab,
                            enter = dockItemEnter,
                            exit = dockItemExit,
                        ) {
                            DetailsDockActionButton(
                                iconRes = R.drawable.ic_bookmark,
                                contentDescription = stringResource(R.string.bookmarks),
                                isSelected = selectedTabId == DETAILS_TAB_BOOKMARKS,
                                modernStyle = isModernDockEnabled,
                                spacingAfter = 0.dp,
                                onClick = { onActionClick(DetailsAction.ToggleBookmarkView) },
                            )
                        }
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .then(modernDockDragModifier)
                        .width(primaryDockWidth),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        when (topBarMode) {
                            DetailsPaneTopBarMode.ExpandedChapterTools -> {
                                if (showCollapsedHandle) {
                                    ExpandedPaneUtilityDock(
                                        modifier = Modifier.weight(1f),
                                        modernStyle = isModernDockEnabled,
                                        sheetExpansionProgress = paneOpacityProgress,
                                        isSearchEnabled = isChapterSearchAvailable,
                                        isSearchActive = isChapterSearchVisible,
                                        isChaptersReversed = isChaptersReversed,
                                        isChaptersInGridView = isChaptersInGridView,
                                        isHideReadChapters = isHideReadChapters,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        isDownloadedOnly = isDownloadedOnly,
                                        isDownloadedFilterVisible = isDownloadedFilterVisible,
                                        onSearchClick = onChapterSearchToggle,
                                        onToggleChaptersReversed = onToggleChaptersReversed,
                                        onToggleChaptersGrid = onToggleChaptersGrid,
                                        onToggleHideReadChapters = onToggleHideReadChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onToggleDownloadedOnly = onToggleDownloadedOnly,
                                        onShowGridSizeControls = detailsPaneState::showGridSizeControls,
                                    )
                                } else {
                                    ReadDock(
                                        modifier = Modifier.weight(1f),
                                        modernStyle = isModernDockEnabled,
                                        compact = compactModernDock,
                                        readLabel = resolveReadActionLabel(
                                            contentType = contentType,
                                            historyInfo = historyInfo,
                                            isLoading = isLoading,
                                        ),
                                        contentType = contentType,
                                        branches = branches,
                                        historyInfo = historyInfo,
                                        isDownloadAvailable = historyInfo.canDownload,
                                        isEnabled = !isLoading && historyInfo.isValid,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onReadClick = { onActionClick(DetailsAction.Resume) },
                                        onIncognitoClick = { onActionClick(DetailsAction.ResumeIncognito) },
                                        onForgetClick = { onActionClick(DetailsAction.ForgetHistory) },
                                        onDownloadClick = { onActionClick(DetailsAction.Download) },
                                        onBranchSelected = { onActionClick(DetailsAction.SelectBranch(it)) },
                                    )
                                    Spacer(modifier = Modifier.width(DualPaneDetailsDockGap))
                                    ExpandedPaneUtilityDock(
                                        modifier = Modifier.width(ModernDetailsDockToolsWidth),
                                        modernStyle = isModernDockEnabled,
                                        sheetExpansionProgress = paneOpacityProgress,
                                        isSearchEnabled = isChapterSearchAvailable,
                                        isSearchActive = isChapterSearchVisible,
                                        isChaptersReversed = isChaptersReversed,
                                        isChaptersInGridView = isChaptersInGridView,
                                        isHideReadChapters = isHideReadChapters,
                                        isMergeRepeatedChapters = isMergeRepeatedChapters,
                                        showMergeRepeatedChapters = showMergeRepeatedChapters,
                                        isDownloadedOnly = isDownloadedOnly,
                                        isDownloadedFilterVisible = isDownloadedFilterVisible,
                                        onSearchClick = onChapterSearchToggle,
                                        onToggleChaptersReversed = onToggleChaptersReversed,
                                        onToggleChaptersGrid = onToggleChaptersGrid,
                                        onToggleHideReadChapters = onToggleHideReadChapters,
                                        onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                        onToggleDownloadedOnly = onToggleDownloadedOnly,
                                        onShowGridSizeControls = detailsPaneState::showGridSizeControls,
                                    )
                                }
                            }

                            DetailsPaneTopBarMode.ExpandedGridTools -> {
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd,
                                ) {
                                    DetailsDockContainer(
                                        modernStyle = isModernDockEnabled,
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            DetailsChromeButton(
                                                onClick = onTogglePageThumbnailsFitPreview,
                                                modifier = Modifier.size(42.dp),
                                            ) {
                                                Icon(
                                                    painter = rememberSafePainter(R.drawable.ic_aspect_ratio),
                                                    contentDescription = stringResource(R.string.fit_page_thumbnails),
                                                    tint = if (isPageThumbnailsFitPreview) {
                                                        MaterialTheme.colorScheme.primary
                                                    } else {
                                                        MaterialTheme.colorScheme.onSurface
                                                    },
                                                )
                                            }
                                            DetailsChromeButton(
                                                onClick = detailsPaneState::showGridSizeControls,
                                                modifier = Modifier.size(42.dp),
                                            ) {
                                                Icon(
                                                    painter = rememberSafePainter(R.drawable.ic_size_large),
                                                    contentDescription = stringResource(R.string.grid_size),
                                                    tint = MaterialTheme.colorScheme.onSurface,
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            else -> {
                                ReadDock(
                                    modifier = Modifier.weight(1f),
                                    modernStyle = isModernDockEnabled,
                                    compact = compactModernDock,
                                    readLabel = resolveReadActionLabel(
                                        contentType = contentType,
                                        historyInfo = historyInfo,
                                        isLoading = isLoading,
                                    ),
                                    contentType = contentType,
                                    branches = branches,
                                    historyInfo = historyInfo,
                                    isDownloadAvailable = historyInfo.canDownload,
                                    isEnabled = !isLoading && historyInfo.isValid,
                                    isMergeRepeatedChapters = isMergeRepeatedChapters,
                                    showMergeRepeatedChapters = showMergeRepeatedChapters,
                                    onToggleMergeRepeatedChapters = onToggleMergeRepeatedChapters,
                                    onReadClick = { onActionClick(DetailsAction.Resume) },
                                    onIncognitoClick = { onActionClick(DetailsAction.ResumeIncognito) },
                                    onForgetClick = { onActionClick(DetailsAction.ForgetHistory) },
                                    onDownloadClick = { onActionClick(DetailsAction.Download) },
                                    onBranchSelected = { onActionClick(DetailsAction.SelectBranch(it)) },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DetailsPaneDragHandle(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(28.dp)
            .height(4.dp)
            .background(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.42f),
                shape = RoundedCornerShape(999.dp),
            ),
    )
}

@Composable
private fun ChapterSelectionTopBar(
    state: ChapterSelectionUiState,
    modernStyle: Boolean,
    modifier: Modifier = Modifier,
) {
    var isMoreExpanded by remember { mutableStateOf(false) }
    val hasOverflowActions = state.canBookmark || state.canMarkCurrent
    Row(
        modifier = modifier.height(ModernDetailsDockChromeHeight),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DetailsDockContainer(modernStyle = modernStyle) {
                IconButton(
                    onClick = state.onClearSelection,
                    modifier = Modifier.size(ModernDetailsDockChromeHeight),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = stringResource(R.string.close),
                    )
                }
            }
            Text(
                text = state.selectedCount.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        DetailsDockContainer(
            modernStyle = modernStyle,
            modifier = Modifier.weight(1f, fill = false),
        ) {
            Row(
                modifier = Modifier
                    .height(ModernDetailsDockChromeHeight)
                    .padding(horizontal = 5.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.canDownload) {
                    ChapterSelectionActionButton(onClick = state.onDownload) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_download),
                            contentDescription = stringResource(R.string.download),
                        )
                    }
                }
                if (state.canDelete) {
                    ChapterSelectionActionButton(onClick = state.onDelete) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_delete),
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
                if (state.canSelectAll) {
                    ChapterSelectionActionButton(onClick = state.onSelectAll) {
                        Icon(
                            painter = rememberSafePainter(R.drawable.ic_select_all),
                            contentDescription = stringResource(R.string.select_all),
                        )
                    }
                }
                if (hasOverflowActions) {
                    Box {
                        ChapterSelectionActionButton(onClick = { isMoreExpanded = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = stringResource(R.string.options),
                            )
                        }
                        GlassDropdownMenu(
                            expanded = isMoreExpanded,
                            onDismissRequest = { isMoreExpanded = false },
                            offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                        ) {
                            if (state.canBookmark) {
                                CompactDropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.isBookmarkRemoveAction) {
                                                    R.string.bookmark_remove
                                                } else {
                                                    R.string.bookmark_add
                                                },
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            painter = rememberSafePainter(R.drawable.ic_bookmark),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        isMoreExpanded = false
                                        state.onBookmark()
                                    },
                                )
                            }
                            if (state.canMarkCurrent) {
                                CompactDropdownMenuItem(
                                    text = { Text(stringResource(R.string.mark_as_current)) },
                                    leadingIcon = {
                                        Icon(
                                            painter = rememberSafePainter(R.drawable.ic_current_chapter),
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        isMoreExpanded = false
                                        state.onMarkCurrent()
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterSelectionActionButton(
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(42.dp),
    ) {
        content()
    }
}

@Composable
private fun PageGridSizeControlsRow(
    sizeValue: Float,
    aspectRatio: Float,
    onSizeValueChange: (Float) -> Unit,
    onAspectRatioChange: (Float) -> Unit,
    onBackClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val heightRatio = 1f / aspectRatio.coerceIn(PageThumbnailAspectRatioMin, PageThumbnailAspectRatioMax)
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.padding(start = 4.dp, end = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.42f),
            ),
        ) {
            IconButton(onClick = onBackClick) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            LabeledGridSlider(
                label = stringResource(R.string.grid_size),
                value = sizeValue,
                onValueChange = onSizeValueChange,
                valueRange = 50f..150f,
            )
            LabeledGridSlider(
                label = stringResource(R.string.grid_aspect_ratio),
                value = heightRatio,
                onValueChange = { heightRatioValue ->
                    onAspectRatioChange(1f / heightRatioValue.coerceIn(PageThumbnailHeightRatioMin, PageThumbnailHeightRatioMax))
                },
                valueRange = PageThumbnailHeightRatioMin..PageThumbnailHeightRatioMax,
            )
        }
    }
}

@Composable
private fun LabeledGridSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(40.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.width(112.dp),
        )
        KototoroSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun ExpandedPaneUtilityDock(
    modifier: Modifier = Modifier,
    modernStyle: Boolean,
    sheetExpansionProgress: Float,
    isSearchEnabled: Boolean,
    isSearchActive: Boolean,
    isChaptersReversed: Boolean,
    isChaptersInGridView: Boolean,
    isHideReadChapters: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    isDownloadedOnly: Boolean,
    isDownloadedFilterVisible: Boolean,
    onSearchClick: () -> Unit,
    onToggleChaptersReversed: () -> Unit,
    onToggleChaptersGrid: () -> Unit,
    onToggleHideReadChapters: () -> Unit,
    onToggleMergeRepeatedChapters: () -> Unit,
    onToggleDownloadedOnly: () -> Unit,
    onShowGridSizeControls: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    DetailsDockContainer(
        modernStyle = modernStyle,
        modifier = modifier,
    ) {
        Row(
            modifier = Modifier
                .height(ModernDetailsDockChromeHeight)
                .fillMaxWidth()
                .padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onSearchClick,
                enabled = isSearchEnabled,
                modifier = Modifier
                    .width(42.dp)
                    .height(42.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_chapters),
                    tint = if (isSearchActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
            }
            Box {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .width(42.dp)
                        .height(42.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = stringResource(R.string.options),
                        tint = MaterialTheme.colorScheme.onSurface,
                    )
                }
                GlassDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                    offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                ) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.reverse)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_sort_desc)
                        },
                        trailingIcon = {
                            MenuSelectionIndicator(selected = isChaptersReversed)
                        },
                        onClick = {
                            expanded = false
                            onToggleChaptersReversed()
                        },
                    )
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.chapters_grid_view)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_grid)
                        },
                        trailingIcon = {
                            MenuSelectionIndicator(selected = isChaptersInGridView)
                        },
                        onClick = {
                            expanded = false
                            onToggleChaptersGrid()
                        },
                    )
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.hide_read_chapters)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_eye_off)
                        },
                        trailingIcon = {
                            MenuSelectionIndicator(selected = isHideReadChapters)
                        },
                        onClick = {
                            expanded = false
                            onToggleHideReadChapters()
                        },
                    )
                    if (showMergeRepeatedChapters) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.merge_branch_chapters)) },
                            leadingIcon = {
                                DetailsMenuIcon(R.drawable.ic_list_group)
                            },
                            trailingIcon = {
                                MenuSelectionIndicator(selected = isMergeRepeatedChapters)
                            },
                            onClick = {
                                expanded = false
                                onToggleMergeRepeatedChapters()
                            },
                        )
                    }
                    if (isChaptersInGridView) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.display_options)) },
                            leadingIcon = {
                                DetailsMenuIcon(R.drawable.ic_settings)
                            },
                            onClick = {
                                expanded = false
                                onShowGridSizeControls()
                            },
                        )
                    }
                    if (isDownloadedFilterVisible) {
                        CompactDropdownMenuItem(
                            text = { Text(stringResource(R.string.downloaded)) },
                            leadingIcon = {
                                DetailsMenuIcon(R.drawable.ic_download)
                            },
                            trailingIcon = {
                                MenuSelectionIndicator(selected = isDownloadedOnly)
                            },
                            onClick = {
                                expanded = false
                                onToggleDownloadedOnly()
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MenuSelectionIndicator(
    selected: Boolean,
) {
    val strokeColor = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.48f)
    }
    Box(
        modifier = Modifier
            .size(18.dp)
            .border(
                width = 1.5.dp,
                color = strokeColor,
                shape = RoundedCornerShape(5.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = strokeColor,
                modifier = Modifier.size(12.dp),
            )
        }
    }
}

@Composable
internal fun DetailsMenuIcon(
    iconRes: Int,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = rememberSafePainter(iconRes),
        contentDescription = null,
        modifier = modifier.size(20.dp),
    )
}

@Composable
internal fun DetailsDockActionButton(
    iconRes: Int,
    contentDescription: String,
    isSelected: Boolean,
    modernStyle: Boolean = false,
    spacingAfter: androidx.compose.ui.unit.Dp = if (modernStyle) 0.dp else 4.dp,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue = Color.Transparent,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "detailsDockSelectionColor",
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            modernStyle && isSelected -> MaterialTheme.colorScheme.primary
            modernStyle -> MaterialTheme.colorScheme.onSurface
            isSelected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.onSurface
        },
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "detailsDockSelectionContentColor",
    )
    Surface(
        modifier = Modifier.padding(end = spacingAfter),
        shape = RoundedCornerShape(if (modernStyle) 18.dp else 16.dp),
        color = containerColor,
        tonalElevation = 0.dp,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .width(42.dp)
                .height(42.dp),
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = contentDescription,
                tint = contentColor,
            )
        }
    }
}

sealed interface DetailsAction {
    data object OpenCover : DetailsAction
    data class OpenContent(val content: Content) : DetailsAction
    data class OpenSource(val source: ContentSource) : DetailsAction
    data class OpenTrackingDiscover(
        val service: ScrobblerService,
        val forceLoad: Boolean = false,
    ) : DetailsAction
    data class SearchAuthorOnSource(val author: String, val source: ContentSource) : DetailsAction
    data class SearchAuthorEverywhere(val author: String) : DetailsAction
    data class SearchTagOnSource(val tag: ContentTag) : DetailsAction
    data class SearchTagEverywhere(val tagTitle: String) : DetailsAction
    data class OpenWebUrl(val url: String) : DetailsAction
    data class SelectBranch(val branch: String?) : DetailsAction
    data object ManageCategories : DetailsAction
    data object ManageDownloads : DetailsAction
    data object Favorite : DetailsAction
    data object Share : DetailsAction
    data class ShareLink(val title: String, val link: String) : DetailsAction
    data object Download : DetailsAction
    data object DeleteLocal : DetailsAction
    data object EditOverride : DetailsAction
    data object CreateShortcut : DetailsAction
    data object Translate : DetailsAction
    data object ToggleTranslation : DetailsAction
    data object FindSimilar : DetailsAction
    data object OpenAlternatives : DetailsAction
    data object OpenOnlineVariant : DetailsAction
    data class OpenBrowserPage(
        val url: String,
        val source: ContentSource?,
        val title: String?,
    ) : DetailsAction
    data object OpenMetadataInBrowser : DetailsAction
    data object OpenLocalSourceInBrowser : DetailsAction
    data object OpenReadingRecord : DetailsAction
    data object ToggleSafe : DetailsAction
    data object ToggleList : DetailsAction
    data object ToggleGrid : DetailsAction
    data object ToggleBookmarkView : DetailsAction
    data object Resume : DetailsAction
    data object ResumeIncognito : DetailsAction
    data object ForgetHistory : DetailsAction
    data class OpenTrackingDetails(
        val service: org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService,
        val remoteId: Long,
        val url: String?,
    ) : DetailsAction
    data class ManageTrackingBinding(
        val service: org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService,
        val remoteId: Long,
        val title: String,
        val url: String?,
    ) : DetailsAction
    data class BindTrackingMatch(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
    data class IgnoreTrackingSuggestion(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
    data class RemoveTrackingMatch(
        val match: org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteMatchResult,
    ) : DetailsAction
}

internal fun DetailsAction.isWorkOnlyAction(): Boolean = when (this) {
    is DetailsAction.SelectBranch,
    DetailsAction.ManageCategories,
    DetailsAction.ManageDownloads,
    DetailsAction.Favorite,
    DetailsAction.Download,
    DetailsAction.DeleteLocal,
    DetailsAction.EditOverride,
    DetailsAction.CreateShortcut,
    DetailsAction.FindSimilar,
    DetailsAction.OpenAlternatives,
    DetailsAction.OpenOnlineVariant,
    DetailsAction.OpenLocalSourceInBrowser,
    DetailsAction.OpenReadingRecord,
    DetailsAction.ToggleList,
    DetailsAction.ToggleGrid,
    DetailsAction.ToggleBookmarkView,
    DetailsAction.Resume,
    DetailsAction.ResumeIncognito,
    DetailsAction.ForgetHistory,
    is DetailsAction.ManageTrackingBinding,
    is DetailsAction.BindTrackingMatch,
    is DetailsAction.IgnoreTrackingSuggestion,
    is DetailsAction.RemoveTrackingMatch -> true
    else -> false
}

internal data class BrowserTarget(
    val url: String,
    val source: ContentSource?,
    val title: String?,
)

@Composable
private fun ReadDock(
    modifier: Modifier = Modifier,
    modernStyle: Boolean = false,
    compact: Boolean = false,
    readLabel: String,
    contentType: ContentType?,
    branches: List<ContentBranch>,
    historyInfo: HistoryInfo,
    isDownloadAvailable: Boolean,
    isEnabled: Boolean,
    isMergeRepeatedChapters: Boolean,
    showMergeRepeatedChapters: Boolean,
    onToggleMergeRepeatedChapters: () -> Unit,
    onReadClick: () -> Unit,
    onIncognitoClick: () -> Unit,
    onForgetClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onBranchSelected: (String?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var menuAnchorBounds by remember { mutableStateOf<Rect?>(null) }
    val hasBranchOptions = branches.size > 1
    val canOpenIncognito = !historyInfo.isIncognitoMode
    val canForgetHistory = historyInfo.history != null
    val hasQuickActions = canOpenIncognito || canForgetHistory || isDownloadAvailable
    val hasMenuActions = hasQuickActions || hasBranchOptions

    val shapeRadiusPercent by androidx.compose.animation.core.animateIntAsState(targetValue = if (expanded) 50 else 0)
    val optionGap by androidx.compose.animation.core.animateDpAsState(
        targetValue = when {
            expanded -> 8.dp
            modernStyle -> 0.dp
            else -> 2.dp
        },
    )
    val dividerAlpha by animateFloatAsState(
        targetValue = if (modernStyle && !expanded) 0.22f else 0f,
        animationSpec = KototoroMotion.fadeFast(),
        label = "readDockDividerAlpha",
    )
    val actionIconRes = when (contentType) {
        ContentType.VIDEO, ContentType.HENTAI_VIDEO -> R.drawable.ic_play
        else -> R.drawable.ic_read
    }
    val readButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topStartPercent = 50,
        bottomStartPercent = 50,
        topEndPercent = shapeRadiusPercent,
        bottomEndPercent = shapeRadiusPercent,
    )
    val trailingButtonShape = androidx.compose.foundation.shape.RoundedCornerShape(
        topEndPercent = 50,
        bottomEndPercent = 50,
        topStartPercent = shapeRadiusPercent,
        bottomStartPercent = shapeRadiusPercent,
    )

    Row(
        modifier = modifier
            .height(if (modernStyle) 52.dp else 50.dp)
            .padding(
                all = if (modernStyle) 0.dp else 4.dp,
            ),
        horizontalArrangement = Arrangement.spacedBy(optionGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight(),
            shape = readButtonShape,
            color = if (modernStyle) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
            } else {
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
            },
            contentColor = if (modernStyle) {
                MaterialTheme.colorScheme.onPrimary
            } else {
                MaterialTheme.colorScheme.onPrimaryContainer
            },
            tonalElevation = 0.dp,
            shadowElevation = if (modernStyle) 4.dp else 0.dp,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(readButtonShape)
                    .clickable(enabled = isEnabled, onClick = onReadClick)
                    .padding(horizontal = if (modernStyle) 6.dp else 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (modernStyle) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            painter = rememberSafePainter(actionIconRes),
                            contentDescription = if (compact) readLabel else null,
                            modifier = Modifier.size(22.dp),
                        )
                        AnimatedVisibility(
                            visible = !compact,
                            enter = fadeIn(KototoroMotion.fadeDefault()) + expandHorizontally(
                                animationSpec = tween(
                                    ModernDetailsDockAnimationDurationMillis,
                                    easing = FastOutSlowInEasing,
                                ),
                                expandFrom = Alignment.Start,
                            ),
                            exit = fadeOut(KototoroMotion.fadeFast()) + shrinkHorizontally(
                                animationSpec = KototoroMotion.tweenEaseOut(320),
                                shrinkTowards = Alignment.Start,
                            ),
                        ) {
                            Text(
                                text = readLabel,
                                modifier = Modifier.padding(start = 8.dp),
                                style = MaterialTheme.typography.labelLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                } else {
                    Text(
                        text = readLabel,
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        Box(
            modifier = Modifier
                .width(ModernDetailsDockMoreButtonWidth)
                .fillMaxHeight()
                .onGloballyPositioned { menuAnchorBounds = it.boundsInRoot() },
        ) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                shape = trailingButtonShape,
                color = if (modernStyle) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.96f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.96f)
                },
                contentColor = if (modernStyle) {
                    MaterialTheme.colorScheme.onPrimary
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                },
                tonalElevation = 0.dp,
                shadowElevation = if (modernStyle) 4.dp else 0.dp,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(trailingButtonShape)
                        .clickable(enabled = hasMenuActions, onClick = { expanded = true }),
                    contentAlignment = Alignment.Center,
                ) {
                    if (modernStyle) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .width(1.dp)
                                .height(24.dp)
                                .background(MaterialTheme.colorScheme.onPrimary.copy(alpha = dividerAlpha)),
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (hasBranchOptions) {
                            stringResource(R.string.system_default)
                        } else {
                            stringResource(R.string.options)
                        },
                    )
                }
            }
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
                useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
                anchorBounds = menuAnchorBounds,
                openAboveAnchor = true,
            ) {
                if (canOpenIncognito) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.incognito_mode)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_incognito)
                        },
                        onClick = {
                            expanded = false
                            onIncognitoClick()
                        },
                    )
                }
                if (canForgetHistory) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.remove_from_history)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_delete)
                        },
                        onClick = {
                            expanded = false
                            onForgetClick()
                        },
                    )
                }
                if (isDownloadAvailable) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.download)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_download)
                        },
                        onClick = {
                            expanded = false
                            onDownloadClick()
                        },
                    )
                }
                if (hasQuickActions && (showMergeRepeatedChapters || hasBranchOptions)) {
                    HorizontalDivider()
                }
                if (showMergeRepeatedChapters) {
                    CompactDropdownMenuItem(
                        text = { Text(stringResource(R.string.merge_branch_chapters)) },
                        leadingIcon = {
                            DetailsMenuIcon(R.drawable.ic_list_group)
                        },
                        trailingIcon = {
                            MenuSelectionIndicator(selected = isMergeRepeatedChapters)
                        },
                        onClick = {
                            expanded = false
                            onToggleMergeRepeatedChapters()
                        },
                    )
                    if (!isMergeRepeatedChapters && hasBranchOptions) {
                        HorizontalDivider()
                    }
                }
                if (!isMergeRepeatedChapters) {
                    branches.forEach { branch ->
                        CompactDropdownMenuItem(
                            text = {
                                Text(
                                    text = buildString {
                                        append(branch.name ?: stringResource(R.string.system_default))
                                        append(" / ")
                                        append(branch.count)
                                    },
                                )
                            },
                            leadingIcon = {
                                DetailsMenuIcon(R.drawable.ic_source_empty)
                            },
                            trailingIcon = {
                                if (branch.isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                    )
                                }
                            },
                            onClick = {
                                expanded = false
                                onBranchSelected(branch.name)
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun lerpFloat(start: Float, stop: Float, fraction: Float): Float {
    return start + (stop - start) * fraction.coerceIn(0f, 1f)
}

internal fun modernDockDragHandleRevealProgress(
    isModernDockEnabled: Boolean,
    paneOpacityProgress: Float,
): Float {
    return if (isModernDockEnabled) {
        ((1f - paneOpacityProgress) / 0.32f).coerceIn(0f, 1f)
    } else {
        1f
    }
}

internal fun modernDockActionsTopPadding(
    handleTopInset: androidx.compose.ui.unit.Dp,
    handleRevealProgress: Float,
): androidx.compose.ui.unit.Dp {
    val handleSpace = modernDockDragHandleHeight(handleRevealProgress) +
        modernDockDragHandleGap(handleRevealProgress)
    val reservedTopSpace = maxOf(
        handleTopInset,
        modernDockDragHandleHeight(1f) + modernDockDragHandleGap(1f),
    )
    return reservedTopSpace - handleSpace
}

internal fun modernDockDragHandleHeight(revealProgress: Float): androidx.compose.ui.unit.Dp {
    return 18.dp * revealProgress.coerceIn(0f, 1f)
}

internal fun modernDockDragHandleGap(revealProgress: Float): androidx.compose.ui.unit.Dp {
    return 4.dp * revealProgress.coerceIn(0f, 1f)
}

@Composable
internal fun rememberDetailsSheetGlassPrefs() =
    rememberGlassPrefsOrFallback()

@androidx.compose.ui.tooling.preview.Preview(showBackground = true)
@Composable
private fun DetailsMenuIconPreview() {
    org.skepsun.kototoro.core.ui.theme.KototoroTheme {
        DetailsMenuIcon(iconRes = R.drawable.ic_info_outline)
    }
}

