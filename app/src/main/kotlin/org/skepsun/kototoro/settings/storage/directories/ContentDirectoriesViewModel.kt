package org.skepsun.kototoro.settings.storage.directories

import android.net.Uri
import android.os.StatFs
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.computeSize
import org.skepsun.kototoro.core.util.ext.isReadable
import org.skepsun.kototoro.core.util.ext.isWriteable
import org.skepsun.kototoro.local.data.LocalStorageManager
import java.io.File
import javax.inject.Inject

@HiltViewModel
class ContentDirectoriesViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    private val settings: AppSettings,
) : BaseViewModel() {

    val items = MutableStateFlow(emptyList<DirectoryConfigModel>())
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
            val dir = storageManager.resolveUri(uri)
            if (!dir.canRead()) {
                throw AccessDeniedException(dir)
            }
            if (dir !in storageManager.getApplicationStorageDirs()) {
                settings.userSpecifiedContentDirectories += dir
                loadList()
            }
        }
    }

    fun onRemoveClick(directory: File) {
        settings.userSpecifiedContentDirectories -= directory
        if (settings.mangaStorageDir == directory) {
            settings.mangaStorageDir = null
        }
        loadList()
    }

    private fun loadList() {
        val prevJob = loadingJob
        loadingJob = launchJob(Dispatchers.Default) {
            prevJob?.cancelAndJoin()
            val downloadDir = storageManager.getDefaultWriteableDir()
            val applicationDirs = storageManager.getApplicationStorageDirs()
            val customDirs = settings.userSpecifiedContentDirectories - applicationDirs
            items.value = (
                applicationDirs.map { dir -> dir.toDirectoryModelSafe(downloadDir, true) } +
                customDirs.map { dir -> dir.toDirectoryModelSafe(downloadDir, false) }
            ).filterNotNull()
        }
    }

    private suspend fun File.toDirectoryModelSafe(
        downloadDir: File?,
        isAppPrivate: Boolean,
    ): DirectoryConfigModel? = try {
        DirectoryConfigModel(
            title = storageManager.getDirectoryDisplayName(this, isFullPath = false),
            path = this,
            isDefault = this == downloadDir,
            isAccessible = isReadable() && isWriteable(),
            isAppPrivate = isAppPrivate,
            size = computeSize(),
            available = StatFs(absolutePath).availableBytes,
        )
    } catch (_: Exception) {
        null
    }
}
