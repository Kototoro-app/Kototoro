package org.skepsun.kototoro.reader.ui.compose

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.BatteryManager
import android.view.RoundedCorner
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.core.content.ContextCompat
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.delay
import org.skepsun.kototoro.R
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_BOOKMARKS
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_CHAPTERS
import org.skepsun.kototoro.details.ui.compose.DETAILS_TAB_PAGES
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionBar
import org.skepsun.kototoro.details.ui.pager.chapters.compose.ChapterSelectionUiState
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.prefs.ReaderControl
import org.skepsun.kototoro.core.prefs.ReaderInfoBarLayout
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.KototoroLoadingIndicator
import org.skepsun.kototoro.core.ui.compose.KototoroMotion
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.toTransparentImmersiveColor
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.ReaderActionsCallbacks
import org.skepsun.kototoro.reader.ui.ReaderActionsUiState
import org.skepsun.kototoro.reader.ui.autoScrollSpeedMultiplier
import org.skepsun.kototoro.reader.domain.TapGridArea
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlDestination
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressBar
import org.skepsun.kototoro.reader.ui.compose.design.ReaderProgressDock
import org.skepsun.kototoro.reader.ui.compose.design.readerControlContentColor
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import kotlin.math.roundToInt
import kotlin.math.ceil
import kotlin.math.sqrt

private val ReaderTopImmersiveFeatherExtension = 72.dp
private val ReaderBottomImmersiveFeatherExtension = 48.dp
private val ReaderInfoBarHorizontalPadding = 20.dp
private val ReaderInfoBarVerticalPadding = 6.dp
private val ReaderInfoBarObstructionSpacing = 4.dp
private val ReaderInfoBarItemSpacing = 10.dp
/**
 * 底部右侧悬浮控件列的基准：离导航条的距离与单个药丸的高度。
 * 放在底部居中的东西（章节标题药丸）要用同一组值才能和它们对齐（issue #509）。
 */
private val ReaderFloatingControlInset = 62.dp
private val ReaderFloatingControlHeight = 44.dp
private val ReaderTopImmersiveStops = listOf(0f, 0.24f, 0.50f, 0.70f, 0.86f, 1f)
private val ReaderBottomImmersiveStops = listOf(0f, 0.18f, 0.38f, 0.70f, 1f)

@Immutable
internal data class ComposeReaderChromeState(
    val controlsVisible: Boolean = true,
    val eInkModeEnabled: Boolean = false,
    val eInkRefresh: ReaderEInkRefresh? = null,
    val loadingVisible: Boolean = false,
    val title: String = "",
    val subtitle: String = "",
    val zoomVisible: Boolean = false,
    val infoBar: ReaderInfoBarState = ReaderInfoBarState(),
    val message: ReaderMessage? = null,
    val autoScroll: ReaderAutoScrollUiState = ReaderAutoScrollUiState(),
    val actions: ReaderActionsUiState = ReaderActionsUiState(),
    val options: ComposeReaderOptionsState = ComposeReaderOptionsState(),
    val toolsVisible: Boolean = false,
    val chaptersVisible: Boolean = false,
    val chapterPanel: ReaderChapterPanelUiState = ReaderChapterPanelUiState(),
)

@Immutable
internal data class ReaderEInkRefresh(
    val id: Long,
    val durationMillis: Int,
    val colorArgb: Int,
)

@Immutable
internal data class ReaderChapterPanelUiState(
    val searchEnabled: Boolean = true,
    val searchVisible: Boolean = false,
    val searchQuery: String = "",
    val chaptersReversed: Boolean = false,
    val chaptersInGridView: Boolean = false,
    val hideReadChapters: Boolean = false,
    val mergeRepeatedChapters: Boolean = false,
    val showMergeRepeatedChapters: Boolean = false,
    val downloadedOnly: Boolean = false,
    val downloadedFilterVisible: Boolean = false,
)

@Immutable
internal data class ReaderInfoBarState(
    val visible: Boolean = false,
    val text: String = "",
    val progressText: String = "",
    val showSystemStatus: Boolean = true,
    val drawBackground: Boolean = false,
    val darkContent: Boolean = false,
    val layout: ReaderInfoBarLayout = ReaderInfoBarLayout.CENTERED,
    val avoidDisplayCutout: Boolean = false,
)

@Immutable
internal data class ReaderMessage(
    val id: Long,
    val text: String,
    val durationMillis: Long?,
    val actionLabel: String? = null,
)

@Immutable
internal data class ReaderAutoScrollUiState(
    val visible: Boolean = false,
    val active: Boolean = false,
    val manuallyPaused: Boolean = false,
    val speed: Float = AppSettings.DEFAULT_READER_AUTOSCROLL_SPEED,
    val fabVisible: Boolean = false,
    val pauseOnUi: Boolean = true,
    val showPageDelay: Boolean = false,
    val pageDelaySeconds: Long = 0L,
)

internal data class ReaderAutoScrollCallbacks(
    val onOpen: () -> Unit = {},
    val onClose: () -> Unit = {},
    val onActiveChanged: (Boolean) -> Unit = {},
    val onPausedChanged: (Boolean) -> Unit = {},
    val onSpeedChanged: (Float) -> Unit = {},
    val onFabChanged: (Boolean) -> Unit = {},
    val onPauseOnUiChanged: (Boolean) -> Unit = {},
)

internal data class ComposeReaderChromeCallbacks(
    val onNavigateBack: () -> Unit = {},
    val onZoomIn: () -> Unit = {},
    val onZoomOut: () -> Unit = {},
    val onMessageExpired: (Long) -> Unit = {},
    val onEInkRefreshConsumed: (Long) -> Unit = {},
    val onMessageAction: () -> Unit = {},
    val autoScroll: ReaderAutoScrollCallbacks = ReaderAutoScrollCallbacks(),
    val actions: ReaderActionsCallbacks = ReaderActionsCallbacks(),
    val onReaderInteraction: () -> Unit = {},
    val onGridTap: (TapGridArea) -> Unit = {},
    val onGridLongTap: (TapGridArea, Offset, IntSize) -> Unit = { _, _, _ -> },
    val onBackPressed: () -> Unit = {},
    val options: ComposeReaderOptionsCallbacks = ComposeReaderOptionsCallbacks(),
    val chapterPanel: ReaderChapterPanelCallbacks = ReaderChapterPanelCallbacks(),
    val onPrimaryDestination: (ReaderControlDestination) -> Unit = {},
    val onPrimaryDestinationLongPress: (ReaderControlDestination) -> Unit = {},
)

internal data class ReaderChapterPanelCallbacks(
    val onTabSelected: (Int) -> Unit = {},
    val onSearchToggle: () -> Unit = {},
    val onSearchQueryChange: (String) -> Unit = {},
    val onToggleChaptersReversed: () -> Unit = {},
    val onToggleChaptersGrid: () -> Unit = {},
    val onToggleHideReadChapters: () -> Unit = {},
    val onToggleMergeRepeatedChapters: () -> Unit = {},
    val onToggleDownloadedOnly: () -> Unit = {},
)

