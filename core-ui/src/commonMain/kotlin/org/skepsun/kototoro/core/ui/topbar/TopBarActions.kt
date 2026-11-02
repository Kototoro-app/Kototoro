package org.skepsun.kototoro.core.ui.topbar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.reader.ui.compose.design.ReaderPanelIcons

/*
 * The buttons and menu rows of the action capsule at the right of Android's top bar (source type, more). The capsule
 * surface, the popup that hosts the menu and focus hand-off stay with each host; these draw what sits inside them.
 */

/**
 * One icon button of the top-bar action capsule. [active] tints the icon with the primary color, as a filter does
 * while it narrows the page; [badge] adds the small error dot Android puts on "more" when an update waits.
 */
@Composable
fun TopBarIconAction(
    icon: Painter,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
    badge: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    iconSize: Dp = 20.dp,
    inactiveTint: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    focusShape: Shape = RoundedCornerShape(12.dp),
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .size(size)
            .tvFocusable(enabled = enabled, shape = focusShape, addFocusTarget = false),
        colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Transparent),
    ) {
        Box {
            Icon(
                painter = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(iconSize),
                tint = if (active) MaterialTheme.colorScheme.primary else inactiveTint,
            )
            if (badge) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .size(8.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(percent = 50)),
                )
            }
        }
    }
}

/** One source-type row of [SourceTypeMenuItems]; [key] is the host's own value, handed back on click. */
@Immutable
data class SourceTypeMenuEntry<K>(
    val key: K,
    val title: String,
    val icon: Painter,
    val selected: Boolean,
    val enabled: Boolean = true,
)

/**
 * Android's source-type menu: an "all" row with a checkbox that is ticked while nothing narrows the page, then one
 * row per source type with its icon (primary while selected) and a trailing check. Each pick closes the menu on
 * Android, which the host does in [onSelect].
 */
@Composable
fun <K> SourceTypeMenuItems(
    allLabel: String,
    entries: List<SourceTypeMenuEntry<K>>,
    onSelect: (K?) -> Unit,
    allModifier: Modifier = Modifier,
    entryModifier: (K) -> Modifier = { Modifier },
) {
    CompactDropdownMenuItem(
        text = { CompactDropdownMenuText(allLabel) },
        onClick = { onSelect(null) },
        modifier = allModifier,
        leadingIcon = {
            Checkbox(
                checked = entries.none { it.selected },
                onCheckedChange = null,
                modifier = Modifier.size(24.dp),
            )
        },
    )
    entries.forEach { entry ->
        CompactDropdownMenuItem(
            text = { CompactDropdownMenuText(entry.title) },
            onClick = { onSelect(entry.key) },
            modifier = entryModifier(entry.key),
            enabled = entry.enabled,
            leadingIcon = {
                Icon(
                    painter = entry.icon,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = if (entry.selected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            },
            trailingIcon = {
                if (entry.selected) {
                    Icon(
                        painter = rememberVectorPainter(ReaderPanelIcons.Check),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            },
        )
    }
}
