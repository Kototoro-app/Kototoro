package org.skepsun.kototoro.history.data

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.TABLE_FAVOURITES
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.TABLE_WORK_HISTORY
import org.skepsun.kototoro.core.db.TABLE_WORK_FAVOURITES
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentList
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.db.entity.toContentTagsList
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
import org.skepsun.kototoro.parsers.util.findById
import org.skepsun.kototoro.parsers.util.levenshteinDistance
import org.skepsun.kototoro.scrobbling.common.domain.Scrobbler
import org.skepsun.kototoro.scrobbling.common.domain.tryScrobble
import org.skepsun.kototoro.search.domain.SearchKind
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import javax.inject.Inject
import javax.inject.Provider

@Reusable
class HistoryRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val scrobblers: Set<@JvmSuppressWildcards Scrobbler>,
	private val mangaRepository: ContentDataRepository,
	private val localObserver: HistoryLocalObserver,
	private val newChaptersUseCaseProvider: Provider<CheckNewChaptersUseCase>,
	private val entityGraphRepository: EntityGraphRepository,
) {

	private data class WorkHistoryOwner(
		val entityId: Long?,
		val anchorMangaId: Long,
	)

	private data class RecentContentEntry(
		val updatedAt: Long,
		val content: Content,
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

	suspend fun getLastOrNull(): Content? {
		return findRecentContentsByWorkAnchor(offset = 0, limit = 1).firstOrNull()
	}

	fun observeLast(): Flow<Content?> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(
				TABLE_HISTORY,
				TABLE_WORK_HISTORY,
				TABLE_ENTITY_GRAPH_BINDING,
				TABLE_ENTITY_PREFERENCES,
				TABLE_MANGA,
			),
			emitInitialState = true,
		).mapLatest {
			findRecentContentsByWorkAnchor(offset = 0, limit = 1).firstOrNull()
		}.distinctUntilChanged()
	}

	fun observeAll(): Flow<List<Content>> {
		return observeRecentContents(limit = null)
	}

	fun observeCount(): Flow<Int> {
		return db.getHistoryDao().observeCount()
	}

	fun observeAll(limit: Int): Flow<List<Content>> {
		return observeRecentContents(limit)
	}

	fun observeAllWithHistory(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int
	): Flow<List<ContentWithHistory>> {
		val requiresLocalMapping = ListFilterOption.Downloaded in filterOptions
		val effectiveFilters = if (requiresLocalMapping) {
			filterOptions - ListFilterOption.Downloaded
		} else {
			filterOptions
		}
		val flow = db.invalidationTracker.createFlow(
			tables = arrayOf(
				TABLE_HISTORY,
				TABLE_WORK_HISTORY,
				TABLE_ENTITY_GRAPH_BINDING,
				TABLE_ENTITY_PREFERENCES,
				TABLE_MANGA,
				TABLE_FAVOURITES,
				TABLE_WORK_FAVOURITES,
				TABLE_MANGA_TAGS,
				TABLE_TAGS,
				"tracks",
				"local_index",
			),
			emitInitialState = true,
		).mapLatest {
			buildObservedHistoryList(order, effectiveFilters, limit)
		}.distinctUntilChanged()
		return if (requiresLocalMapping) {
			localObserver.observe(flow)
		} else {
			flow
		}
	}

	fun observeOne(id: Long): Flow<ContentHistory?> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(
				TABLE_HISTORY,
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
			mangaRepository.storeContent(anchorManga, replaceExisting = true)
			val branch = manga.chapters?.findById(chapterId)?.branch
			val now = System.currentTimeMillis()
			val entity = HistoryEntity(
				mangaId = anchorManga.id,
				createdAt = now,
				updatedAt = now,
				chapterId = chapterId,
				page = page,
				scroll = scroll.toFloat(), // we migrate to int, but decide to not update database
				percent = percent,
				chaptersCount = manga.chapters?.count { it.branch == branch } ?: 0,
				deletedAt = 0L,
				parentChapterId = parentChapterId,  // 保存父章节ID
			)
			android.util.Log.d("HistoryRepository", "Upserting history: anchorMangaId=${anchorManga.id}, sourceMangaId=${manga.id}, chapterId=$chapterId, parentChapterId=$parentChapterId")
			try {
				val result = db.getHistoryDao().upsert(entity)
				android.util.Log.d("HistoryRepository", "Upsert result: $result (true=inserted, false=updated)")
			} catch (e: Exception) {
				android.util.Log.e("HistoryRepository", "Upsert failed", e)
				throw e
			}
			owner.entityId?.let { entityId ->
				db.getWorkHistoryDao().upsert(
					WorkHistoryEntity(
						entityId = entityId,
						anchorMangaId = anchorManga.id,
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
			newChaptersUseCaseProvider.get()(manga, chapterId)
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
		return buildMap {
			mangaIds.distinct().forEach { mangaId ->
				val progress = getProgress(mangaId, mode) ?: return@forEach
				put(mangaId, progress)
			}
		}
	}

	suspend fun clear() {
		db.getWorkHistoryDao().clear()
		db.getHistoryDao().clear()
	}

	suspend fun delete(manga: Content) = db.withTransaction {
		val ownerRef = resolveHistoryOwnerRef(manga.id)
		ownerRef.entityId?.let { entityId ->
			db.getWorkHistoryDao().delete(entityId)
		}
		resolveHistoryAnchorIds(ownerRef).forEach { historyMangaId ->
			db.getHistoryDao().delete(historyMangaId)
		}
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteAfter(minDate: Long) = db.withTransaction {
		db.getWorkHistoryDao().deleteAfter(minDate)
		db.getHistoryDao().deleteAfter(minDate)
		mangaRepository.gcChaptersCache()
	}

	suspend fun deleteNotFavorite() = db.withTransaction {
		mirrorLegacyDeletesToWorkHistory()
		db.getHistoryDao().deleteNotFavorite()
		mangaRepository.gcChaptersCache()
	}

	suspend fun delete(ids: Collection<Long>): ReversibleHandle {
		val ownerRefs = ids.mapTo(LinkedHashSet()) { resolveHistoryOwnerRef(it) }
		val resolvedIds = ownerRefs.flatMapTo(LinkedHashSet()) { resolveHistoryAnchorIds(it) }
		val resolvedEntityIds = ownerRefs.mapNotNullTo(LinkedHashSet()) { it.entityId }
		db.withTransaction {
			for (entityId in resolvedEntityIds) {
				db.getWorkHistoryDao().delete(entityId)
			}
			for (id in resolvedIds) {
				db.getHistoryDao().delete(id)
			}
			mangaRepository.gcChaptersCache()
		}
		return ReversibleHandle {
			recover(resolvedIds, resolvedEntityIds)
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

	suspend fun getPopularTags(limit: Int): List<ContentTag> {
		if (limit <= 0) {
			return emptyList()
		}
		return getAllRecentContents()
			.asSequence()
			.flatMap { it.tags.asSequence() }
			.groupingBy { it }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(limit)
			.map { it.key }
	}

	suspend fun getPopularSources(limit: Int): List<ContentSource> {
		if (limit <= 0) {
			return emptyList()
		}
		return getAllRecentContents()
			.groupingBy { it.source }
			.eachCount()
			.entries
			.sortedByDescending { it.value }
			.take(limit)
			.map { it.key }
	}

	fun shouldSkip(manga: Content): Boolean = settings.isIncognitoModeEnabled(manga.isNsfw())

	fun observeShouldSkip(manga: Content): Flow<Boolean> {
		return settings.observe(AppSettings.KEY_INCOGNITO_MODE, AppSettings.KEY_INCOGNITO_NSFW)
			.map { shouldSkip(manga) }
			.distinctUntilChanged()
	}

	private suspend fun recover(historyIds: Collection<Long>, entityIds: Collection<Long>) {
		db.withTransaction {
			for (entityId in entityIds) {
				db.getWorkHistoryDao().recover(entityId)
			}
			for (id in historyIds) {
				db.getHistoryDao().recover(id)
			}
		}
	}

	private suspend fun getOneByWorkAnchor(mangaId: Long): ContentHistory? {
		return findHistoryEntityByWorkAnchor(mangaId)?.toContentHistory()
	}

	private fun observeRecentContents(limit: Int?): Flow<List<Content>> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(
				TABLE_HISTORY,
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

	private suspend fun findRecentContentsByWorkAnchor(offset: Int, limit: Int?): List<Content> {
		if (limit != null && limit <= 0) {
			return emptyList()
		}
		val targetSize = if (limit == null) Int.MAX_VALUE else offset + limit
		val entries = ArrayList<RecentContentEntry>()
		entries += collectRecentWorkEntries(targetSize)
		entries += collectRecentLegacyEntries(targetSize)
		return entries
			.sortedByDescending { it.updatedAt }
			.drop(offset)
			.let { list ->
				if (limit == null) list else list.take(limit)
			}
			.map { it.content }
	}

	private suspend fun getAllRecentContents(maxCount: Int = Int.MAX_VALUE): List<Content> {
		return findRecentContentsByWorkAnchor(offset = 0, limit = if (maxCount == Int.MAX_VALUE) null else maxCount)
	}

	private suspend fun collectRecentWorkEntries(targetSize: Int): List<RecentContentEntry> {
		val result = ArrayList<RecentContentEntry>()
		val pageSize = if (targetSize == Int.MAX_VALUE) 100 else minOf(100, maxOf(targetSize, 20))
		var offset = 0
		while (true) {
			val page = db.getWorkHistoryDao().findAll(offset, pageSize)
				.filter { it.deletedAt == 0L }
			if (page.isEmpty()) {
				break
			}
			page.mapNotNullTo(result) { history ->
				val content = resolveRepresentativeContentForWorkHistory(history)
					?: db.getMangaDao().find(history.anchorMangaId)?.toContent()
				content?.let { RecentContentEntry(history.updatedAt, it) }
			}
			offset += pageSize
			if (targetSize != Int.MAX_VALUE && result.size >= targetSize) {
				break
			}
		}
		return result
	}

	private suspend fun collectRecentLegacyEntries(targetSize: Int): List<RecentContentEntry> {
		val result = ArrayList<RecentContentEntry>()
		val pageSize = if (targetSize == Int.MAX_VALUE) 100 else minOf(100, maxOf(targetSize, 20))
		var offset = 0
		while (true) {
			val page = db.getHistoryDao().findAll(offset, pageSize)
			if (page.isEmpty()) {
				break
			}
			val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(page.map { it.manga.id })
			for (history in page) {
				if (entityIdsByMangaId[history.manga.id] != null) {
					continue
				}
				result += RecentContentEntry(history.history.updatedAt, history.toContent())
			}
			offset += pageSize
			if (targetSize != Int.MAX_VALUE && result.size >= targetSize) {
				break
			}
		}
		return result
	}

	private suspend fun buildObservedHistoryList(
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		limit: Int,
	): List<ContentWithHistory> {
		val oversampleLimit = if (limit > 0) limit * 4 else Int.MAX_VALUE
		val favouriteCache = HashMap<Long, Set<Long>>()
		val trackCache = HashMap<Long, TrackAggregate>()
		val contents = getAllRecentContents(oversampleLimit)

		// ---- Batch entity resolution (eliminates N+1) ----
		val allMangaIds = contents.mapTo(LinkedHashSet()) { it.id }
		val entityIdsByMangaId = entityGraphRepository.findEntityIdsByAnyMangaIds(allMangaIds)
		val distinctEntityIds = entityIdsByMangaId.values.distinct()

		// Batch entity preferences
		val prefByEntityId = if (distinctEntityIds.isEmpty()) {
			emptyMap()
		} else {
			db.getEntityGraphDao().findEntityPrefsByIds(distinctEntityIds)
				.associateBy({ it.entityId }, { it.preferredLocalMangaId })
		}

		// Build owner refs from precomputed maps (no per-item DB calls)
		val ownerRefsByMangaId = allMangaIds.associateWith { mangaId ->
			val entityId = entityIdsByMangaId[mangaId]
			val anchorMangaId = if (entityId != null) {
				prefByEntityId[entityId] ?: mangaId
			} else {
				mangaId
			}
			HistoryOwnerRef(
				cacheKey = entityId ?: -mangaId,
				entityId = entityId,
				anchorMangaId = anchorMangaId,
			)
		}

		val preferredLocalIdsByEntity = distinctEntityIds.associateWith { entityId ->
			prefByEntityId[entityId]
		}

		// Batch work history lookup
		val workHistoryByEntityId = if (distinctEntityIds.isEmpty()) {
			emptyMap()
		} else {
			db.getWorkHistoryDao().findByEntityIds(distinctEntityIds)
				.filter { it.deletedAt == 0L }
				.associateBy(WorkHistoryEntity::entityId)
		}

		// Resolve legacy anchor IDs using precomputed pref map
		val entitiesWithoutPref = distinctEntityIds.filter { prefByEntityId[it] == null }
		val bindingsByEntityId = if (entitiesWithoutPref.isEmpty()) {
			emptyMap()
		} else {
			db.getEntityGraphDao().findActiveBindingsByEntities(entitiesWithoutPref)
				.groupBy({ it.entityId }, { it.externalId.toLongOrNull() })
				.mapValues { (_, ids) -> ids.filterNotNull().toSet() }
		}
		val legacyAnchorIds = ownerRefsByMangaId.values
			.flatMapTo(LinkedHashSet()) { resolveHistoryAnchorIds(it, prefByEntityId, bindingsByEntityId) }
		val legacyHistoryByMangaId = db.getHistoryDao().findByIds(legacyAnchorIds).associateBy(HistoryEntity::mangaId)
		val baseList = contents.mapNotNull { content ->
			val ownerRef = ownerRefsByMangaId.getValue(content.id)
			val history = ownerRef.entityId?.let(workHistoryByEntityId::get)?.toLegacyHistoryEntity()
				?: resolveHistoryAnchorIds(ownerRef)
					.firstNotNullOfOrNull(legacyHistoryByMangaId::get)
				?: return@mapNotNull null
			ContentWithHistory(
				manga = content,
				history = history.toContentHistory(),
				entityId = ownerRef.entityId,
				preferredLocalMangaId = ownerRef.entityId?.let(preferredLocalIdsByEntity::get) ?: content.id,
			)
		}
		val filtered = baseList
			.filter { item ->
				matchesHistoryFilters(
					item = item,
					filterOptions = filterOptions,
					favouriteCache = favouriteCache,
					trackCache = trackCache,
				)
			}
		prewarmTrackAggregatesIfNeeded(
			items = filtered,
			order = order,
			filterOptions = filterOptions,
			trackCache = trackCache,
		)
		return filtered.sortedWith(
			historyComparator(
				order = order,
				favouriteCache = favouriteCache,
				trackCache = trackCache,
			),
		)
			.let { if (limit > 0) it.take(limit) else it }
	}

	private suspend fun matchesHistoryFilters(
		item: ContentWithHistory,
		filterOptions: Set<ListFilterOption>,
		favouriteCache: MutableMap<Long, Set<Long>>,
		trackCache: MutableMap<Long, TrackAggregate>,
	): Boolean {
		return filterOptions.all { option ->
			when (option) {
				ListFilterOption.Downloaded -> true
				ListFilterOption.Macro.COMPLETED -> ReadingProgress.isCompleted(item.history.percent)
				ListFilterOption.Macro.NEW_CHAPTERS -> getTrackAggregate(item, trackCache).newChapters > 0
				ListFilterOption.Macro.FAVORITE -> getFavouriteCategoryIds(item, favouriteCache).isNotEmpty()
				ListFilterOption.Macro.NSFW -> item.manga.isNsfw()
				is ListFilterOption.Inverted -> when (option.option) {
					ListFilterOption.Macro.NSFW -> !item.manga.isNsfw()
					ListFilterOption.Macro.FAVORITE -> getFavouriteCategoryIds(item, favouriteCache).isEmpty()
					else -> true
				}
				is ListFilterOption.Tag -> item.manga.tags.any { tag -> tag.title == option.tag.title && tag.key == option.tag.key }
				is ListFilterOption.Source -> item.manga.source.name == option.mangaSource.name
				is ListFilterOption.Favorite -> option.category.id in getFavouriteCategoryIds(item, favouriteCache)
				is ListFilterOption.Branch -> {
					val branch = item.manga.findChapterById(item.history.chapterId)?.branch
					branch == option.titleText
				}
			}
		}
	}

	private fun historyComparator(
		order: ListSortOrder,
		favouriteCache: MutableMap<Long, Set<Long>>,
		trackCache: MutableMap<Long, TrackAggregate>,
	): Comparator<ContentWithHistory> {
		val titleComparator = compareBy<ContentWithHistory> { it.manga.title }
		return when (order) {
			ListSortOrder.LAST_READ -> compareByDescending<ContentWithHistory> { it.history.updatedAt.toEpochMilli() }
			ListSortOrder.LONG_AGO_READ -> compareBy<ContentWithHistory> { it.history.updatedAt.toEpochMilli() }
			ListSortOrder.NEWEST -> compareByDescending<ContentWithHistory> { it.history.createdAt.toEpochMilli() }
			ListSortOrder.OLDEST -> compareBy<ContentWithHistory> { it.history.createdAt.toEpochMilli() }
			ListSortOrder.PROGRESS -> compareByDescending<ContentWithHistory> { it.history.percent }
			ListSortOrder.UNREAD -> compareBy<ContentWithHistory> { it.history.percent }
			ListSortOrder.ALPHABETIC -> titleComparator
			ListSortOrder.ALPHABETIC_REVERSE -> titleComparator.reversed()
			ListSortOrder.NEW_CHAPTERS -> compareByDescending<ContentWithHistory> {
				getCachedTrackAggregate(it, trackCache).newChapters
			}.thenByDescending { it.history.updatedAt.toEpochMilli() }
			ListSortOrder.UPDATED -> compareByDescending<ContentWithHistory> {
				getCachedTrackAggregate(it, trackCache).lastChapterDate
			}.thenByDescending { it.history.updatedAt.toEpochMilli() }
			else -> compareByDescending<ContentWithHistory> { it.history.updatedAt.toEpochMilli() }
		}
	}

	private suspend fun prewarmTrackAggregatesIfNeeded(
		items: List<ContentWithHistory>,
		order: ListSortOrder,
		filterOptions: Set<ListFilterOption>,
		trackCache: MutableMap<Long, TrackAggregate>,
	) {
		val needsTrackData = order == ListSortOrder.NEW_CHAPTERS ||
			order == ListSortOrder.UPDATED ||
			ListFilterOption.Macro.NEW_CHAPTERS in filterOptions
		if (!needsTrackData) {
			return
		}
		items.forEach { item ->
			getTrackAggregate(item, trackCache)
		}
	}

	private suspend fun getFavouriteCategoryIds(
		item: ContentWithHistory,
		cache: MutableMap<Long, Set<Long>>,
	): Set<Long> {
		val ownerRef = resolveHistoryOwnerRef(item)
		return cache.getOrPut(ownerRef.cacheKey) {
			runBlockingCategoryLookup(ownerRef)
		}
	}

	private suspend fun runBlockingCategoryLookup(ownerRef: HistoryOwnerRef): Set<Long> {
		val result = LinkedHashSet<Long>()
		resolveHistoryAnchorIds(ownerRef).forEach { anchorId ->
			result += db.getFavouritesDao().findCategoriesIds(anchorId)
		}
		return result
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

	private fun getCachedTrackAggregate(
		item: ContentWithHistory,
		cache: MutableMap<Long, TrackAggregate>,
	): TrackAggregate {
		val cacheKey = item.entityId ?: -item.manga.id
		return cache[cacheKey] ?: TrackAggregate(
			newChapters = 0,
			lastChapterDate = 0L,
		)
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
		val ownerRef = resolveHistoryOwnerRef(mangaId)
		findWorkHistoryEntityByWorkAnchor(mangaId)?.let { return it.toLegacyHistoryEntity() }
		for (anchorId in resolveHistoryAnchorIds(ownerRef)) {
			db.getHistoryDao().find(anchorId)?.let { return it }
		}
		return null
	}

	private suspend fun findWorkHistoryEntityByWorkAnchor(mangaId: Long): WorkHistoryEntity? {
		val entityId = resolveWorkEntityId(mangaId) ?: return null
		return db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L }
	}

	private suspend fun resolveRepresentativeContentForWorkHistory(entity: WorkHistoryEntity): Content? {
		val preferredLocalId = db.getEntityGraphDao().findEntityPrefs(entity.entityId)?.preferredLocalMangaId
		val candidateIds = buildList {
			preferredLocalId?.let(::add)
			add(entity.anchorMangaId)
		}.distinct()
		for (candidateId in candidateIds) {
			db.getMangaDao().find(candidateId)?.toContent()?.let { return it }
		}
		return null
	}

	private suspend fun resolveWorkHistoryOwner(manga: Content): WorkHistoryOwner {
		// History ownership is work-first. The returned anchor manga id is the preferred local
		// projection used for legacy history rows and compatibility lookups.
		val entityId = resolveWorkEntityId(manga.id)
		if (entityId == null) {
			return WorkHistoryOwner(
				entityId = null,
				anchorMangaId = manga.id,
			)
		}
		val preferredLocalId = db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
		return WorkHistoryOwner(
			entityId = entityId,
			anchorMangaId = preferredLocalId ?: manga.id,
		)
	}

	private suspend fun resolveWorkEntityId(mangaId: Long): Long? {
		return entityGraphRepository.findEntityIdsByAnyMangaIds(setOf(mangaId))[mangaId]
	}

	private suspend fun resolveHistoryOwnerRef(mangaId: Long): HistoryOwnerRef {
		val entityId = resolveWorkEntityId(mangaId)
		val anchorMangaId = if (entityId != null) {
			db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId ?: mangaId
		} else {
			mangaId
		}
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
		val entityId = entityGraphRepository.findEntityIdsByAnyMangaIds(setOf(mangaId))[mangaId] ?: return setOf(mangaId)
		val preferredLocalId = db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
		if (preferredLocalId != null) {
			return setOf(preferredLocalId)
		}
		val localIds = entityGraphRepository.getBindings(entityId)
			.asSequence()
			.mapNotNull { binding ->
				when (binding.source) {
					"local_manga", "0" -> binding.externalId.toLongOrNull()
					else -> null
				}
			}
			.toCollection(LinkedHashSet())
		return if (localIds.isEmpty()) setOf(mangaId) else localIds
	}

	private suspend fun resolveHistoryAnchorIds(ownerRef: HistoryOwnerRef): Set<Long> {
		val entityId = ownerRef.entityId ?: return setOf(ownerRef.anchorMangaId)
		val preferredLocalId = db.getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
		if (preferredLocalId != null) {
			return setOf(preferredLocalId)
		}
		val localIds = entityGraphRepository.getBindings(entityId)
			.asSequence()
			.mapNotNull { binding ->
				when (binding.source) {
					"local_manga", "0" -> binding.externalId.toLongOrNull()
					else -> null
				}
			}
			.toCollection(LinkedHashSet())
		return if (localIds.isEmpty()) setOf(ownerRef.anchorMangaId) else localIds
	}

	private fun resolveHistoryAnchorIds(
		ownerRef: HistoryOwnerRef,
		prefByEntityId: Map<Long, Long?>,
		bindingsByEntityId: Map<Long, Set<Long>>,
	): Set<Long> {
		val entityId = ownerRef.entityId ?: return setOf(ownerRef.anchorMangaId)
		val preferredLocalId = prefByEntityId[entityId]
		if (preferredLocalId != null) {
			return setOf(preferredLocalId)
		}
		// Fallback: use all local manga bindings for this entity (from batch query).
		val localIds = bindingsByEntityId[entityId].orEmpty()
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
		db.getHistoryDao().update(newEntity)
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

	private suspend fun mirrorLegacyDeletesToWorkHistory() {
		val entityIds = db.getEntityGraphDao().dumpPrefs().map { it.entityId }
		for (entityId in entityIds) {
			val activeHistory = db.getWorkHistoryDao().find(entityId)?.takeIf { it.deletedAt == 0L } ?: continue
			val isFavorite = isWorkFavorite(entityId)
			if (!isFavorite) {
				db.getWorkHistoryDao().delete(entityId)
				db.getHistoryDao().delete(activeHistory.anchorMangaId)
			}
		}
	}

	private suspend fun isWorkFavorite(entityId: Long): Boolean {
		val localIds = entityGraphRepository.getBindings(entityId)
			.asSequence()
			.mapNotNull { binding ->
				when (binding.source) {
					"local_manga", "0" -> binding.externalId.toLongOrNull()
					else -> null
				}
			}
			.toList()
		if (localIds.isEmpty()) {
			return false
		}
		return db.getFavouritesDao().countByMangaIds(localIds) > 0
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
}
