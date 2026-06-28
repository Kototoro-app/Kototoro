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
import android.view.View
import android.view.ViewGroup.MarginLayoutParams
import android.widget.ArrayAdapter
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.core.text.buildSpannedString
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.core.view.updatePadding
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import io.noties.markwon.Markwon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.github.AppVersion
import org.skepsun.kototoro.core.nav.router
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseActivity
import org.skepsun.kototoro.core.util.FileSize
import org.skepsun.kototoro.core.util.ext.consumeAllSystemBarsInsets
import org.skepsun.kototoro.core.util.ext.getDisplayMessage
import org.skepsun.kototoro.core.util.ext.observe
import org.skepsun.kototoro.core.util.ext.observeEvent
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.setTextAndVisible
import org.skepsun.kototoro.core.util.ext.showOrHide
import org.skepsun.kototoro.core.util.ext.systemBarsInsets
import org.skepsun.kototoro.core.util.ext.textAndVisible
import org.skepsun.kototoro.databinding.ActivityAppUpdateBinding

@AndroidEntryPoint
class AppUpdateActivity : BaseActivity<ActivityAppUpdateBinding>(), View.OnClickListener {

	private val viewModel: AppUpdateViewModel by viewModels()
	private lateinit var downloadReceiver: UpdateDownloadReceiver

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
		setContentView(ActivityAppUpdateBinding.inflate(layoutInflater))
		downloadReceiver = UpdateDownloadReceiver(viewModel)
		viewModel.nextVersion.observe(this, ::onNextVersionChanged)
		viewBinding.buttonCancel.setOnClickListener(this)
		viewBinding.buttonUpdate.setOnClickListener(this)
		setupMirrorSelector()

		ContextCompat.registerReceiver(
			this,
			downloadReceiver,
			IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
			ContextCompat.RECEIVER_EXPORTED,
		)
		combine(viewModel.isLoading, viewModel.downloadProgress, ::Pair)
			.observe(this, ::onProgressChanged)
		viewModel.downloadState.observe(this, ::onDownloadStateChanged)
		viewModel.selectedMirror.observe(this, ::onSelectedMirrorChanged)
		viewModel.updateMessage.observe(this, ::onUpdateMessageChanged)
		viewModel.onError.observeEvent(this, ::onError)
		viewModel.onDownloadDone.observeEvent(this) { intent ->
			try {
				startActivity(intent)
			} catch (e: ActivityNotFoundException) {
				e.printStackTraceDebug()
			}
		}
	}

	override fun onDestroy() {
		unregisterReceiver(downloadReceiver)
		super.onDestroy()
	}

	override fun onApplyWindowInsets(
		v: View,
		insets: WindowInsetsCompat
	): WindowInsetsCompat {
		val barsInsets = insets.systemBarsInsets
		viewBinding.root.updatePadding(top = barsInsets.top)
		viewBinding.dockedToolbarChild.updateLayoutParams<MarginLayoutParams> {
			leftMargin = barsInsets.left
			rightMargin = barsInsets.right
			bottomMargin = barsInsets.bottom
		}
		viewBinding.scrollView.updatePadding(
			left = barsInsets.left,
			right = barsInsets.right,
		)
		return insets.consumeAllSystemBarsInsets()
	}

	override fun onClick(v: View) {
		when (v.id) {
			R.id.button_cancel -> finishAfterTransition()
			R.id.button_update -> doUpdate()
		}
	}

	private suspend fun onNextVersionChanged(version: AppVersion?) {
		viewBinding.buttonUpdate.isEnabled = version != null && !viewModel.isLoading.value
		if (version == null) {
			viewBinding.textViewContent.setText(R.string.loading_)
			return
		}
		val markwon = Markwon.create(this)
		val message = withContext(Dispatchers.Default) {
			buildSpannedString {
				append(getString(R.string.new_version_s, version.name))
				appendLine()
				append(getString(R.string.size_s, FileSize.BYTES.format(this@AppUpdateActivity, version.patchSize ?: version.apkSize)))
				appendLine()
				appendLine()
				append(markwon.toMarkdown(version.description))
			}
		}
		markwon.setParsedMarkdown(viewBinding.textViewContent, message)
	}

	private fun doUpdate() {
		viewModel.installIntent.value?.let { intent ->
			try {
				startActivity(intent)
			} catch (e: Exception) {
				onError(e)
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
			Snackbar.make(viewBinding.scrollView, R.string.operation_not_supported, Snackbar.LENGTH_SHORT).show()
		}
	}

	private fun setupMirrorSelector() {
		val labels = resources.getStringArray(R.array.pref_github_mirror_entries).toList()
		val values = resources.getStringArray(R.array.pref_github_mirror_values)
			.map { AppSettings.GitHubMirror.fromValue(it) }
		viewBinding.autoCompleteMirror.setAdapter(
			ArrayAdapter(this, android.R.layout.simple_dropdown_item_1line, labels),
		)
		viewBinding.autoCompleteMirror.setOnItemClickListener { _, _, position, _ ->
			values.getOrNull(position)?.let(viewModel::setMirror)
		}
		onSelectedMirrorChanged(viewModel.selectedMirror.value)
	}

	private fun onSelectedMirrorChanged(mirror: AppSettings.GitHubMirror) {
		val values = resources.getStringArray(R.array.pref_github_mirror_values)
		val labels = resources.getStringArray(R.array.pref_github_mirror_entries)
		val index = values.indexOf(mirror.value).takeIf { it >= 0 } ?: 0
		viewBinding.autoCompleteMirror.setText(labels.getOrElse(index) { labels.firstOrNull().orEmpty() }, false)
	}

	private fun onProgressChanged(value: Pair<Boolean, Float>) {
		val (isLoading, downloadProgress) = value
		val indicator = viewBinding.progressBar
		indicator.showOrHide(isLoading)
		indicator.isIndeterminate = downloadProgress <= 0f
		if (downloadProgress > 0f) {
			indicator.setProgressCompat((indicator.max * downloadProgress).toInt(), true)
		}
		viewBinding.buttonUpdate.isEnabled = !isLoading && viewModel.nextVersion.value != null
	}

	private fun onDownloadStateChanged(state: Int) {
		val message = when (state) {
			DownloadManager.STATUS_FAILED -> R.string.error_occurred
			DownloadManager.STATUS_PAUSED -> R.string.downloads_paused
			else -> 0
		}
		viewBinding.textViewError.setTextAndVisible(message)
	}

	private fun onUpdateMessageChanged(msg: String?) {
		if (msg != null) {
			viewBinding.textViewError.textAndVisible = msg
		} else {
			// Restore previous download state visibility
			onDownloadStateChanged(viewModel.downloadState.value)
		}
	}

	private fun onError(e: Throwable) {
		viewBinding.textViewError.textAndVisible = e.getDisplayMessage(resources)
	}

	private class UpdateDownloadReceiver(
		private val viewModel: AppUpdateViewModel,
	) : BroadcastReceiver() {

		override fun onReceive(context: Context, intent: Intent) {
			when (intent.action) {
				DownloadManager.ACTION_DOWNLOAD_COMPLETE -> {
					viewModel.onDownloadComplete(intent)
				}
			}
		}
	}
}