@Composable
private fun ReaderChapterPanelToolbar(
    selectedTabId: Int,
    isFullyExpanded: Boolean,
    state: ReaderChapterPanelUiState,
    callbacks: ReaderChapterPanelCallbacks,
) {
    var moreMenuExpanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(52.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ReaderChapterPanelTab(
            selected = selectedTabId == DETAILS_TAB_CHAPTERS,
            iconResId = R.drawable.ic_list,
            contentDescription = stringResource(R.string.chapters),
            onClick = { callbacks.onTabSelected(DETAILS_TAB_CHAPTERS) },
        )
        ReaderChapterPanelTab(
            selected = selectedTabId == DETAILS_TAB_PAGES,
            iconResId = R.drawable.ic_grid,
            contentDescription = stringResource(R.string.pages),
            onClick = { callbacks.onTabSelected(DETAILS_TAB_PAGES) },
        )
        ReaderChapterPanelTab(
            selected = selectedTabId == DETAILS_TAB_BOOKMARKS,
            iconResId = R.drawable.ic_bookmark,
            contentDescription = stringResource(R.string.bookmarks),
            onClick = { callbacks.onTabSelected(DETAILS_TAB_BOOKMARKS) },
        )

        Box(modifier = Modifier.weight(1f))

        AnimatedVisibility(visible = isFullyExpanded && selectedTabId == DETAILS_TAB_CHAPTERS) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = callbacks.onSearchToggle,
                    enabled = state.searchEnabled,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = stringResource(R.string.search_chapters),
                        tint = if (state.searchVisible) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Box {
                    IconButton(
                        onClick = { moreMenuExpanded = true },
                        modifier = Modifier.size(44.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.MoreVert,
                            contentDescription = stringResource(R.string.options),
                        )
                    }
                    ReaderChapterPanelMoreMenu(
                        expanded = moreMenuExpanded,
                        state = state,
                        callbacks = callbacks,
                        onDismissRequest = { moreMenuExpanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun ReaderChapterPanelTab(
    selected: Boolean,
    iconResId: Int,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedRectangle(18.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.surfaceContainerHigh
        } else {
            Color.Transparent
        },
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier.size(44.dp),
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = contentDescription,
                tint = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}

@Composable
private fun ReaderChapterPanelMoreMenu(
    expanded: Boolean,
    state: ReaderChapterPanelUiState,
    callbacks: ReaderChapterPanelCallbacks,
    onDismissRequest: () -> Unit,
) {
    GlassDropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        offset = androidx.compose.ui.unit.DpOffset(x = 0.dp, y = 4.dp),
    ) {
        ReaderChapterPanelMenuItem(
            text = stringResource(R.string.search_chapters),
            selected = state.searchVisible,
            enabled = state.searchEnabled,
            onClick = {
                onDismissRequest()
                callbacks.onSearchToggle()
            },
        )
        ReaderChapterPanelMenuItem(
            text = stringResource(R.string.reverse),
            selected = state.chaptersReversed,
            onClick = {
                onDismissRequest()
                callbacks.onToggleChaptersReversed()
            },
        )
        ReaderChapterPanelMenuItem(
            text = stringResource(R.string.chapters_grid_view),
            selected = state.chaptersInGridView,
            onClick = {
                onDismissRequest()
                callbacks.onToggleChaptersGrid()
            },
        )
        ReaderChapterPanelMenuItem(
            text = stringResource(R.string.hide_read_chapters),
            selected = state.hideReadChapters,
            onClick = {
                onDismissRequest()
                callbacks.onToggleHideReadChapters()
            },
        )
        if (state.showMergeRepeatedChapters) {
            ReaderChapterPanelMenuItem(
                text = stringResource(R.string.merge_branch_chapters),
                selected = state.mergeRepeatedChapters,
                onClick = {
                    onDismissRequest()
                    callbacks.onToggleMergeRepeatedChapters()
                },
            )
        }
        if (state.downloadedFilterVisible) {
            ReaderChapterPanelMenuItem(
                text = stringResource(R.string.downloaded),
                selected = state.downloadedOnly,
                onClick = {
                    onDismissRequest()
                    callbacks.onToggleDownloadedOnly()
                },
            )
        }
    }
}

@Composable
private fun ReaderChapterPanelMenuItem(
    text: String,
    selected: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    CompactDropdownMenuItem(
        text = { Text(text) },
        leadingIcon = {
            if (selected) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
        enabled = enabled,
        onClick = onClick,
    )
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun ComposeReaderActivityScaffold(
    state: ComposeReaderChromeState,
    callbacks: ComposeReaderChromeCallbacks,
    showFloatingControlLabels: Boolean,
    infoBarEmbedded: Boolean = false,
    modifier: Modifier = Modifier,
    chapterPanelTabId: Int = DETAILS_TAB_CHAPTERS,
    chaptersPanelContent: @Composable (Int, ReaderChapterPanelUiState, (ChapterSelectionUiState?) -> Unit) -> Unit =
        { _, _, _ -> },
    translationTaskPanelContent: @Composable () -> Unit = {},
    content: @Composable () -> Unit,
) {
    var chapterSelectionState by remember { mutableStateOf<ChapterSelectionUiState?>(null) }
    BackHandler { callbacks.onBackPressed() }
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val immersiveBaseColor = if (isSystemInDarkTheme()) Color.Black else Color.White
    val immersiveTransparent = immersiveBaseColor.toTransparentImmersiveColor()
    val topImmersiveHeight = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 76.dp
    val bottomImmersiveHeight = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 84.dp
    val readerBackdrop = if (isIosStyle && !state.eInkModeEnabled) {
        rememberLayerBackdrop { drawContent() }
    } else {
        null
    }
    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides readerBackdrop,
        LocalLiquidGlassLayerBackdrop provides readerBackdrop,
    ) {
        Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(readerBackdrop?.let { Modifier.layerBackdrop(it) } ?: Modifier),
        ) {
            content()
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            enter = fadeIn().whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = fadeOut().whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                ImmersiveEdgeGradient(
                    height = topImmersiveHeight + ReaderTopImmersiveFeatherExtension,
                    colors = listOf(
                        immersiveBaseColor.copy(alpha = 0.86f),
                        immersiveBaseColor.copy(alpha = 0.62f),
                        immersiveBaseColor.copy(alpha = 0.32f),
                        immersiveBaseColor.copy(alpha = 0.12f),
                        immersiveBaseColor.copy(alpha = 0.035f),
                        immersiveTransparent,
                    ),
                    stops = ReaderTopImmersiveStops,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth(),
                )
                ImmersiveEdgeGradient(
                    height = bottomImmersiveHeight + ReaderBottomImmersiveFeatherExtension,
                    colors = listOf(
                        immersiveTransparent,
                        immersiveBaseColor.copy(alpha = 0.035f),
                        immersiveBaseColor.copy(alpha = 0.16f),
                        immersiveBaseColor.copy(alpha = 0.42f),
                        immersiveBaseColor.copy(alpha = 0.78f),
                    ),
                    stops = ReaderBottomImmersiveStops,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth(),
                )
            }
        }

        AnimatedVisibility(
            visible = state.controlsVisible,
            // Alpha animations create an offscreen layer that clips Backdrop shadows to these bounds.
            enter = slideInVertically { -it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = slideOutVertically { -it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderComposeTopBar(
                state = state,
                onNavigateBack = callbacks.onNavigateBack,
                onChapters = callbacks.actions.onPages,
                onOptions = callbacks.actions.onOptions,
            )
        }

        AnimatedVisibility(
            visible = shouldShowReaderInfoBar(state.infoBar.visible, state.controlsVisible) &&
                !infoBarEmbedded,
            enter = fadeIn(
                animationSpec = KototoroMotion.InfoBarEnter,
            ).whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = fadeOut(animationSpec = KototoroMotion.fadeFast())
                .whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            ReaderComposeInfoBar(state.infoBar)
        }

        AnimatedVisibility(
            visible = state.controlsVisible && state.actions.sliderEnabled,
            // Alpha transitions clip the rounded Backdrop shadow to a rectangular layer.
            enter = slideInVertically { it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = slideOutVertically { it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 4.dp),
        ) {
            ReaderProgressDock(isIosStyle = isIosStyle) {
                ReaderProgressControl(
                    state = state.actions,
                    callbacks = callbacks.actions,
                    isIosStyle = isIosStyle,
                )
            }
        }

        // 底部标题药丸跟右侧悬浮按钮共用同一组基准（同高、同底距），
        // 否则它会和最近的那颗悬浮按钮错位（issue #509 的反馈）。
        AnimatedVisibility(
            visible = state.controlsVisible && state.options.chapterTitleAtBottom,
            enter = slideInVertically { it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = slideOutVertically { it }.whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(horizontal = 12.dp)
                .padding(bottom = ReaderFloatingControlInset),
        ) {
            ReaderChapterTitleChip(
                state = state,
                onChapters = callbacks.actions.onPages,
                height = ReaderFloatingControlHeight,
            )
        }

        val floatingControls = resolveReaderFloatingControls(
            configured = state.actions.controls,
            translationAvailable = state.actions.translateRequestedVisible,
            translationContextualVisible = state.actions.translateContextualVisible,
        )
        val floatingControlExitOffset = with(LocalDensity.current) { 32.dp.roundToPx() }
        AnimatedVisibility(
            visible = state.controlsVisible && !state.chaptersVisible && floatingControls.isNotEmpty(),
            // Keep Backdrop shadows out of the alpha layer used by fade transitions.
            enter = slideInHorizontally { it + floatingControlExitOffset }
                .whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            exit = slideOutHorizontally { it + floatingControlExitOffset }
                .whenReaderAnimationsEnabled(!state.eInkModeEnabled),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .navigationBarsPadding()
                .padding(end = 16.dp, bottom = ReaderFloatingControlInset),
        ) {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = if (showFloatingControlLabels) {
                    Modifier.width(IntrinsicSize.Max).widthIn(max = 200.dp)
                } else {
                    Modifier
                },
            ) {
                floatingControls.forEach { control ->
                    ReaderFloatingControlButton(
                        control = control,
                        state = state.actions,
                        callbacks = callbacks.actions,
                        showLabel = showFloatingControlLabels,
                    )
                }
            }
        }

            if (state.chaptersVisible) {
                ReaderAnchoredBottomSheet(
                    onDismissRequest = callbacks.onBackPressed,
                ) { sheetDragModifier ->
                    Column(
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        val selectionState = chapterSelectionState
                        Box(modifier = sheetDragModifier.fillMaxWidth()) {
                            if (chapterPanelTabId == DETAILS_TAB_CHAPTERS && selectionState != null) {
                                ChapterSelectionBar(
                                    state = selectionState,
                                    modifier = Modifier.height(52.dp),
                                )
                            } else {
                                ReaderChapterPanelToolbar(
                                    selectedTabId = chapterPanelTabId,
                                    isFullyExpanded = true,
                                    state = state.chapterPanel,
                                    callbacks = callbacks.chapterPanel,
                                )
                            }
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            chaptersPanelContent(
                                chapterPanelTabId,
                                state.chapterPanel,
                                { chapterSelectionState = it },
                            )
                        }
                    }
                }
            }

            if (state.options.visible) {
                ReaderAnchoredBottomSheet(
                    onDismissRequest = callbacks.options.onDismiss,
                ) { sheetDragModifier ->
                    ComposeReaderOptionsSheet(
                        state = state.options,
                        callbacks = callbacks.options,
                        embedded = true,
                        translationTaskPanelContent = translationTaskPanelContent,
                        headerModifier = sheetDragModifier,
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxSize(),
                    )
                }
        }

        if (state.toolsVisible) {
            ModalBottomSheet(
                onDismissRequest = {
                    callbacks.onPrimaryDestination(ReaderControlDestination.TOOLS)
                },
            ) {
                ComposeReaderToolsSheet(
                    visible = true,
                    translateActive = state.actions.translateActive,
                    callbacks = callbacks.options,
                    onDismiss = {
                        callbacks.onPrimaryDestination(ReaderControlDestination.TOOLS)
                    },
                    embedded = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (state.autoScroll.visible) {
            ModalBottomSheet(onDismissRequest = callbacks.autoScroll.onClose) {
                ReaderAutoScrollPanel(state.autoScroll, callbacks.autoScroll)
            }
        }

        if (state.zoomVisible) {
            Column(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(12.dp),
            ) {
                IconButton(onClick = callbacks.onZoomIn, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_zoom_in), stringResource(R.string.zoom_in))
                }
                IconButton(onClick = callbacks.onZoomOut, modifier = Modifier.size(48.dp)) {
                    Icon(painterResource(R.drawable.ic_zoom_out), stringResource(R.string.zoom_out))
                }
            }
        }

        if (state.loadingVisible) {
            Surface(
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surfaceContainer,
                modifier = Modifier.align(Alignment.Center),
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(20.dp),
                ) {
                    KototoroLoadingIndicator()
                    Text(
                        text = stringResource(R.string.loading_),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
        }

        ReaderMessageHost(
            message = state.message,
            animationsEnabled = !state.eInkModeEnabled,
            onExpired = callbacks.onMessageExpired,
            onAction = callbacks.onMessageAction,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = if (state.controlsVisible) 104.dp else 20.dp),
        )

        if (state.autoScroll.active && state.autoScroll.fabVisible && !state.controlsVisible && !state.autoScroll.visible) {
            // Auto-scroll affordance: a floating glass pill (like the reader's
            // other pill chrome) — uniform hairline + press gloss, no resting
            // highlight.
            GlassSurface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = 16.dp)
                    .size(48.dp),
                shape = CircleShape,
                style = GlassDefaults.topBarChromeStyle(),
                componentRole = GlassComponentRole.PillControl,
            ) {
                IconButton(
                    onClick = callbacks.autoScroll.onOpen,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_timer_run),
                        contentDescription = stringResource(R.string.automatic_scroll),
                    )
                }
            }
        }

        EInkRefreshOverlay(state.eInkRefresh, callbacks.onEInkRefreshConsumed)
        }
    }
}

@Composable
internal fun EInkRefreshOverlay(
    command: ReaderEInkRefresh?,
    onConsumed: (Long) -> Unit = {},
) {
    var activeCommand by remember { mutableStateOf<ReaderEInkRefresh?>(null) }
    LaunchedEffect(command?.id) {
        activeCommand = command
        if (command != null) {
            delay(command.durationMillis.toLong())
            if (activeCommand?.id == command.id) {
                activeCommand = null
                onConsumed(command.id)
            }
        }
    }
    activeCommand?.let { active ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(active.colorArgb)),
        )
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderProgressControl(
    state: ReaderActionsUiState,
    callbacks: ReaderActionsCallbacks,
    isIosStyle: Boolean,
) {
    ReaderProgressBar(
        value = state.sliderValue,
        max = state.sliderMax.toFloat(),
        onValueChange = callbacks.onSliderValueChanged,
        onValueChangeFinished = callbacks.onSliderValueChangeFinished,
        onPreviousChapter = callbacks.onPreviousChapter,
        onNextChapter = callbacks.onNextChapter,
        previousEnabled = state.previousEnabled,
        nextEnabled = state.nextEnabled,
        isIosStyle = isIosStyle,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun ReaderAutoScrollPanel(state: ReaderAutoScrollUiState, callbacks: ReaderAutoScrollCallbacks) {
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = callbacks.onClose) { Text("×", style = MaterialTheme.typography.titleLarge) }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll), modifier = Modifier.weight(1f))
            Switch(checked = state.active, onCheckedChange = callbacks.onActiveChanged)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.manuallyPaused) stringResource(R.string.play) else stringResource(R.string.pause), modifier = Modifier.weight(1f))
            Switch(checked = !state.manuallyPaused, onCheckedChange = { callbacks.onPausedChanged(!it) })
        }
        Text(text = stringResource(R.string.speed_value, autoScrollSpeedMultiplier(state.speed)))
        Slider(value = state.speed, onValueChange = callbacks.onSpeedChanged, valueRange = 0f..1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll_fab), modifier = Modifier.weight(1f))
            Switch(checked = state.fabVisible, onCheckedChange = callbacks.onFabChanged)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.reader_autoscroll_pause_on_ui), modifier = Modifier.weight(1f))
            Switch(checked = state.pauseOnUi, onCheckedChange = callbacks.onPauseOnUiChanged)
        }
        if (state.showPageDelay) {
            Text(stringResource(R.string.page_switch_timer, state.pageDelaySeconds), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun ReaderComposeInfoBar(
    state: ReaderInfoBarState,
    systemStatus: ReaderSystemStatus = rememberReaderSystemStatus(),
) {
    val contentColor = if (state.darkContent) Color.Black.copy(alpha = 0.78f) else Color.White.copy(alpha = 0.78f)
    val scrimColor = if (state.darkContent) Color.White.copy(alpha = 0.78f) else Color.Black.copy(alpha = 0.78f)
    val textStyle = TextStyle(
        color = contentColor,
        fontSize = 12.sp,
        fontFeatureSettings = "tnum",
    )
    Surface(
        color = if (state.drawBackground) scrimColor else Color.Transparent,
        modifier = Modifier.fillMaxWidth(),
    ) {
        ReaderCutoutAwareInfoBarLayout(
            layout = state.layout,
            avoidDisplayCutout = state.avoidDisplayCutout,
            readingStatus = {
                ReaderInfoBarText(
                    text = state.text,
                    style = textStyle,
                    outlineColor = scrimColor,
                    drawOutline = !state.drawBackground,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
            progressStatus = if (state.progressText.isNotEmpty()) {
                {
                    ReaderInfoBarText(
                        text = state.progressText,
                        style = textStyle,
                        outlineColor = scrimColor,
                        drawOutline = !state.drawBackground,
                        maxLines = 1,
                    )
                }
            } else {
                null
            },
            systemStatus = if (state.showSystemStatus) {
                {
                    ReaderSystemStatusContent(
                        status = systemStatus,
                        contentColor = contentColor,
                        outlineColor = scrimColor,
                        drawOutline = !state.drawBackground,
                        textStyle = textStyle,
                    )
                }
            } else {
                null
            },
        )
    }
}

@Preview(showBackground = true, widthDp = 390, heightDp = 80)
@Composable
private fun ReaderComposeInfoBarPreview() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White),
    ) {
        Box(modifier = Modifier.padding(top = 32.dp)) {
            ReaderComposeInfoBar(
                state = ReaderInfoBarState(
                    visible = true,
                    text = "第 17/38 章 第 18/120 页",
                    progressText = "14%",
                    showSystemStatus = true,
                    darkContent = false,
                    layout = ReaderInfoBarLayout.CENTERED,
                    avoidDisplayCutout = true,
                ),
                systemStatus = ReaderSystemStatus(
                    time = "14:30",
                    battery = "72%",
                ),
            )
        }
    }
}

@Composable
private fun ReaderInfoBarText(
    text: String,
    style: TextStyle,
    outlineColor: Color,
    drawOutline: Boolean,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
) {
    val outlineWidth = with(LocalDensity.current) { 0.75.dp.toPx() }
    Box(modifier = modifier) {
        if (drawOutline) {
            Text(
                text = text,
                style = style.copy(
                    color = outlineColor,
                    drawStyle = Stroke(width = outlineWidth),
                ),
                maxLines = maxLines,
                overflow = overflow,
            )
        }
        Text(
            text = text,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

@Composable
private fun ReaderCutoutAwareInfoBarLayout(
    layout: ReaderInfoBarLayout,
    avoidDisplayCutout: Boolean,
    readingStatus: @Composable () -> Unit,
    progressStatus: (@Composable () -> Unit)?,
    systemStatus: (@Composable () -> Unit)?,
) {
    val density = LocalDensity.current
    val layoutDirection = LocalLayoutDirection.current
    val view = LocalView.current
    // Reading the Compose inset values keeps this branch in sync when immersive mode or rotation changes.
    val displayCutoutInsets = WindowInsets.displayCutout
    val hasDisplayCutoutInsets = displayCutoutInsets.getLeft(density, layoutDirection) > 0 ||
        displayCutoutInsets.getTop(density) > 0 ||
        displayCutoutInsets.getRight(density, layoutDirection) > 0 ||
        displayCutoutInsets.getBottom(density) > 0
    val displayCutouts = if (
        avoidDisplayCutout && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P && hasDisplayCutoutInsets
    ) {
        view.rootWindowInsets?.displayCutout?.boundingRects.orEmpty()
    } else {
        emptyList()
    }
    val topLeftRoundedCorner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)
    } else {
        null
    }
    val topRightRoundedCorner = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        view.rootWindowInsets?.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)
    } else {
        null
    }
    var boundsInWindow by remember { mutableStateOf<IntRect?>(null) }
    val localCutout = boundsInWindow?.let { barBounds ->
        displayCutouts
            .asSequence()
            .filter { cutout ->
                cutout.right > barBounds.left && cutout.left < barBounds.right &&
                    cutout.bottom > barBounds.top && cutout.top < barBounds.bottom
            }
            .maxByOrNull { cutout ->
                minOf(cutout.right, barBounds.right) - maxOf(cutout.left, barBounds.left)
            }
            ?.let { cutout ->
                ReaderInfoBarCutout(
                    left = (cutout.left - barBounds.left).coerceAtLeast(0),
                    right = (cutout.right - barBounds.left).coerceAtMost(barBounds.width),
                )
            }
    }
    val baseHorizontalPadding = with(density) { ReaderInfoBarHorizontalPadding.roundToPx() }
    val verticalPadding = with(density) { ReaderInfoBarVerticalPadding.roundToPx() }
    val obstructionSpacing = with(density) { ReaderInfoBarObstructionSpacing.roundToPx() }
    val itemSpacing = with(density) { ReaderInfoBarItemSpacing.roundToPx() }
    val contentTopInWindow = boundsInWindow?.top?.plus(verticalPadding)
    val leftPadding = maxOf(
        baseHorizontalPadding,
        boundsInWindow?.let { bounds ->
            topLeftRoundedCorner?.let { corner ->
                resolveReaderInfoBarRoundedCornerPadding(
                    edgeDistanceToCenter = corner.center.x - bounds.left,
                    cornerCenterY = corner.center.y,
                    radius = corner.radius,
                    contentTopY = contentTopInWindow ?: bounds.top,
                    spacing = obstructionSpacing,
                )
            }
        } ?: 0,
    )
    val rightPadding = maxOf(
        baseHorizontalPadding,
        boundsInWindow?.let { bounds ->
            topRightRoundedCorner?.let { corner ->
                resolveReaderInfoBarRoundedCornerPadding(
                    edgeDistanceToCenter = bounds.right - corner.center.x,
                    cornerCenterY = corner.center.y,
                    radius = corner.radius,
                    contentTopY = contentTopInWindow ?: bounds.top,
                    spacing = obstructionSpacing,
                )
            }
        } ?: 0,
    )

    Layout(
        content = {
            readingStatus()
            progressStatus?.invoke()
            systemStatus?.invoke()
        },
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { coordinates ->
                val position = coordinates.positionInWindow()
                val updatedBounds = IntRect(
                    left = position.x.roundToInt(),
                    top = position.y.roundToInt(),
                    right = position.x.roundToInt() + coordinates.size.width,
                    bottom = position.y.roundToInt() + coordinates.size.height,
                )
                if (boundsInWindow != updatedBounds) boundsInWindow = updatedBounds
            },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val contentHeightLimit = (constraints.maxHeight - verticalPadding * 2).coerceAtLeast(0)
        val childConstraints = Constraints(
            minWidth = 0,
            maxWidth = (width - leftPadding - rightPadding).coerceAtLeast(0),
            minHeight = 0,
            maxHeight = contentHeightLimit,
        )
        var measurableIndex = 1
        val progressMeasurable = if (progressStatus != null) measurables.getOrNull(measurableIndex++) else null
        val systemPlaceable = if (systemStatus != null) {
            measurables.getOrNull(measurableIndex)?.measure(childConstraints)
        } else {
            null
        }
        val systemWidth = systemPlaceable?.width ?: 0
        val naturalProgressPlaceable = progressMeasurable?.measure(childConstraints)
        val progressWidth = naturalProgressPlaceable?.width ?: 0
        val trailingWidth = if (layout == ReaderInfoBarLayout.CENTERED && progressWidth > 0) {
            itemSpacing + progressWidth + systemWidth
        } else {
            systemWidth
        }
        val readingMeasurable = measurables.first()
        val desiredReadingWidth = readingMeasurable
            .maxIntrinsicWidth(contentHeightLimit)
            .coerceAtMost((childConstraints.maxWidth - trailingWidth).coerceAtLeast(0))
        val placement = resolveReaderInfoBarPlacement(
            width = width,
            desiredReadingWidth = desiredReadingWidth,
            systemStatusWidth = trailingWidth,
            layout = layout,
            leftPadding = leftPadding,
            rightPadding = rightPadding,
            cutout = localCutout,
            cutoutSpacing = obstructionSpacing,
        )
        val readingPlaceable = readingMeasurable.measure(
            childConstraints.copy(maxWidth = placement.readingWidth),
        )
        val progressPlacement = naturalProgressPlaceable?.let { progress ->
            if (layout == ReaderInfoBarLayout.CENTERED) {
                ReaderInfoBarItemPlacement(
                    x = placement.systemStatusX + itemSpacing,
                    width = progress.width,
                )
            } else {
                resolveReaderInfoBarProgressPlacement(
                    readingEnd = placement.readingX + readingPlaceable.width,
                    systemStatusX = placement.systemStatusX,
                    desiredProgressWidth = progress.width,
                    cutout = localCutout,
                    cutoutSpacing = obstructionSpacing,
                )
            }
        }
        val progressPlaceable = progressPlacement?.let { progress ->
            if (progress.width == naturalProgressPlaceable.width) {
                naturalProgressPlaceable
            } else {
                progressMeasurable.measure(childConstraints.copy(maxWidth = progress.width))
            }
        }
        val resolvedSystemStatusX = if (layout == ReaderInfoBarLayout.CENTERED && progressPlaceable != null) {
            progressPlacement.x + progressPlaceable.width
        } else {
            placement.systemStatusX
        }
        val contentHeight = maxOf(
            readingPlaceable.height,
            progressPlaceable?.height ?: 0,
            systemPlaceable?.height ?: 0,
        )
        val height = (contentHeight + verticalPadding * 2).coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(width, height) {
            readingPlaceable.place(
                x = placement.readingX,
                y = (height - readingPlaceable.height) / 2,
            )
            systemPlaceable?.place(
                x = resolvedSystemStatusX,
                y = (height - systemPlaceable.height) / 2,
            )
            progressPlaceable?.place(
                x = progressPlacement.x,
                y = (height - progressPlaceable.height) / 2,
            )
        }
    }
}

