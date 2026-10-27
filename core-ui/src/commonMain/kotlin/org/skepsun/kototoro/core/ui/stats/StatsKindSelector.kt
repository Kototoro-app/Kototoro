package org.skepsun.kototoro.core.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * One choice of [StatsKindSelector]. The key is the host's own enum name, so a caller projects its content-type enum in
 * and maps the picked key back; `core-ui` deliberately does not depend on the statistics domain.
 */
data class StatsKindOption(val key: String, val label: String)

/**
 * Android's `ContentKindSelector`: a single segmented pill — one capsule per content type inside a tonal capsule, the
 * selected one filled with the primary colour. Android and Windows each drew their own copy of this control around the
 * same statistics screen, so it lives here now and both hosts project their `StatsContentKind` into it.
 *
 * The hosts still own what makes their copy theirs: Android passes a taller pill and its TV focus ring through
 * [optionModifier], Windows tags every pill and disables the whole row while an action is running. Labels arrive
 * pre-formatted so each host keeps reading its own string catalog.
 */
@Composable
fun StatsKindSelector(
    options: List<StatsKindOption>,
    selectedKey: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pillHeight: Dp = 36.dp,
    horizontalPadding: Dp = 13.dp,
    optionModifier: @Composable (option: StatsKindOption, selected: Boolean) -> Modifier = { _, _ -> Modifier },
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier.padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            options.forEach { option ->
                val selected = option.key == selectedKey
                Box(
                    modifier = optionModifier(option, selected)
                        .clip(RoundedCornerShape(percent = 50))
                        .height(pillHeight)
                        .background(if (selected) MaterialTheme.colorScheme.primary else Color.Transparent)
                        .clickable(enabled = enabled) { onSelect(option.key) }
                        .padding(horizontal = horizontalPadding),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = if (selected) {
                            MaterialTheme.colorScheme.onPrimary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }
        }
    }
}
