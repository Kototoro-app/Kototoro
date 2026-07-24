package org.skepsun.kototoro.settings.about

import android.Manifest
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.nav.router

@AndroidEntryPoint
class AppUpdateActivity : BaseComposeActivity() {

	private val viewModel: AppUpdateViewModel by viewModels()
	private lateinit var downloadReceiver: UpdateDownloadReceiver
	private var operationErrorMessage by mutableStateOf<String?>(null)

	private val permissionRequest = registerForActivityResult(
		ActivityResultContracts.RequestPermission(),
	) {
		if (it) {
			viewModel.startDownload()
		} else {
			openInBrowser()
		}
	}

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		downloadReceiver = UpdateDownloadReceiver(viewModel)

		ContextCompat.registerReceiver(
			this,
			downloadReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)

		viewModel.onError.observeEvent(this) {
			operationErrorMessage = it.getDisplayMessage(resources)
		}
		viewModel.onDownloadDone.observeEvent(this) { intent ->
			try {
				startActivity(intent)
			} catch (e: ActivityNotFoundException) {
				e.printStackTraceDebug()
			}
		}

		setComposeContent {
			val nextVersion = viewModel.nextVersion.collectAsStateWithLifecycle().value
			val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
			val downloadProgress = viewModel.downloadProgress.collectAsStateWithLifecycle().value
			val downloadState = viewModel.downloadState.collectAsStateWithLifecycle().value
			val selectedMirror = viewModel.selectedMirror.collectAsStateWithLifecycle().value
			val updateMessage = viewModel.updateMessage.collectAsStateWithLifecycle().value

			AppUpdateScreen(
				version = nextVersion,
				isLoading = isLoading,
				downloadProgress = downloadProgress,
				downloadState = downloadState,
				updateMessage = updateMessage,
				operationErrorMessage = operationErrorMessage,
				mirrorOptions = rememberMirrorOptions(),
				selectedMirror = selectedMirror,
				onMirrorSelected = viewModel::setMirror,
				onCancel = ::finishAfterTransition,
				onUpdate = ::doUpdate,
			)
		}
	}

	override fun onDestroy() {
		unregisterReceiver(downloadReceiver)
		super.onDestroy()
	}

	private fun rememberMirrorOptions(): List<AppUpdateMirrorOption> {
		val labels = resources.getStringArray(R.array.pref_github_mirror_entries)
		val values = resources.getStringArray(R.array.pref_github_mirror_values)
		return values.mapIndexedNotNull { index, value ->
			AppUpdateMirrorOption(
				mirror = AppSettings.GitHubMirror.fromValue(value),
				label = labels.getOrNull(index).orEmpty(),
			)
		}
	}

	private fun doUpdate() {
		operationErrorMessage = null
		viewModel.installIntent.value?.let { intent ->
			try {
				startActivity(intent)
			} catch (e: Exception) {
				operationErrorMessage = e.getDisplayMessage(resources)
			}
			return
		}
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			permissionRequest.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
		} else {
			viewModel.startDownload()
		}
	}

	private fun openInBrowser() {
		val url = viewModel.getReleasePageUrl() ?: return
		if (!router.openExternalBrowser(url, getString(R.string.open_in_browser))) {
			lifecycleScope.launch {
				snackbarHostState.showSnackbar(getString(R.string.operation_not_supported))
			}
		}
	}

	private class UpdateDownloadReceiver(
		private val viewModel: AppUpdateViewModel,
	) : BroadcastReceiver() {

		override fun onReceive(context: Context, intent: Intent) {
			if (intent.action == DownloadManager.ACTION_DOWNLOAD_COMPLETE) {
				viewModel.onDownloadComplete(intent)
			}
		}
	}
}
