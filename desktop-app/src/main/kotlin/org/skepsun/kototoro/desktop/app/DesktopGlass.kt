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
    val backdrop = LocalDesktopBackdrop.current
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
