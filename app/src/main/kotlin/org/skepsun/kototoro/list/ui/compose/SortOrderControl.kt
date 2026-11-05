package org.skepsun.kototoro.list.ui.compose

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.ToggleButtonDefaults
import androidx.compose.material3.ToggleButtonShapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.titleResId
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuItem
import org.skepsun.kototoro.main.ui.compose.CompactDropdownMenuText
import org.skepsun.kototoro.main.ui.compose.GlassDropdownMenu
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import org.skepsun.kototoro.core.ui.adaptive.tvFocusable

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun SortOrderControl(
    sortOrders: List<ListSortOrder>,
    selectedSortOrder: ListSortOrder?,
    onSortOrderSelected: (ListSortOrder) -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    onReorderClick: (() -> Unit)? = null,
    focusRequester: FocusRequester? = null,
) {
    val focusModifier = if (focusRequester == null) Modifier else Modifier.focusRequester(focusRequester)
    val options = remember(sortOrders) { listSortOptions(sortOrders) }
    val supportsDirection = options.any { it.ascending != null && it.descending != null }
    val selected = options.firstOrNull { it.contains(selectedSortOrder) } ?: options.firstOrNull() ?: return
    val selectedOrder = selectedSortOrder?.takeIf { selected.contains(it) } ?: selected.orderFor(true)
    val descending = selected.descending == selectedOrder
    var expanded by remember { mutableStateOf(false) }
    val label = stringResource(selected.titleResId)
    val optionFocus = remember(options) { options.associateWith { FocusRequester() } }
    LaunchedEffect(expanded) {
        if (!expanded) return@LaunchedEffect
        // Material3 puts the focus on the first row; on a remote that reads as "the sort order
        // jumped", so keep retrying until the currently applied option takes it instead.
        val target = optionFocus[selected] ?: return@LaunchedEffect
        repeat(SortMenuFocusAttempts) {
            withFrameNanos { }
            if (runCatching { target.requestFocus() }.isSuccess) return@LaunchedEffect
        }
    }
    val selectOrder: (ListSortOrder) -> Unit = { order ->
        onSortOrderSelected(order)
        if (compact) expanded = false
    }

    // The sheet form keeps the direction buttons on the dropdown's row, so sorting costs one line.
    Row(modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else ButtonGroupDefaults.ConnectedSpaceBetween),
        verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = if (compact) Modifier else Modifier.weight(1f)) {
            if (compact) {
                IconButton(
                    onClick = { expanded = true },
                    modifier = Modifier
                        .size(32.dp)
                        .then(focusModifier)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = "${stringResource(R.string.sort_order)}: $label, " +
                            stringResource(if (descending) R.string.sort_order_desc else R.string.sort_order_asc),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            } else {
                // The sheet form: the leading button of a connected group whose other buttons are the directions.
                ToggleButton(
                    checked = false,
                    onCheckedChange = { expanded = true },
                    shapes = if (supportsDirection) {
                        ButtonGroupDefaults.connectedLeadingButtonShapes()
                    } else {
                        ToggleButtonShapes(CircleShape, CircleShape, CircleShape)
                    },
                    colors = displayOptionToggleColors(),
                    contentPadding = PaddingValues(horizontal = 12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(focusModifier)
                        .tvFocusable(shape = CircleShape, addFocusTarget = false),
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_sort),
                        contentDescription = null,
                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                    )
                    Text(
                        text = label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = ToggleButtonDefaults.IconSpacing),
                    )
                    Icon(
                        painter = painterResource(R.drawable.ic_expand_more),
                        contentDescription = null,
                        modifier = Modifier.size(ToggleButtonDefaults.IconSize),
                    )
                }
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
                    val isManual = option.contains(ListSortOrder.MANUAL)
                    CompactDropdownMenuItem(
                        text = {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                CompactDropdownMenuText(stringResource(option.titleResId))
                                if (isManual && onReorderClick != null) {
                                    Spacer(modifier = Modifier.width(16.dp))
                                    Icon(
                                        painter = painterResource(R.drawable.ic_reorder_handle),
                                        contentDescription = stringResource(R.string.reorder),
                                        tint = if (option == selected) {
                                            MaterialTheme.colorScheme.primary
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                        modifier = Modifier.size(16.dp),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.focusRequester(optionFocus.getValue(option)),
                        onClick = {
                            val targetOrder = option.orderFor(descending)
                            selectOrder(targetOrder)
                            if (targetOrder == ListSortOrder.MANUAL && onReorderClick != null) {
                                expanded = false
                                onReorderClick()
                            }
                        },
                        leadingIcon = {
                            Icon(
                                painter = painterResource(
                                    if (option == selected) R.drawable.ic_check else R.drawable.ic_sort,
                                ),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        },
                    )
                }
            }
        }
        if (!compact && supportsDirection) {
            SortDirectionButtons(selected, selectedOrder, selectOrder)
        }
    }
}

/**
 * The direction half of the sheet's connected sort group, styled like the display mode buttons: the applied direction
 * is filled and shows its label, the other shows only its icon.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun SortDirectionButtons(
    option: ListSortOption,
    selectedOrder: ListSortOrder,
    onSortOrderSelected: (ListSortOrder) -> Unit,
) {
    val directions = listOf(
        Triple(option.ascending, R.drawable.ic_sort_asc, R.string.sort_order_asc),
        Triple(option.descending, R.drawable.ic_sort_desc, R.string.sort_order_desc),
    )
    directions.forEachIndexed { index, (order, iconRes, textRes) ->
        val checked = order == selectedOrder
        val text = stringResource(textRes)
        ToggleButton(
            checked = checked,
            onCheckedChange = { order?.let(onSortOrderSelected) },
            enabled = order != null,
            shapes = if (index == 0) {
                ButtonGroupDefaults.connectedMiddleButtonShapes()
            } else {
                ButtonGroupDefaults.connectedTrailingButtonShapes()
            },
            colors = displayOptionToggleColors(),
            contentPadding = PaddingValues(horizontal = 12.dp),
            modifier = Modifier
                .semantics { contentDescription = text }
                .tvFocusable(enabled = order != null, shape = CircleShape, addFocusTarget = false),
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(ToggleButtonDefaults.IconSize),
            )
            AnimatedVisibility(
                visible = checked,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut(),
            ) {
                Text(
                    text = text,
                    maxLines = 1,
                    modifier = Modifier.padding(start = ToggleButtonDefaults.IconSpacing),
                )
            }
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
            modifier = Modifier.tvFocusable(
                enabled = option.ascending != null,
                shape = CircleShape,
                addFocusTarget = false,
            ),
        )
        FilterChip(
            selected = option.descending == selectedOrder,
            onClick = { option.descending?.let(onSortOrderSelected) },
            enabled = option.descending != null,
            label = { Text(stringResource(R.string.sort_order_desc)) },
            modifier = Modifier.tvFocusable(
                enabled = option.descending != null,
                shape = CircleShape,
                addFocusTarget = false,
            ),
        )
    }
}

private const val SortMenuFocusAttempts = 20
