package org.skepsun.kototoro.local.data

import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.parsers.model.NovelChapterContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.ext.deleteAwait
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.takeIfWriteable
import org.skepsun.kototoro.core.util.ext.withChildren
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.local.data.output.LocalContentUtil
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.ContentRating
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentListFilter
import org.skepsun.kototoro.parsers.model.ContentListFilterCapabilities
import org.skepsun.kototoro.parsers.model.ContentListFilterOptions
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.SortOrder
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.parsers.util.mapToSet
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import java.io.File
import java.util.EnumSet
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

private const val MAX_PARALLELISM = 4
private const val FILENAME_SKIP = ".notamanga"
private const val BACKUP_SUFFIX = ".bk"

@Singleton
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val localContentIndex: LocalContentIndex,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
	private val settings: AppSettings,
	private val lock: ContentLock,
	private val repositoryFactory: Provider<ContentRepository.Factory>,
) : ContentRepository {

	override val source = LocalMangaSource

	override val filterCapabilities: ContentListFilterCapabilities
		get() = ContentListFilterCapabilities(
			isMultipleTagsSupported = true,
			isTagsExclusionSupported = true,
			isSearchSupported = true,
			isSearchWithFiltersSupported = true,
		)

	override val sortOrders: Set<SortOrder> = EnumSet.of(
		SortOrder.ALPHABETICAL,
		SortOrder.RATING,
		SortOrder.NEWEST,
		SortOrder.RELEVANCE,
	)

	override var defaultSortOrder: SortOrder
		get() = settings.localListOrder
		set(value) {
			settings.localListOrder = value
		}

	override suspend fun getFilterOptions() = ContentListFilterOptions(
		availableTags = localContentIndex.getAvailableTags(
			skipNsfw = settings.isNsfwContentDisabled,
		).mapToSet { ContentTag(title = it, key = it, source = source) },
		availableContentRating = if (!settings.isNsfwContentDisabled) {
			EnumSet.of(ContentRating.SAFE, ContentRating.ADULT)
		} else {
			emptySet()
		},
		availableContentTypes = EnumSet.of(ContentType.MANGA, ContentType.NOVEL, ContentType.VIDEO),
	)

	override suspend fun getList(offset: Int, order: SortOrder?, filter: ContentListFilter?): List<Content> {
		if (offset > 0) {
			return emptyList()
		}
		val list = getRawList()
		if (settings.isNsfwContentDisabled) {
			list.removeAll { it.manga.isNsfw() }
		}
		if (filter != null) {
			val query = filter.query
			if (!query.isNullOrEmpty()) {
				list.retainAll { x -> x.isMatchesQuery(query) }
			}
			if (filter.tags.isNotEmpty()) {
				list.retainAll { x -> x.containsTags(filter.tags.mapToSet { it.title }) }
			}
			if (filter.types.isNotEmpty()) {
				list.retainAll { x -> (x.manga.source?.contentType ?: ContentType.MANGA) in filter.types }
			}
			if (filter.tagsExclude.isNotEmpty()) {
				list.removeAll { x -> x.containsAnyTag(filter.tagsExclude.mapToSet { it.title }) }
			}
			filter.contentRating.singleOrNull()?.let { contentRating ->
				val isNsfw = contentRating == ContentRating.ADULT
				list.retainAll { it.manga.isNsfw() == isNsfw }
			}
			if (!query.isNullOrEmpty() && order == SortOrder.RELEVANCE) {
				list.sortBy { it.manga.title.levenshteinDistance(query) }
			}
		}
		when (order) {
			SortOrder.ALPHABETICAL -> list.sortWith(compareBy(AlphanumComparator()) { x -> x.manga.title })
			SortOrder.RATING -> list.sortByDescending { it.manga.rating }
			SortOrder.NEWEST,
			SortOrder.UPDATED -> list.sortWith(compareBy({ -it.createdAt }, { it.manga.id }))

			else -> Unit
		}
		return list.unwrap()
	}

	override suspend fun getDetails(manga: Content): Content = when {
		!manga.isLocal -> {
			// For saved manga, always re-parse from disk to get fresh chapter data
			// This ensures we get updated chapters after EPUB download/extraction
			// Bypass localContentIndex cache by using LocalContentParser.find directly
			val parser = LocalContentParser.find(storageManager.getAllReadableDirs(), manga)
			if (parser != null) {
				// Parse directly from disk to get fresh data
				parser.getContent(withDetails = true).manga
			} else {
				throw IllegalArgumentException("Content is not local or saved")
			}
		}

		else -> LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga
	}

	override suspend fun getPages(chapter: ContentChapter, nextChapterUrl: String?): List<ContentPage> {
		if (!chapter.source.isLocal) {
			android.util.Log.d("LocalMangaRepository", "Delegating getPages to original source: ${chapter.source.name}")
			return repositoryFactory.get().create(chapter.source).getPages(chapter, nextChapterUrl)
		}
		
		android.util.Log.d("LocalMangaRepository", "getPages: chapter.url=${chapter.url}, title=${chapter.title}")
		
		// NEW ARCHITECTURE: EPUB chapters use epub:// protocol
		if (chapter.url.startsWith("epub://") || chapter.url.startsWith("localepub://")) {
			android.util.Log.d("LocalMangaRepository", "EPUB chapter detected (new architecture / localepub)")
			// Return a special page that will be handled by NovelContentLoader
			return listOf(
				ContentPage(
					id = 0,
					url = chapter.url,
					preview = null,
					source = LocalMangaSource,
				)
			)
		}
		
		// Legacy EPUB chapters with file://path#chapter/N format are no longer supported
		// Users need to re-download to use the new architecture
		if (chapter.url.contains("#chapter/") && chapter.url.startsWith("file://")) {
			android.util.Log.w("LocalMangaRepository", "Legacy EPUB chapter format detected: ${chapter.url}")
			android.util.Log.w("LocalMangaRepository", "Please re-download this manga to use the new EPUB architecture")
			// Return empty list to indicate unsupported format
			return emptyList()
		}
		
		// 普通章节，使用LocalContentParser
		android.util.Log.d("LocalMangaRepository", "Using LocalContentParser for regular chapter")
		return LocalContentParser(chapter.url.toUri()).getPages(chapter)
	}

	override suspend fun getChapterContent(chapter: ContentChapter, nextChapterUrl: String?): NovelChapterContent? {
		if (!chapter.source.isLocal) {
			android.util.Log.d("LocalMangaRepository", "Delegating getChapterContent to original source: ${chapter.source.name}")
			return repositoryFactory.get().create(chapter.source).getChapterContent(chapter, nextChapterUrl)
		}
		return super.getChapterContent(chapter, nextChapterUrl)
	}

	suspend fun delete(manga: Content): Boolean {
		val file = if (manga.isLocal) {
			manga.url.toUri().toFile()
		} else {
			findSavedContent(manga, withDetails = false)?.file ?: return false
		}
		val result = file.deleteAwait()
		if (result) {
			localContentIndex.delete(manga.id)
			localStorageChanges.emit(null)
		}
		return result
	}

	suspend fun deleteChapters(manga: Content, ids: Set<Long>) = lock.withLock(manga) {
		val subject = if (manga.isLocal) {
			org.skepsun.kototoro.local.domain.model.LocalContent(manga)
		} else {
			checkNotNull(findSavedContent(manga, withDetails = false)) {
				"Content is not stored on local storage"
			}
		}
		LocalContentUtil(subject.manga, subject.file).deleteChapters(ids)
		val updated = LocalContentParser(subject.file).getContent(withDetails = true)
		localStorageChanges.emit(updated)
	}

	suspend fun getRemoteContent(localContent: Content): Content? {
		return runCatchingCancellable {
			LocalContentParser(localContent.url.toUri()).getContentInfo()?.takeUnless { it.isLocal }
		}.onFailure {
			it.printStackTraceDebug()
		}.getOrNull()
	}

	suspend fun findSavedContent(remoteContent: Content, withDetails: Boolean = true): LocalContent? = runCatchingCancellable {
		// very fast path
		localContentIndex.get(remoteContent.id, withDetails)?.let { cached ->
			return@runCatchingCancellable cached
		}
		// fast path
		LocalContentParser.find(storageManager.getAllReadableDirs(), remoteContent)?.let {
			return it.getContent(withDetails)
		}
		// slow path
		val files = getAllFiles()
		return channelFlow {
			for (file in files) {
				launch {
					val mangaInput = LocalContentParser.getOrNull(file)
					runCatchingCancellable {
						val mangaInfo = mangaInput?.getContentInfo()
						if (mangaInfo != null && mangaInfo.id == remoteContent.id) {
							send(mangaInput)
						}
					}.onFailure {
						it.printStackTraceDebug()
					}
				}
			}
		}.firstOrNull()?.getContent(withDetails)
	}.onSuccess { x: LocalContent? ->
		if (x != null) {
			localContentIndex.put(x)
		}
	}.onFailure {
		it.printStackTraceDebug()
	}.getOrNull()

	override suspend fun getPageUrl(page: ContentPage) = page.url

	override suspend fun getRelated(seed: Content): List<Content> = emptyList()

	suspend fun getOutputDir(manga: Content, fallback: File?): File? {
		val isVideo = manga.source?.getContentType() == ContentType.VIDEO
		val isNovel = manga.source?.getContentType() == ContentType.NOVEL

		val defaultDir = fallback?.takeIfWriteable() ?: when {
			isVideo -> storageManager.getVideoRoot()?.takeIfWriteable()
			isNovel -> storageManager.getDefaultNovelWriteableDir()
			else -> storageManager.getDefaultWriteableDir()
		}
		
		if (defaultDir != null && LocalContentOutput.get(defaultDir, manga) != null) {
			return defaultDir
		}
		
		val writeableDirs = when {
			isVideo -> storageManager.getVideoWriteableDirs()
			isNovel -> storageManager.getNovelWriteableDirs()
			else -> storageManager.getWriteableDirs()
		}
		
		return writeableDirs
			.firstOrNull {
				LocalContentOutput.get(it, manga) != null
			} ?: defaultDir
	}

	suspend fun cleanup(): Boolean {
		if (lock.isNotEmpty()) {
			return false
		}
		val dirs = storageManager.getAllWriteableDirs()
		runInterruptible(Dispatchers.IO) {
			val filter = TempFileFilter()
			dirs.forEach { dir ->
				dir.withChildren { children ->
					children.forEach { child ->
						if (filter.accept(child)) {
							child.deleteRecursively()
						}
					}
				}
			}
		}
		return true
	}

	fun getRawListAsFlow(): Flow<LocalContent> = channelFlow {
		val files = getAllFiles()
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		for (file in files) {
			launch(dispatcher) {
				runCatchingCancellable {
					LocalContentParser.getOrNull(file)?.getContent(withDetails = false)
				}.onFailure { e ->
					e.printStackTraceDebug()
				}.onSuccess { m ->
					if (m != null) send(m)
				}
			}
		}
	}

	private suspend fun getRawList(): ArrayList<LocalContent> = getRawListAsFlow().toCollection(ArrayList())

	private suspend fun getAllFiles() = storageManager.getAllReadableDirs()
		.asSequence()
		.flatMap { dir ->
			dir.withChildren { children -> 
				children.filterNot { it.isHidden || it.shouldSkip() }.toList()
			}
		}

	private fun Collection<LocalContent>.unwrap(): List<Content> = map { it.manga }

	private fun File.shouldSkip(): Boolean {
		return name.endsWith(BACKUP_SUFFIX, ignoreCase = true) ||
			(isDirectory && File(this, FILENAME_SKIP).exists())
	}
}