@Immutable
internal data class ReaderInfoBarCutout(
    val left: Int,
    val right: Int,
)

@Immutable
internal data class ReaderInfoBarPlacement(
    val readingX: Int,
    val readingWidth: Int,
    val systemStatusX: Int,
)

@Immutable
internal data class ReaderInfoBarItemPlacement(
    val x: Int,
    val width: Int,
)

internal fun resolveReaderInfoBarProgressPlacement(
    readingEnd: Int,
    systemStatusX: Int,
    desiredProgressWidth: Int,
    cutout: ReaderInfoBarCutout?,
    cutoutSpacing: Int,
): ReaderInfoBarItemPlacement {
    val availableStart = readingEnd + cutoutSpacing
    val availableEnd = (systemStatusX - cutoutSpacing).coerceAtLeast(availableStart)
    val overlappingCutout = cutout?.takeIf {
        it.right > availableStart && it.left < availableEnd
    }
    if (overlappingCutout == null) {
        val width = desiredProgressWidth.coerceIn(0, availableEnd - availableStart)
        return ReaderInfoBarItemPlacement(
            x = availableStart + (availableEnd - availableStart - width) / 2,
            width = width,
        )
    }

    val leftEnd = (overlappingCutout.left - cutoutSpacing).coerceIn(availableStart, availableEnd)
    val rightStart = (overlappingCutout.right + cutoutSpacing).coerceIn(availableStart, availableEnd)
    val leftWidth = leftEnd - availableStart
    val rightWidth = availableEnd - rightStart
    return if (rightWidth >= leftWidth) {
        ReaderInfoBarItemPlacement(
            x = rightStart,
            width = desiredProgressWidth.coerceIn(0, rightWidth),
        )
    } else {
        val width = desiredProgressWidth.coerceIn(0, leftWidth)
        ReaderInfoBarItemPlacement(
            x = leftEnd - width,
            width = width,
        )
    }
}

