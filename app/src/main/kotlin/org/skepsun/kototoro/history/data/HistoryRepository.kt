package org.skepsun.kototoro.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.toList
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentList
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.entitygraph.data.EntityGraphRepository
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.list.domain.ReadingProgress
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.model.ContentTag
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.tryScrobble
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.tracker.domain.SourceTrackerEvent
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventEmitter
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import javax.inject.Inject
import javax.inject.Provider

private const val RESUME_HISTORY_FILTER_BATCH_SIZE = 32

data class HistoryPopularFilterOptions(
    val tags: List<ContentTag> = emptyList(),
    val sources: List<ContentSource> = emptyList(),
)

@Reusable
class HistoryRepository @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
    private val mangaRepository: ContentDataRepository,
    private val localObserver: HistoryLocalObserver,
    private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
    private val entityGraphRepository: EntityGraphRepository,
    private val workResolver: WorkResolver,
    private val workAggregateRepository: WorkAggregateRepository,
    private val spaceContentPolicy: SpaceContentPolicy,
    private val sourceTrackerEvents: SourceTrackerEventEmitter,
) {

    private data class WorkHistoryOwner(
        val entityId: Long?,
        val anchorMangaId: Long,
    )

    private data class TrackAggregate(
        val newChapters: Int,
        val lastChapterDate: Long,
    )

    private data class HistoryOwnerRef(
        val cacheKey: Long,
        val entityId: Long?,
        val anchorMangaId: Long,
    )

    private data class HistoryAggregateItem(
        val aggregate: WorkAggregate,
        val content: ContentWithHistory,
    ) {
        val favouriteCategoryIds: Set<Long> = aggregate.categories.mapTo(LinkedHashSet()) { it.id }
    }

    suspend fun getList(offset: Int, limit: Int): List<Content> {
        return findRecentContentsByWorkAnchor(offset, limit)
    }

    suspend fun search(query: String, kind: SearchKind, limit: Int): List<Content> {
        if (limit <= 0) {
            return emptyList()
        }
        val normalizedQuery = query.trim()
        if (normalizedQuery.isEmpty()) {
            return emptyList()
        }
        val comparator = compareBy<Content> { it.title.levenshteinDistance(normalizedQuery) }
            .thenBy { it.title }
        return getAllRecentContents()
            .asSequence()
            .filter { content -> content.matchesHistorySearch(normalizedQuery, kind) }
            .let { sequence ->
                when (kind) {
                    SearchKind.SIMPLE,
                    SearchKind.TITLE,
                    SearchKind.ADVANCED -> sequence.sortedWith(comparator)
                    SearchKind.AUTHOR,
                    SearchKind.TAG -> sequence
                }
            }
            .take(limit)
            .toList()
    }

    suspend fun getLastOrNull(
        spaceId: SpaceId? = null,
        excludeNsfw: Boolean = false,
    ): Content? {
        return findRecentContentsByWorkAnchor(
            offset = 0,
            limit = 1,
            spaceId = spaceId,
            excludeNsfw = excludeNsfw,
        ).firstOrNull()
    }

    fun observeLast(
        spaceId: SpaceId? = null,
        excludeNsfw: Boolean = false,
    ): Flow<Content?> {
        val invalidations = db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_WORK_HISTORY,
                TABLE_ENTITY_GRAPH_BINDING,
                TABLE_ENTITY_PREFERENCES,
                TABLE_MANGA,
                TABLE_TAGS,
                TABLE_MANGA_TAGS,
            ),
            emitInitialState = true,
        )
        val allowedSourceNames = spaceId?.let(spaceContentPolicy::observeAllowedSourceNames) ?: flowOf(null)
        return combine(invalidations, allowedSourceNames) { _, sources -> sources }
            .mapLatest { sources ->
            findRecentContentsByWorkAnchor(
                offset = 0,
                limit = 1,
                spaceId = spaceId,
                allowedSourceNames = sources,
                excludeNsfw = excludeNsfw,
            ).firstOrNull()
        }.distinctUntilChanged()
    }

    fun observeAll(): Flow<List<Content>> {
        return observeRecentContents(limit = null)
    }

    fun observeCount(): Flow<Int> {
        return db.getWorkHistoryDao().observeCountActive()
            .distinctUntilChanged()
    }

    fun observeAll(limit: Int): Flow<List<Content>> {
        return observeRecentContents(limit)
    }

    fun observeRecentWithHistory(
        limit: Int,
        tabTypes: Set<ContentType>? = null,
    ): Flow<List<ContentWithHistory>> {
        require(limit > 0)
        return db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_WORK_HISTORY,
                TABLE_WORK_FAVOURITES,
                TABLE_ENTITY_GRAPH_BINDING,
                TABLE_ENTITY_PREFERENCES,
                TABLE_MANGA,
                TABLE_TAGS,
                TABLE_MANGA_TAGS,
            ),
            emitInitialState = true,
        ).mapLatest {
            mapPagingAggregates(
                aggregates = if (tabTypes == null) {
                    workAggregateRepository.findRecentHistoryAggregates(limit)
                } else {
                    workAggregateRepository.findRecentHistoryAggregatesByTypes(limit, tabTypes)
                },
                filterOptions = emptySet(),
            )
        }.distinctUntilChanged()
    }

    fun observeOne(id: Long): Flow<ContentHistory?> {
        return db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_WORK_HISTORY,
                TABLE_ENTITY_GRAPH_BINDING,
                TABLE_ENTITY_PREFERENCES,
            ),
            emitInitialState = true,
        ).mapLatest {
            getOneByWorkAnchor(id)
        }.distinctUntilChanged()
    }

    suspend fun addOrUpdate(
        manga: Content,
        chapterId: Long,
        page: Int,
        scroll: Int,
        percent: Float,
        force: Boolean,
        parentChapterId: Long? = null  // EPUB父章节ID，用于支持内部章节
    ) {
        // 添加调用栈日志，帮助追踪谁在保存历史记录
        if (parentChapterId != null && chapterId == parentChapterId) {
            android.util.Log.w("HistoryRepository", "WARNING: chapterId == parentChapterId! This might be incorrect.")
            android.util.Log.w("HistoryRepository", "Stack trace:", Exception("Stack trace"))
        }

        if (!force && shouldSkip(manga)) {
            return
        }
        assert(manga.chapters != null)
        db.withTransaction {
            val owner = resolveWorkHistoryOwner(manga)
            val anchorManga = if (owner.anchorMangaId == manga.id) {
                manga
            } else {
                db.getMangaDao().find(owner.anchorMangaId)?.toContent() ?: manga
            }
            val storedAnchorManga = mangaRepository.storeContentAndReturn(anchorManga, replaceExisting = true)
            val branch = manga.chapters?.findById(chapterId)?.branch
            val now = System.currentTimeMillis()
            android.util.Log.d("HistoryRepository", "Upserting history: anchorMangaId=${storedAnchorManga.id}, sourceMangaId=${manga.id}, chapterId=$chapterId, parentChapterId=$parentChapterId")
            owner.entityId?.let { entityId ->
                db.getWorkHistoryDao().upsert(
                    WorkHistoryEntity(
                        entityId = entityId,
                        anchorMangaId = storedAnchorManga.id,
                        createdAt = now,
                        updatedAt = now,
                        chapterId = chapterId,
                        page = page,
                        scroll = scroll.toFloat(),
                        percent = percent,
                        chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
                        deletedAt = 0L,
                        parentChapterId = parentChapterId,
                    ),
                )
            }
            newChaptersUseCaseProvider.get()(manga, chapterId, percent)
            scrobblers.forEach { it.tryScrobble(manga, chapterId) }
        }
    }

    suspend fun getOne(manga: Content): ContentHistory? {
        val entity = findHistoryEntityByWorkAnchor(manga.id)
        android.util.Log.d("HistoryRepository", "getOne: mangaId=${manga.id}, entity=${entity?.let { "chapterId=${it.chapterId}, parentChapterId=${it.parentChapterId}" } ?: "null"}")
        val recovered = entity?.recoverIfNeeded(manga)
        android.util.Log.d("HistoryRepository", "getOne after recover: ${recovered?.let { "chapterId=${it.chapterId}, parentChapterId=${it.parentChapterId}" } ?: "null"}")
        return recovered?.toContentHistory()
    }

    suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
        val entity = findHistoryEntityByWorkAnchor(mangaId) ?: return null
        val fixedPercent = if (ReadingProgress.isCompleted(entity.percent)) 1f else entity.percent
        return ReadingProgress(
            percent = fixedPercent,
            totalChapters = entity.chaptersCount,
            mode = mode,
        ).takeIf { it.isValid() }
    }

    suspend fun getProgress(mangaIds: Collection<Long>, mode: ProgressIndicatorMode): Map<Long, ReadingProgress> {
        if (mangaIds.isEmpty()) return emptyMap()
        val distinctMangaIds = mangaIds.distinct()
        val identitiesByMangaId = workResolver.resolveManyByMangaIds(distinctMangaIds)
        val entityIdsByMangaId = identitiesByMangaId.mapValues { (_, identity) -> identity.entityId }
        val historyByEntityId = db.getWorkHistoryDao()
            .findByEntityIds(entityIdsByMangaId.values.filterNotNull().distinct())
            .associateBy(WorkHistoryEntity::entityId)
        return buildMap {
            distinctMangaIds.forEach { mangaId ->
                val entityId = entityIdsByMangaId[mangaId] ?: return@forEach
                val history = historyByEntityId[entityId] ?: return@forEach
                val fixedPercent = if (ReadingProgress.isCompleted(history.percent)) 1f else history.percent
                val progress = ReadingProgress(
                    percent = fixedPercent,
                    totalChapters = history.chaptersCount,
                    mode = mode,
                ).takeIf { it.isValid() } ?: return@forEach
                put(mangaId, progress)
            }
        }
    }

    suspend fun updateProgress(mangaId: Long, percent: Float, chaptersCount: Int): Boolean {
        val entity = findWorkHistoryEntityByWorkAnchor(mangaId) ?: return false
        if (entity.percent == percent && entity.chaptersCount == chaptersCount) {
            return false
        }
        db.getWorkHistoryDao().update(
            entity.copy(
                percent = percent,
                chaptersCount = chaptersCount,
            ),
        )
        emitRead(mangaId, percent)
        return true
    }

    suspend fun clear() {
        db.getWorkHistoryDao().clear()
        db.getHistoryDao().clear()
    }

    suspend fun normalizeWorkHistoryForSync(): Boolean {
        if (settings.requiresWorkMigrationNormalization) {
            normalizeWorkHistory()
        }
        return true
    }

    suspend fun normalizeWorkHistoryIfNeeded() {
        if (!settings.requiresWorkMigrationNormalization) {
            return
        }
        normalizeWorkHistory()
    }

    suspend fun delete(manga: Content) {
        db.withTransaction {
            val ownerRef = resolveHistoryOwnerRef(manga.id)
            ownerRef.entityId?.let { entityId ->
                db.getWorkHistoryDao().delete(entityId)
            }
            mangaRepository.gcChaptersCache()
        }
        emitUnread(manga)
    }

    suspend fun deleteAfter(minDate: Long) = db.withTransaction {
        db.getWorkHistoryDao().deleteAfter(minDate)
        mangaRepository.gcChaptersCache()
    }

    suspend fun deleteNotFavorite() = db.withTransaction {
        deleteWorkHistoryWithoutFavourite()
        mangaRepository.gcChaptersCache()
    }

    suspend fun delete(ids: Collection<Long>): ReversibleHandle {
        val ownerRefs = ids.mapTo(LinkedHashSet()) { resolveHistoryOwnerRef(it) }
        val resolvedEntityIds = ownerRefs.mapNotNullTo(LinkedHashSet()) { it.entityId }
        db.withTransaction {
            for (entityId in resolvedEntityIds) {
                db.getWorkHistoryDao().delete(entityId)
            }
            mangaRepository.gcChaptersCache()
        }
        emitUnreadAll(ids)
        return ReversibleHandle {
            recover(resolvedEntityIds)
        }
    }

    /**
     * Try to replace one manga with another one
     * Useful for replacing saved manga on deleting it with remote source
     */
    suspend fun deleteOrSwap(manga: Content, alternative: Content?) {
        if (alternative == null || db.getMangaDao().update(alternative.toEntity()) <= 0) {
            delete(manga)
        }
    }

    suspend fun getPopularFilterOptions(tagLimit: Int, sourceLimit: Int): HistoryPopularFilterOptions {
        if (tagLimit <= 0 && sourceLimit <= 0) {
            return HistoryPopularFilterOptions()
        }
        val contents = getAllRecentContents()
        val tagCounts = LinkedHashMap<ContentTag, Int>()
        val sourceCounts = LinkedHashMap<String, Int>()
        for (content in contents) {
            if (tagLimit > 0) {
                for (tag in content.tags) {
                    tagCounts[tag] = tagCounts.getOrDefault(tag, 0) + 1
                }
            }
            if (sourceLimit > 0) {
                val sourceName = content.source.name
                sourceCounts[sourceName] = sourceCounts.getOrDefault(sourceName, 0) + 1
            }
        }
        val tags = if (tagLimit > 0) {
            tagCounts.entries
                .sortedByDescending { it.value }
                .take(tagLimit)
                .map { it.key }
        } else {
            emptyList()
        }
        val sources = if (sourceLimit > 0) {
            sourceCounts.entries
                .sortedByDescending { it.value }
                .take(sourceLimit)
                .map { it.key }
                .toContentSources()
        } else {
            emptyList()
        }
        return HistoryPopularFilterOptions(tags = tags, sources = sources)
    }

    suspend fun getPopularTags(limit: Int): List<ContentTag> =
        getPopularFilterOptions(tagLimit = limit, sourceLimit = 0).tags

    suspend fun getPopularSources(limit: Int): List<ContentSource> =
        getPopularFilterOptions(tagLimit = 0, sourceLimit = limit).sources

    fun shouldSkip(manga: Content): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

    fun observeShouldSkip(manga: Content): Flow<Boolean> {
        return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
            .map { shouldSkip(manga) }
            .distinctUntilChanged()
    }

    private suspend fun recover(entityIds: Collection<Long>) {
        db.withTransaction {
            for (entityId in entityIds) {
                db.getWorkHistoryDao().recover(entityId)
            }
        }
    }

    private suspend fun getOneByWorkAnchor(mangaId: Long): ContentHistory? {
        return findHistoryEntityByWorkAnchor(mangaId)?.toContentHistory()
    }

    private fun observeRecentContents(limit: Int?): Flow<List<Content>> {
        return db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_WORK_HISTORY,
                TABLE_ENTITY_GRAPH_BINDING,
                TABLE_ENTITY_PREFERENCES,
                TABLE_MANGA,
            ),
            emitInitialState = true,
        ).mapLatest {
            findRecentContentsByWorkAnchor(offset = 0, limit = limit)
        }.distinctUntilChanged()
    }

    private suspend fun findRecentContentsByWorkAnchor(
        offset: Int,
        limit: Int?,
        spaceId: SpaceId? = null,
        allowedSourceNames: Set<String>? = spaceId?.let(spaceContentPolicy::allowedSourceNames),
        excludeNsfw: Boolean = false,
    ): List<Content> {
        if (limit != null && limit <= 0) {
            return emptyList()
        }
        val targetSize = if (limit == null) Int.MAX_VALUE else offset + limit
        if (!excludeNsfw) {
            return workAggregateRepository.findRecentHistoryAggregates(targetSize, spaceId, allowedSourceNames)
                .drop(offset)
                .let { list -> if (limit == null) list else list.take(limit) }
                .mapNotNull { it.displayProjection }
        }
        var queryLimit = if (limit == null) targetSize else maxOf(targetSize, RESUME_HISTORY_FILTER_BATCH_SIZE)
        while (true) {
            val aggregates = workAggregateRepository.findRecentHistoryAggregates(
                queryLimit,
                spaceId,
                allowedSourceNames,
            )
            val visible = aggregates.mapNotNull { it.displayProjection }
                .let { contents -> if (excludeNsfw) contents.filterNot { it.isNsfw() } else contents }
            if (
                limit == null ||
                visible.size >= targetSize ||
                aggregates.size < queryLimit ||
                queryLimit == Int.MAX_VALUE
            ) {
                return visible.drop(offset).let { list -> if (limit == null) list else list.take(limit) }
            }
            queryLimit = (queryLimit.toLong() * 2L)
                .coerceAtMost(Int.MAX_VALUE.toLong())
                .toInt()
        }
    }

    private suspend fun getAllRecentContents(maxCount: Int = Int.MAX_VALUE): List<Content> {
        return findRecentContentsByWorkAnchor(offset = 0, limit = if (maxCount == Int.MAX_VALUE) null else maxCount)
    }

    suspend fun mapPagingAggregates(
        aggregates: List<WorkAggregate>,
        filterOptions: Set<ListFilterOption>,
    ): List<ContentWithHistory> {
        val trackCache = HashMap<Long, TrackAggregate>()
        return aggregates.mapNotNull { aggregate ->
            val content = aggregate.displayProjection ?: return@mapNotNull null
            val history = aggregate.history ?: return@mapNotNull null
            HistoryAggregateItem(
                aggregate = aggregate,
                content = ContentWithHistory(
                    manga = content,
                    history = history.toLegacyHistoryEntity().toContentHistory(),
                    entityId = aggregate.identity.entityId,
                    preferredLocalMangaId = aggregate.identity.preferredMangaId ?: content.id,
                ),
            )
        }.filter { item ->
            matchesHistoryFilters(
                item = item.content,
                filterOptions = filterOptions,
                favouriteCategoryIds = item.favouriteCategoryIds,
                trackCache = trackCache,
            )
        }.map(HistoryAggregateItem::content)
    }

    private suspend fun matchesHistoryFilters(
        item: ContentWithHistory,
        filterOptions: Set<ListFilterOption>,
        favouriteCategoryIds: Set<Long>,
        trackCache: MutableMap<Long, TrackAggregate>,
    ): Boolean {
        return filterOptions.all { option ->
            when (option) {
                ListFilterOption.Downloaded -> true
                ListFilterOption.Macro.COMPLETED -> ReadingProgress.isCompleted(item.history.percent)
                ListFilterOption.Macro.NEW_CHAPTERS -> getTrackAggregate(item, trackCache).newChapters > 0
                ListFilterOption.Macro.MULTI_PROJECTION -> true
                ListFilterOption.Macro.BROKEN_PROJECTION -> true
                ListFilterOption.Macro.FAVORITE -> favouriteCategoryIds.isNotEmpty()
                ListFilterOption.Macro.NSFW -> item.manga.isNsfw()
                is ListFilterOption.Inverted -> when (option.option) {
                    ListFilterOption.Macro.NSFW -> !item.manga.isNsfw()
                    ListFilterOption.Macro.FAVORITE -> favouriteCategoryIds.isEmpty()
                    else -> true
                }
                is ListFilterOption.Tag -> item.manga.tags.any { tag -> tag.title == option.tag.title && tag.key == option.tag.key }
                is ListFilterOption.Source -> item.manga.source.name == option.mangaSource.name
                is ListFilterOption.PublicationState -> item.manga.state == option.state
                is ListFilterOption.ReadingStatus -> true
                is ListFilterOption.Favorite -> option.category.id in favouriteCategoryIds
                is ListFilterOption.Branch -> {
                    val branch = item.manga.findChapterById(item.history.chapterId)?.branch
                    branch == option.titleText
                }
            }
        }
    }

    private suspend fun findFavouriteCategoryIdsByEntityId(entityIds: Collection<Long>): Map<Long, Set<Long>> {
        if (entityIds.isEmpty()) {
            return emptyMap()
        }
        return db.getWorkFavouritesDao()
            .findCategoryMemberships(entityIds.distinct())
            .groupBy { it.entityId }
            .mapValues { (_, entries) -> entries.mapTo(LinkedHashSet()) { it.categoryId } }
    }

    private suspend fun getTrackAggregate(
        item: ContentWithHistory,
        cache: MutableMap<Long, TrackAggregate>,
    ): TrackAggregate {
        val ownerRef = resolveHistoryOwnerRef(item)
        return cache.getOrPut(ownerRef.cacheKey) {
            runBlockingTrackAggregateLookup(ownerRef)
        }
    }

    private suspend fun runBlockingTrackAggregateLookup(ownerRef: HistoryOwnerRef): TrackAggregate {
        var newChapters = 0
        var lastChapterDate = 0L
        resolveHistoryAnchorIds(ownerRef).forEach { anchorId ->
            val entity = db.getTracksDao().find(anchorId) ?: return@forEach
            newChapters += entity.newChapters
            lastChapterDate = maxOf(lastChapterDate, entity.lastChapterDate)
        }
        return TrackAggregate(
            newChapters = newChapters,
            lastChapterDate = lastChapterDate,
        )
    }

    private suspend fun findHistoryEntityByWorkAnchor(mangaId: Long): HistoryEntity? {
        findWorkHistoryEntityByWorkAnchor(mangaId)?.let { return it.toLegacyHistoryEntity() }
        return null
    }

    private suspend fun findWorkHistoryEntityByWorkAnchor(mangaId: Long): WorkHistoryEntity? {
        val entityId = resolveWorkEntityId(mangaId) ?: return null
        return db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L }
    }

    private suspend fun resolveWorkHistoryOwner(manga: Content): WorkHistoryOwner {
        // History ownership is work-first. The returned anchor manga id is the preferred local
        // projection used for legacy history rows and compatibility lookups.
        val identity = workResolver.resolveByMangaId(manga.id)
        val entityId = identity.entityId
        if (entityId == null) {
            return WorkHistoryOwner(
                entityId = null,
                anchorMangaId = manga.id,
            )
        }
        return WorkHistoryOwner(
            entityId = entityId,
            anchorMangaId = identity.preferredMangaId ?: manga.id,
        )
    }

    private suspend fun resolveWorkEntityId(mangaId: Long): Long? {
        return workResolver.resolveByMangaId(mangaId).entityId
    }

    private suspend fun resolveHistoryOwnerRef(mangaId: Long): HistoryOwnerRef {
        val identity = workResolver.resolveByMangaId(mangaId)
        val entityId = identity.entityId
        val anchorMangaId = identity.preferredMangaId ?: mangaId
        return HistoryOwnerRef(
            cacheKey = entityId ?: -mangaId,
            entityId = entityId,
            anchorMangaId = anchorMangaId,
        )
    }

    private fun resolveHistoryOwnerRef(item: ContentWithHistory): HistoryOwnerRef {
        val entityId = item.entityId
        val anchorMangaId = item.preferredLocalMangaId ?: item.manga.id
        return HistoryOwnerRef(
            cacheKey = entityId ?: -item.manga.id,
            entityId = entityId,
            anchorMangaId = anchorMangaId,
        )
    }

    private suspend fun resolveHistoryAnchorIds(mangaId: Long): Set<Long> {
        val identity = workResolver.resolveByMangaId(mangaId)
        identity.preferredMangaId?.let { return setOf(it) }
        return identity.localMangaIds.ifEmpty { setOf(mangaId) }
    }

    private suspend fun resolveHistoryAnchorIds(ownerRef: HistoryOwnerRef): Set<Long> {
        val entityId = ownerRef.entityId ?: return setOf(ownerRef.anchorMangaId)
        val identity = workResolver.resolveByEntityId(entityId)
        identity?.preferredMangaId?.let { return setOf(it) }
        val localIds = identity?.localMangaIds.orEmpty()
        return if (localIds.isEmpty()) setOf(ownerRef.anchorMangaId) else localIds
    }

    private suspend fun HistoryEntity.recoverIfNeeded(manga: Content): HistoryEntity {
        val chapters = manga.chapters
        if (manga.isLocal || chapters.isNullOrEmpty() || chapters.findById(chapterId) != null) {
            return this
        }

        // 对于EPUB内部章节，不要尝试恢复
        // parentChapterId != null && parentChapterId != chapterId 表示这是EPUB内部章节
        // 详情页显示的是父章节列表，所以内部章节ID在列表中找不到是正常的
        if (parentChapterId != null && parentChapterId != chapterId) {
            android.util.Log.d("HistoryRepository", "Skipping recovery for EPUB internal chapter: $chapterId (parent: $parentChapterId)")
            return this
        }

        android.util.Log.w("HistoryRepository", "recoverIfNeeded: Chapter $chapterId not found in ${chapters.size} chapters, attempting recovery")
        android.util.Log.w("HistoryRepository", "First 3 chapter IDs: ${chapters.take(3).map { it.id }}")
        val newChapterId = chapters.getOrNull(
            (chapters.size * percent).toInt(),
        )?.id ?: return this
        android.util.Log.w("HistoryRepository", "Recovered: $chapterId -> $newChapterId (percent=$percent)")
        val newEntity = copy(chapterId = newChapterId)
        resolveWorkEntityId(manga.id)?.let { entityId ->
            db.getWorkHistoryDao().update(
                WorkHistoryEntity(
                    entityId = entityId,
                    anchorMangaId = newEntity.mangaId,
                    createdAt = newEntity.createdAt,
                    updatedAt = newEntity.updatedAt,
                    chapterId = newEntity.chapterId,
                    page = newEntity.page,
                    scroll = newEntity.scroll,
                    percent = newEntity.percent,
                    deletedAt = newEntity.deletedAt,
                    chaptersCount = newEntity.chaptersCount,
                    parentChapterId = newEntity.parentChapterId,
                ),
            )
        }
        return newEntity
    }

    private suspend fun deleteWorkHistoryWithoutFavourite() {
        for (activeHistory in db.getWorkHistoryDao().dump().toList().filter { it.deletedAt == 0L }) {
            val isFavorite = isWorkFavorite(activeHistory.entityId)
            if (!isFavorite) {
                db.getWorkHistoryDao().delete(activeHistory.entityId)
            }
        }
    }

    private suspend fun isWorkFavorite(entityId: Long): Boolean {
        return db.getWorkFavouritesDao().findCategoriesCount(entityId) > 0
    }

    private suspend fun normalizeWorkHistory() {
        val localHistory = db.getHistoryDao().findAllEntriesIncludingDeleted()
        if (localHistory.isEmpty()) {
            return
        }
        val mangaById = db.getMangaDao()
            .findEntitiesByIds(localHistory.map { it.mangaId }.distinct())
            .associateBy { it.id }
        val contentById = mangaById.mapValues { (_, manga) -> manga.toContent(tags = emptySet(), chapters = null) }
        val ensuredEntityIds = ensureMigrationWorkEntities(contentById.values)
        val existingEntityIds = resolveEntityIdsByMangaIds(localHistory.map { it.mangaId })
        val entityIdsByMangaId = existingEntityIds + ensuredEntityIds
        val normalized = LinkedHashMap<Long, WorkHistoryEntity>()
        for (history in localHistory) {
            val entityId = entityIdsByMangaId[history.mangaId] ?: continue
            val candidate = WorkHistoryEntity(
                entityId = entityId,
                anchorMangaId = history.mangaId,
                createdAt = history.createdAt,
                updatedAt = history.updatedAt,
                chapterId = history.chapterId,
                page = history.page,
                scroll = history.scroll,
                percent = history.percent,
                deletedAt = history.deletedAt,
                chaptersCount = history.chaptersCount,
                parentChapterId = history.parentChapterId,
            )
            val existing = normalized[entityId]
            normalized[entityId] = if (existing == null || candidate.updatedAt >= existing.updatedAt) {
                candidate.copy(createdAt = minOf(existing?.createdAt ?: candidate.createdAt, candidate.createdAt))
            } else {
                existing
            }
        }
        db.withTransaction {
            normalized.values.forEach { candidate ->
                val local = db.getWorkHistoryDao().find(candidate.entityId)
                if (local == null || candidate.updatedAt >= local.updatedAt) {
                    db.getWorkHistoryDao().upsert(
                        candidate.copy(createdAt = minOf(local?.createdAt ?: candidate.createdAt, candidate.createdAt)),
                    )
                }
            }
            db.getHistoryDao().clear()
        }
    }

    private suspend fun resolveEntityIdsByMangaIds(mangaIds: Collection<Long>): Map<Long, Long> {
        return identitiesToEntityIds(workResolver.resolveManyByMangaIds(mangaIds))
    }

    private suspend fun ensureMigrationWorkEntities(contents: Collection<Content>): Map<Long, Long> {
        return contents.associate { content ->
            content.id to requireNotNull(
                workResolver.ensureForProjection(
                    content = content,
                    provenance = WorkIdentityProvenance.MIGRATION,
                ).entityId,
            )
        }
    }

    private fun identitiesToEntityIds(
        identitiesByMangaId: Map<Long, org.skepsun.kototoro.work.domain.WorkIdentity>,
    ): Map<Long, Long> {
        return identitiesByMangaId
            .mapValues { (_, identity) -> identity.entityId }
            .filterValues { it != null }
            .mapValues { (_, entityId) -> requireNotNull(entityId) }
    }

    private fun WorkHistoryEntity.toLegacyHistoryEntity() = HistoryEntity(
        mangaId = anchorMangaId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        chapterId = chapterId,
        page = page,
        scroll = scroll,
        percent = percent,
        deletedAt = deletedAt,
        chaptersCount = chaptersCount,
        parentChapterId = parentChapterId,
    )

    private fun HistoryWithContent.toContent() = manga.toContent(tags.toContentTags(), null)

    private fun Content.matchesHistorySearch(query: String, kind: SearchKind): Boolean {
        val normalizedQuery = query.lowercase()
        fun String?.containsQuery() = this?.lowercase()?.contains(normalizedQuery) == true
        fun Iterable<String>.anyContainsQuery() = any { it.lowercase().contains(normalizedQuery) }
        return when (kind) {
            SearchKind.SIMPLE,
            SearchKind.TITLE,
            SearchKind.ADVANCED -> {
                title.containsQuery() ||
                    altTitles.anyContainsQuery()
            }
            SearchKind.AUTHOR -> authors.anyContainsQuery()
            SearchKind.TAG -> tags.any { it.title.containsQuery() }
        }
    }

    /**
     * T4B.1: progress write committed — publish [SourceTrackerEvent.Read].
     *
     * Only fires from deliberate `updateProgress` calls (details-page progress setter /
     * mark-complete), not from per-page reader autosaves in [addOrUpdate].
     */
    private suspend fun emitRead(mangaId: Long, percent: Float) {
        val content = db.getMangaDao().find(mangaId)?.toContent() ?: return
        emitSourceTrackerEvent(
            SourceTrackerEvent.Read(
                contentId = content.id,
                sourceKey = content.source.name,
                percent = percent,
                contentUrl = content.eventUrl(),
            ),
        )
    }

    /** T4B.1: single-content history deletion (mark unread) committed — publish [SourceTrackerEvent.Unread]. */
    private fun emitUnread(manga: Content) {
        emitSourceTrackerEvent(
            SourceTrackerEvent.Unread(
                contentId = manga.id,
                sourceKey = manga.source.name,
                contentUrl = manga.eventUrl(),
            ),
        )
    }

    /** T4B.1: bulk history deletion committed — publish [SourceTrackerEvent.Unread] per resolved content. */
    private suspend fun emitUnreadAll(mangaIds: Collection<Long>) {
        for (mangaId in mangaIds) {
            val content = db.getMangaDao().find(mangaId)?.toContent() ?: continue
            emitSourceTrackerEvent(
                SourceTrackerEvent.Unread(
                    contentId = content.id,
                    sourceKey = content.source.name,
                    contentUrl = content.eventUrl(),
                ),
            )
        }
    }

    /** Best-effort post-commit publish: DB result is already final, so never propagate emitter failures. */
    private fun emitSourceTrackerEvent(event: SourceTrackerEvent) {
        try {
            sourceTrackerEvents.emit(event)
        } catch (e: Exception) {
            // Local write already succeeded; a broken emitter must not surface to the caller.
        }
    }

    private fun Content.eventUrl(): String? {
        return publicUrl.takeIf { it.isNotBlank() } ?: url.takeIf { it.isNotBlank() }
    }
}
