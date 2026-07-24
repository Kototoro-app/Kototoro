package org.skepsun.kototoro.settings.override

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import coil3.request.ImageRequest
import coil3.request.crossfade
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.ui.BaseComposeActivity
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.tryLaunch
import org.skepsun.kototoro.core.util.ext.mangaExtra
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.picker.ui.PageImagePickContract

@AndroidEntryPoint
class OverrideConfigActivity : BaseComposeActivity(),
	ActivityResultCallback<Uri?> {

	private val viewModel: OverrideConfigViewModel by viewModels()
	private val errorMessage = MutableStateFlow<String?>(null)

	private val pickCoverFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument(), this)
	private val pickPageLauncher = registerForActivityResult(PageImagePickContract(), this)

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		viewModel.onSaved.observeEvent(this) { onDataSaved() }
		viewModel.onError.observeEvent(this) { errorMessage.value = it.getDisplayMessage(resources) }
		setComposeContent {
			val data = viewModel.data.collectAsStateWithLifecycle().value
			val isLoading = viewModel.isLoading.collectAsStateWithLifecycle().value
			val error = errorMessage.collectAsStateWithLifecycle().value
			val manga = data?.first
			val override = data?.second
			val coverUrl = override?.coverUrl.ifNullOrEmpty { manga?.coverUrl }
			val coverRequest = remember(manga?.id, coverUrl) {
				manga?.let {
					ImageRequest.Builder(this@OverrideConfigActivity)
						.data(coverUrl)
						.mangaExtra(it)
						.crossfade(true)
						.build()
				}
			}
			OverrideConfigScreen(
				mangaTitle = manga?.title.orEmpty(),
				coverRequest = coverRequest,
				initialName = override?.title.orEmpty(),
				canResetCover = override?.coverUrl?.isNotEmpty() == true,
				isDataReady = data != null,
				isLoading = isLoading,
				errorMessage = error,
				onSave = {
					errorMessage.value = null
					viewModel.save(it)
				},
				onPickFile = {
					if (!pickCoverFileLauncher.tryLaunch(arrayOf("image/*"))) {
						lifecycleScope.launch {
							snackbarHostState.showSnackbar(getString(R.string.operation_not_supported))
						}
					}
				},
				onPickPage = { manga?.let { pickPageLauncher.launch(it) } },
				onResetCover = { viewModel.updateCover(null) },
				onNavigateUp = ::finish,
			)
		}
	}

	override fun onActivityResult(result: Uri?) {
		if (result != null) {
			if (result.host?.startsWith(packageName) != true) {
				contentResolver.takePersistableUriPermission(result, Intent.FLAG_GRANT_READ_URI_PERMISSION)
			}
			viewModel.updateCover(result.toString())
		}
	}

	private fun onDataSaved() {
		setResult(RESULT_OK)
		finish()
	}
}
