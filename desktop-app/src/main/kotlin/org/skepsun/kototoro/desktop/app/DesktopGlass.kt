package org.skepsun.kototoro.desktop.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import org.skepsun.kototoro.core.ui.glass.*

internal val LocalDesktopBackdrop = staticCompositionLocalOf<Backdrop?> { null }

/** Resolves host style/window availability; both platforms call the Android glass draw implementation in core-ui. */
@Composable
internal fun DesktopControlSurface(modifier: Modifier, shape: Shape, content: @Composable BoxScope.() -> Unit) {
    // The page under a floating bar first (FloatingTopBarScaffold), so the glass refracts what scrolls beneath it.
    val backdrop = org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop.current ?: LocalDesktopBackdrop.current
    if (LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS && backdrop != null) {
        SharedLiquidGlassSurface(backdrop, emptyGlassTuningState(), GlassStyle(.82f, .24f, 0.dp, 4.dp), shape,
            GlassComponentRole.PillControl, modifier = modifier, content = content)
    } else {
        Surface(modifier, shape = shape, color = MaterialTheme.colorScheme.surfaceContainer,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .24f))) {
            Box(content = content)
        }
    }
}

/**
 * A control that already sits on a card: its tonal fill would add a third surface tone inside that card, so it keeps
 * only the hairline. Used by the source rail, where the card already separates itself from the page.
 */
@Composable
internal fun DesktopOutlinedControlSurface(modifier: Modifier, shape: Shape,
    content: @Composable BoxScope.() -> Unit) {
    val backdrop = org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop.current ?: LocalDesktopBackdrop.current
    if (LocalDesktopInterfaceStyle.current == DesktopInterfaceStyle.IOS && backdrop != null) {
        SharedLiquidGlassSurface(backdrop, emptyGlassTuningState(), GlassStyle(.82f, .24f, 0.dp, 4.dp), shape,
            GlassComponentRole.PillControl, modifier = modifier, content = content)
    } else {
        Surface(modifier, shape = shape, color = androidx.compose.ui.graphics.Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .38f))) {
            Box(content = content)
        }
    }
}
