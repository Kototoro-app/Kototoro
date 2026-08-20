package org.skepsun.kototoro.discover.ui

import android.os.Bundle
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.discover.ui.compose.DiscoverScreen
import org.skepsun.kototoro.list.ui.model.LoadingState
import org.skepsun.kototoro.scrobbling.common.domain.model.ScrobblerService

@AndroidEntryPoint
class TrackingDiscoverActivity : BaseComposeActivity() {

	@OptIn(ExperimentalMaterial3Api::class)
	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		val initialService = intent.getStringExtra(AppRouter.KEY_ID)
			?.let { name -> ScrobblerService.entries.firstOrNull { it.name == name } }
			?: ScrobblerService.BANGUMI
		val forceLoad = intent.getBooleanExtra(AppRouter.KEY_FORCE_LOAD, false)

		setComposeContent {
			val viewModel: DiscoverViewModel = hiltViewModel()
			val items = viewModel.content.collectAsStateWithLifecycle(emptyList()).value
			val isLoading = viewModel.isLoading.collectAsStateWithLifecycle(initialValue = false).value
			val activeService = viewModel.activeService.collectAsStateWithLifecycle().value
			val availableServices = viewModel.availableServices.collectAsStateWithLifecycle().value
			val scheduleCategory = (activeService ?: initialService).let(viewModel::getScheduleCategory)

			LaunchedEffect(initialService, forceLoad) {
				viewModel.setForceLoad(forceLoad)
				viewModel.selectService(initialService)
				if (forceLoad) {
					viewModel.refresh()
				}
			}

			Scaffold(
				topBar = {
					TopAppBar(
						title = {
							Text(
								text = stringResource(
									(activeService ?: initialService).titleResId,
								),
							)
						},
						navigationIcon = {
							IconButton(onClick = ::finish) {
								Icon(
									imageVector = Icons.AutoMirrored.Filled.ArrowBack,
									contentDescription = stringResource(R.string.back),
								)
							}
						},
						actions = {
							if (scheduleCategory != null) {
								IconButton(
									onClick = {
										router.openTrackingDiscoveryCategory(
											activeService ?: initialService,
											scheduleCategory.id,
											scheduleCategory.nameResId,
										)
									},
								) {
									Icon(
										imageVector = Icons.Filled.DateRange,
										contentDescription = stringResource(R.string.open_daily_schedule),
									)
								}
							}
						},
					)
				},
			) { paddingValues ->
				DiscoverScreen(
					contentPadding = paddingValues,
					items = items,
					isRefreshing = isLoading,
					isCarousel = true,
					isLoadingOnly = items.size <= 1 && items.any { it is LoadingState },
					activeService = activeService,
					availableServices = availableServices,
					onRefresh = viewModel::refresh,
					onLoadMore = viewModel::loadNextPage,
					onItemClick = { item, _, _ ->
						val service = activeService ?: initialService
						if (viewModel.supportsDetails(service)) {
							router.openTrackingSiteDetails(service, item.manga.id, item.manga.url)
						} else {
							val url = item.manga.url.ifBlank { item.manga.publicUrl }
							if (url.isNotBlank()) {
								router.openExternalBrowser(url)
							}
						}
					},
					onSelectService = viewModel::selectService,
					onOpenSchedule = scheduleCategory?.let { category ->
						{
							router.openTrackingDiscoveryCategory(
								activeService ?: initialService,
								category.id,
								category.nameResId,
							)
						}
					},
					onCategoryMoreClick = { category ->
						val service = activeService ?: initialService
						router.openTrackingDiscoveryCategory(service, category.id, category.nameResId)
					},
					gridSpanCount = 3,
					modifier = Modifier.fillMaxSize(),
				)
			}
		}
	}
}
