package org.skepsun.kototoro.settings.storage.directories

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
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
import org.skepsun.kototoro.local.data.StorageContentKind
import org.skepsun.kototoro.local.domain.model.computeTreeSize
import javax.inject.Inject

@HiltViewModel
class ContentDirectoriesViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val storageManager: LocalStorageManager,
    private val settings: AppSettings,
) : BaseViewModel() {

    val mangaItems = MutableStateFlow(emptyList<DirectoryConfigModel>())
    val novelItems = MutableStateFlow(emptyList<DirectoryConfigModel>())
    val videoItems = MutableStateFlow(emptyList<DirectoryConfigModel>())
    private val _isInitialLoading = MutableStateFlow(true)
    val isInitialLoading = _isInitialLoading.asStateFlow()
    private var loadingJob: Job? = null

    init {
        loadList()
    }

    fun updateList() {
        loadList()
    }

    fun onCustomDirectoryPicked(uri: Uri, kind: StorageContentKind) {
        launchLoadingJob(Dispatchers.Default) {
            loadingJob?.cancelAndJoin()
            storageManager.takePermissions(uri)
            val root = storageManager.resolveRoot(uri)
            if (!root.isReadable()) {
                throw AccessDeniedException(java.io.File(root.displayPath))
            }
            when (kind) {
                StorageContentKind.MANGA -> settings.userSpecifiedContentDirectoryUris += root.uri
                StorageContentKind.NOVEL -> settings.userSpecifiedNovelDirectoryUris += root.uri
                StorageContentKind.VIDEO -> settings.userSpecifiedVideoDirectoryUris += root.uri
            }
            storageManager.setDirIsNoMedia(root)
            loadList()
        }
    }

    fun onRemoveClick(root: LocalStorageRoot, kind: StorageContentKind) {
        when (kind) {
            StorageContentKind.MANGA -> {
                settings.userSpecifiedContentDirectoryUris -= root.uri
                if (settings.mangaStorageUri == root.uri) settings.mangaStorageUri = null
            }
            StorageContentKind.NOVEL -> {
                settings.userSpecifiedNovelDirectoryUris -= root.uri
                if (settings.novelStorageUri == root.uri) settings.novelStorageUri = null
            }
            StorageContentKind.VIDEO -> {
                settings.userSpecifiedVideoDirectoryUris -= root.uri
                if (settings.videoStorageUri == root.uri) settings.videoStorageUri = null
            }
        }
        loadList()
    }

    fun onDefaultClick(root: LocalStorageRoot, kind: StorageContentKind) {
        launchLoadingJob(Dispatchers.Default) {
            require(root.isWriteable()) { "Directory is not writeable: ${root.displayPath}" }
            when (kind) {
                StorageContentKind.MANGA -> settings.mangaStorageUri = root.uri
                StorageContentKind.NOVEL -> settings.novelStorageUri = root.uri
                StorageContentKind.VIDEO -> settings.videoStorageUri = root.uri
            }
            loadList()
        }
    }

    private fun loadList() {
        val prevJob = loadingJob
        loadingJob = launchLoadingJob(Dispatchers.Default) {
            try {
                prevJob?.cancelAndJoin()
                mangaItems.value = loadItems(StorageContentKind.MANGA)
                novelItems.value = loadItems(StorageContentKind.NOVEL)
                videoItems.value = loadItems(StorageContentKind.VIDEO)
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
            available = storageManager.computeAvailableSize(this),
        )
    } catch (_: Exception) {
        null
    }

    private suspend fun loadItems(kind: StorageContentKind): List<DirectoryConfigModel> {
        val downloadRoot = when (kind) {
            StorageContentKind.MANGA -> storageManager.getDefaultWriteableRoot()
            StorageContentKind.NOVEL -> storageManager.getDefaultNovelWriteableRoot()
            StorageContentKind.VIDEO -> storageManager.getDefaultVideoWriteableRoot()
        }
        return storageManager.getReadableRoots(kind).mapNotNull { root ->
            root.toDirectoryModelSafe(
                downloadRoot = downloadRoot,
                isAppPrivate = root.rawFile?.absolutePath?.contains(context.packageName) == true,
            )
        }
    }

    private fun LocalStorageRoot.computeSize(): Long {
        return file.computeTreeSize()
    }
}
