package org.skepsun.kototoro.discover.ui.category

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.skepsun.kototoro.R
import org.skepsun.kototoro.discover.ui.compose.DiscoverScreen
import org.skepsun.kototoro.list.ui.compose.ContentCardUiPrefs
import org.skepsun.kototoro.list.ui.model.ContentListModel
import org.skepsun.kototoro.list.ui.model.ListModel
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable
fun DiscoverCategoryScreen(
    items: List<ListModel>,
    isRefreshing: Boolean,
    isDateDriven: Boolean,
    selectedCalendarDateMillis: Long?,
    service: ScrobblerService,
    onRefresh: () -> Unit,
    onLoadMore: () -> Unit,
    onItemClick: (ContentListModel, Rect?, String?) -> Unit,
    onDateClick: () -> Unit,
    onTodayClick: () -> Unit,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    gridSpanCount: Int = 3,
) {
    Column(
        modifier = modifier.background(MaterialTheme.colorScheme.surface),
    ) {
        val cardUiPrefs = remember {
            ContentCardUiPrefs(
                badgesTopLeft = setOf("tracker"),
                badgesTopRight = setOf("score"),
                badgesBottomLeft = emptySet(),
                badgesBottomRight = setOf("nsfw"),
            )
        }
        if (isDateDriven) {
            DiscoverCategoryCalendarBar(
                selectedCalendarDateMillis = selectedCalendarDateMillis,
                onDateClick = onDateClick,
                onTodayClick = onTodayClick,
                onDayClick = onDayClick,
            )
        }
        DiscoverScreen(
            items = items,
            isRefreshing = isRefreshing,
            isCarousel = false,
            isLoadingOnly = items.size <= 1 && items.any { it is LoadingState },
            activeService = service,
            availableServices = emptyList(),
            onRefresh = onRefresh,
            onLoadMore = onLoadMore,
            onItemClick = onItemClick,
            onCategoryMoreClick = {},
            contentPadding = PaddingValues(0.dp),
            gridSpanCount = gridSpanCount,
            cardUiPrefsOverride = cardUiPrefs,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun DiscoverCategoryCalendarBar(
    selectedCalendarDateMillis: Long?,
    onDateClick: () -> Unit,
    onTodayClick: () -> Unit,
    onDayClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedDate = remember(selectedCalendarDateMillis) {
        selectedCalendarDateMillis
            ?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val dateText = selectedDate
        ?.let { DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).format(it) }
        ?: stringResource(R.string.select_date)
    val days = listOf(
        1 to R.string.day_monday,
        2 to R.string.day_tuesday,
        3 to R.string.day_wednesday,
        4 to R.string.day_thursday,
        5 to R.string.day_friday,
        6 to R.string.day_saturday,
        7 to R.string.day_sunday,
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(top = 8.dp, bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onDateClick,
                modifier = Modifier
                    .weight(1f)
                    .widthIn(min = 0.dp),
            ) {
                Text(dateText)
            }
            TextButton(onClick = onTodayClick) {
                Text(stringResource(R.string.today))
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            days.forEach { (day, labelResId) ->
                FilterChip(
                    selected = selectedDate?.dayOfWeek?.value == day,
                    onClick = { onDayClick(day) },
                    label = { Text(stringResource(labelResId)) },
                )
            }
        }
    }
}
