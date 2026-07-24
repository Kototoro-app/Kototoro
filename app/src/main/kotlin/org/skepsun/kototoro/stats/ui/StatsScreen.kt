package org.skepsun.kototoro.stats.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.model.FavouriteCategory
import org.skepsun.kototoro.core.util.KototoroColors
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.stats.domain.StatsPeriod
import org.skepsun.kototoro.stats.domain.StatsRecord
import org.skepsun.kototoro.stats.ui.views.PieChartView

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StatsScreen(
    period: StatsPeriod,
    categories: List<FavouriteCategory>,
    selectedCategoryIds: Set<Long>,
    stats: List<StatsRecord>,
    isLoading: Boolean,
    onNavigateUp: () -> Unit,
    onPeriodSelected: (StatsPeriod) -> Unit,
    onCategoryChecked: (FavouriteCategory, Boolean) -> Unit,
    onClearStats: () -> Unit,
    onContentClick: (Content) -> Unit,
) {
    var periodMenuExpanded by remember { mutableStateOf(false) }
    var showClearDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val totalDuration = stats.sumOf { it.duration }
    val chartData = if (totalDuration > 0L) {
        stats.map { record ->
            PieChartView.Segment(
                value = (record.duration / 1000).toInt(),
                label = record.manga?.title ?: context.getString(R.string.other_manga),
                percent = record.duration.toFloat() / totalDuration.toFloat(),
                color = KototoroColors.ofContent(context, record.manga),
                tag = record.manga,
            )
        }
    } else {
        emptyList()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(text = context.getString(R.string.reading_stats)) },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { showClearDialog = true }) {
                        Icon(Icons.Outlined.Delete, contentDescription = context.getString(R.string.clear_stats))
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box {
                    FilterChip(
                        selected = false,
                        onClick = { periodMenuExpanded = true },
                        label = { Text(context.getString(period.titleResId)) },
                    )
                    DropdownMenu(
                        expanded = periodMenuExpanded,
                        onDismissRequest = { periodMenuExpanded = false },
                    ) {
                        StatsPeriod.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(context.getString(option.titleResId)) },
                                onClick = {
                                    periodMenuExpanded = false
                                    onPeriodSelected(option)
                                },
                            )
                        }
                    }
                }
                categories.forEach { category ->
                    FilterChip(
                        selected = category.id in selectedCategoryIds,
                        onClick = { onCategoryChecked(category, category.id !in selectedCategoryIds) },
                        label = { Text(category.title) },
                    )
                }
            }

            if (isLoading) {
                LinearLoadingIndicator()
            }

            if (stats.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = context.getString(R.string.empty_stats_text),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            } else {
                if (chartData.isNotEmpty()) {
                    AndroidView(
                        modifier = Modifier.fillMaxWidth().height(240.dp).padding(24.dp),
                        factory = { viewContext ->
                            PieChartView(viewContext).apply {
                                onSegmentClickListener = object : PieChartView.OnSegmentClickListener {
                                    override fun onSegmentClick(view: PieChartView, segment: PieChartView.Segment) {
                                        (segment.tag as? Content)?.let(onContentClick)
                                    }
                                }
                            }
                        },
                        update = { it.setData(chartData) },
                    )
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 16.dp),
                ) {
                    items(stats, key = { it.manga?.id ?: 0L }) { record ->
                        StatsRow(record = record, onClick = onContentClick)
                    }
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text(context.getString(R.string.clear_stats)) },
            text = { Text(context.getString(R.string.clear_stats_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    onClearStats()
                }) { Text(context.getString(R.string.clear)) }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(context.getString(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun LinearLoadingIndicator() {
    androidx.compose.material3.LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
}

@Composable
private fun StatsRow(record: StatsRecord, onClick: (Content) -> Unit) {
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(record.manga?.let { Modifier.clickable { onClick(it) } } ?: Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(record.manga?.title ?: context.getString(R.string.other_manga))
            Text(record.time.format(context.resources), style = MaterialTheme.typography.bodySmall)
        }
    }
}
