package org.skepsun.kototoro.core.ui.chips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A small option set laid out as wrapping chips.
 *
 * Android's filter sheet already shows its own controls this way (`FilterSheetRoute`'s compact chip flow), because a
 * chip is one click where a menu is two; the same row also serves the Windows filter dialog, whose 580dp sheet fits
 * every short option at once.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChoiceChipFlow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 6.dp,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing),
        content = content,
    )
}

/** One chip of [ChoiceChipFlow]: Android's 36dp height and label style, so a shared row looks the same on both hosts. */
@Composable
fun ChoiceChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    trailingIcon: (@Composable () -> Unit)? = null,
    label: @Composable () -> Unit,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.heightIn(min = 36.dp),
        enabled = enabled,
        label = {
            ProvideTextStyle(MaterialTheme.typography.labelLarge) { label() }
        },
        trailingIcon = trailingIcon,
    )
}
