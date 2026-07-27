package org.skepsun.kototoro.core.ui.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle

@Stable
internal class LiquidGlassBackdropHost {

    private val backdrops = mutableMapOf<Any, LayerBackdrop>()

    var activeBackdrop by mutableStateOf<LayerBackdrop?>(null)
        private set

    fun backdropFor(ownerKey: Any?): LayerBackdrop? =
        ownerKey?.let(backdrops::get)

    fun update(ownerKey: Any, backdrop: LayerBackdrop, active: Boolean) {
        if (active) {
            backdrops[ownerKey] = backdrop
            activeBackdrop = backdrop
        } else {
            clear(ownerKey, backdrop)
        }
    }

    fun clear(ownerKey: Any, backdrop: LayerBackdrop) {
        if (backdrops[ownerKey] === backdrop) {
            backdrops.remove(ownerKey)
            if (activeBackdrop === backdrop) {
                activeBackdrop = null
            }
        }
    }
}

internal val LocalLiquidGlassBackdropHost = staticCompositionLocalOf<LiquidGlassBackdropHost?> { null }

@Composable
internal fun RouteLiquidGlassBackdrop(
    ownerKey: Any,
    active: Boolean,
    content: @Composable (LayerBackdrop?) -> Unit,
) {
    if (LocalInterfaceStyle.current != InterfaceStyle.IOS) {
        content(null)
        return
    }
    val host = LocalLiquidGlassBackdropHost.current
    val backgroundColor = MaterialTheme.colorScheme.background
    val backdrop = key(ownerKey) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    }

    SideEffect {
        host?.update(ownerKey = ownerKey, backdrop = backdrop, active = active)
    }
    DisposableEffect(host, ownerKey, backdrop) {
        onDispose {
            host?.clear(ownerKey = ownerKey, backdrop = backdrop)
        }
    }

    CompositionLocalProvider(
        LocalLiquidGlassBackdrop provides backdrop,
        LocalLiquidGlassLayerBackdrop provides backdrop,
    ) {
        content(backdrop)
    }
}
