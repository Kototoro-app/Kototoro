package org.skepsun.kototoro.local.data

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.ContentScopePolicy
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
import org.skepsun.kototoro.core.util.ext.toUriOrNull
import org.skepsun.kototoro.core.util.ext.withChildren
import org.skepsun.kototoro.local.data.index.LocalContentIndex
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.data.importer.LocalImportSupport
import org.skepsun.kototoro.local.data.output.LocalContentOutput
import org.skepsun.kototoro.local.data.output.LocalContentOutput.Companion.ENTRY_NAME_INDEX
import org.skepsun.kototoro.local.data.output.LocalContentUtil
import org.skepsun.kototoro.local.domain.ContentLock
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.local.domain.model.LocalReadStatusFilter
import org.skepsun.kototoro.local.domain.model.isLocalChapterUrl
import org.skepsun.kototoro.list.domain.ReadingProgress
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

private const val LOCAL_PAGE_SIZE = 128

/** 单次查询历史进度时 IN 子句的最大 id 数量（SQLite 变量上限的保护）。 */
private const val HISTORY_QUERY_CHUNK_SIZE = 500

/** 并发读取本地文件修改时间的并行度；太高会给 SAF provider 造成压力。 */
private const val MODIFIED_TIME_LOOKUP_PARALLELISM = 8

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
        // 添加时间（新→旧 / 旧→新）——本地文件的创建时间
        SortOrder.NEWEST,
        SortOrder.NEWEST_ASC,
        SortOrder.ALPHABETICAL,
        SortOrder.ALPHABETICAL_DESC,
        SortOrder.RATING,
        SortOrder.RATING_ASC,
        SortOrder.RELEVANCE,
    )

    override var defaultSortOrder: SortOrder
        // 旧版本可能存了这里已不再提供的排序值（如 POPULARITY），回落到"添加时间（新→旧）"，
        // 保证界面显示的排序和实际排序一致。
        get() = settings.localListOrder.takeIf { it in sortOrders } ?: SortOrder.NEWEST
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
        availableContentTypes = ContentScopePolicy.supportedTypes,
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
        return getFilteredAndSortedList(order, filter, applyLibraryFilters = false).unwrap()
    }

    private suspend fun getFilteredAndSortedList(
        order: SortOrder?,
        filter: ContentListFilter?,
        applyLibraryFilters: Boolean = true,
    ): ArrayList<LocalContent> {
        localContentIndex.updateIfRequired()
        val list = getIndexedList()
        // 本构建只支持漫画：磁盘上遗留的 EPUB/TXT（小说）与视频在列表层就被挡掉，
        // 既覆盖旧数据，也覆盖"自动识别"导入可能带进来的非漫画文件。
        list.retainAll { item -> ContentScopePolicy.isSupported(item.manga.source?.contentType) }
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
        // 阅读状态是"整理方式"而不是搜索条件，独立于 filter 持久化；
        // 批量操作（删除、清空已读章节等）走 getAll() 并关闭这一层，避免只处理到筛选后的子集。
        if (applyLibraryFilters) {
            applyReadStatusFilter(list)
        }
        sortInPlace(list, order)
        return list
    }

    /**
     * 按 [AppSettings.localReadStatusFilter] 过滤本地内容。
     * 进度取自 history 表（已删除的历史按"未读"处理），与卡片上显示的阅读状态保持一致。
     */
    private suspend fun applyReadStatusFilter(list: MutableList<LocalContent>) {
        val status = settings.localReadStatusFilter
        if (status == LocalReadStatusFilter.ALL || list.isEmpty()) {
            return
        }
        val progress = list.mapTo(ArrayList(list.size)) { it.manga.id }
            .chunked(HISTORY_QUERY_CHUNK_SIZE)
            .flatMap { ids -> db.getHistoryDao().findProgress(ids) }
            .associateBy { it.mangaId }
        list.retainAll { item ->
            val percent = progress[item.manga.id]?.percent ?: 0f
            when (status) {
                LocalReadStatusFilter.UNREAD -> percent <= 0f
                LocalReadStatusFilter.READING -> percent > 0f && !ReadingProgress.isCompleted(percent)
                LocalReadStatusFilter.COMPLETED -> ReadingProgress.isCompleted(percent)
                LocalReadStatusFilter.ALL -> true
            }
        }
    }

    private suspend fun sortInPlace(list: MutableList<LocalContent>, order: SortOrder?) {
        when (order) {
            // 添加时间：本地文件的修改时间（SAF 目录需要先补时间，否则全是 0）
            SortOrder.NEWEST,
            SortOrder.UPDATED,
            SortOrder.ADDED -> {
                resolveModifiedTimes(list)
                list.sortWith(compareBy({ -it.createdAt }, { it.manga.id }))
            }

            SortOrder.NEWEST_ASC,
            SortOrder.UPDATED_ASC,
            SortOrder.ADDED_ASC -> {
                resolveModifiedTimes(list)
                list.sortWith(compareBy({ it.createdAt }, { it.manga.id }))
            }

            SortOrder.ALPHABETICAL -> list.sortWith(
                Comparator { a, b -> AlphanumComparator().compare(a.manga.title, b.manga.title) },
            )

            SortOrder.ALPHABETICAL_DESC -> list.sortWith(
                Comparator { a, b -> AlphanumComparator().compare(b.manga.title, a.manga.title) },
            )

            SortOrder.RATING -> list.sortWith(compareByDescending { it.manga.rating })

            SortOrder.RATING_ASC -> list.sortWith(compareBy { it.manga.rating })

            else -> Unit
        }
    }

    /**
     * 补齐排序需要的时间戳。
     *
     * SAF 下取一次时间就是一次 ContentResolver 查询，条目多时串行会把首屏拖到秒级：
     * 这里并发查询（限制并行度，避免压垮 provider），并走 [LocalContentIndex] 的进程内缓存，
     * 只有显式重扫（下拉刷新）才会让缓存失效。
     */
    private suspend fun resolveModifiedTimes(list: List<LocalContent>) {
        val pending = list.filter { it.modifiedAt <= 0L }
        if (pending.isEmpty()) {
            return
        }
        coroutineScope {
            val dispatcher = Dispatchers.IO.limitedParallelism(MODIFIED_TIME_LOOKUP_PARALLELISM)
            pending.map { item ->
                async(dispatcher) {
                    val uri = item.storageUri ?: item.manga.url.toUriOrNull() ?: return@async
                    val key = uri.toString()
                    val cached = localContentIndex.getCachedModifiedTime(key)
                    val value = cached ?: storageManager.lastModified(uri).also {
                        localContentIndex.putCachedModifiedTime(key, it)
                    }
                    item.modifiedAt = value
                }
            }.awaitAll()
        }
        // 写回磁盘缓存，下次冷启动首屏不必再逐条查询 SAF。
        localContentIndex.flushModifiedTimes()
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

        else -> LocalContentParser(manga.url.toUri()).getContent(withDetails = true).manga.let { parsed ->
            if (parsed.id != manga.id) parsed.copy(id = manga.id) else parsed
        }
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
            LocalPdfPages.evictPdfRenderersUnder(uri)
            localContentIndex.delete(manga.id)
            localStorageChanges.emit(null)
        }
        return result
    }

    suspend fun deleteChapters(manga: Content, ids: Set<Long>) = lock.withLock(manga) {
        val subject = if (manga.isLocal) {
            LocalContent(manga)
        } else {
            checkNotNull(findSavedContent(manga, withDetails = false)) {
                "Content is not stored on local storage"
            }
        }
        val root = checkNotNull(UniFile.fromUri(App.getInstance(), subject.toUri())) {
            "Cannot resolve local content URI: ${subject.toUri()}"
        }
        var updated: LocalContent? = null
        try {
            LocalContentUtil(subject.manga, root, App.getInstance().cacheDir).deleteChapters(ids)
            updated = readLocalContent(subject)
            if (updated == null) {
                try {
                    runInterruptible(Dispatchers.IO) { root.deleteEmptyContentRoot() }
                } finally {
                    localContentIndex.delete(manga.id)
                }
            }
        } finally {
            // A failed batch can still remove files. Revalidate the current root on invalidation.
            localStorageChanges.emit(updated)
        }
    }

    /** Re-read the exact storage location, including downloads whose title has since changed. */
    suspend fun readLocalContent(content: LocalContent): LocalContent? = withContext(Dispatchers.IO) {
        val uri = content.toUri()
        val root = checkNotNull(UniFile.fromUri(App.getInstance(), uri)) { "Cannot resolve local content URI: $uri" }
        if (!root.exists()) return@withContext null
        val parsed = LocalContentParser(root, App.getInstance().cacheDir)
            .getContent(withDetails = true, forceRefresh = true)
        parsed.takeIf { it.manga.hasDownloadedChapters() }?.copy(storageUri = uri)
    }

    suspend fun getRemoteContent(localContent: Content): Content? {
        return runCatchingCancellable {
            LocalContentParser(localContent.url.toUri()).getContentInfo()?.takeUnless { it.isLocal }
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrNull()
    }

    suspend fun findSavedContent(remoteContent: Content, withDetails: Boolean = true): LocalContent? = runCatchingCancellable {
        // very fast path: already indexed, so nothing is written back. Re-indexing the hit stored it
        // under the remote row (the File parse keeps the remote url), adding a duplicate library entry.
        localContentIndex.get(remoteContent.id, withDetails)?.let { cached ->
            return cached
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
        val candidates = getOutputDirCandidates(manga, fallback)
        val defaultDir = candidates.firstOrNull()
        if (defaultDir != null && LocalContentOutput.get(defaultDir, manga, App.getInstance().cacheDir) != null) {
            return defaultDir
        }
        return candidates
            .drop(1)
            .firstOrNull {
                LocalContentOutput.get(it, manga, App.getInstance().cacheDir) != null
            } ?: defaultDir
    }

    /**
     * 有序的下载根目录候选：首个是首选（fallback / 用户配置的下载目录），其余是同类型下
     * 其余可写根。
     *
     * 注意 `LocalStorageRoot.isWriteable()` 对 SAF 树 URI 只查元数据标志位，授权被系统回收后
     * 它仍可能返回 true；真正的判定只能靠实际创建文件（UniFile 失败时返回 null，见
     * StorageWriteException）。下载侧应遍历候选逐个尝试，见 DownloadWorker。
     */
    suspend fun getOutputDirCandidates(manga: Content, fallback: Uri?): List<LocalStorageRoot> {
        val contentType = manga.source?.getContentType()
        val isVideo = contentType == ContentType.VIDEO
        val isNovel = contentType == ContentType.NOVEL

        val defaultDir = fallback?.let { storageManager.resolveRoot(it) }?.takeIf(LocalStorageRoot::isWriteable) ?: when {
            isVideo -> storageManager.getDefaultVideoWriteableRoot()
            isNovel -> storageManager.getDefaultNovelWriteableRoot()
            else -> storageManager.getDefaultWriteableRoot()
        }

        val writeableDirs = when {
            isVideo -> storageManager.getVideoWriteableRoots()
            isNovel -> storageManager.getNovelWriteableRoots()
            else -> storageManager.getWriteableRoots()
        }
        return buildList {
            if (defaultDir != null) {
                add(defaultDir)
            }
            addAll(writeableDirs.filterNot { it == defaultDir })
        }
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
                    val parser = LocalContentParser.getOrNull(file, App.getInstance().cacheDir)
                    val indexedDirectory = file.isDirectory && file.findFile(ENTRY_NAME_INDEX) != null
                    parser?.getContent(withDetails = indexedDirectory)?.takeIf {
                        !indexedDirectory || it.manga.hasDownloadedChapters()
                    }
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
     * 注意：local_index 表也可能包含 source 为远程源的已下载漫画（下载完成时
     * 写入本地 path，但 manga 行保留远程 source），因此不能按 LOCAL source 过滤。
     * file 定位/详情页使用 local_index 表中的 path 作为 storageUri：
     * 下载到自定义目录（SAF）时是 content://，直接按 manga.url 拼 File 会得到无效
     * 根路径，导致 createdAt 抛 NoSuchFileException（已由 LocalContent.createdAt 兜底）。
     */
    private suspend fun getIndexedList(): ArrayList<LocalContent> {
        val dao = db.getMangaDao()
        val entities = db.getLocalContentIndexDao().findAllPaths()
        if (entities.isEmpty()) {
            return ArrayList()
        }
        val pathByMangaId = entities.associateBy { it.mangaId }
        return dao.findWithTagsByIds(pathByMangaId.keys)
            .asSequence()
            .mapNotNull { mwt ->
                val path = pathByMangaId[mwt.manga.id]?.path ?: return@mapNotNull null
                val storageUri = path.toUriOrNull()
                LocalContent(mwt.toContent(), storageUri = storageUri)
            }
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

private fun Content.hasDownloadedChapters(): Boolean = chapters.orEmpty().any { it.url.isLocalChapterUrl() }

private fun UniFile.deleteEmptyContentRoot() {
    if (!exists()) return
    if (isDirectory) {
        val children = listFiles() ?: return
        val coverEntry = findFile(ENTRY_NAME_INDEX)?.openInputStream()?.bufferedReader()?.use {
            ContentIndex(it.readText()).getCoverEntry()
        }
        if (children.any { !it.isFile || (it.name != ENTRY_NAME_INDEX && it.name != coverEntry) }) return
    }
    check(delete()) { "Cannot remove empty local content at $uri" }
}
