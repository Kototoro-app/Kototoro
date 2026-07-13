package org.skepsun.kototoro.core.lnreader

import android.content.Context
import android.util.Log
import com.dokar.quickjs.QuickJs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.skepsun.kototoro.core.jsonsource.JsonContentSource
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.parser.RelatedContentSearchFallback
import org.skepsun.kototoro.core.util.MultiMutex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.parsers.model.SortOrder
import java.util.EnumSet

/**
 * ContentRepository implementation for LNReader JS plugins.
 * 
 * Maps LNReaderPluginBridge output → Kototoro's Content/ContentChapter/ContentPage models.
 * Each content operation creates a fresh QuickJS context (matches IReader pattern of
 * one engine per operation for isolation/memory safety).
 */
class LNReaderContentRepository(
	override val source: ContentSource,
	private val appContext: Context,
	private val httpClient: OkHttpClient
) : ContentRepository {
	
	companion object {
		private const val TAG = "LNReaderContentRepo"
		private const val CHAPTER_SEPARATOR = "|||"
	}
	
	private val jsContent: String
		get() = (source as? JsonContentSource)?.entity?.config ?: ""
	
	private val pluginId: String
		get() = (source as? JsonContentSource)?.entity?.id ?: source.name
	
	override val sortOrders: Set<SortOrder> = EnumSet.of(SortOrder.POPULARITY, SortOrder.NEWEST)
	override var defaultSortOrder: SortOrder = SortOrder.POPULARITY
	
	override val filterCapabilities: ContentListFilterCapabilities = ContentListFilterCapabilities(
		isSearchSupported = true,
		isMultipleTagsSupported = true,
	)
	
	private var cachedFilterOptions: ContentListFilterOptions? = null
	private val chapterHtmlMutex = MultiMutex<String>()
	private val chapterHtmlCache = object : LinkedHashMap<String, String>(16, 0.75f, true) {
		override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
			return size > 8
		}
	}
	
	override val listPagingMode: ContentRepository.ListPagingMode
		get() = ContentRepository.ListPagingMode.PAGE_INDEX
	
	// ==================== Content Operations ====================
	
	override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
		val page = offset + 1 // LNReader uses 1-based pages
		
		val selectedFilters = filter?.tags?.associate { tag ->
			val parts = tag.key.split("::", limit = 2)
			if (parts.size == 2) parts[0] to parts[1] else tag.key to tag.key
		}?.takeIf { it.isNotEmpty() }
		
		return try {
			val query = filter?.query
			if (!query.isNullOrBlank()) {
				// Search mode
				executeInPluginContext { bridge ->
					bridge.searchNovels(query, page).map { it.toContent() }
				}
			} else {
				// Browse mode
				executeInPluginContext { bridge ->
					bridge.popularNovels(page, selectedFilters).map { it.toContent() }
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (e is org.skepsun.kototoro.core.exceptions.CloudFlareException || e is org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException) throw e
			Log.e(TAG, "getList failed for ${source.name}", e)
			throw Exception("LNReader JS Error in ${source.name}: ${e.message}", e)
		}
	}
	
	override suspend fun getDetails(manga: Content): Content {
		val candidatePaths = buildList {
			manga.url
				?.takeIf { it.isNotBlank() }
				?.substringBefore(CHAPTER_SEPARATOR)
				?.let(::add)
			manga.publicUrl
				?.takeIf { it.isNotBlank() }
				?.substringBefore(CHAPTER_SEPARATOR)
				?.takeUnless { it in this }
				?.let(::add)
		}.distinct()
		if (candidatePaths.isEmpty()) return manga
		
		return try {
			var lastError: Exception? = null
			candidatePaths.firstNotNullOfOrNull { novelPath ->
				runCatching {
					executeInPluginContext { bridge ->
						val details = bridge.parseNovel(novelPath)
						var finalizedChapters = details.chapters
						if (finalizedChapters.isEmpty() && details.totalPages > 0) {
							Log.d(TAG, "parseNovel returned 0 chapters but totalPages=${details.totalPages}, fetching via parsePage...")
							val allChapters = mutableListOf<LNReaderChapter>()
							for (page in 1..details.totalPages) {
								var success = false
								var retries = 0
								while (!success && retries < 3) {
									try {
										val pageChapters = bridge.parsePage(novelPath, page)
										allChapters.addAll(pageChapters)
										Log.d(TAG, "parsePage($page/${details.totalPages}) returned ${pageChapters.size} chapters")
										success = true
									} catch (e: Exception) {
										retries++
										Log.w(TAG, "parsePage($page) attempt $retries failed: ${e.message}")
										if (retries >= 3) {
											Log.e(TAG, "parsePage($page) failed 3 times, giving up on remaining pages.")
											break
										}
										kotlinx.coroutines.delay(1000L * retries)
									}
								}
								if (!success) break
								if (page < details.totalPages) {
									kotlinx.coroutines.delay(300L)
								}
							}
							Log.d(TAG, "Finished fetching chapters. Total aggregated chapters: ${allChapters.size}")
							finalizedChapters = allChapters
						}

						manga.copy(
							title = details.name.ifBlank { manga.title },
							coverUrl = details.cover.ifBlank { manga.coverUrl },
							largeCoverUrl = details.cover.ifBlank { manga.largeCoverUrl },
							description = details.summary.ifBlank { manga.description },
							tags = if (details.genres.isNotEmpty()) {
								details.genres.map { org.skepsun.kototoro.parsers.model.ContentTag(it, it, source) }.toSet()
							} else manga.tags,
							authors = if (details.author.isNotBlank()) setOf(details.author) else manga.authors,
							url = details.path.ifBlank { novelPath },
							publicUrl = details.path.ifBlank { manga.publicUrl.ifBlank { novelPath } },
							chapters = finalizedChapters.mapIndexed { index, ch ->
								ContentChapter(
									id = (ch.path.hashCode().toLong() and 0x7FFFFFFF) + index,
									title = ch.name.ifBlank { ch.chapterNumber?.let { "Chapter $it" } ?: "Chapter ${index + 1}" },
									number = (index + 1).toFloat(),
									volume = 0,
									url = "${details.path.ifBlank { novelPath }}${CHAPTER_SEPARATOR}${ch.path}",
									scanlator = ch.releaseTime,
									uploadDate = 0,
									branch = null,
									source = source,
								)
							}
						)
					}
				}.onFailure { error ->
					if (error is Exception) {
						lastError = error
						Log.w(TAG, "getDetails retry with alternate path failed for ${source.name}: path=$novelPath", error)
					}
				}.getOrNull()
			} ?: run {
				val cachedChapters = manga.chapters
				if (!cachedChapters.isNullOrEmpty()) {
					Log.w(TAG, "getDetails fallback to cached chapters for ${source.name} after parse failure")
					manga.copy(
						url = candidatePaths.first(),
						publicUrl = manga.publicUrl.ifBlank { candidatePaths.first() },
					)
				} else {
					throw (lastError ?: IllegalStateException("No valid LNReader detail path"))
				}
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (e is org.skepsun.kototoro.core.exceptions.CloudFlareException || e is org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException) throw e
			Log.e(TAG, "getDetails failed for ${source.name}", e)
			throw Exception("LNReader JS Error: ${e.message}", e)
		}
	}
	
	override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
		// LNReader chapters return HTML text, not page images
		// We create a single "page" containing the HTML content
		return try {
			val htmlContent = loadChapterHtml(chapter)
			if (htmlContent.isNotBlank()) {
				val encoded = android.util.Base64.encodeToString(
					htmlContent.toByteArray(Charsets.UTF_8),
					android.util.Base64.NO_WRAP
				)
				listOf(
					ContentPage(
						id = chapter.id,
						url = "data:text/html;base64,$encoded",
						preview = null,
						source = source
					)
				)
			} else {
				emptyList()
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (e is org.skepsun.kototoro.core.exceptions.CloudFlareException || e is org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException) throw e
			Log.e(TAG, "getPages failed for ${source.name}", e)
			throw Exception("LNReader JS Error: ${e.message}", e)
		}
	}
	
	/**
	 * Get chapter content as novel HTML.
	 * This is the key method for novel reading — returns HTML text.
	 */
	override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): NovelChapterContent? {
		return try {
			val htmlContent = loadChapterHtml(chapter)
			if (htmlContent.isNotBlank()) {
				NovelChapterContent(
					html = htmlContent,
					images = emptyList(),
				)
			} else {
				null
			}
		} catch (e: CancellationException) {
			throw e
		} catch (e: Exception) {
			if (e is org.skepsun.kototoro.core.exceptions.CloudFlareException || e is org.skepsun.kototoro.core.exceptions.InteractiveActionRequiredException) throw e
			Log.e(TAG, "getChapterContent failed for ${source.name}", e)
			throw Exception("LNReader JS Error: ${e.message}", e)
		}
	}
	
	override suspend fun getPageUrl(page: ContentPage): String = page.url
	
	override suspend fun getFilterOptions(): ContentListFilterOptions {
		cachedFilterOptions?.let { return it }
		
		return try {
			executeInPluginContext { bridge ->
				val lnFilters = bridge.getFilters()
				val tagGroups = lnFilters.map { lnFilter ->
					org.skepsun.kototoro.parsers.model.ContentTagGroup(
						title = lnFilter.label,
						tags = lnFilter.options.map { opt ->
							org.skepsun.kototoro.parsers.model.ContentTag(
								title = opt.label,
								key = "${lnFilter.key}::${opt.value}",
								source = source
							)
						}.toSet()
					)
				}
				ContentListFilterOptions(tagGroups = tagGroups).also { cachedFilterOptions = it }
			}
		} catch (e: Exception) {
			Log.w(TAG, "Failed to get filters: ${e.message}")
			ContentListFilterOptions()
		}
	}
	
	override suspend fun getRelated(seed: Content): List<Content> {
		return RelatedContentSearchFallback.find(seed) { query ->
			getList(
				offset = 0,
				order = defaultSortOrder,
				filter = ContentListFilter(query = query),
			)
		}
	}
	
	// ==================== Internal ====================
	
	/**
	 * Execute a block within a fresh plugin context.
	 * Creates QuickJS engine → loads plugin → runs block → disposes.
	 */
	private suspend fun <T> executeInPluginContext(block: suspend (LNReaderPluginBridge) -> T): T {
		return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
			val fetchBridge = LNReaderFetchBridge(httpClient, pluginId)
			val engine = LNReaderEngine(appContext, fetchBridge)
			val qjs = engine.createPluginContext(jsContent, pluginId)
			
			try {
				val bridge = LNReaderPluginBridge(qjs, pluginId)
				block(bridge).also {
					// Re-throw any fatal interactive exceptions that were tunneled out of QuickJS fetch 
					// even if Javascript swallowed the error and resolved the promise
					fetchBridge.pendingFatalException?.let { throw it }
				}
			} catch (e: CancellationException) {
				throw e
			} catch (e: Exception) {
				fetchBridge.pendingFatalException?.let { throw it }
				Log.e(TAG, "executeInPluginContext failed for ${source.name}", e)
				throw Exception("LNReader JS Error in ${source.name}: ${e.message}", e)
			} finally {
				qjs.close()
			}
		}
	}

	private suspend fun loadChapterHtml(chapter: ContentChapter): String {
		val cacheKey = chapter.url
		synchronized(chapterHtmlCache) {
			chapterHtmlCache[cacheKey]?.let { return it }
		}

		return chapterHtmlMutex.withLock(cacheKey) {
			synchronized(chapterHtmlCache) {
				chapterHtmlCache[cacheKey]?.let { return@withLock it }
			}

			val parts = chapter.url.split(CHAPTER_SEPARATOR, limit = 2)
			val chapterPath = if (parts.size == 2) parts[1] else chapter.url
			val htmlContent = executeInPluginContext { bridge ->
				bridge.parseChapter(chapterPath)
			}
			if (htmlContent.isNotBlank()) {
				synchronized(chapterHtmlCache) {
					chapterHtmlCache[cacheKey] = htmlContent
				}
			}
			htmlContent
		}
	}
	
	/**
	 * Convert LNReader novel item to Kototoro Content model.
	 */
	private fun LNReaderNovelItem.toContent(): Content {
		return Content(
			id = path.hashCode().toLong() and 0x7FFFFFFF,
			title = name,
			altTitles = emptySet(),
			url = path,
			publicUrl = path,
			rating = 0f,
			contentRating = null,
			coverUrl = cover.ifBlank { null },
			tags = emptySet(),
			state = null,
			authors = emptySet(),
			description = null,
			source = source
		)
	}
}
