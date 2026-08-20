package org.skepsun.kototoro.home.ui.compose.sections

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.glass.GlassSurface
import org.skepsun.kototoro.core.ui.theme.LocalMaterialExpressiveComponentsEnabled
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle


@Composable
internal fun QuickActionsSection(
    actions: List<HomeQuickAction>,
    modifier: Modifier = Modifier,
) {
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    Column(
        modifier = modifier
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.quick_access),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val itemSpacing = 8.dp
            val preferredItemWidth = 68.dp
            val columns = ((maxWidth + itemSpacing) / (preferredItemWidth + itemSpacing))
                .toInt()
                .coerceAtLeast(2)
            val itemWidth = (maxWidth - itemSpacing * (columns - 1)) / columns
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(itemSpacing),
                verticalArrangement = if (isIosStyle) {
                    Arrangement.spacedBy(7.dp, Alignment.CenterVertically)
                } else {
                    Arrangement.spacedBy(8.dp)
                },
                maxItemsInEachRow = columns,
            ) {
                actions.forEachIndexed { index, action ->
                    QuickAccessButton(
                        action = action,
                        paletteIndex = index,
                        modifier = Modifier.size(width = itemWidth, height = 64.dp),
                    )
                }
            }
        }
    }
}

internal data class HomeQuickAction(
    val label: String,
    val iconRes: Int,
    val onClick: () -> Unit,
    val enabled: Boolean = true,
)

@Composable
private fun QuickAccessButton(
    action: HomeQuickAction,
    paletteIndex: Int,
    modifier: Modifier = Modifier,
) {
    val expressive = LocalMaterialExpressiveComponentsEnabled.current
    val isIosStyle = LocalInterfaceStyle.current == InterfaceStyle.IOS
    val containerColor = when {
        !expressive -> MaterialTheme.colorScheme.surfaceContainerLow
        paletteIndex % 3 == 0 -> MaterialTheme.colorScheme.secondaryContainer
        paletteIndex % 3 == 1 -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val expressiveContentColor = when (paletteIndex % 3) {
        0 -> MaterialTheme.colorScheme.onSecondaryContainer
        1 -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
    val textColor = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isIosStyle || !expressive -> MaterialTheme.colorScheme.onSurface
        else -> expressiveContentColor
    }
    val iconTint = when {
        !action.enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
        isIosStyle -> MaterialTheme.colorScheme.primary
        !expressive -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> expressiveContentColor
    }
    val shape = RoundedCornerShape(if (expressive) 20.dp else 16.dp)
    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clickable(enabled = action.enabled, onClick = action.onClick)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            HomeQuickActionIcon(
                iconRes = action.iconRes,
                tint = iconTint,
                modifier = Modifier.size(if (expressive) 20.dp else 18.dp),
            )
            Text(
                text = action.label,
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = textColor,
            )
        }
    }
    if (isIosStyle) {
        GlassSurface(
            modifier = modifier,
            shape = shape,
            style = GlassDefaults.subtleStyle(),
        ) {
            content()
        }
    } else {
        Surface(
            modifier = modifier,
            shape = shape,
            color = containerColor,
            tonalElevation = if (expressive) 0.dp else 1.dp,
        ) {
            content()
        }
    }
}

@Composable
private fun HomeQuickActionIcon(
    iconRes: Int,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    Icon(
        painter = painterResource(iconRes),
        contentDescription = null,
        modifier = modifier,
        tint = tint,
    )
}

