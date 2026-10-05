package org.skepsun.kototoro.reader.ui.compose.panel

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import org.skepsun.kototoro.core.ui.compose.StableAnchoredSheetLayout
import org.skepsun.kototoro.reader.ui.compose.design.ProvideReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors

enum class ReaderPanelSurfaceMode { Glass, Opaque, EInk }

private val GlassShape = RoundedRectangle(28.dp)
private const val GLASS_VEIL_ALPHA = 0.62f

/** Draws a glass sheet over the page (Android's liquid glass). Without one, a glass panel falls back to opaque. */
interface ReaderPanelGlass {
    @Composable
    fun Sheet(shape: Shape, content: @Composable () -> Unit)
}

val LocalReaderPanelGlass = staticCompositionLocalOf<ReaderPanelGlass?> { null }

fun readerPanelSurfaceMode(isIosStyle: Boolean, eInk: Boolean, hasBackdrop: Boolean): ReaderPanelSurfaceMode =
    when {
        eInk -> ReaderPanelSurfaceMode.EInk
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
    content: @Composable (dragModifier: Modifier) -> Unit,
) {
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var headerHeightPx by remember { mutableIntStateOf(0) }
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
        ) { scope ->
            ReaderPanelSurface(surfaceMode, colors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
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
                        header()
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
