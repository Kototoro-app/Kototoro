package org.skepsun.kototoro.core.parser

import android.util.Log
import coil3.request.CachePolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.cache.MemoryContentCache
import org.skepsun.kototoro.core.cache.SafeDeferred
import org.skepsun.kototoro.core.util.MultiMutex
import org.skepsun.kototoro.core.util.ext.processLifecycleScope
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.runCatchingCancellable

abstract class CachingContentRepository(
	private val cache: MemoryContentCache,
) : ContentRepository {

	private val detailsMutex = MultiMutex<ContentCacheLockKey>()
	private val relatedContentMutex = MultiMutex<ContentCacheLockKey>()
	private val pagesMutex = MultiMutex<ContentCacheLockKey>()

	final override suspend fun getDetails(manga: Content): Content = getDetails(manga, CachePolicy.ENABLED)

	final override suspend fun getDetails(
		manga: Content,
		fetchMode: ContentRepository.DetailsFetchMode,
	): Content = getDetails(
		manga = manga,
		cachePolicy = when (fetchMode) {
			ContentRepository.DetailsFetchMode.ALLOW_CACHE -> CachePolicy.ENABLED
			ContentRepository.DetailsFetchMode.FORCE_REFRESH -> CachePolicy.WRITE_ONLY
		},
	)

	final override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> = pagesMutex.withLock(chapter.cacheLockKey()) {
		val cacheUrl = chapter.cacheUrl()
		cache.getPages(source, cacheUrl)?.let {
			if (it.isNotEmpty()) {
				Log.w("CachingRepo", "getPages cache-hit chapterId=${chapter.id} url=${chapter.url} pages=${it.size}")
				return it
			}
			Log.w("CachingRepo", "getPages empty cache ignored chapterId=${chapter.id} url=${chapter.url}")
		}
		val pages = asyncSafe {
			Log.d("CachingRepo", "getPages getPagesImpl chapterId=${chapter.id} url=${chapter.url}")
			getPagesImpl(chapter, nextChapterUrl).distinctById()
		}
		cache.putPages(source, cacheUrl, pages)
		pages
	}.await()

	final override suspend fun getRelated(seed: Content): List<Content> = relatedContentMutex.withLock(seed.cacheLockKey()) {
		cache.getRelatedContent(source, seed.url)?.let { return it }
		val related = asyncSafe {
			getRelatedContentImpl(seed).filterNot { it.id == seed.id }
		}
		cache.putRelatedContent(source, seed.url, related)
		related
	}.await()

	suspend fun getDetails(manga: Content, cachePolicy: CachePolicy): Content = detailsMutex.withLock(manga.cacheLockKey()) {
		if (cachePolicy.readEnabled) {
			cache.getDetails(source, manga.url)?.let { return it }
		}
		val details = asyncSafe {
			getDetailsImpl(manga)
		}
		if (cachePolicy.writeEnabled) {
			cache.putDetails(source, manga.url, details)
		}
		details
	}.await()

	suspend fun peekDetails(manga: Content): Content? {
		return cache.getDetails(source, manga.url)
	}

	fun invalidateCache() {
		cache.clear(source)
	}

	protected abstract suspend fun getDetailsImpl(manga: Content): Content

	protected abstract suspend fun getRelatedContentImpl(seed: Content): List<Content>

	protected abstract suspend fun getPagesImpl(chapter: ContentChapter, nextChapterUrl: String? = null): List<ContentPage>

	override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): org.skepsun.kototoro.parsers.model.NovelChapterContent? = null

	private suspend fun <T> asyncSafe(block: suspend CoroutineScope.() -> T): SafeDeferred<T> {
		var dispatcher = currentCoroutineContext()[CoroutineDispatcher.Key]
		if (dispatcher == null || dispatcher is MainCoroutineDispatcher) {
			dispatcher = Dispatchers.Default
		}
		return SafeDeferred(
			processLifecycleScope.async(dispatcher) {
				runCatchingCancellable { block() }
			},
		)
	}

	private fun List<ContentPage>.distinctById(): List<ContentPage> {
		if (isEmpty()) {
			return emptyList()
		}
		val result = ArrayList<ContentPage>(size)
		val set = HashSet<String>(size)
		for (page in this) {
			val key = "${page.id}#${page.url}"
			if (set.add(key)) {
				result.add(page)
			} else if (BuildConfig.DEBUG) {
				Log.w(null, "Duplicate page: $page")
			}
		}
		return result
	}

	private fun Content.cacheLockKey() = ContentCacheLockKey(
		source = source.name,
		url = url.ifBlank { publicUrl }.ifBlank { id.toString() },
	)

	private fun ContentChapter.cacheLockKey() = ContentCacheLockKey(
		source = source.name,
		url = cacheUrl(),
	)

	private fun ContentChapter.cacheUrl() = "$id#${url.ifBlank { id.toString() }}"

	private data class ContentCacheLockKey(
		val source: String,
		val url: String,
	)
}
