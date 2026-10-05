package org.skepsun.kototoro.core.ui.source

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Android's source quick-access grid tile. Icon resolution, badges and TV focus belong to the host. */
@Composable
fun TabletSourceTile(
    title: String,
    cardHeight: Dp,
    iconContainerSize: Dp,
    iconSize: Dp,
    titleTextSize: TextUnit,
    onClick: () -> Unit,
    icon: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    expressive: Boolean = false,
    enabled: Boolean = true,
    onLongClick: (() -> Unit)? = null,
    cardShape: Shape = RoundedCornerShape(if (expressive) 20.dp else 14.dp),
    cardBackground: Color = MaterialTheme.colorScheme.background,
    focusModifier: Modifier = Modifier,
    supportingText: String? = null,
    badges: @Composable BoxScope.() -> Unit = {},
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier.fillMaxWidth().height(cardHeight).clip(cardShape)
            .background(if (selected) colors.secondaryContainer else cardBackground)
            .then(focusModifier)
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Box(
            Modifier.size(iconContainerSize).clip(RoundedCornerShape(14.dp))
                .background(colors.surfaceVariant.copy(alpha = if (expressive) 0.62f else 0.44f)),
            contentAlignment = Alignment.Center,
        ) {
            icon(Modifier.size(iconSize))
            badges()
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelMedium.copy(
                fontSize = titleTextSize,
                lineHeight = (titleTextSize.value + 2f).sp,
            ),
            color = if (selected) colors.onSecondaryContainer else colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
        if (!supportingText.isNullOrBlank()) Text(
            supportingText,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}
