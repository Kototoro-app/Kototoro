package org.skepsun.kototoro.details.domain

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.collection.LruCache
import androidx.core.net.toUri
import coil3.ImageLoader
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.size.Scale
import coil3.toBitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.mangaSourceExtra
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.local.data.ChapterThumbnailCache
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import java.io.File
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions as LegacyTooManyRequestExceptions
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions

/**
 * Resolves the first page of a chapter for the optional chapter-list thumbnails (#631).
 *
 * A downloaded chapter reads its own archive and costs nothing. Anything else asks the source for the page list, which
 * many sources rate-limit, so remote lookups are paced per source: one at a time, at least [MIN_REMOTE_INTERVAL_MS]
 * apart, and none at all for a while after the source answered "too many requests". Page answers are remembered for
 * the session; a failed lookup is not, so the chapter is tried again the next time it scrolls into view.
 *
 * Once a first page has loaded, a downscaled copy is kept in [storedThumbnails] under the chapter's key, so later
 * sessions show it straight from disk without asking the source again. Data cleanup clears it (#631).
 */
@Singleton
class ChapterThumbnailResolver @Inject constructor(
    @ApplicationContext private val context: Context,
    private val mangaRepositoryFactory: ContentRepository.Factory,
    @ChapterThumbnailCache private val storedThumbnails: LocalStorageCache,
    private val imageLoader: Provider<ImageLoader>,
) {
    private val cache = LruCache<String, Resolved>(MAX_CACHED_CHAPTERS)
    private val localPermits = Semaphore(MAX_CONCURRENT_LOCAL_LOOKUPS)
    private val sources = ConcurrentHashMap<String, SourcePacing>()

    /** Only image chapters have a page to show; novels and video episodes are left alone. */
    fun supports(chapter: ContentChapter): Boolean = chapter.source.getContentType().isManga()

    /**
     * What a card shows for [chapter]: the stored thumbnail [File], or a [Bitmap] when the page loaded but could not be
     * stored; null while there is nothing to show.
     */
    suspend fun thumbnail(chapter: ContentChapter): Any? {
        if (!supports(chapter)) return null
        val key = chapter.cacheKey()
        runCatchingCancellable { storedThumbnails[key] }.getOrNull()?.let { return it }
        val page = firstPage(chapter) ?: return null
        val bitmap = render(page) ?: return null
        return runCatchingCancellable {
            val buffer = Buffer()
            withContext(Dispatchers.Default) {
                bitmap.compress(Bitmap.CompressFormat.JPEG, STORED_JPEG_QUALITY, buffer.outputStream())
            }
            storedThumbnails.set(key, buffer, MimeType("image/jpeg"))
        }.onFailure { it.printStackTraceDebug() }.getOrNull() ?: bitmap
    }

    /** Drops every stored thumbnail, so the next look at a chapter asks its source again. */
    suspend fun clearStored() {
        cache.evictAll()
        storedThumbnails.clear()
    }

    /** Large enough for the one-per-row grid on a phone; the page itself comes from the page cache when it can. */
    private suspend fun render(page: ContentPage): Bitmap? {
        val request = ImageRequest.Builder(context)
            .data(page)
            .mangaSourceExtra(page.source)
            .size(STORED_WIDTH_PX, STORED_HEIGHT_PX)
            .scale(Scale.FIT)
            .allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED)
            .build()
        val result = imageLoader.get().execute(request) as? SuccessResult ?: return null
        return result.image.toBitmap()
    }

    suspend fun firstPage(chapter: ContentChapter): ContentPage? {
        if (!supports(chapter)) return null
        val key = chapter.cacheKey()
        cache[key]?.let { return it.page }
        return if (chapter.isLocalPageSource()) {
            localPermits.withPermit { lookup(key) { LocalContentParser(chapter.url.toUri()).getPages(chapter) } }
        } else {
            // A card that is scrolled straight past is cancelled here and never reaches the source.
            delay(SETTLE_DELAY_MS)
            val pacing = sources.getOrPut(chapter.source.name, ::SourcePacing)
            pacing.mutex.withLock {
                cache[key]?.let { return@withLock it.page }
                if (SystemClock.elapsedRealtime() < pacing.pausedUntil) return@withLock null
                val wait = pacing.lastRequestAt + MIN_REMOTE_INTERVAL_MS - SystemClock.elapsedRealtime()
                if (wait > 0) delay(wait)
                pacing.lastRequestAt = SystemClock.elapsedRealtime()
                lookup(key, onFailure = { error -> pacing.pauseIfRateLimited(error) }) {
                    mangaRepositoryFactory.create(chapter.source).getPages(chapter)
                }
            }
        }
    }

    private suspend inline fun lookup(
        key: String,
        onFailure: (Throwable) -> Unit = {},
        crossinline load: suspend () -> List<ContentPage>,
    ): ContentPage? = runCatchingCancellable { load().firstOrNull() }
        .onSuccess { cache.put(key, Resolved(it)) }
        .onFailure(onFailure)
        .getOrNull()

    /** Same rule as the reader's ChaptersLoader: a downloaded chapter's URL points at local storage. */
    private fun ContentChapter.isLocalPageSource(): Boolean {
        val uri = url.toUri()
        return uri.isFileUri() ||
            uri.isZipUri() ||
            uri.isContentZipUri() ||
            org.skepsun.kototoro.local.data.LocalPdfPages.isPdfPageUri(uri) ||
            uri.scheme == "content" ||
            source.isLocal
    }

    private fun ContentChapter.cacheKey() = "${source.name}:$id:$url"

    private class Resolved(val page: ContentPage?)

    private class SourcePacing {
        val mutex = Mutex()

        @Volatile
        var lastRequestAt = 0L

        @Volatile
        var pausedUntil = 0L

        fun pauseIfRateLimited(error: Throwable) {
            val retryDelay = when (error) {
                is TooManyRequestExceptions -> error.getRetryDelay()
                is LegacyTooManyRequestExceptions -> error.getRetryDelay()
                // Extensions that do not map the status to the typed exception usually keep the code in the message.
                else -> if (error.message?.contains("429") == true) 0L else return
            }
            val pause = if (retryDelay > 0) retryDelay.coerceAtMost(MAX_PAUSE_MS) else DEFAULT_PAUSE_MS
            pausedUntil = SystemClock.elapsedRealtime() + pause
        }
    }

    private companion object {
        const val MAX_CACHED_CHAPTERS = 512
        const val MAX_CONCURRENT_LOCAL_LOOKUPS = 2
        const val SETTLE_DELAY_MS = 400L
        const val MIN_REMOTE_INTERVAL_MS = 1_000L
        const val DEFAULT_PAUSE_MS = 60_000L
        const val MAX_PAUSE_MS = 10 * 60_000L
        const val STORED_WIDTH_PX = 720
        const val STORED_HEIGHT_PX = 1080
        const val STORED_JPEG_QUALITY = 85
    }
}
