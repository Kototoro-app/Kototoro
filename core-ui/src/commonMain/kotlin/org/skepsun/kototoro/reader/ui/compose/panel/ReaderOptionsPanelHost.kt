package org.skepsun.kototoro.reader.ui.compose.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.compose.StableAnchoredSheetLayout
import org.skepsun.kototoro.reader.ui.compose.design.ProvideReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors

enum class ReaderPanelSurfaceMode { Glass, Opaque, EInk }

private val GlassShape = RoundedRectangle(28.dp)
private const val GLASS_VEIL_ALPHA = 0.62f

/** How many frames a sheet focus request is retried before the panel gives up on the ring. */
private const val FOCUS_RETRY_FRAMES = 10

/** How many frames the ring is re-requested for after the sheet collapses back to its peek. */
private const val COLLAPSE_FOCUS_FRAMES = 3

/** Draws a glass sheet over the page (Android's liquid glass). Without one, a glass panel falls back to opaque. */
interface ReaderPanelGlass {
    @Composable
    fun Sheet(shape: Shape, content: @Composable () -> Unit)
}

val LocalReaderPanelGlass = staticCompositionLocalOf<ReaderPanelGlass?> { null }

/**
 * Moves the options sheet between its anchors for a device that has no drag gesture.
 *
 * A remote can focus a detail tab but cannot pull the sheet up, so the tab alone would switch to a
 * page nobody can see. The slot fills these in on TV only; the defaults make both calls no-ops.
 */
class ReaderSheetActions {
    var expand: () -> Unit = {}
    var collapse: () -> Unit = {}

    /**
     * Whether the sheet currently shows more than its peek. Content below the peek is composed and
     * focusable even though it is off screen or clipped, so a direction press can otherwise hand the
     * focus to a control nobody can see. Off TV it always reports true, which turns the guard below
     * into a no-op.
     */
    var isExpanded: () -> Boolean = { true }

    /**
     * Puts the ring on the first row of the detail page and reports whether that row took it.
     *
     * Revealing the sheet alone is not enough for a remote: the header is what held the ring, and it
     * stops being focusable the moment the sheet passes its peek, so the press that expanded the
     * sheet would leave nothing focused at all. The row therefore has to be asked for by name.
     */
    var focusDetailRow: () -> Boolean = { false }
}

val LocalReaderSheetActions = staticCompositionLocalOf { ReaderSheetActions() }

/**
 * Hands the ring to a control that the remote is expected to reach.
 *
 * A plain [FocusRequester.requestFocus] is enough on a desktop, but on a television it is silently
 * dropped when the window sits in touch mode - the state it stays in after one tap on the screen or
 * one closed sheet. The host therefore replaces this with a request that leaves touch mode first.
 */
val LocalTvFocusRequest = staticCompositionLocalOf<(FocusRequester) -> Boolean> {
    { requester -> runCatching { requester.requestFocus() }.getOrDefault(false) }
}

/**
 * Picks the surface a reader panel is drawn on.
 *
 * A television sits across the room from a moving page, and glass lets that page's own art through, so
 * the panel takes the solid surface there and keeps the tint only for a handheld screen at arm's length.
 */
fun readerPanelSurfaceMode(
    isIosStyle: Boolean,
    eInk: Boolean,
    hasBackdrop: Boolean,
    isTv: Boolean = false,
): ReaderPanelSurfaceMode = when {
    eInk -> ReaderPanelSurfaceMode.EInk
    isTv -> ReaderPanelSurfaceMode.Opaque
    isIosStyle && hasBackdrop -> ReaderPanelSurfaceMode.Glass
    else -> ReaderPanelSurfaceMode.Opaque
}

fun readerPanelScrimAlpha(mode: ReaderPanelSurfaceMode): Float = when (mode) {
    ReaderPanelSurfaceMode.Glass -> 0.32f
    ReaderPanelSurfaceMode.Opaque -> 0.42f
    ReaderPanelSurfaceMode.EInk -> 0f
}

