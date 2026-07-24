package org.skepsun.kototoro.details.ui.scrobbling

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.fragment.app.FragmentActivity
import dagger.hilt.EntryPoint
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.nav.AppRouter
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.details.ui.DetailsViewModel
import org.skepsun.kototoro.tracking.discovery.domain.TrackingSiteDiscoveryService

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ScrobblingInfoSheetEntryPoint {
	fun trackingSiteDiscoveryService(): TrackingSiteDiscoveryService
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblingInfoSheetRoute(
	scrobblerServiceId: Int,
	onDismissRequest: () -> Unit,
	viewModel: DetailsViewModel = hiltViewModel(),
) {
	val context = LocalContext.current
	val anchor = LocalView.current
	val activity = context.findActivity() as? FragmentActivity
	val router = remember(activity) { activity?.let(::AppRouter) }
	val discoveryService = remember(context) {
		EntryPointAccessors.fromApplication<ScrobblingInfoSheetEntryPoint>(
			context.applicationContext,
		).trackingSiteDiscoveryService()
	}
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val scrobblingItems by viewModel.scrobblingInfo.collectAsStateWithLifecycle()

	LaunchedEffect(scrobblingItems, scrobblerServiceId) {
		if (scrobblingItems.isNotEmpty() && scrobblingItems.none { it.scrobbler.id == scrobblerServiceId }) {
			onDismissRequest()
		}
	}

	KototoroTheme {
		ModalBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetState = sheetState,
			modifier = Modifier.fillMaxHeight(0.9f),
		) {
			if (scrobblingItems.isEmpty()) {
				Box(
					modifier = Modifier.fillMaxSize(),
					contentAlignment = Alignment.Center,
				) {
					CircularProgressIndicator()
				}
			} else {
				ScrobblingInfoSheetContent(
					viewModel = viewModel,
					scrobblerServiceId = scrobblerServiceId,
					discoveryService = discoveryService,
					onDismissRequest = onDismissRequest,
					onOpenCover = { info ->
						router?.openImage(
							url = info.coverUrl,
							source = null,
							anchor = anchor,
						)
					},
					onOpenBrowser = { url ->
						router?.openExternalBrowser(url, context.getString(R.string.open_in_browser)) ?: false
					},
					onEdit = { service ->
						viewModel.manga.value?.let { manga ->
							onDismissRequest()
							router?.showScrobblingSelectorSheet(manga, service)
						}
					},
					onUnregister = {
						viewModel.unregisterScrobbling(scrobblerServiceId)
						onDismissRequest()
					},
					onUpdate = { rating, status ->
						viewModel.updateScrobbling(scrobblerServiceId, rating, status)
					},
				)
			}
		}
	}
}
