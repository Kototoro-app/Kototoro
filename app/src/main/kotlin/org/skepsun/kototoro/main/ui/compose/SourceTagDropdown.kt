package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.compose.CompactTopBarCompactButtonSize
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.compose.rememberSafePainter
import org.skepsun.kototoro.core.ui.adaptive.tvDownFocusLink
import org.skepsun.kototoro.core.ui.glass.GlassDefaults
import org.skepsun.kototoro.core.ui.topbar.SourceTypeMenuEntry
import org.skepsun.kototoro.core.ui.topbar.SourceTypeMenuItems
import org.skepsun.kototoro.core.ui.topbar.TopBarIconAction
import org.skepsun.kototoro.core.prefs.InterfaceStyle
import org.skepsun.kototoro.core.ui.theme.LocalInterfaceStyle
import org.skepsun.kototoro.explore.ui.model.SourceTag
import org.skepsun.kototoro.explore.ui.model.titleRes
import org.skepsun.kototoro.explore.ui.model.iconRes

private val CompactSourceTagIconSize = 18.dp

/**
 * Icon button + dropdown menu for selecting a single [SourceTag].
 *
 * The icon tint changes to primary when a tag is actively selected,
 * and shows the specific tag's icon when exactly one is selected.
 *
 * When [customMenuContent] is provided (e.g. the favourites page's combined
 * filter panel), it replaces the default single-tag menu. The receiver's
 * [close] parameter dismisses the popup, keeping the anchor button's press
 * gloss (via `anchorTapThrough`).
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
    /**
     * TV only: the first stop of the page below the bar, so DOWN leaves the chrome instead of falling
     * through to the navigation rail. It has to land on the focusable button, not [modifier]'s Box.
     */
    downFocusLink: FocusRequester? = null,
    customMenuContent: (@Composable ((close: () -> Unit) -> Unit))? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    var anchorBounds by remember { mutableStateOf<Rect?>(null) }
    // Back the ring up onto this button when the menu closes: the popup owns the focus while it is
    // open, and without this the remote lands on the navigation rail after a BACK.
    val anchorFocus = remember { FocusRequester() }

    val iconRes = if (selectedTags.size == 1) {
        selectedTags.first().iconRes
    } else {
        R.drawable.ic_filter_menu
    }
    val sortedEntries = remember(entries, selectedTags) {
        SourceTag.menuOrder(entries, selectedTags)
    }

    Box(
        modifier = modifier
            .size(buttonSize)
            .onGloballyPositioned { anchorBounds = it.boundsInRoot() },
    ) {
        TopBarIconAction(
            icon = rememberSafePainter(iconRes),
            contentDescription = stringResource(R.string.filter),
            onClick = {
                if (!onButtonClickIntercept(null)) {
                    // Toggle: with anchorTapThrough below, the tap that closes
                    // the menu lands here too, keeping the pill's press gloss.
                    expanded = !expanded
                }
            },
            modifier = Modifier
                .focusRequester(anchorFocus)
                .tvDownFocusLink(downFocusLink),
            active = selectedTags.isNotEmpty(),
            size = buttonSize,
            iconSize = iconSize,
        )
        GlassDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(x = 0.dp, y = 4.dp),
            style = GlassDefaults.subtleStyle(),
            useRootOverlay = LocalInterfaceStyle.current == InterfaceStyle.IOS,
            // Keep the pill's press gloss when this button dismisses the menu.
            anchorTapThrough = true,
            anchorBounds = anchorBounds,
            anchorFocusRequester = anchorFocus,
        ) {
            if (customMenuContent != null) {
                val close = { expanded = false }
                customMenuContent(close)
            } else {
                SourceTypeMenuItems(
                    allLabel = stringResource(R.string.all),
                    entries = sortedEntries.map { tag ->
                        SourceTypeMenuEntry(
                            key = tag,
                            title = stringResource(tag.titleRes),
                            icon = rememberSafePainter(tag.iconRes),
                            selected = tag in selectedTags,
                            enabled = tag in enabledTags,
                        )
                    },
                    onSelect = { tag ->
                        expanded = false
                        onTagSelected(tag)
                    },
                )
            }
        }
    }
}