internal fun resolveReaderInfoBarRoundedCornerPadding(
    edgeDistanceToCenter: Int,
    cornerCenterY: Int,
    radius: Int,
    contentTopY: Int,
    spacing: Int,
): Int {
    if (radius <= 0 || edgeDistanceToCenter <= 0) return 0
    val verticalDistance = (cornerCenterY - contentTopY).coerceIn(0, radius)
    val visibleRadiusAtContentTop = sqrt(
        radius.toDouble() * radius - verticalDistance.toDouble() * verticalDistance,
    )
    val clippedInset = edgeDistanceToCenter - visibleRadiusAtContentTop
    return if (clippedInset > 0) ceil(clippedInset + spacing).toInt() else 0
}

internal fun resolveReaderInfoBarPlacement(
    width: Int,
    desiredReadingWidth: Int,
    systemStatusWidth: Int,
    layout: ReaderInfoBarLayout,
    leftPadding: Int,
    rightPadding: Int,
    cutout: ReaderInfoBarCutout?,
    cutoutSpacing: Int,
): ReaderInfoBarPlacement {
    val contentLeft = leftPadding
    val contentRight = (width - rightPadding).coerceAtLeast(contentLeft)
    val availableWidth = contentRight - contentLeft
    val readingWidth = desiredReadingWidth.coerceIn(0, (availableWidth - systemStatusWidth).coerceAtLeast(0))
    val naturalSystemX = when (layout) {
        ReaderInfoBarLayout.CENTERED -> (width - readingWidth - systemStatusWidth) / 2 + readingWidth
        ReaderInfoBarLayout.SPLIT -> contentRight - systemStatusWidth
    }
    val naturalReadingX = when (layout) {
        ReaderInfoBarLayout.CENTERED -> naturalSystemX - readingWidth
        ReaderInfoBarLayout.SPLIT -> contentLeft
    }
    val protectedCutout = cutout?.let {
        ReaderInfoBarCutout(
            left = (it.left - cutoutSpacing).coerceIn(contentLeft, contentRight),
            right = (it.right + cutoutSpacing).coerceIn(contentLeft, contentRight),
        )
    }?.takeIf { it.left < it.right }
        ?: return ReaderInfoBarPlacement(naturalReadingX, readingWidth, naturalSystemX)

    val leftWidth = (protectedCutout.left - contentLeft).coerceAtLeast(0)
    val rightWidth = (contentRight - protectedCutout.right).coerceAtLeast(0)

    if (layout == ReaderInfoBarLayout.SPLIT) {
        val readingPlacement = resolveReaderEdgeAnchoredItem(
            itemWidth = readingWidth,
            contentLeft = contentLeft,
            contentRight = contentRight,
            cutout = protectedCutout,
            anchorAtStart = true,
        )
        val systemPlacement = resolveReaderEdgeAnchoredItem(
            itemWidth = systemStatusWidth,
            contentLeft = contentLeft,
            contentRight = contentRight,
            cutout = protectedCutout,
            anchorAtStart = false,
        )
        return ReaderInfoBarPlacement(
            readingX = readingPlacement.x,
            readingWidth = readingPlacement.width,
            systemStatusX = systemPlacement.x,
        )
    }

    fun overlaps(x: Int, itemWidth: Int): Boolean =
        itemWidth > 0 && x < protectedCutout.right && x + itemWidth > protectedCutout.left

    if (!overlaps(naturalReadingX, readingWidth) && !overlaps(naturalSystemX, systemStatusWidth)) {
        return ReaderInfoBarPlacement(naturalReadingX, readingWidth, naturalSystemX)
    }

    val totalWidth = readingWidth + systemStatusWidth
    if (readingWidth <= leftWidth && systemStatusWidth <= rightWidth) {
        return ReaderInfoBarPlacement(
            readingX = if (layout == ReaderInfoBarLayout.CENTERED) {
                protectedCutout.left - readingWidth
            } else {
                contentLeft
            },
            readingWidth = readingWidth,
            systemStatusX = if (layout == ReaderInfoBarLayout.CENTERED) {
                protectedCutout.right
            } else {
                contentRight - systemStatusWidth
            },
        )
    }
    if (totalWidth <= leftWidth) {
        val start = if (layout == ReaderInfoBarLayout.CENTERED) {
            contentLeft + (leftWidth - totalWidth) / 2
        } else {
            contentLeft
        }
        return ReaderInfoBarPlacement(start, readingWidth, start + readingWidth)
    }
    if (totalWidth <= rightWidth) {
        val start = if (layout == ReaderInfoBarLayout.CENTERED) {
            protectedCutout.right + (rightWidth - totalWidth) / 2
        } else {
            contentRight - totalWidth
        }
        return ReaderInfoBarPlacement(start, readingWidth, start + readingWidth)
    }

    val systemOnRight = systemStatusWidth <= rightWidth || systemStatusWidth > leftWidth
    val safeReadingWidth = if (systemOnRight) leftWidth else rightWidth
    val resolvedReadingWidth = readingWidth.coerceAtMost(safeReadingWidth)
    return if (systemOnRight) {
        ReaderInfoBarPlacement(
            readingX = contentLeft,
            readingWidth = resolvedReadingWidth,
            systemStatusX = protectedCutout.right,
        )
    } else {
        ReaderInfoBarPlacement(
            readingX = protectedCutout.right,
            readingWidth = resolvedReadingWidth,
            systemStatusX = protectedCutout.left - systemStatusWidth,
        )
    }
}

