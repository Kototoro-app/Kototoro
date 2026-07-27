package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.MaterialTheme
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassComponentRole
import org.skepsun.kototoro.core.ui.glass.GlassStyle
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.compose.LocalLiquidGlassBackdrop

internal class RootGlassMenuHost {
    var request by mutableStateOf<RootGlassMenuRequest?>(null)
}

internal val LocalRootGlassMenuHost = staticCompositionLocalOf<RootGlassMenuHost?> { null }

internal data class RootGlassMenuRequest(
    val id: Any,
    val backdrop: Backdrop,
    val anchorBounds: Rect,
    val shape: RoundedCornerShape,
    val openAboveAnchor: Boolean,
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
    val surfaceColor = MaterialTheme.colorScheme.surfaceContainer
    val density = LocalDensity.current
    val menuGapPx = with(density) { 4.dp.roundToPx() }
    var measuredMenuWidthPx by remember(request.id) { mutableStateOf(0) }
    var measuredMenuHeightPx by remember(request.id) { mutableStateOf(0) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .zIndex(100f),
    ) {
        val menuOffset = calculateRootGlassMenuOffset(
            rootSize = IntSize(
                width = with(density) { maxWidth.roundToPx() },
                height = with(density) { maxHeight.roundToPx() },
            ),
            menuSize = IntSize(measuredMenuWidthPx, measuredMenuHeightPx),
            anchorBounds = request.anchorBounds,
            gapPx = menuGapPx,
            openAboveAnchor = request.openAboveAnchor,
        )
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
                .offset { menuOffset }
                .alpha(if (measuredMenuWidthPx > 0 && measuredMenuHeightPx > 0) 1f else 0f)
                .widthIn(max = maxWidth)
                .wrapContentWidth()
                .heightIn(max = maxHeight * 0.65f)
                .zIndex(1f)
                .onGloballyPositioned {
                    measuredMenuWidthPx = it.size.width
                    measuredMenuHeightPx = it.size.height
                }
                .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.48f), request.shape)
                .drawBackdrop(
                    backdrop = request.backdrop,
                    shape = { request.shape },
                    effects = {
                        vibrancy()
                        blur(6.dp.toPx())
                    },
                    onDrawSurface = {
                        drawRect(surfaceColor.copy(alpha = 0.42f))
                    },
                )
                .border(1.dp, Color.White.copy(alpha = 0.22f), request.shape),
        ) {
            CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
                CompactMenuContent(request.scrollState, request.content)
            }
        }
    }
}

internal fun calculateRootGlassMenuOffset(
    rootSize: IntSize,
    menuSize: IntSize,
    anchorBounds: Rect,
    gapPx: Int,
    openAboveAnchor: Boolean,
): IntOffset {
    val x = (anchorBounds.right.toInt() - menuSize.width)
        .coerceIn(0, (rootSize.width - menuSize.width).coerceAtLeast(0))
    val preferredY = if (openAboveAnchor) {
        anchorBounds.top.toInt() - menuSize.height - gapPx
    } else {
        anchorBounds.bottom.toInt() + gapPx
    }
    val y = preferredY.coerceIn(0, (rootSize.height - menuSize.height).coerceAtLeast(0))
    return IntOffset(x, y)
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
    openAboveAnchor: Boolean = false,
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
    val backdrop = LocalLiquidGlassBackdrop.current
    val requestId = remember { Any() }
    val useRootMenu = useRootOverlay &&
        expanded &&
        anchorBounds != null &&
        rootHost != null &&
        backdrop != null

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
                backdrop = backdrop!!,
                anchorBounds = anchorBounds!!,
                shape = shape,
                openAboveAnchor = openAboveAnchor,
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
            offset
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
            .wrapContentWidth()
            .wrapContentWidth()
        GlassSurface(
            modifier = menuModifier,
            shape = shape,
            style = style,
            // DropdownMenu is rendered in a separate Popup window. A layer backdrop
            // from the main window would be sampled with the wrong coordinate origin.
            dialogSurface = true,
            componentRole = GlassComponentRole.Menu,
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
    enabled: Boolean = true,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    CompositionLocalProvider(LocalTextStyle provides MaterialTheme.typography.labelLarge) {
        Row(
            modifier = modifier
                .height(48.dp)
                .fillMaxWidth()
                .alpha(if (enabled) 1f else 0.38f)
                .clickable(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
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
}

@Composable
private fun CompactMenuContent(
    scrollState: androidx.compose.foundation.ScrollState,
    content: @Composable ColumnScope.() -> Unit,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 48.dp) {
        Column(
            modifier = Modifier
                .width(IntrinsicSize.Max)
                .verticalScroll(scrollState)
                .padding(vertical = 4.dp),
            content = content,
        )
    }
}

@Composable
internal fun CompactDropdownMenuText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        style = MaterialTheme.typography.labelLarge,
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
