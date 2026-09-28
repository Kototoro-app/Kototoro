package org.skepsun.kototoro.reader.ui.compose.panel

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.StableAnchoredSheetLayout
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.compose.design.ProvideReaderPanelColors
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelColors

internal enum class ReaderPanelSurfaceMode { Glass, Opaque, EInk }

internal fun readerPanelSurfaceMode(isIosStyle: Boolean, eInk: Boolean, hasBackdrop: Boolean): ReaderPanelSurfaceMode =
    when {
        eInk -> ReaderPanelSurfaceMode.EInk
        isIosStyle && hasBackdrop -> ReaderPanelSurfaceMode.Glass
        else -> ReaderPanelSurfaceMode.Opaque
    }

internal fun readerPanelScrimAlpha(mode: ReaderPanelSurfaceMode): Float = when (mode) {
    ReaderPanelSurfaceMode.Glass -> 0.32f
    ReaderPanelSurfaceMode.Opaque -> 0.42f
    ReaderPanelSurfaceMode.EInk -> 0f
}

@Composable
internal fun rememberReaderPanelSurfaceMode(eInk: Boolean): ReaderPanelSurfaceMode = readerPanelSurfaceMode(
    isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS,
    eInk = eInk,
    hasBackdrop = LocalLiquidGlassBackdrop.current != null,
)

/**
 * The reader options panel. Must be placed in the reader's own window, beside (not inside) the
 * content that carries `layerBackdrop`, so the glass surface can show the page through it.
 * Opens at the peek anchor, which shows exactly [quickLayer].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderOptionsPanelHost(
    colors: ReaderPanelColors,
    surfaceMode: ReaderPanelSurfaceMode,
    onDismissRequest: () -> Unit,
    quickLayer: @Composable ColumnScope.() -> Unit,
    details: @Composable (dragModifier: Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val navigationInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var quickLayerHeightPx by remember { mutableIntStateOf(0) }
    val peekHeight = if (quickLayerHeightPx > 0) {
        with(density) { quickLayerHeightPx.toDp() } + navigationInset
    } else {
        null
    }
    ProvideReaderPanelColors(colors) {
        StableAnchoredSheetLayout(
            onDismissRequest = onDismissRequest,
            modifier = modifier,
            sheetMaxWidth = ReaderControlTokens.SheetMaxWidth,
            scrimColor = Color.Black.copy(alpha = readerPanelScrimAlpha(surfaceMode)),
            usePeekAnchor = true,
            peekHeight = peekHeight,
        ) { scope ->
            ReaderPanelSurface(surfaceMode, colors) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = scope.contentBottomPadding),
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(scope.dragModifier)
                            .onSizeChanged { quickLayerHeightPx = it.height },
                    ) {
                        BottomSheetDefaults.DragHandle(color = colors.contentSecondary.copy(alpha = 0.6f))
                        quickLayer()
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .navigationBarsPadding(),
                    ) {
                        details(scope.dragModifier)
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
    when (mode) {
        ReaderPanelSurfaceMode.Glass -> GlassSurface(
            modifier = Modifier.fillMaxSize(),
            // Inside ProvideReaderPanelColors, so the glass tint comes from the panel scheme.
            style = GlassDefaults.prominentStyle().copy(containerAlpha = 0.86f),
            shape = RoundedRectangle(28.dp),
            componentRole = GlassComponentRole.Sheet,
        ) {
            content()
        }
        ReaderPanelSurfaceMode.Opaque -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            shadowElevation = 6.dp,
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
        ReaderPanelSurfaceMode.EInk -> Surface(
            shape = sheetShape,
            color = colors.container,
            contentColor = colors.content,
            border = BorderStroke(1.dp, colors.content),
            modifier = Modifier.fillMaxSize(),
        ) {
            content()
        }
    }
}
