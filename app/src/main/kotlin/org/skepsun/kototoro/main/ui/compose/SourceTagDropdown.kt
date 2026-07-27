package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.CompactTopBarCompactButtonSize
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.explore.ui.model.SourceTag

private val CompactSourceTagIconSize = 18.dp

/**
 * Icon button + dropdown menu for selecting a single [SourceTag].
 *
 * The icon tint changes to primary when a tag is actively selected,
 * and shows the specific tag's icon when exactly one is selected.
 */
@Composable
fun SourceTagDropdown(
    selectedTags: Set<SourceTag>,
    entries: List<SourceTag> = SourceTag.quickFilterEntries,
    enabledTags: Set<SourceTag> = entries.toSet(),
    onButtonClickIntercept: (android.view.View?) -> Boolean = { false },
    onTagSelected: (SourceTag?) -> Unit,
    modifier: Modifier = Modifier,
    buttonSize: Dp = CompactTopBarCompactButtonSize,
    iconSize: Dp = CompactSourceTagIconSize,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }

    val iconRes = if (selectedTags.size == 1) {
        selectedTags.first().iconRes
    } else {
        R.drawable.ic_filter_menu
    }
    val tint = if (selectedTags.isNotEmpty()) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    val sortedEntries = remember(entries, selectedTags) {
        entries.sortedBy { tag -> tag !in selectedTags }
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .onGloballyPositioned { anchorBounds = it.boundsInRoot() },
    ) {
        IconButton(
            onClick = {
                if (!onButtonClickIntercept(null)) {
                    expanded = true
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                painter = rememberSafePainter(iconRes),
                contentDescription = stringResource(R.string.filter),
                modifier = Modifier.size(iconSize),
                tint = tint,
            )
        }
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = 0.dp, y = 4.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            anchorBounds = anchorBounds,
        ) {
            CompactDropdownMenuItem(
                text = { CompactDropdownMenuText(stringResource(R.string.all)) },
                onClick = {
                    expanded = false
                    onTagSelected(null)
                },
                leadingIcon = {
                    Checkbox(
                        checked = selectedTags.isEmpty(),
                        onCheckedChange = null,
                        modifier = Modifier.size(24.dp),
                    )
                },
            )

            sortedEntries.forEach { tag ->
                CompactDropdownMenuItem(
                    text = { CompactDropdownMenuText(stringResource(tag.titleRes)) },
                    onClick = {
                        expanded = false
                        onTagSelected(tag)
                    },
                    enabled = tag in enabledTags,
                    leadingIcon = {
                        Icon(
                            painter = rememberSafePainter(tag.iconRes),
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = if (tag in selectedTags) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    },
                    trailingIcon = {
                        if (tag in selectedTags) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
        }
    }
}