/**
 * The reader options panel: [ReaderPanelHost] opening at the peek anchor, which shows exactly
 * [quickLayer].
 */
@Composable
fun ReaderOptionsPanelHost(
    colors: ReaderPanelColors,
    surfaceMode: ReaderPanelSurfaceMode,
    onDismissRequest: () -> Unit,
    quickLayer: @Composable ColumnScope.() -> Unit,
    details: @Composable (dragModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
    openExpanded: Boolean = false,
    initialFocusRequester: FocusRequester? = null,
) {
    ReaderPanelHost(
        colors = colors,
        surfaceMode = surfaceMode,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        usePeekAnchor = true,
        openExpanded = openExpanded,
        header = quickLayer,
        content = details,
        initialFocusRequester = initialFocusRequester,
    )
}

/**
 * A reader panel (options, chapters). Must be placed in the reader's own window, beside (not
 * inside) the content that carries `layerBackdrop`, so the glass surface can show the page through
 * it. [header] sits in the drag area under the handle; with [usePeekAnchor] the panel opens showing
 * exactly the header.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderPanelHost(
    colors: ReaderPanelColors,
    surfaceMode: ReaderPanelSurfaceMode,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    usePeekAnchor: Boolean = false,
    header: @Composable ColumnScope.() -> Unit = {},
    openExpanded: Boolean = false,
    /**
     * The control an opened panel should start on. Only the caller knows what the panel begins with,
     * and taking the focus for the sheet as a whole would park it on a column that draws no ring and
     * covers every row, which leaves a remote with nothing to step to.
     */
    initialFocusRequester: FocusRequester? = null,
    content: @Composable (dragModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeightPx by remember { mutableIntStateOf(0) }
    // A remote has no pointer, so the sheet has to take the focus it is opened for; without this the
    // ring stays on the control behind the sheet and the whole panel is unreachable.
    val isTv = LocalUiPresentationConfig.current.isTv
    val requestFocus = LocalTvFocusRequest.current
    val sheetFocusRequester = remember { FocusRequester() }
    val focusTarget = initialFocusRequester ?: sheetFocusRequester
    var sheetHasFocus by remember { mutableStateOf(false) }
    LaunchedEffect(isTv, focusTarget) {
        if (!isTv) return@LaunchedEffect
        if (initialFocusRequester == null) {
            // Panels that never declared a start control keep the previous single attempt: retrying
            // there could park the ring on the sheet column, which is worse than leaving it behind.
            withFrameNanos { }
            requestFocus(focusTarget)
            return@LaunchedEffect
        }
        // A real control is targeted, and the sheet is measured, anchored and slid into place over
        // several frames: a request issued before that control has a position is dropped. Retry until
        // the sheet reports the focus, then stop for good - asking again would pull the ring back out.
        repeat(FOCUS_RETRY_FRAMES) {
            withFrameNanos { }
            if (sheetHasFocus) return@LaunchedEffect
            if (!requestFocus(focusTarget)) delay(70)
        }
    }
    val peekHeight = if (usePeekAnchor && headerHeightPx > 0) {
        with(density) { headerHeightPx.toDp() } + navigationInset
    } else {
        null
    }
    ProvideReaderPanelColors(colors) {
        StableAnchoredSheetLayout(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetMaxWidth = ReaderControlTokens.SheetMaxWidth,
            scrimColor = Color.Black.copy(alpha = readerPanelScrimAlpha(surfaceMode)),
            usePeekAnchor = usePeekAnchor,
            peekHeight = peekHeight,
            openExpanded = openExpanded,
            collapseBeforeDismiss = isTv,
        ) { scope ->
            // Collapsing back to the peek on TV brings the quick layer into view again, so the
            // focus has to come with it: left in the detail page it would sit on a hidden control.
            var wasExpanded by remember { mutableStateOf(false) }
            LaunchedEffect(scope.isExpandedPastPeek) {
                val collapsing = wasExpanded && !scope.isExpandedPastPeek
                wasExpanded = scope.isExpandedPastPeek
                if (!isTv || !collapsing) return@LaunchedEffect
                // Collapsing back to the peek brings the quick layer into view again, so the ring has
                // to come with it. The sheet's own focus flag cannot gate this: it still reports the
                // detail page's control for a frame after that page is gone, and a gated request is
                // then never made. The ring would sit on nothing and the next direction press would
                // hand it to the reader's page slider behind the sheet - a sliver of ring at the
                // bottom edge and a dead panel. Ask outright instead, a few frames apart, so the
                // request that lands is the one that sticks.
                repeat(COLLAPSE_FOCUS_FRAMES) {
                    withFrameNanos { }
                    requestFocus(focusTarget)
                }
            }
            val sheetActions = remember { ReaderSheetActions() }
            SideEffect {
                if (isTv) {
                    sheetActions.expand = scope.expand
                    sheetActions.collapse = scope.collapse
                    sheetActions.isExpanded = { scope.isExpandedPastPeek }
                } else {
                    sheetActions.expand = {}
                    sheetActions.collapse = {}
                    sheetActions.isExpanded = { true }
                }
            }
            CompositionLocalProvider(LocalReaderSheetActions provides sheetActions) {
                ReaderPanelSurface(surfaceMode, colors) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .onFocusChanged { sheetHasFocus = it.hasFocus }
                            .focusRequester(sheetFocusRequester)
                            .focusGroup()
                            .testTag("reader-panel-sheet")
                            // The sheet's offset from the top equals contentBottomPadding; only once it
                            // slides under the status bar does the content need pushing down.
                            .padding(
                                top = (statusBarInset - scope.contentBottomPadding).coerceAtLeast(0.dp),
                                bottom = scope.contentBottomPadding,
                            ),
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    // Keep measuring the full header so collapsing it never moves the peek anchor.
                                    val header = measurable.measure(constraints)
                                    val visibleHeight = if (scope.isExpandedPastPeek) {
                                        minOf(header.height, 48.dp.roundToPx())
                                    } else {
                                        header.height
                                    }
                                    layout(header.width, visibleHeight) { header.placeRelative(0, 0) }
                                }
                                // The peek is the header's natural height. While the sheet is hidden or
                                // dragged down the column above is shorter than that; measured inside it
                                // the header would shrink and the peek anchor would follow the finger.
                                .wrapContentHeight(align = Alignment.Top, unbounded = true)
                                .then(scope.dragModifier)
                                .onSizeChanged { headerHeightPx = it.height },
                        ) {
                            BottomSheetDefaults.DragHandle(color = colors.contentSecondary.copy(alpha = 0.6f))
                            // Past the peek the sheet keeps the header composed but clips it to the handle,
                            // so its children stay focusable above the detail page: UP from the page's first
                            // row lands on a control nobody can see and the ring disappears for a press.
                            // Dropping the header's focus while it is collapsed lets that press reach the tab
                            // bar, which is the row actually drawn at the top. Off TV nothing collapses.
                            Box(
                                modifier = Modifier.focusProperties {
                                    canFocus = !isTv || !scope.isExpandedPastPeek
                                },
                            ) {
                                this@Column.header()
                            }
                        }
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .navigationBarsPadding(),
                        ) {
                            content(scope.dragModifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderPanelSurface(
    mode: ReaderPanelSurfaceMode,
    colors: ReaderPanelColors,
    content: @Composable () -> Unit,
) {
    val sheetShape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    val glass = LocalReaderPanelGlass.current
    when {
        mode == ReaderPanelSurfaceMode.Glass && glass != null -> glass.Sheet(GlassShape) {
            // The liquid glass tint alpha comes from the glass tuning (about 0.4), which lets a busy
            // manga page drown the panel text; this veil in the panel colour keeps the blur visible
            // but the text readable.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(GlassShape)
                    .background(colors.container.copy(alpha = GLASS_VEIL_ALPHA)),
            ) {
                content()
            }
        }
        mode == ReaderPanelSurfaceMode.EInk -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            border = BorderStroke(1.dp, colors.content),
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
        else -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}
