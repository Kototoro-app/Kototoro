package org.skepsun.kototoro.settings.storage.directories

import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.LocalStorageRoot
import javax.inject.Inject

@HiltViewModel
class ContentDirectoriesViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    private val settings: AppSettings,
) : BaseViewModel() {

    val items = MutableStateFlow(emptyList<DirectoryConfigModel>())
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading = _isInitialLoading.asStateFlow()
    private var loadingJob: Job? = null

    init {
        loadList()
    }

    fun updateList() {
        loadList()
    }

    fun onCustomDirectoryPicked(uri: Uri) {
        launchLoadingJob(Dispatchers.Default) {
            loadingJob?.cancelAndJoin()
            storageManager.takePermissions(uri)
            val root = storageManager.resolveRoot(uri)
			if (!root.isReadable()) {
				throw AccessDeniedException(java.io.File(root.displayPath))
			}
			if (root !in storageManager.getApplicationStorageRoots()) {
				settings.userSpecifiedContentDirectoryUris += root.uri
				loadList()
			}
		}
	}

	fun onRemoveClick(root: LocalStorageRoot) {
		settings.userSpecifiedContentDirectoryUris -= root.uri
		if (settings.mangaStorageUri == root.uri) {
			settings.mangaStorageUri = null
        }
        loadList()
    }

    private fun loadList() {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            try {
                prevJob?.cancelAndJoin()
                val downloadRoot = storageManager.getDefaultWriteableRoot()
                val applicationRoots = storageManager.getApplicationStorageRoots()
                val customRoots = settings.userSpecifiedContentDirectoryUris.mapNotNull { uri ->
                    runCatching { storageManager.resolveRoot(uri) }.getOrNull()
                }.toSet() - applicationRoots
                items.value = (
                    applicationRoots.map { root -> root.toDirectoryModelSafe(downloadRoot, true) } +
                        customRoots.map { root -> root.toDirectoryModelSafe(downloadRoot, false) }
                    ).filterNotNull()
            } finally {
                _isInitialLoading.value = false
            }
        }
    }

	private suspend fun LocalStorageRoot.toDirectoryModelSafe(
		downloadRoot: LocalStorageRoot?,
		isAppPrivate: Boolean,
	): DirectoryConfigModel? = try {
		DirectoryConfigModel(
			title = storageManager.getDirectoryDisplayName(this, isFullPath = false),
			root = this,
			isDefault = this == downloadRoot,
			isAccessible = isReadable() && isWriteable(),
			isAppPrivate = isAppPrivate,
			size = computeSize(),
			available = rawFile?.let { StatFs(it.absolutePath).availableBytes },
		)
	} catch (_: Exception) {
		null
	}

	private fun LocalStorageRoot.computeSize(): Long {
		var size = 0L
		val pending = ArrayDeque<com.hippo.unifile.UniFile>()
		pending.add(file)
		while (pending.isNotEmpty()) {
			val item = pending.removeFirst()
			if (item.isDirectory) {
				item.listFiles()?.forEach(pending::addLast)
			} else {
				size += item.length().coerceAtLeast(0L)
			}
		}
		return size
	}
}
