package org.skepsun.kototoro.main.ui.compose

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/*
 * The compact menu rows of Android's top bar and glass menus. They hold no menu surface of their own, so Android
 * places them in its glass popup and the desktop host in its plain Material menu.
 */

/** Menu rows grow on TV so a label stays readable from across the room. */
private const val TV_MENU_ITEM_HEIGHT_DP = 56

@Composable
fun CompactDropdownMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    leadingIcon: (@Composable (() -> Unit))? = null,
    trailingIcon: (@Composable (() -> Unit))? = null,
) {
    val isTvPresentation = LocalUiPresentationConfig.current.isTv
    CompositionLocalProvider(
        LocalTextStyle provides if (isTvPresentation) {
            MaterialTheme.typography.titleMedium
        } else {
            MaterialTheme.typography.labelLarge
        },
    ) {
        Row(
            modifier = modifier
                .height(if (isTvPresentation) TV_MENU_ITEM_HEIGHT_DP.dp else 48.dp)
                .fillMaxWidth()
                .tvFocusable(
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    addFocusTarget = false,
                )
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
fun CompactDropdownMenuText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier,
        // Item and header labels share this helper, so the TV scale lives here as well; heads-up
        // section labels inside a menu would otherwise stay phone-sized next to body-sized items.
        style = if (LocalUiPresentationConfig.current.isTv) {
            MaterialTheme.typography.titleMedium
        } else {
            MaterialTheme.typography.labelLarge
        },
    )
}

@Composable
fun CompactDropdownMenuDivider(
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .width(96.dp)
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.32f)),
    )
}
