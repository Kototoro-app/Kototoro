package org.skepsun.kototoro.video.data

import android.content.Context
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import org.skepsun.kototoro.core.prefs.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 视频缓存管理器
 * 提供ExoPlayer的缓存支持，实现：
 * 1. 视频片段缓存，避免重复下载
 * 2. 断点续播，退出后可继续播放
 * 3. 自动清理旧缓存（LRU策略）
 */
@Singleton
class VideoCache @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings,
) {

    companion object {
        private const val MIN_CACHE_MB = 256
        private const val MAX_CACHE_MB = 4096
    }

    private val cacheSizeBytes: Long
        get() {
            val mb = settings.videoCacheSizeMb.coerceIn(MIN_CACHE_MB, MAX_CACHE_MB)
            return mb * 1024L * 1024L
        }

    @Volatile
    private var activeCache: SimpleCache? = null

    @Volatile
    private var activeCacheLimitBytes: Long = -1L

    val cache: Cache
        get() = getOrCreateCache()

    @Synchronized
    private fun getOrCreateCache(): Cache {
        val desiredLimit = cacheSizeBytes
        val current = activeCache
        if (current != null && activeCacheLimitBytes == desiredLimit) {
            return current
        }
        current?.release()
        val cacheDir = context.getExternalFilesDir("video_cache")
            ?: File(context.filesDir, "video_cache")
        val databaseProvider = StandaloneDatabaseProvider(context)
        val evictor = LeastRecentlyUsedCacheEvictor(desiredLimit)
        return SimpleCache(cacheDir, evictor, databaseProvider).also {
            activeCache = it
            activeCacheLimitBytes = desiredLimit
        }
    }

    fun clear() {
        runCatching {
            cache.keys.forEach { key ->
                cache.removeResource(key)
            }
        }
    }

    fun getCacheSize(): Long {
        return runCatching {
            cache.cacheSpace
        }.getOrDefault(0L)
    }

    /**
     * 检查缓存健康状态
     */
    fun checkCacheHealth(): Boolean {
        return runCatching {
            cache.cacheSpace >= 0
        }.getOrDefault(false)
    }

    /**
     * 修复缓存（清空并重建）
     */
    fun repairCache() {
        runCatching {
            val cacheDir = context.getExternalFilesDir("video_cache")
                ?: File(context.filesDir, "video_cache")
            if (cacheDir.exists()) {
                cache.release()
                cacheDir.deleteRecursively()
                cacheDir.mkdirs()
            }
        }
    }
}
