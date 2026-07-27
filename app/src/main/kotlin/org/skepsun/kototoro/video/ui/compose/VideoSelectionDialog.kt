package org.skepsun.kototoro.video.ui.compose

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties

internal data class VideoSelectionDialogState(
    val title: String,
    val options: List<String>,
    val selectedIndex: Int,
    val anchorBounds: IntRect = IntRect.Zero,
    val placement: PlayerMenuPlacement = PlayerMenuPlacement.BesideAnchor,
    val onSelect: (Int) -> Unit,
)

@Composable
internal fun VideoSelectionDialog(
    state: VideoSelectionDialogState,
    onDismissRequest: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 6.dp.roundToPx() }
    val marginPx = with(density) { 8.dp.roundToPx() }
    val maxHeight = (androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp * 0.72f).dp
    Popup(
        popupPositionProvider = PlayerMenuPositionProvider(
            targetBounds = state.anchorBounds,
            placement = state.placement,
            gapPx = gapPx,
            marginPx = marginPx,
        ),
        onDismissRequest = onDismissRequest,
        properties = PopupProperties(focusable = true, clippingEnabled = true),
    ) {
        Surface(
            modifier = Modifier
                .widthIn(min = 220.dp, max = 300.dp)
                .heightIn(max = maxHeight),
            shape = RoundedCornerShape(18.dp),
            color = Color.Black.copy(alpha = 0.88f),
            contentColor = Color.White,
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            shadowElevation = 14.dp,
        ) {
            Column {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    itemsIndexed(state.options) { index, label ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onSelect(index) }
                                .padding(horizontal = 14.dp, vertical = 11.dp),
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (index == state.selectedIndex) {
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
