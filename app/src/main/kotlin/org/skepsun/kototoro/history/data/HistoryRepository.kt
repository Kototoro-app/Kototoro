package org.skepsun.kototoro.history.data

import android.util.Log
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_HISTORY
import org.skepsun.kototoro.core.db.TABLE_MANGA
import org.skepsun.kototoro.core.db.TABLE_MANGA_TAGS
import org.skepsun.kototoro.core.db.TABLE_TAGS
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.ContentHistory
import org.skepsun.kototoro.core.model.getContentType
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.model.isNsfw
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.ProgressIndicatorMode
import org.skepsun.kototoro.core.ui.util.ReversibleHandle
import org.skepsun.kototoro.core.model.toContentSources
import org.skepsun.kototoro.history.domain.model.ContentWithHistory
import org.skepsun.kototoro.history.domain.recoverHistoryChapterId
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
import org.skepsun.kototoro.space.domain.SpaceContentPolicy
import org.skepsun.kototoro.space.domain.SpaceId
import org.skepsun.kototoro.tracker.domain.CheckNewChaptersUseCase
import org.skepsun.kototoro.tracker.domain.SourceTrackerEvent
import org.skepsun.kototoro.tracker.domain.SourceTrackerEventEmitter
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider

private const val NSFW_FILTER_BATCH_SIZE = 32

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
    private val spaceContentPolicy: SpaceContentPolicy,
    private val sourceTrackerEvents: SourceTrackerEventEmitter,
) {

    suspend fun getList(offset: Int, limit: Int): List<Content> {
        return findRecentContents(offset, limit)
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
        return findRecentContents(
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
                TABLE_HISTORY,
                TABLE_MANGA,
                TABLE_TAGS,
                TABLE_MANGA_TAGS,
            ),
            emitInitialState = true,
        )
        val allowedSourceNames = spaceId?.let(spaceContentPolicy::observeAllowedSourceNames) ?: flowOf(null)
        return combine(invalidations, allowedSourceNames) { _, sources -> sources }
            .mapLatest { sources ->
                findRecentContents(
                    offset = 0,
                    limit = 1,
                    spaceId = spaceId,
                    allowedSourceNames = sources,
                    excludeNsfw = excludeNsfw,
                ).firstOrNull()
            }.distinctUntilChanged()
    }

    fun observeRecentBySpace(
        spaceId: SpaceId? = null,
        limit: Int = 5,
        excludeNsfw: Boolean = false,
    ): Flow<List<Content>> {
        val invalidations = db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_HISTORY,
                TABLE_MANGA,
                TABLE_TAGS,
                TABLE_MANGA_TAGS,
            ),
            emitInitialState = true,
        )
        val allowedSourceNames = spaceId?.let(spaceContentPolicy::observeAllowedSourceNames) ?: flowOf(null)
        return combine(invalidations, allowedSourceNames) { _, sources -> sources }
            .mapLatest { sources ->
                findRecentContents(
                    offset = 0,
                    limit = limit,
                    spaceId = spaceId,
                    allowedSourceNames = sources,
                    excludeNsfw = excludeNsfw,
                )
            }.distinctUntilChanged()
    }

    fun observeAll(): Flow<List<Content>> {
        return observeRecentContents(limit = null)
    }

    fun observeCount(): Flow<Int> {
        return db.getHistoryDao().observeCountActive()
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
                TABLE_HISTORY,
                TABLE_MANGA,
                TABLE_TAGS,
                TABLE_MANGA_TAGS,
            ),
            emitInitialState = true,
        ).mapLatest {
            val queryLimit = if (tabTypes != null) limit * 3 else limit
            val recent = db.getHistoryDao().findRecent(queryLimit)
            recent.asSequence()
                .map { row ->
                    val content = row.toContent()
                    ContentWithHistory(
                        manga = content,
                        history = row.history.toContentHistory(),
                        entityId = content.id,
                        preferredLocalMangaId = content.id,
                    )
                }
                .filter { item -> tabTypes == null || item.manga.source.getContentType() in tabTypes }
                .take(limit)
                .toList()
        }.distinctUntilChanged()
    }

    fun observeOne(id: Long): Flow<ContentHistory?> {
        return db.invalidationTracker.createFlow(
            tables = arrayOf(TABLE_HISTORY, TABLE_MANGA),
            emitInitialState = true,
        ).mapLatest {
            db.getHistoryDao().find(id)?.takeIf { it.deletedAt == 0L }?.toContentHistory()
        }.distinctUntilChanged()
    }

    suspend fun addOrUpdate(
        manga: Content,
        chapterId: Long,
        page: Int,
        scroll: Int,
        percent: Float,
        force: Boolean,
        parentChapterId: Long? = null
    ) {
        if (!force && shouldSkip(manga)) {
            return
        }
        assert(manga.chapters != null)
        db.withTransaction {
            val storedManga = mangaRepository.storeContentAndReturn(manga, replaceExisting = true)
            val branch = manga.chapters?.findById(chapterId)?.branch
            val now = System.currentTimeMillis()
            db.getHistoryDao().upsert(
                HistoryEntity(
                    mangaId = storedManga.id,
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
            newChaptersUseCaseProvider.get()(manga, chapterId, percent)
            scrobblers.forEach { it.tryScrobble(manga, chapterId) }
        }
    }

    suspend fun getOne(manga: Content): ContentHistory? {
        val entity = db.getHistoryDao().find(manga.id)?.takeIf { it.deletedAt == 0L }
        val recovered = entity?.recoverIfNeeded(manga)
        return recovered?.toContentHistory()
    }

    suspend fun getProgress(mangaId: Long, mode: ProgressIndicatorMode): ReadingProgress? {
        val entity = db.getHistoryDao().find(mangaId)?.takeIf { it.deletedAt == 0L } ?: return null
        return ReadingProgress.fromHistory(
            percent = entity.percent,
            totalChapters = entity.chaptersCount,
            mode = mode,
        )
    }

    suspend fun getProgress(mangaIds: Collection<Long>, mode: ProgressIndicatorMode): Map<Long, ReadingProgress> {
        if (mangaIds.isEmpty()) return emptyMap()
        val distinctMangaIds = mangaIds.distinct()
        val historyByMangaId = db.getHistoryDao().findAllByMangaIds(distinctMangaIds)
            .associateBy { it.mangaId }
        return buildMap {
            distinctMangaIds.forEach { mangaId ->
                val history = historyByMangaId[mangaId] ?: return@forEach
                val progress = ReadingProgress.fromHistory(
                    percent = history.percent,
                    totalChapters = history.chaptersCount,
                    mode = mode,
                ) ?: return@forEach
                put(mangaId, progress)
            }
        }
    }

    suspend fun updateProgress(mangaId: Long, percent: Float, chaptersCount: Int): Boolean {
        val entity = db.getHistoryDao().find(mangaId)?.takeIf { it.deletedAt == 0L } ?: return false
        if (entity.percent == percent && entity.chaptersCount == chaptersCount) {
            return false
        }
        db.getHistoryDao().update(
            entity.copy(
                percent = percent,
                chaptersCount = chaptersCount,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        emitRead(mangaId, percent)
        return true
    }

    suspend fun clear() {
        db.getHistoryDao().clear()
    }

    suspend fun delete(manga: Content) {
        db.withTransaction {
            db.getHistoryDao().delete(manga.id)
            mangaRepository.gcChaptersCache()
        }
        emitUnread(manga)
    }

    suspend fun deleteAfter(minDate: Long) = db.withTransaction {
        db.getHistoryDao().deleteAfter(minDate)
        mangaRepository.gcChaptersCache()
    }

    suspend fun deleteNotFavorite() = db.withTransaction {
        db.getHistoryDao().deleteNotFavorite()
        mangaRepository.gcChaptersCache()
    }

    suspend fun delete(ids: Collection<Long>): ReversibleHandle {
        db.withTransaction {
            for (id in ids) {
                db.getHistoryDao().delete(id)
            }
            mangaRepository.gcChaptersCache()
        }
        emitUnreadAll(ids)
        return ReversibleHandle {
            recover(ids)
        }
    }

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

    private suspend fun recover(ids: Collection<Long>) {
        db.withTransaction {
            for (id in ids) {
                db.getHistoryDao().recover(id)
            }
        }
    }

    private fun observeRecentContents(limit: Int?): Flow<List<Content>> {
        return db.invalidationTracker.createFlow(
            tables = arrayOf(
                TABLE_HISTORY,
                TABLE_MANGA,
            ),
            emitInitialState = true,
        ).mapLatest {
            findRecentContents(offset = 0, limit = limit)
        }.distinctUntilChanged()
    }

    private suspend fun findRecentContents(
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
        // NSFW rows are filtered after loading, so keep widening the window until enough
        // visible rows are found or the history is exhausted.
        var fetchSize = if (excludeNsfw) maxOf(targetSize, NSFW_FILTER_BATCH_SIZE) else targetSize
        while (true) {
            val rows = findRecentRows(spaceId, allowedSourceNames, fetchSize)
            val contents = rows.map { it.toContent() }
            val visible = if (excludeNsfw) contents.filterNot { it.isNsfw() } else contents
            if (visible.size >= targetSize || rows.size < fetchSize || fetchSize == Int.MAX_VALUE) {
                return visible.drop(offset).let { if (limit == null) it else it.take(limit) }
            }
            fetchSize = if (fetchSize > Int.MAX_VALUE / 2) Int.MAX_VALUE else fetchSize * 2
        }
    }

    private suspend fun findRecentRows(
        spaceId: SpaceId?,
        allowedSourceNames: Set<String>?,
        limit: Int,
    ): List<HistoryWithContent> {
        val dao = db.getHistoryDao()
        if (spaceId == null) {
            return dao.findRecent(limit)
        }
        val allowedTypes = spaceContentPolicy.allowedTypes(spaceId).map { it.name }
        return if (allowedSourceNames != null) {
            dao.findRecentForSpaceAndSources(
                allowedTypes = allowedTypes,
                allowedSources = allowedSourceNames,
                limit = limit,
            )
        } else {
            dao.findRecentForSpace(allowedTypes = allowedTypes, limit = limit)
        }
    }

    private suspend fun getAllRecentContents(maxCount: Int = Int.MAX_VALUE): List<Content> {
        return findRecentContents(offset = 0, limit = if (maxCount == Int.MAX_VALUE) null else maxCount)
    }

    private suspend fun HistoryEntity.recoverIfNeeded(manga: Content): HistoryEntity {
        val newChapterId = recoverHistoryChapterId(
            isLocal = manga.isLocal,
            chapterId = chapterId,
            parentChapterId = parentChapterId,
            percent = percent,
            chapterIds = manga.chapters?.map { it.id },
        ) ?: return this
        val newEntity = copy(chapterId = newChapterId)
        db.getHistoryDao().update(newEntity)
        return newEntity
    }

    private fun HistoryEntity.toContentHistory() = ContentHistory(
        createdAt = Instant.ofEpochMilli(createdAt),
        updatedAt = Instant.ofEpochMilli(updatedAt),
        chapterId = chapterId,
        page = page,
        scroll = scroll.toInt(),
        percent = percent,
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

    private fun emitUnread(manga: Content) {
        emitSourceTrackerEvent(
            SourceTrackerEvent.Unread(
                contentId = manga.id,
                sourceKey = manga.source.name,
                contentUrl = manga.eventUrl(),
            ),
        )
    }

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