private fun resolveReaderEdgeAnchoredItem(
    itemWidth: Int,
    contentLeft: Int,
    contentRight: Int,
    cutout: ReaderInfoBarCutout,
    anchorAtStart: Boolean,
): ReaderInfoBarItemPlacement {
    val naturalX = if (anchorAtStart) contentLeft else contentRight - itemWidth
    val overlapsCutout = itemWidth > 0 && naturalX < cutout.right && naturalX + itemWidth > cutout.left
    if (!overlapsCutout) return ReaderInfoBarItemPlacement(naturalX, itemWidth)

    val beforeWidth = (cutout.left - contentLeft).coerceAtLeast(0)
    val afterWidth = (contentRight - cutout.right).coerceAtLeast(0)
    if (anchorAtStart) {
        if (itemWidth <= beforeWidth) return ReaderInfoBarItemPlacement(contentLeft, itemWidth)
        if (itemWidth <= afterWidth) return ReaderInfoBarItemPlacement(cutout.right, itemWidth)
    } else {
        if (itemWidth <= afterWidth) return ReaderInfoBarItemPlacement(contentRight - itemWidth, itemWidth)
        if (itemWidth <= beforeWidth) return ReaderInfoBarItemPlacement(cutout.left - itemWidth, itemWidth)
    }
    return if (beforeWidth >= afterWidth) {
        ReaderInfoBarItemPlacement(contentLeft, itemWidth.coerceAtMost(beforeWidth))
    } else {
        ReaderInfoBarItemPlacement(cutout.right, itemWidth.coerceAtMost(afterWidth))
    }
}

