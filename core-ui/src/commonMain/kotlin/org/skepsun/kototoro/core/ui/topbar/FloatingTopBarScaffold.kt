package org.skepsun.kototoro.core.ui.topbar

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdgeGradient
import org.skepsun.kototoro.core.ui.compose.ImmersiveEdge
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassLayerBackdrop
import org.skepsun.kototoro.core.ui.theme.LocalIsIosInterfaceStyle

/**
 * Android's floating top chrome: the page draws under its top bar, the bar floats over it behind the immersive edge
 * gradient, and only the bar sees the page as its liquid-glass backdrop (the content itself gets none, so glass never
 * samples itself). [content] receives the bar's height as top padding; a scrolling container applies it as its
 * content padding so rows scroll under the bar, as on Android.
 *
 * [backdropColor] is painted beneath the recorded page so the glass refracts the page colour, not transparency.
 * [recordBackdrop] false leaves the page unrecorded (native video or browser windows); the bar then has no glass.
 * The page fills the scaffold without sizing it, so [modifier] has to give it a size (fillMaxSize, a weight).
 */
@Composable
fun FloatingTopBarScaffold(
    topBar: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    backdropColor: Color = MaterialTheme.colorScheme.background,
    scrimColor: Color = MaterialTheme.colorScheme.surface,
    recordBackdrop: Boolean = true,
    content: @Composable (PaddingValues) -> Unit,
) {
    val density = LocalDensity.current
    var barHeight by remember { mutableStateOf(0.dp) }
    val backdrop = rememberLayerBackdrop(onDraw = remember(backdropColor) {
        {
            drawRect(backdropColor)
            drawContent()
        }
    })
    Box(modifier) {
        Box(Modifier.matchParentSize().then(if (recordBackdrop) Modifier.layerBackdrop(backdrop) else Modifier)) {
            CompositionLocalProvider(LocalLiquidGlassBackdrop provides null, LocalLiquidGlassLayerBackdrop provides null) {
                content(PaddingValues(top = barHeight))
            }
        }
        if (barHeight > 0.dp && recordBackdrop) FloatingTopBarScrim(barHeight, scrimColor)
        Box(Modifier.align(Alignment.TopStart).fillMaxWidth().onSizeChanged { size ->
            barHeight = with(density) { size.height.toDp() }
        }) {
            val shared = backdrop.takeIf { recordBackdrop }
            CompositionLocalProvider(LocalLiquidGlassBackdrop provides shared, LocalLiquidGlassLayerBackdrop provides shared) {
                topBar()
            }
        }
    }
}

/**
 * The fade behind the floating bar (Android's `ImmersiveEdgeGradient` at rest): Material keeps a stable tonal fade,
 * iOS a lighter one so the glass controls carry the separation.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FloatingTopBarScrim(barHeight: Dp, base: Color) {
    val ios = LocalIsIosInterfaceStyle.current
    ImmersiveEdgeGradient(
        height = barHeight,
        color = base.copy(alpha = if (ios) .74f else .96f),
        edge = ImmersiveEdge.TOP,
        modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(),
    )
}
