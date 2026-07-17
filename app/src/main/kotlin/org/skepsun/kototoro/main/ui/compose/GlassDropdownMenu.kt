package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.glass.GlassVisualTreatment
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop

internal class RootGlassMenuHost {
    var request by mutableStateOf<RootGlassMenuRequest?>(null)
}

internal val LocalRootGlassMenuHost = staticCompositionLocalOf<RootGlassMenuHost?> { null }

internal data class RootGlassMenuRequest(
    val id: Any,
    val anchorBounds: Rect,
    val shape: RoundedCornerShape,
    val scrollState: androidx.compose.foundation.ScrollState,
    val onDismissRequest: () -> Unit,
    val content: @Composable ColumnScope.() -> Unit,
)

@Composable
internal fun RootGlassMenuOverlay(
    host: RootGlassMenuHost,
    modifier: Modifier = Modifier,
) {
    val request = host.request ?: return
    val backdrop = LocalLiquidGlassBackdrop.current
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val density = LocalDensity.current
    val menuGapPx = with(density) { 4.dp.roundToPx() }
    var measuredMenuWidthPx by remember(request.id) { mutableStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        val rootWidthPx = with(density) { maxWidth.roundToPx() }
        val menuWidthPx = measuredMenuWidthPx
        val x = (request.anchorBounds.right.toInt() - menuWidthPx)
            .coerceIn(0, (rootWidthPx - menuWidthPx).coerceAtLeast(0))
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clickable(interactionSource = null, indication = null) {
                    host.request = null
                    request.onDismissRequest()
                },
        )
        Box(
            modifier = Modifier
                .offset { IntOffset(x, request.anchorBounds.bottom.toInt() + menuGapPx) }
                .widthIn(max = 280.dp)
                .wrapContentWidth()
                .heightIn(max = maxHeight * 0.65f)
                .zIndex(1f)
                .onGloballyPositioned { measuredMenuWidthPx = it.size.width }
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.48f), request.shape)
                .drawBackdrop(
                    backdrop = backdrop!!,
                    shape = { request.shape },
                    effects = {
                        vibrancy()
                        blur(6.dp.toPx())
                        lens(
                            refractionHeight = 8.dp.toPx(),
                            refractionAmount = 8.dp.toPx(),
                            chromaticAberration = false,
                        )
                    },
                    onDrawSurface = {
                        drawRect(surfaceColor.copy(alpha = 0.42f))
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), request.shape),
        ) {
            CompactMenuContent(request.scrollState, request.content)
        }
    }
}

@Composable
fun GlassDropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: DpOffset = DpOffset(0.dp, 0.dp),
    alignToAnchorEnd: Boolean = false,
    useRootOverlay: Boolean = false,
    anchorBounds: Rect? = null,
    shape: RoundedCornerShape = RoundedCornerShape(20.dp),
    style: GlassStyle = GlassDefaults.prominentStyle(),
    content: @Composable ColumnScope.() -> Unit,
) {
    val scrollState = rememberScrollState()
    val configuration = LocalConfiguration.current
    val maxMenuHeight = remember(configuration.screenHeightDp) {
        (configuration.screenHeightDp.dp * 0.65f).coerceAtLeast(320.dp)
    }
    val rootHost = LocalRootGlassMenuHost.current
    val requestId = remember { Any() }
    val useRootMenu = useRootOverlay && expanded && anchorBounds != null && rootHost != null

    DisposableEffect(useRootOverlay, rootHost, requestId) {
        onDispose {
            if (rootHost?.request?.id == requestId) {
                rootHost.request = null
            }
        }
    }
    if (useRootMenu) {
        SideEffect {
            rootHost?.request = RootGlassMenuRequest(
                id = requestId,
                anchorBounds = anchorBounds!!,
                shape = shape,
                scrollState = scrollState,
                onDismissRequest = onDismissRequest,
                content = content,
            )
        }
    } else {
        DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        modifier = modifier,
        offset = if (alignToAnchorEnd) {
            DpOffset(x = -152.dp + offset.x, y = offset.y)
        } else {
            offset
        },
        shape = shape,
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = null,
        ) {
        val menuModifier = Modifier
            .heightIn(max = maxMenuHeight)
            .then(if (alignToAnchorEnd) Modifier.width(192.dp) else Modifier.widthIn(min = 176.dp))
            .wrapContentWidth()
        GlassSurface(
            modifier = menuModifier,
            shape = shape,
            style = style,
            // DropdownMenu is rendered in a separate Popup window. A layer backdrop
            // from the main window would be sampled with the wrong coordinate origin.
            allowRuntimeHaze = false,
            dialogSurface = true,
            componentRole = GlassComponentRole.Menu,
            visualTreatment = GlassVisualTreatment.Standard,
        ) {
            CompactMenuContent(scrollState, content)
        }
        }
    }
}

@Composable
internal fun CompactDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    Row(
        modifier = modifier
            .height(40.dp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clickable(onClick = onClick),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leadingIcon?.let {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                it()
            }
        }
        CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
            text()
        }
        trailingIcon?.let {
            CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
                it()
            }
        }
    }
}

@Composable
private fun CompactMenuContent(
    scrollState: androidx.compose.foundation.ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 40.dp) {
        Column(
            modifier = Modifier
                .verticalScroll(scrollState)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun CompactDropdownMenuText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
internal fun CompactDropdownMenuDivider() {
    Box(
        modifier = Modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .width(96.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
    )
}