@Composable
private fun ReaderSystemStatusContent(
    status: ReaderSystemStatus,
    contentColor: Color,
    outlineColor: Color,
    drawOutline: Boolean,
    textStyle: TextStyle,
) {
    val icon = painterResource(
        if (status.isCharging) {
            R.drawable.ic_battery_charging_outline
        } else {
            R.drawable.ic_battery_outline
        },
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp),
    ) {
        ReaderOutlinedBatteryIcon(
            painter = icon,
            contentColor = contentColor,
            outlineColor = outlineColor,
            drawOutline = drawOutline,
        )
        ReaderInfoBarText(
            text = status.battery,
            style = textStyle,
            outlineColor = outlineColor,
            drawOutline = drawOutline,
            modifier = Modifier.width(38.dp),
        )
        ReaderInfoBarText(
            text = status.time,
            style = textStyle,
            outlineColor = outlineColor,
            drawOutline = drawOutline,
            maxLines = 1,
        )
    }
}

@Composable
private fun ReaderOutlinedBatteryIcon(
    painter: androidx.compose.ui.graphics.painter.Painter,
    contentColor: Color,
    outlineColor: Color,
    drawOutline: Boolean,
) {
    val iconSize = 16.dp
    val outlineWidth = 0.75.dp
    Canvas(modifier = Modifier.size(18.dp)) {
        val iconSizePx = iconSize.toPx()
        val outlineWidthPx = outlineWidth.toPx()
        val iconOffset = Offset(
            x = (size.width - iconSizePx) / 2f,
            y = (size.height - iconSizePx) / 2f,
        )
        fun drawLayer(scale: Float, tint: Color) {
            withTransform({
                translate(iconOffset.x, iconOffset.y)
                scale(
                    scaleX = scale,
                    scaleY = scale,
                    pivot = Offset(iconSizePx / 2f, iconSizePx / 2f),
                )
            }) {
                with(painter) {
                    draw(
                        size = Size(iconSizePx, iconSizePx),
                        colorFilter = ColorFilter.tint(tint),
                    )
                }
            }
        }
        if (drawOutline) {
            val outerScale = (iconSizePx + outlineWidthPx * 2f) / iconSizePx
            drawLayer(outerScale, outlineColor)
            drawLayer(1f / outerScale, outlineColor)
        }
        drawLayer(1f, contentColor)
    }
}

