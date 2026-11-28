package org.skepsun.kototoro.local.ui.compose

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.titleResId
import org.skepsun.kototoro.list.ui.compose.SortOrderControl
import org.skepsun.kototoro.local.domain.model.LocalReadStatusFilter
import org.skepsun.kototoro.local.ui.LocalLibrarySortOrders

/**
 * 本地漫画库的排序与筛选条。
 *
 * 排序复用列表页共享的 [SortOrderControl]（紧凑形态：图标 + 下拉菜单，菜单里带升降序切换），
 * 因此"添加时间"走的就是本地文件的创建时间；筛选是阅读状态（全部/未读/在读/已读完）。
 */
@Composable
internal fun LocalLibraryToolbar(
    sortOrder: ListSortOrder,
    onSortOrderSelected: (ListSortOrder) -> Unit,
    readStatusFilter: LocalReadStatusFilter,
    onReadStatusFilterSelected: (LocalReadStatusFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SortOrderControl(
                sortOrders = LocalLibrarySortOrders,
                selectedSortOrder = sortOrder,
                onSortOrderSelected = onSortOrderSelected,
                compact = true,
            )
            Text(
                text = stringResource(sortOrder.titleResId),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LocalReadStatusFilter.entries.forEach { filter ->
                FilterChip(
                    selected = filter == readStatusFilter,
                    onClick = { onReadStatusFilterSelected(filter) },
                    label = {
                        Text(
                            text = stringResource(filter.titleResId),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                )
            }
        }
    }
}
