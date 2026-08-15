package org.skepsun.kototoro.settings.storage

import android.net.Uri
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.MutableEventFlow
import org.skepsun.kototoro.core.util.ext.call
import org.skepsun.kototoro.local.data.LocalStorageManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ContentDirectorySelectViewModel @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val settings: AppSettings,
) : BaseViewModel() {

	private var contentType: String? = null

	val items = MutableStateFlow(emptyList<DirectoryModel>())
	val onDismissDialog = MutableEventFlow<Unit>()
	val onPickDirectory = MutableEventFlow<Unit>()

	fun initialize(contentType: String) {
		this.contentType = contentType
		refresh()
	}

	fun onItemClick(item: DirectoryModel) {
		if (item.root != null) {
			when (contentType) {
				CONTENT_TYPE_NOVEL -> settings.novelStorageUri = item.root.uri
				CONTENT_TYPE_VIDEO -> settings.videoStorageUri = item.root.uri
				else -> settings.mangaStorageUri = item.root.uri
			}
			onDismissDialog.call(Unit)
		} else {
			onPickDirectory.call(Unit)
		}
	}

	fun onCustomDirectoryPicked(uri: Uri) {
		launchJob(Dispatchers.Default) {
			storageManager.takePermissions(uri)
			val root = storageManager.resolveRoot(uri)
			if (!root.isWriteable()) {
				throw AccessDeniedException(File(root.displayPath))
			}
			when (contentType) {
				CONTENT_TYPE_NOVEL -> settings.novelStorageUri = root.uri
				CONTENT_TYPE_VIDEO -> settings.videoStorageUri = root.uri
				else -> settings.mangaStorageUri = root.uri
			}
			storageManager.setDirIsNoMedia(root)
			onDismissDialog.call(Unit)
		}
	}

	fun refresh() {
		launchJob(Dispatchers.Default) {
			val defaultValue = when (contentType) {
				CONTENT_TYPE_NOVEL -> storageManager.getDefaultNovelWriteableRoot()
				CONTENT_TYPE_VIDEO -> storageManager.getDefaultVideoWriteableRoot()
				else -> storageManager.getDefaultWriteableRoot()
			}
			val available = when (contentType) {
				CONTENT_TYPE_NOVEL -> storageManager.getNovelWriteableRoots()
				CONTENT_TYPE_VIDEO -> storageManager.getVideoWriteableRoots()
				else -> storageManager.getWriteableRoots()
			}
			items.value = buildList(available.size + 1) {
				available.mapTo(this) { root ->
					DirectoryModel(
						title = storageManager.getDirectoryDisplayName(root, isFullPath = false),
						titleRes = 0,
						root = root,
						isChecked = root == defaultValue,
						isAvailable = true,
						isRemovable = false,
					)
				}
				this += DirectoryModel(
					title = null,
					titleRes = R.string.pick_custom_directory,
					root = null,
					isChecked = false,
					isAvailable = true,
					isRemovable = false,
				)
			}
		}
	}

	companion object {
		const val CONTENT_TYPE_MANGA = "manga"
		const val CONTENT_TYPE_NOVEL = "novel"
		const val CONTENT_TYPE_VIDEO = "video"
	}
}