@Immutable
internal data class ReaderSystemStatus(
    val time: String = "",
    val battery: String = "",
    val isCharging: Boolean = false,
)

@Composable
internal fun rememberReaderSystemStatus(): ReaderSystemStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(ReaderSystemStatus()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val batteryStatus = intent.getIntExtra(
                    BatteryManager.EXTRA_STATUS,
                    BatteryManager.BATTERY_STATUS_UNKNOWN,
                )
                val battery = if (level >= 0 && scale > 0) "${level * 100 / scale}%" else status.battery
                val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())
                status = ReaderSystemStatus(
                    time = time,
                    battery = battery,
                    isCharging = isReaderBatteryCharging(batteryStatus),
                )
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIME_TICK)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        onDispose { context.unregisterReceiver(receiver) }
    }
    return status
}

@Composable
internal fun BoxScope.ReaderPageInfoBar(
    state: ReaderInfoBarState,
    controlsVisible: Boolean,
    systemStatus: ReaderSystemStatus,
    animationsEnabled: Boolean = true,
) {
    AnimatedVisibility(
        visible = shouldShowReaderInfoBar(state.visible, controlsVisible),
        enter = fadeIn(animationSpec = KototoroMotion.InfoBarEnter)
            .whenReaderAnimationsEnabled(animationsEnabled),
        exit = fadeOut(animationSpec = KototoroMotion.fadeFast())
            .whenReaderAnimationsEnabled(animationsEnabled),
        modifier = Modifier.align(Alignment.TopCenter),
    ) {
        ReaderComposeInfoBar(state, systemStatus)
    }
}

@Composable
private fun ReaderMessageHost(
    message: ReaderMessage?,
    animationsEnabled: Boolean,
    onExpired: (Long) -> Unit,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var displayedMessage by remember { mutableStateOf(message) }
    LaunchedEffect(message) {
        if (message != null) displayedMessage = message
    }
    LaunchedEffect(message?.id) {
        val current = message ?: return@LaunchedEffect
        delay(current.durationMillis ?: return@LaunchedEffect)
        onExpired(current.id)
    }
    AnimatedVisibility(
        visible = message != null,
        enter = fadeIn().whenReaderAnimationsEnabled(animationsEnabled),
        exit = fadeOut().whenReaderAnimationsEnabled(animationsEnabled),
        modifier = modifier,
    ) {
        Surface(shape = MaterialTheme.shapes.small, color = Color.Black.copy(alpha = 0.78f), contentColor = Color.White) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 10.dp)) {
                Text(
                    text = displayedMessage?.text.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 10.dp),
                )
                displayedMessage?.actionLabel?.let { label ->
                    TextButton(onClick = onAction) { Text(label) }
                }
            }
        }
    }
}

@Composable
private fun ReaderComposeTopBar(
    state: ComposeReaderChromeState,
    onNavigateBack: () -> Unit,
    onChapters: () -> Unit,
    onOptions: () -> Unit,
) {
    val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        ReaderTopControlSurface(
            shape = Capsule(),
            modifier = Modifier
                .align(Alignment.CenterStart)
                .size(48.dp),
        ) {
            IconButton(onClick = onNavigateBack) {
                Icon(
                    painter = painterResource(androidx.appcompat.R.drawable.abc_ic_ab_back_material),
                    contentDescription = stringResource(androidx.appcompat.R.string.abc_action_bar_up_description),
                    tint = contentColor,
                )
            }
        }
        if (!state.options.chapterTitleAtBottom) {
            ReaderChapterTitleChip(
                state = state,
                onChapters = onChapters,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        ReaderTopControlSurface(
            shape = Capsule(),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .size(48.dp),
        ) {
            IconButton(onClick = onOptions) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.options),
                    tint = contentColor,
                )
            }
        }
    }
}

