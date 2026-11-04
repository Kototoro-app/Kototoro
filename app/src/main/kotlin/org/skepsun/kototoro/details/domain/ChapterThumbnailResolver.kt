package org.skepsun.kototoro.details.domain

import android.os.SystemClock
import androidx.collection.LruCache
import androidx.core.net.toUri
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isManga
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions as LegacyTooManyRequestExceptions
import org.skepsun.kototoro.parsers.exception.TooManyRequestExceptions

/**
 * Resolves the first page of a chapter for the optional chapter-list thumbnails (#631).
 *
 * A downloaded chapter reads its own archive and costs nothing. Anything else asks the source for the page list, which
 * many sources rate-limit, so remote lookups are paced per source: one at a time, at least [MIN_REMOTE_INTERVAL_MS]
 * apart, and none at all for a while after the source answered "too many requests". Answers are remembered for the
 * session; a failed lookup is not, so the chapter is tried again the next time it scrolls into view.
 */
@Singleton
class ChapterThumbnailResolver @Inject constructor(
    private val mangaRepositoryFactory: ContentRepository.Factory,
) {
    private val cache = LruCache<String, Resolved>(MAX_CACHED_CHAPTERS)
    private val localPermits = Semaphore(MAX_CONCURRENT_LOCAL_LOOKUPS)
    private val sources = ConcurrentHashMap<String, SourcePacing>()

    /** Only image chapters have a page to show; novels and video episodes are left alone. */
    fun supports(chapter: ContentChapter): Boolean = chapter.source.getContentType().isManga()

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
    }
}
