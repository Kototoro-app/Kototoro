package org.skepsun.kototoro.local.data

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
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
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.core.util.ext.withChildren
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.importer.LocalImportSupport
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

private val LOCAL_SOURCE_NAMES = listOf(
	LocalMangaSource.name,
	LocalNovelSource.name,
	LocalVideoSource.name,
)

private const val LOCAL_PAGE_SIZE = 128

@Singleton
class LocalMangaRepository @Inject constructor(
	private val storageManager: LocalStorageManager,
	private val db: MangaDatabase,
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
		val list = getFilteredAndSortedList(order, filter)
		if (!filter?.query.isNullOrBlank()) {
			// 搜索/实体匹配需要完整结果集（与远程源一致），不分页。
			return list.unwrap()
		}
		val from = offset.coerceAtLeast(0)
		if (from >= list.size) {
			return emptyList()
		}
		val until = minOf(from + LOCAL_PAGE_SIZE, list.size)
		return list.subList(from, until).unwrap()
	}

	/**
	 * 完整本地列表（分页之外的批量消费方：删除、清空已读章节等）。
	 * 与 [getList] 一样从数据库读取，不触发文件系统扫描（仅索引过期时修复）。
	 */
	suspend fun getAll(order: SortOrder? = null, filter: ContentListFilter? = null): List<Content> {
		return getFilteredAndSortedList(order, filter).unwrap()
	}

	private suspend fun getFilteredAndSortedList(order: SortOrder?, filter: ContentListFilter?): ArrayList<LocalContent> {
		localContentIndex.updateIfRequired()
		val list = getIndexedList()
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
		return list
	}

	override suspend fun getDetails(manga: Content): Content = when {
		!manga.isLocal -> {
			// For saved manga, always re-parse from disk to get fresh chapter data
			// This ensures we get updated chapters after EPUB download/extraction
			// Bypass localContentIndex cache by using LocalContentParser.find directly
			val parser = findParser(manga)
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
		
		// EPUB chapters from every persisted format are handled by NovelContentLoader.
		if (chapter.url.startsWith("epub://") ||
			org.skepsun.kototoro.local.epub.parseEpubChapterReference(chapter.url) != null
		) {
			android.util.Log.d("LocalMangaRepository", "EPUB chapter detected")
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
		val uri = if (manga.isLocal) {
			manga.url.toUri()
		} else {
			findSavedContent(manga, withDetails = false)?.toUri() ?: return false
		}
		val result = if (uri.scheme == "content") {
			UniFile.fromUri(App.getInstance(), uri)?.delete() == true
		} else {
			val file = if (uri.scheme == "file") {
				File(requireNotNull(uri.path) { "File uri path is null: $uri" })
			} else {
				File(uri.schemeSpecificPart)
			}
			file.deleteAwait()
		}
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
		val root = checkNotNull(UniFile.fromUri(App.getInstance(), subject.toUri())) {
			"Cannot resolve local content URI: ${subject.toUri()}"
		}
		LocalContentUtil(subject.manga, root, App.getInstance().cacheDir).deleteChapters(ids)
		val updated = LocalContentParser(root, App.getInstance().cacheDir).getContent(
			withDetails = true,
			forceRefresh = true,
		)
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
		findParser(remoteContent)?.let {
			return it.getContent(withDetails)
		}
		// slow path
		val files = getAllStorageFiles()
		return channelFlow {
			for (file in files) {
				launch {
				val mangaInput = LocalContentParser.getOrNull(file, App.getInstance().cacheDir)
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

	suspend fun getOutputDir(manga: Content, fallback: Uri?): LocalStorageRoot? {
		val isVideo = manga.source?.getContentType() == ContentType.VIDEO
		val isNovel = manga.source?.getContentType() == ContentType.NOVEL

		val defaultDir = fallback?.let { storageManager.resolveRoot(it) }?.takeIf(LocalStorageRoot::isWriteable) ?: when {
			isVideo -> storageManager.getDefaultVideoWriteableRoot()
			isNovel -> storageManager.getDefaultNovelWriteableRoot()
			else -> storageManager.getDefaultWriteableRoot()
		}
		
		if (defaultDir != null && LocalContentOutput.get(defaultDir, manga, App.getInstance().cacheDir) != null) {
			return defaultDir
		}
		
		val writeableDirs = when {
			isVideo -> storageManager.getVideoWriteableRoots()
			isNovel -> storageManager.getNovelWriteableRoots()
			else -> storageManager.getWriteableRoots()
		}
		
		return writeableDirs
			.firstOrNull {
				LocalContentOutput.get(it, manga, App.getInstance().cacheDir) != null
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
		val files = getAllStorageFiles()
		val dispatcher = Dispatchers.IO.limitedParallelism(MAX_PARALLELISM)
		for (file in files) {
			launch(dispatcher) {
				runCatchingCancellable {
					LocalContentParser.getOrNull(file, App.getInstance().cacheDir)?.getContent(withDetails = false)
				}.onFailure { e ->
					e.printStackTraceDebug()
				}.onSuccess { m ->
					if (m != null) send(m)
				}
			}
		}
	}

	/**
	 * 从数据库读取本地内容清单（local_index 为主路径）。
	 * 只读 manga/local_index 表，不解析文件系统；索引过期时由
	 * [LocalContentIndex.updateIfRequired] 触发一次全量扫描修复。
	 * 结果以 local_index 中存在的记录为准，避免把已删除的本地文件继续展示。
	 */
	private suspend fun getIndexedList(): ArrayList<LocalContent> {
		val dao = db.getMangaDao()
		val all = dao.findAllBySources(LOCAL_SOURCE_NAMES)
		if (all.isEmpty()) {
			return ArrayList()
		}
		val indexed = db.getLocalContentIndexDao()
			.findExistingIds(all.mapTo(ArrayList(all.size)) { it.manga.id })
			.toHashSet()
		return all.asSequence()
			.filter { it.manga.id in indexed }
			.map { LocalContent(it.toContent()) }
			.toCollection(ArrayList())
	}

	private suspend fun getAllStorageFiles(): List<UniFile> = storageManager.getAllReadableRoots()
		.flatMap { root ->
			root.file.listFiles().orEmpty().filterNot { child ->
				child.name?.endsWith(BACKUP_SUFFIX, ignoreCase = true) == true ||
					child.name?.startsWith(LocalImportSupport.IMPORT_STAGING_PREFIX) == true ||
					child.isDirectory && child.findFile(FILENAME_SKIP) != null
			}
		}

	private suspend fun findParser(manga: Content): LocalContentParser? {
		val baseName = manga.title.toFileNameSafe()
		val candidates = setOf(baseName, "$baseName.cbz", "${manga.id}_$baseName", "${manga.id}_$baseName.cbz")
		return storageManager.getAllReadableRoots().firstNotNullOfOrNull { root ->
			candidates.firstNotNullOfOrNull { name ->
				root.file.findFile(name)?.let { file ->
					LocalContentParser.getOrNull(file, App.getInstance().cacheDir)?.takeIf { parser ->
						runCatchingCancellable { parser.getContentInfo()?.id == manga.id }.getOrDefault(false)
					}
				}
				}
			}
	}

	private fun Collection<LocalContent>.unwrap(): List<Content> = map { it.manga }

}