/**
 * The work title + current chapter, opening the chapter list on tap. Shown in the top bar,
 * or above the progress dock when [ComposeReaderOptionsState.chapterTitleAtBottom] is on —
 * one-handed readers reach the bottom of the screen, not the top (issue #509).
 */
@Composable
private fun ReaderChapterTitleChip(
    state: ComposeReaderChromeState,
    onChapters: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 48.dp,
) {
    val contentColor = if (isSystemInDarkTheme()) Color.White else Color.Black
    val chapterControlShape = RoundedRectangle(24.dp)
    ReaderTopControlSurface(
        shape = chapterControlShape,
        modifier = modifier
            .widthIn(min = 148.dp, max = 176.dp)
            .height(height),
        contentModifier = Modifier
            .clip(chapterControlShape)
            .clickable(onClick = onChapters),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 5.dp),
        ) {
            Text(
                text = state.title,
                color = contentColor,
                style = MaterialTheme.typography.titleMedium.copy(fontSize = 15.sp, lineHeight = 19.sp),
                maxLines = 1,
            )
            if (state.subtitle.isNotEmpty()) {
                Text(
                    text = state.subtitle,
                    color = contentColor.copy(alpha = 0.78f),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, lineHeight = 13.sp),
                    maxLines = 1,
                )
            }
        }
    }
}

internal fun shouldShowReaderInfoBar(infoBarEnabled: Boolean, controlsVisible: Boolean): Boolean =
    infoBarEnabled && !controlsVisible

internal fun isReaderBatteryCharging(status: Int): Boolean =
    status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

internal fun resolveReaderFloatingControls(
    configured: Set<ReaderControl>,
    translationAvailable: Boolean,
    translationContextualVisible: Boolean,
): List<ReaderControl> {
    val configuredControls = ReaderControl.FLOATING
        .filter { control -> control in configured && (control != ReaderControl.TRANSLATE || translationAvailable) }
    if (!translationAvailable || !translationContextualVisible || ReaderControl.TRANSLATE in configuredControls) {
        return configuredControls.take(ReaderControl.MAX_FLOATING_CONTROLS)
    }
    return configuredControls
        .take(ReaderControl.MAX_FLOATING_CONTROLS - 1)
        .plus(ReaderControl.TRANSLATE)
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ReaderFloatingControlButton(
    control: ReaderControl,
    state: ReaderActionsUiState,
    callbacks: ReaderActionsCallbacks,
    showLabel: Boolean,
) {
    val icon = when (control) {
        ReaderControl.SCREEN_ROTATION -> if (state.autoRotationEnabled) {
            R.drawable.ic_screen_rotation_lock
        } else {
            R.drawable.ic_screen_rotation
        }
        ReaderControl.SAVE_PAGE -> R.drawable.ic_save
        ReaderControl.TIMER -> if (state.timerActive) R.drawable.ic_timer_run else R.drawable.ic_timer
        ReaderControl.BOOKMARK -> if (state.bookmarkAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark
        ReaderControl.TRANSLATE -> R.drawable.ic_translate
        ReaderControl.DOWNLOAD -> R.drawable.ic_download
        else -> return
    }
    val contentDescription = when (control) {
        ReaderControl.SCREEN_ROTATION -> stringResource(
            if (state.autoRotationEnabled) R.string.lock_screen_rotation else R.string.rotate_screen,
        )
        ReaderControl.SAVE_PAGE -> stringResource(R.string.save_page)
        ReaderControl.TIMER -> stringResource(R.string.automatic_scroll)
        ReaderControl.BOOKMARK -> stringResource(
            if (state.bookmarkAdded) R.string.bookmark_remove else R.string.bookmark_add,
        )
        ReaderControl.TRANSLATE -> state.translateContentDescription.ifEmpty {
            stringResource(R.string.novel_translate)
        }
        ReaderControl.DOWNLOAD -> stringResource(R.string.download)
        else -> return
    }
    val visibleLabel = if (control == ReaderControl.TRANSLATE) {
        stringResource(R.string.novel_translate)
    } else {
        contentDescription
    }
    val onClick: () -> Unit = when (control) {
        ReaderControl.SCREEN_ROTATION -> callbacks.onScreenRotation
        ReaderControl.SAVE_PAGE -> callbacks.onSavePage
        ReaderControl.TIMER -> { { callbacks.onTimer(false) } }
        ReaderControl.BOOKMARK -> callbacks.onBookmark
        ReaderControl.TRANSLATE -> callbacks.onTranslate
        ReaderControl.DOWNLOAD -> callbacks.onDownload
        else -> return
    }
    val onLongClick: (() -> Unit)? = when (control) {
        ReaderControl.TIMER -> { { callbacks.onTimer(true) } }
        ReaderControl.BOOKMARK -> callbacks.onBookmarkLongClick
        ReaderControl.TRANSLATE -> callbacks.onTranslateLongClick
        else -> null
    }
    val active = when (control) {
        ReaderControl.TIMER -> state.timerActive
        ReaderControl.BOOKMARK -> state.bookmarkAdded
        ReaderControl.TRANSLATE -> state.translateActive
        else -> false
    }
    val shape = if (showLabel) RoundedRectangle(22.dp) else Capsule()
    val modifier = if (showLabel) {
        Modifier.fillMaxWidth().height(44.dp)
    } else {
        Modifier.size(44.dp)
    }
    val contentColor = if (active) MaterialTheme.colorScheme.primary else readerControlContentColor()
    ReaderTopControlSurface(
        shape = shape,
        modifier = modifier,
        contentModifier = Modifier
            .clip(shape)
            .combinedClickable(
                role = Role.Button,
                onClickLabel = contentDescription,
                onLongClickLabel = if (onLongClick != null) contentDescription else null,
                onClick = onClick,
                onLongClick = onLongClick,
            ),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxSize().padding(horizontal = if (showLabel) 8.dp else 0.dp),
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = contentDescription,
                tint = contentColor,
                modifier = Modifier.size(24.dp),
            )
            if (showLabel) {
                Text(
                    text = visibleLabel,
                    color = contentColor,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun ReaderTopControlSurface(
    shape: Shape,
    modifier: Modifier = Modifier,
    contentModifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    GlassSurface(
        modifier = modifier,
        shape = shape,
        style = GlassDefaults.topBarChromeStyle().copy(
            containerAlpha = 0.84f,
            shadowElevation = ReaderControlTokens.ChromeShadowElevation,
        ),
        // Reader top control surfaces are floating pill controls (capsules),
        // not edge-to-edge bar panels.
        componentRole = GlassComponentRole.PillControl,
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize().then(contentModifier),
        ) {
            content()
        }
    }
}
