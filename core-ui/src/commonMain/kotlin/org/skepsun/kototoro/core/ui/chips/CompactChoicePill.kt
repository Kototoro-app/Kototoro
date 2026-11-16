package org.skepsun.kototoro.core.ui.chips

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.LocalUiPresentationConfig
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable
import org.skepsun.kototoro.list.ui.compose.displayOptionToggleColors

enum class ChoicePillPosition { SINGLE, FIRST, MIDDLE, LAST }

fun choicePillPosition(index: Int, count: Int): ChoicePillPosition = when {
    count <= 1 -> ChoicePillPosition.SINGLE
    index == 0 -> ChoicePillPosition.FIRST
    index == count - 1 -> ChoicePillPosition.LAST
    else -> ChoicePillPosition.MIDDLE
}

/** The display-options toggle treatment, sized for text choices in a browsing rail. */
@Composable
fun CompactChoicePill(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: ChoicePillPosition = ChoicePillPosition.SINGLE,
    dense: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
) = CompactChoicePill(
    selected = selected, onClick = onClick, modifier = modifier, enabled = enabled,
    position = position, dense = dense, leadingIcon = leadingIcon, trailingIcon = trailingIcon,
) {
    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Slot form for existing option labels and facet buttons; selection semantics stay compatible with FilterChip. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CompactChoicePill(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    position: ChoicePillPosition = ChoicePillPosition.SINGLE,
    dense: Boolean = false,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    val isTv = LocalUiPresentationConfig.current.isTv
    val isSelected = selected
    val height = if (isTv) 48.dp else if (dense) 28.dp else 32.dp
    val textStyle = when {
        isTv -> MaterialTheme.typography.titleSmall
        dense -> MaterialTheme.typography.labelSmall
        else -> MaterialTheme.typography.labelMedium
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides if (isTv) 48.dp else height + 4.dp) {
        ToggleButton(
            checked = selected,
            onCheckedChange = { onClick() },
            enabled = enabled,
            shapes = when (position) {
                ChoicePillPosition.SINGLE -> ToggleButtonShapes(
                    RoundedCornerShape(10.dp), RoundedCornerShape(8.dp), CircleShape,
                )
                ChoicePillPosition.FIRST -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                ChoicePillPosition.MIDDLE -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                ChoicePillPosition.LAST -> ButtonGroupDefaults.connectedTrailingButtonShapes()
            },
            colors = displayOptionToggleColors(),
            contentPadding = PaddingValues(horizontal = if (dense) 8.dp else 10.dp, vertical = 0.dp),
            modifier = modifier.height(height).semantics { this.selected = isSelected }
                .tvFocusable(shape = CircleShape, addFocusTarget = false),
        ) {
            leadingIcon?.let { it(); Spacer(Modifier.width(4.dp)) }
            ProvideTextStyle(textStyle.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)) {
                label()
            }
            trailingIcon?.let { Spacer(Modifier.width(4.dp)); it() }
        }
    }
}
