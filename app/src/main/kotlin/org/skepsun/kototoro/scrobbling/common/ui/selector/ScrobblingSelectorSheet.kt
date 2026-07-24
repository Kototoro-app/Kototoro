package org.skepsun.kototoro.scrobbling.common.ui.selector

import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.launch
import org.skepsun.kototoro.core.exceptions.resolve.ExceptionResolver
import org.skepsun.kototoro.core.ui.BaseActivityEntryPoint
import org.skepsun.kototoro.core.ui.theme.KototoroTheme
import org.skepsun.kototoro.core.util.ext.findActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.scrobbling.common.ui.selector.compose.ScrobblingSelectorDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScrobblingSelectorSheetRoute(
	manga: Content,
	scrobblerServiceId: Int = -1,
	onDismissRequest: () -> Unit,
	viewModel: ScrobblingSelectorViewModel = hiltViewModel(),
) {
	val context = LocalContext.current
	val activity = context.findActivity() as? FragmentActivity
	val exceptionResolver = remember(activity) {
		activity?.let {
			EntryPointAccessors.fromApplication<BaseActivityEntryPoint>(
				it.applicationContext,
			).exceptionResolverFactory.create(it)
		}
	}
	val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
	val coroutineScope = rememberCoroutineScope()

	LaunchedEffect(manga, scrobblerServiceId) {
		viewModel.initialize(manga)
		val selectedIndex = viewModel.availableScrobblers.indexOfFirst {
			it.scrobblerService.id == scrobblerServiceId
		}
		if (selectedIndex >= 0) {
			viewModel.setScrobblerIndex(selectedIndex)
		}
	}

	LaunchedEffect(viewModel) {
		viewModel.onError.collect { event ->
			event?.consume { error ->
				Toast.makeText(
					context,
					error.getDisplayMessage(context.resources),
					Toast.LENGTH_LONG,
				).show()
				if (viewModel.isEmpty) {
					onDismissRequest()
				}
			}
		}
	}

	LaunchedEffect(viewModel) {
		viewModel.onClose.collect { event ->
			event?.consume { onDismissRequest() }
		}
	}

	fun retry(error: Throwable) {
		if (ExceptionResolver.canResolve(error) && exceptionResolver != null) {
			coroutineScope.launch {
				if (exceptionResolver.resolve(error)) {
					viewModel.retry()
				}
			}
		} else {
			viewModel.retry()
		}
	}

	KototoroTheme {
		ModalBottomSheet(
			onDismissRequest = onDismissRequest,
			sheetState = sheetState,
			modifier = Modifier.fillMaxHeight(0.9f),
		) {
			ScrobblingSelectorDialog(
				viewModel = viewModel,
				onDismissRequest = onDismissRequest,
				onRetry = ::retry,
			)
		}
	}
}
