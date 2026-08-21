package org.skepsun.kototoro.local.data

import android.content.Context
import android.graphics.Bitmap
import android.os.StatFs
import android.webkit.MimeTypeMap
import com.tomclaw.cache.DiskLruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.Source
import okio.buffer
import okio.sink
import okio.use
import org.skepsun.kototoro.core.exceptions.NoDataReceivedException
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.compressToPNG
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.subdir
import org.skepsun.kototoro.core.util.ext.takeIfReadable
import org.skepsun.kototoro.core.util.ext.takeIfWriteable
import org.skepsun.kototoro.core.util.ext.writeAllCancellable
import org.skepsun.kototoro.parsers.util.ifNullOrEmpty
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.suspendlazy.suspendLazy
import java.io.File
import java.util.UUID

class LocalStorageCache(
    context: Context,
    private val dir: CacheDir,
    private val defaultSize: Long,
    private val minSize: Long,
) {

    private val cacheDir = suspendLazy {
        val dirs = context.externalCacheDirs + context.cacheDir
        dirs.firstNotNullOf {
            it?.subdir(dir.dir)?.takeIfWriteable()
        }
    }
    private val lruCache = suspendLazy {
        val dir = cacheDir.get()
        val availableSize = (getAvailableSize() * 0.8).toLong()
        val size = defaultSize.coerceAtMost(availableSize).coerceAtLeast(minSize)
        runCatchingCancellable {
            DiskLruCache.create(dir, size)
        }.recoverCatching { error ->
            error.printStackTraceDebug()
            dir.deleteRecursively()
            dir.mkdir()
            DiskLruCache.create(dir, size)
        }.getOrThrow()
    }

    suspend operator fun get(url: String): File? = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext null
        val cache = lruCache.get()
        runInterruptible {
            cache.get(url)?.takeIfReadable()
        }
    }

    suspend operator fun set(url: String, source: Source, mimeType: MimeType?): File = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IllegalArgumentException("Cannot cache file with empty URL")
        val file = createBufferFile(url, mimeType)
        try {
            val bytes = file.sink(append = false).buffer().use {
                it.writeAllCancellable(source)
            }
            if (bytes == 0L) {
                throw NoDataReceivedException(url)
            }
            val cache = lruCache.get()
            runInterruptible {
                cache.put(url, file)
            }
        } finally {
            file.delete()
        }
    }

    suspend operator fun set(url: String, bitmap: Bitmap): File = withContext(Dispatchers.IO) {
        if (url.isBlank()) throw IllegalArgumentException("Cannot cache bitmap with empty URL")
        val file = createBufferFile(url, MimeType("image/png"))
        try {
            bitmap.compressToPNG(file)
            val cache = lruCache.get()
            runInterruptible {
                cache.put(url, file)
            }
        } finally {
            file.delete()
        }
    }

    suspend fun clear() {
        val cache = lruCache.get()
        runInterruptible(Dispatchers.IO) {
            cache.clearCache()
        }
    }

    private suspend fun getAvailableSize(): Long = runCatchingCancellable {
        val dir = cacheDir.get()
        runInterruptible(Dispatchers.IO) {
            val statFs = StatFs(dir.absolutePath)
            statFs.availableBytes
        }
    }.onFailure {
        it.printStackTraceDebug()
    }.getOrDefault(defaultSize)

    private suspend fun createBufferFile(url: String, mimeType: MimeType?): File {
        val ext = MimeTypes.getExtension(mimeType) ?: MimeTypeMap.getFileExtensionFromUrl(url).ifNullOrEmpty { "dat" }
        val cacheDir = cacheDir.get()
        val rootDir = checkNotNull(cacheDir.parentFile) { "Cannot get parent for ${cacheDir.absolutePath}" }
        val name = UUID.randomUUID().toString() + "." + ext
        return File(rootDir, name)
    }
}
