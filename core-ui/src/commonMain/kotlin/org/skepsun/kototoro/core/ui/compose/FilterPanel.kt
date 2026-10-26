package org.skepsun.kototoro.core.ui.compose

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.core.ui.adaptive.TvTextButton
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

/*
 * The filter panel vocabulary of Android's favourites page (`FavoritesFilterPanelContent`): titled group cards,
 * compact chips, facet dropdowns for long option sets, switch rows and a centred reset. Hosts bring the options,
 * labels, icons and what a click means; nothing here knows about list filters, sources or settings.
 */

/** Ring shape for the chips and dropdown rows of a filter panel; M3 draws nothing of its own for their focus. */
private val FilterControlRingShape = RoundedCornerShape(8.dp)

/** A titled card holding one filter group. */
@Composable
fun FilterPanelGroup(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            content()
        }
    }
}

/** The panel title with its "done" action, followed by the same faint rule Android draws under it. */
@Composable
fun FilterPanelHeader(
    title: String,
    doneLabel: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    doneModifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TvTextButton(onClick = onDone, modifier = doneModifier) {
                Text(doneLabel)
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    }
}

/** A whole-row switch inside a [FilterPanelGroup]. */
@Composable
fun FilterPanelSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .heightIn(min = 44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

/** The centred reset action at the foot of a filter panel. */
@Composable
fun FilterPanelReset(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier = Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
        TvTextButton(onClick = onClick, modifier = modifier, enabled = enabled) {
            Text(label)
        }
    }
}

/** Wrapping row of [CompactFilterChip]s with the panel's 4dp spacing. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompactFilterChipFlow(
    modifier: Modifier = Modifier,
    content: @Composable FlowRowScope.() -> Unit,
) {
    FlowRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** Android's 28dp filter chip: a panel lists many options, so the chips stay dense. */
@Composable
fun CompactFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 28.dp) {
        FilterChip(
            selected = selected,
            onClick = onClick,
            enabled = enabled,
            modifier = modifier
                .heightIn(min = 28.dp)
                .tvFocusable(enabled = enabled, shape = FilterControlRingShape, addFocusTarget = false),
            leadingIcon = icon,
            label = {
                Text(text = label, style = MaterialTheme.typography.labelSmall)
            },
        )
    }
}

/**
 * The full-width chip that stands for a whole facet (sources, tags): it reads the group title, the single chosen
 * value, or "title · n", and opens whatever picker the host attaches.
 */
@Composable
fun FilterFacetChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        modifier = modifier
            .fillMaxWidth()
            .tvFocusable(enabled = enabled, shape = FilterControlRingShape, addFocusTarget = false),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        label = {
            Text(text = label, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        },
    )
}

/** One option of a [FilterFacetDropdown]. [key] is the host's own value, handed back on click. */
@Immutable
data class FilterFacetOption<K>(
    val key: K,
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
)

/**
 * A [FilterFacetChip] that opens a multi-select menu. Picking an option keeps the menu open, as on Android, so several
 * values can be toggled in one visit; the reset row clears every checked option of this facet.
 */
@Composable
fun <K> FilterFacetDropdown(
    label: String,
    options: List<FilterFacetOption<K>>,
    onOptionClick: (K) -> Unit,
    resetLabel: String,
    onReset: () -> Unit,
    optionIcon: @Composable (FilterFacetOption<K>) -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    menuContainerColor: Color = MenuDefaults.containerColor,
    optionModifier: (K) -> Modifier = { Modifier },
    resetModifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val anyChecked = options.any { it.checked }
    Box {
        FilterFacetChip(
            label = label,
            selected = anyChecked,
            onClick = { expanded = true },
            modifier = modifier,
            leadingIcon = leadingIcon,
            trailingIcon = trailingIcon,
            enabled = enabled && options.isNotEmpty(),
        )
        DropdownMenu(
            containerColor = menuContainerColor,
            expanded = expanded && enabled,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { option ->
                TvDropdownMenuItem(
                    text = { Text(text = option.label, maxLines = 1) },
                    onClick = { onOptionClick(option.key) },
                    modifier = optionModifier(option.key),
                    enabled = option.enabled,
                    leadingIcon = { optionIcon(option) },
                )
            }
            HorizontalDivider()
            TvDropdownMenuItem(
                text = { Text(resetLabel) },
                onClick = onReset,
                modifier = resetModifier,
                enabled = anyChecked,
            )
        }
    }
}
