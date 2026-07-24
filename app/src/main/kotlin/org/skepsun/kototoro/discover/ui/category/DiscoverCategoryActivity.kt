package org.skepsun.kototoro.discover.ui.category

import android.os.Bundle
import androidx.activity.viewModels
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.FilterList
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.filter.ui.FilterCoordinator
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService
import org.skepsun.kototoro.tracking.discovery.domain.isTrackingDateDrivenCategory

@AndroidEntryPoint
class DiscoverCategoryActivity : BaseComposeActivity(), FilterCoordinator.Owner {
    private val viewModel by viewModels<DiscoverCategoryViewModel>()

    override val filterCoordinator: FilterCoordinator
        get() = viewModel.filterCoordinator

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceName = intent.getStringExtra(AppRouter.KEY_ID) ?: return finish()
        val initialCategoryId = intent.getStringExtra(AppRouter.KEY_KIND) ?: return finish()
        val initialTitleRes = intent.getIntExtra(AppRouter.KEY_TITLE, 0)
        viewModel.initialize(serviceName, initialCategoryId)
        setComposeContent {
            val service = remember(serviceName) { ScrobblerService.entries.firstOrNull { it.name == serviceName } }
                ?: return@setComposeContent
            val items by viewModel.content.collectAsStateWithLifecycle(emptyList())
            val loading by viewModel.isLoading.collectAsStateWithLifecycle(false)
            val selectedDate by viewModel.selectedCalendarDateMillis.collectAsStateWithLifecycle()
            var categoryId by remember { mutableStateOf(initialCategoryId) }
            var titleRes by remember { mutableStateOf(initialTitleRes) }
            var showSort by remember { mutableStateOf(false) }
            var showDatePicker by remember { mutableStateOf(false) }
            val sortOptions = viewModel.getCurrentSortOptions()

            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(if (titleRes != 0) getString(titleRes) else getString(R.string.app_name)) },
                        navigationIcon = {
                            IconButton(onClick = ::finishAfterTransition) {
                                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = null)
                            }
                        },
                        actions = {
                            if (sortOptions.size > 1) {
                                IconButton(onClick = { showSort = true }) {
                                    Icon(Icons.Outlined.Sort, contentDescription = getString(R.string.sort_by))
                                }
                            }
                            if (!isTrackingDateDrivenCategory(categoryId)) {
                                IconButton(onClick = { router.showFilterSheet() }) {
                                    Icon(Icons.Outlined.FilterList, contentDescription = getString(R.string.filter))
                                }
                            }
                        },
                    )
                },
            ) { padding ->
                DiscoverCategoryScreen(
                    items = items,
                    isRefreshing = loading,
                    isDateDriven = isTrackingDateDrivenCategory(categoryId),
                    selectedCalendarDateMillis = selectedDate,
                    service = service,
                    onRefresh = viewModel::refresh,
                    onLoadMore = viewModel::loadNextPage,
                    onItemClick = { item, _, _ ->
                        if (viewModel.supportsDetails(service.name)) {
                            router.openTrackingSiteDetails(service, item.manga.id, item.manga.publicUrl)
                        } else {
                            (item.manga.url ?: item.manga.publicUrl)?.takeIf(String::isNotBlank)?.let(router::openExternalBrowser)
                        }
                    },
                    onDateClick = { showDatePicker = true },
                    onTodayClick = viewModel::selectToday,
                    onDayClick = viewModel::applyDayFilter,
                    modifier = Modifier.padding(padding),
                )
            }
            if (showSort) {
                AlertDialog(
                    onDismissRequest = { showSort = false },
                    title = { Text(getString(R.string.sort_by)) },
                    text = {
                        androidx.compose.foundation.layout.Column {
                            sortOptions.forEach { option ->
                                TextButton(onClick = {
                                    viewModel.applySortOption(option.id)?.let {
                                        categoryId = it.targetCategoryId ?: categoryId
                                        titleRes = it.nameResId
                                    }
                                    showSort = false
                                }) {
                                    RadioButton(selected = option.id == viewModel.getSelectedSortOptionId(), onClick = null)
                                    Text(getString(option.nameResId))
                                }
                            }
                        }
                    },
                    confirmButton = {},
                )
            }
            if (showDatePicker) {
                val pickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)
                DatePickerDialog(
                    onDismissRequest = { showDatePicker = false },
                    confirmButton = {
                        TextButton(onClick = {
                            pickerState.selectedDateMillis?.let(viewModel::applyDateFilter)
                            showDatePicker = false
                        }) { Text(getString(android.R.string.ok)) }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDatePicker = false }) { Text(getString(android.R.string.cancel)) }
                    },
                ) { DatePicker(state = pickerState) }
            }
        }
    }
}
