package org.skepsun.kototoro.settings.storage.directories

import android.content.Intent
import android.os.Bundle
import androidx.activity.viewModels
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.exceptions.resolve.SnackbarErrorObserver
import org.skepsun.kototoro.core.os.OpenDocumentTreeHelper
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.tryLaunch

@AndroidEntryPoint
class ContentDirectoriesActivity : BaseComposeActivity() {

	private val viewModel: ContentDirectoriesViewModel by viewModels()
	private val pickFileTreeLauncher = OpenDocumentTreeHelper(
		activityResultCaller = this,
		flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
			or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
			or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
	) {
		if (it != null) viewModel.onCustomDirectoryPicked(it)
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel.onError.observeEvent(
			this,
			SnackbarErrorObserver(window.decorView, null, exceptionResolver) {
				if (it) viewModel.updateList()
			},
		)
		setComposeContent {
			ContentDirectoriesScreen(
				items = viewModel.items.collectAsStateWithLifecycle().value,
				isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value,
				onBack = ::finish,
				onAddDirectory = {
					if (!pickFileTreeLauncher.tryLaunch(null)) {
						lifecycleScope.launch {
							snackbarHostState.showSnackbar(getString(R.string.operation_not_supported))
						}
					}
				},
				onRemoveDirectory = viewModel::onRemoveClick,
			)
		}
	}
}
