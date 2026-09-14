package org.skepsun.kototoro.list.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu

@Composable
internal fun SortOrderControl(
    sortOrders: List<ListSortOrder>,
    selectedSortOrder: ListSortOrder?,
    onSortOrderSelected: (ListSortOrder) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val options = remember(sortOrders) { listSortOptions(sortOrders) }
    val supportsDirection = options.any { it.ascending != null && it.descending != null }
    val selected = options.firstOrNull { it.contains(selectedSortOrder) } ?: options.firstOrNull() ?: return
    val selectedOrder = selectedSortOrder?.takeIf { selected.contains(it) } ?: selected.orderFor(true)
    val descending = selected.descending == selectedOrder
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(selected.titleResId)
    val selectOrder: (ListSortOrder) -> Unit = { order ->
        onSortOrderSelected(order)
        if (compact) expanded = false
    }

    Column(modifier = modifier) {
        Box {
            if (compact) {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = "${stringResource(R.string.sort_order)}: $label, " +
                            stringResource(if (descending) R.string.sort_order_desc else R.string.sort_order_asc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                AssistChip(
                    onClick = { expanded = true },
                    label = { Text(label, maxLines = 1) },
                    leadingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_sort),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    trailingIcon = {
                        Icon(
                            painter = painterResource(R.drawable.ic_expand_more),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = AssistChipDefaults.assistChipColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.72f),
                        labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        trailingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                )
            }
            GlassDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                if (compact) {
                    Text(
                        text = stringResource(R.string.sort_order),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    if (supportsDirection) {
                        SortDirectionControl(
                            option = selected,
                            selectedOrder = selectedOrder,
                            onSortOrderSelected = selectOrder,
                            modifier = Modifier.padding(horizontal = 12.dp),
                        )
                    }
                    HorizontalDivider()
                }
                options.forEach { option ->
                    CompactDropdownMenuItem(
                        text = { CompactDropdownMenuText(stringResource(option.titleResId)) },
                        onClick = {
                            selectOrder(option.orderFor(descending))
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(
                                    if (option == selected) R.drawable.ic_check else R.drawable.ic_sort,
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                            )
                        },
                    )
                }
            }
        }
        if (!compact && supportsDirection) {
            SortDirectionControl(selected, selectedOrder, selectOrder)
        }
    }
}

@Composable
private fun SortDirectionControl(
    option: ListSortOption,
    selectedOrder: ListSortOrder,
    onSortOrderSelected: (ListSortOrder) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = option.ascending == selectedOrder,
            onClick = { option.ascending?.let(onSortOrderSelected) },
            enabled = option.ascending != null,
            label = { Text(stringResource(R.string.sort_order_asc)) },
        )
        FilterChip(
            selected = option.descending == selectedOrder,
            onClick = { option.descending?.let(onSortOrderSelected) },
            enabled = option.descending != null,
            label = { Text(stringResource(R.string.sort_order_desc)) },
        )
    }
}
