package org.skepsun.kototoro.settings

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.Cache
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogMeta
import org.skepsun.kototoro.core.github.GitHubMirrorCatalogRepository
import org.skepsun.kototoro.core.github.GitHubMirrorProbeResult
import org.skepsun.kototoro.core.github.GitHubMirrorProbeState
import org.skepsun.kototoro.core.github.GitHubMirrorSyncState
import org.skepsun.kototoro.core.prefs.GitHubMirrorEntry
import org.skepsun.kototoro.core.ui.BaseViewModel
import org.skepsun.kototoro.core.util.ext.computeSize
import org.skepsun.kototoro.local.data.CacheDir
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.StorageContentKind
import org.skepsun.kototoro.settings.userdata.storage.StorageUsage
import org.skepsun.kototoro.settings.userdata.storage.StorageUsageCategory
import javax.inject.Inject

@HiltViewModel
class StorageAndNetworkSettingsViewModel @Inject constructor(
    private val storageManager: LocalStorageManager,
    private val httpCache: Cache,
    private val mirrorRepository: GitHubMirrorCatalogRepository,
) : BaseViewModel() {

    private val _storageUsage = MutableStateFlow<StorageUsage?>(null)
    val storageUsage: StateFlow<StorageUsage?> = _storageUsage.asStateFlow()

    val mirrorEntries: StateFlow<List<GitHubMirrorEntry>> = mirrorRepository.entries

    val mirrorSyncState: StateFlow<GitHubMirrorSyncState> = mirrorRepository.syncState

    val mirrorCatalogMeta: StateFlow<GitHubMirrorCatalogMeta> = mirrorRepository.meta

    val mirrorProbeState: StateFlow<GitHubMirrorProbeState> = mirrorRepository.probeState

    val mirrorProbeResults: StateFlow<Map<String, GitHubMirrorProbeResult>> = mirrorRepository.probeResults

    fun refreshMirrorCatalog() {
        mirrorRepository.refresh()
    }

    fun cancelMirrorCatalogSync() {
        mirrorRepository.cancelSync()
    }

    fun probeMirrors() {
        mirrorRepository.probeMirrors()
    }

    fun cancelMirrorProbes() {
        mirrorRepository.cancelProbes()
    }

    init {
        refreshStorageUsage()
    }

    fun refreshStorageUsage() {
        launchJob(Dispatchers.Default) {
            _storageUsage.value = loadStorageUsage()
        }
    }

    private suspend fun loadStorageUsage(): StorageUsage {
        val localMangaSize = storageManager.computeStorageSize(StorageContentKind.MANGA)
        val localNovelSize = storageManager.computeStorageSize(StorageContentKind.NOVEL)
        val localVideoSize = storageManager.computeStorageSize(StorageContentKind.VIDEO)

        val thumbsCacheSize = storageManager.computeCacheSize(CacheDir.THUMBS)
        val faviconsCacheSize = storageManager.computeCacheSize(CacheDir.FAVICONS)
        val pagesCacheSize = storageManager.computeCacheSize(CacheDir.PAGES)
        val novelsCacheSize = storageManager.computeCacheSize(CacheDir.NOVELS)
        val videoCacheSize = storageManager.computeCacheSize(CacheDir.VIDEO)
        val videoProxyCacheSize = storageManager.computeCacheSize(CacheDir.VIDEO_PROXY)
        val torrentCacheSize = storageManager.computeCacheSize(CacheDir.TORRENT)
        val danmakuCacheSize = storageManager.computeCacheSize(CacheDir.DANMAKU)
        val ttsCacheSize = storageManager.computeCacheSize(CacheDir.TtsAudio)
        val srCacheSize = storageManager.computeCacheSize(CacheDir.SUPER_RESOLUTION)
        val httpCacheSize = httpCache.directory.computeSize()

        val aiModelsSize = storageManager.computeAiModelsSize()
        val availableSpace = storageManager.computeAvailableSize()

        val knownCacheSize = thumbsCacheSize +
            faviconsCacheSize +
            pagesCacheSize +
            novelsCacheSize +
            videoCacheSize +
            videoProxyCacheSize +
            torrentCacheSize +
            danmakuCacheSize +
            ttsCacheSize +
            srCacheSize +
            httpCacheSize
        val otherCacheSize = (storageManager.computeCacheSize() - knownCacheSize).coerceAtLeast(0L)

        val totalBytes = localMangaSize +
            localNovelSize +
            localVideoSize +
            thumbsCacheSize +
            faviconsCacheSize +
            pagesCacheSize +
            novelsCacheSize +
            videoCacheSize +
            videoProxyCacheSize +
            torrentCacheSize +
            danmakuCacheSize +
            ttsCacheSize +
            srCacheSize +
            httpCacheSize +
            aiModelsSize +
            otherCacheSize +
            availableSpace

        fun item(category: StorageUsageCategory, bytes: Long): StorageUsage.Item {
            val percent = if (totalBytes <= 0L) 0f else (bytes.toDouble() / totalBytes).toFloat()
            return StorageUsage.Item(
                category = category,
                bytes = bytes,
                percent = percent,
            )
        }

        return StorageUsage(
            items = listOf(
                item(StorageUsageCategory.LOCAL_MANGA, localMangaSize),
                item(StorageUsageCategory.LOCAL_NOVELS, localNovelSize),
                item(StorageUsageCategory.LOCAL_VIDEOS, localVideoSize),
                item(StorageUsageCategory.THUMBS_CACHE, thumbsCacheSize),
                item(StorageUsageCategory.FAVICONS_CACHE, faviconsCacheSize),
                item(StorageUsageCategory.PAGES_CACHE, pagesCacheSize),
                item(StorageUsageCategory.NOVELS_CACHE, novelsCacheSize),
                item(StorageUsageCategory.VIDEO_CACHE, videoCacheSize),
                item(StorageUsageCategory.VIDEO_PROXY_CACHE, videoProxyCacheSize),
                item(StorageUsageCategory.TORRENT_CACHE, torrentCacheSize),
                item(StorageUsageCategory.DANMAKU_CACHE, danmakuCacheSize),
                item(StorageUsageCategory.TTS_CACHE, ttsCacheSize),
                item(StorageUsageCategory.SUPER_RESOLUTION_CACHE, srCacheSize),
                item(StorageUsageCategory.HTTP_CACHE, httpCacheSize),
                item(StorageUsageCategory.AI_MODELS, aiModelsSize),
                item(StorageUsageCategory.OTHER_CACHE, otherCacheSize),
                item(StorageUsageCategory.AVAILABLE, availableSpace),
            ),
        )
    }
}
