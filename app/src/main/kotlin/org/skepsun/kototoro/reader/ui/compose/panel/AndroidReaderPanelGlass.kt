package org.skepsun.kototoro.reader.ui.compose.panel

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.reader.ui.compose.design.ReaderChromeSurfaces
import org.skepsun.kototoro.reader.ui.compose.design.ReaderControlTokens

// The panel host itself is shared in core-ui (`ReaderPanelHost`); Android adds its glass and style decision.

@Composable
internal fun rememberReaderPanelSurfaceMode(eInk: Boolean): ReaderPanelSurfaceMode = readerPanelSurfaceMode(
    isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS,
    eInk = eInk,
    hasBackdrop = LocalLiquidGlassBackdrop.current != null,
)

/** Android's liquid glass sheet, provided to the shared reader panels by `KototoroTheme`. */
object AndroidReaderPanelGlass : ReaderPanelGlass {
    @Composable
    override fun Sheet(shape: Shape, content: @Composable () -> Unit) {
        GlassSurface(
            modifier = Modifier.fillMaxSize(),
            style = GlassDefaults.prominentStyle(),
            shape = shape,
            componentRole = GlassComponentRole.Sheet,
        ) {
            content()
        }
    }
}

/** Android's glass for the shared reader chrome (top capsules, title chip, floating buttons, progress dock). */
object AndroidReaderChromeSurfaces : ReaderChromeSurfaces {
    @Composable
    override fun Pill(shape: Shape, modifier: Modifier, content: @Composable () -> Unit) {
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
            content()
        }
    }

    @Composable
    override fun Dock(shape: Shape, modifier: Modifier, content: @Composable () -> Unit) {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            style = GlassDefaults.bottomBarChromeStyle().copy(
                containerAlpha = 0.86f,
                shadowElevation = ReaderControlTokens.ChromeShadowElevation,
            ),
            // Reader bottom docks are compact floating control clusters, not a
            // passive nav bar — pill chrome (hairline + press gloss).
            componentRole = GlassComponentRole.PillControl,
        ) {
            content()
        }
    }
}