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
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
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

/**
 * Backdrop captured from the artwork layer before route content is drawn.
 *
 * Content material may consume this source, while persistent chrome continues
 * to consume the route composite supplied by [LocalLiquidGlassBackdrop].
 * Keeping the two sources separate prevents a content surface from sampling
 * itself through the route-level capture.
 */
val LocalArtworkBackdrop = staticCompositionLocalOf<Backdrop?> { null }

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
    val artworkBackdrop = LocalArtworkBackdrop.current
    val backdrop = key(ownerKey) {
        rememberLayerBackdrop {
            drawRect(backgroundColor)
            drawContent()
        }
    }
    val compositeBackdrop = if (artworkBackdrop != null) {
        rememberCombinedBackdrop(artworkBackdrop, backdrop)
    } else {
        backdrop
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
        // Chrome consumes the artwork source followed by the route composite.
        // The route layer itself remains the only recorded layer, so this
        // combination cannot feed a surface back into its own capture.
        LocalLiquidGlassBackdrop provides compositeBackdrop,
        LocalLiquidGlassLayerBackdrop provides backdrop,
    ) {
        content(backdrop)
    }
}
