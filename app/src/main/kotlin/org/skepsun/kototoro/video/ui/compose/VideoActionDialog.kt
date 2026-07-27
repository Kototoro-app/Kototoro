package org.skepsun.kototoro.video.ui.compose

import androidx.annotation.DrawableRes
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import kotlin.math.roundToInt

internal data class VideoActionDialogItem(
    val title: String,
    val subtitle: String? = null,
    val leadingText: String? = null,
    @DrawableRes val iconRes: Int? = null,
    val checked: Boolean? = null,
    val onClick: () -> Unit,
)

internal data class VideoActionDialogState(
    val title: String,
    val items: List<VideoActionDialogItem>,
    val anchorBounds: IntRect = IntRect.Zero,
)

internal enum class PlayerMenuPlacement {
    BelowAnchor,
    BesideAnchor,
}

internal class PlayerMenuPositionProvider(
    private val targetBounds: IntRect,
    private val placement: PlayerMenuPlacement,
    private val gapPx: Int,
    private val marginPx: Int,
) : PopupPositionProvider {

    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val target = targetBounds.takeUnless { it == IntRect.Zero } ?: anchorBounds
        val maxX = (windowSize.width - popupContentSize.width - marginPx).coerceAtLeast(marginPx)
        val maxY = (windowSize.height - popupContentSize.height - marginPx).coerceAtLeast(marginPx)
        val position = when (placement) {
            PlayerMenuPlacement.BelowAnchor -> {
                val x = (target.right - popupContentSize.width).coerceIn(marginPx, maxX)
                val belowY = target.bottom + gapPx
                val aboveY = target.top - popupContentSize.height - gapPx
                val y = when {
                    belowY <= maxY -> belowY
                    aboveY >= marginPx -> aboveY
                    else -> belowY.coerceIn(marginPx, maxY)
                }
                IntOffset(x, y)
            }
            PlayerMenuPlacement.BesideAnchor -> {
                val rightX = target.right + gapPx
                val leftX = target.left - popupContentSize.width - gapPx
                val preferRight = layoutDirection == LayoutDirection.Ltr
                val preferredX = if (preferRight) rightX else leftX
                val alternateX = if (preferRight) leftX else rightX
                val x = when {
                    preferredX in marginPx..maxX -> preferredX
                    alternateX in marginPx..maxX -> alternateX
                    else -> preferredX.coerceIn(marginPx, maxX)
                }
                IntOffset(x, target.top.coerceIn(marginPx, maxY))
            }
        }
        return position
    }
}

@Composable
internal fun VideoActionDialog(
    state: VideoActionDialogState,
    onDismissRequest: () -> Unit,
    onItemSelected: (VideoActionDialogItem, IntRect) -> Unit,
) {
    val gapPx = with(androidx.compose.ui.platform.LocalDensity.current) { 6.dp.roundToPx() }
    val marginPx = with(androidx.compose.ui.platform.LocalDensity.current) { 8.dp.roundToPx() }
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Popup(
        popupPositionProvider = PlayerMenuPositionProvider(
            targetBounds = state.anchorBounds,
            placement = PlayerMenuPlacement.BelowAnchor,
            gapPx = gapPx,
            marginPx = marginPx,
        ),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 268.dp, max = 340.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.86f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 12.dp,
        ) {
            CompositionLocalProvider(LocalContentColor provides Color.White) {
                Column {
                    Text(
                        text = state.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(state.items) { item ->
                            var itemBounds = IntRect.Zero
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .onGloballyPositioned { coordinates ->
                                        val bounds = coordinates.boundsInWindow()
                                        itemBounds = IntRect(
                                            left = bounds.left.roundToInt(),
                                            top = bounds.top.roundToInt(),
                                            right = bounds.right.roundToInt(),
                                            bottom = bounds.bottom.roundToInt(),
                                        )
                                    }
                                    .clickable { onItemSelected(item, itemBounds) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                            ) {
                                item.iconRes?.let {
                                    Icon(
                                        painter = painterResource(it),
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(22.dp),
                                    )
                                }
                                item.leadingText?.let {
                                    Text(
                                        text = it,
                                        color = Color.White,
                                        modifier = Modifier.padding(end = 12.dp),
                                    )
                                }
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = if (item.iconRes != null) 12.dp else 0.dp),
                                ) {
                                    Text(
                                        text = item.title,
                                        style = MaterialTheme.typography.labelLarge,
                                        color = Color.White,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    item.subtitle?.takeIf(String::isNotBlank)?.let {
                                        Text(
                                            text = it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White.copy(alpha = 0.70f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                                item.checked?.takeIf { it }?.let {
                                    Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                        tint = Color.White,
                                        modifier = Modifier.size(20.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
