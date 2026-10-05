package org.skepsun.kototoro.tracker.domain

import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.toInstantOrNull
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.toCriteria
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.TRACK_LOG_RETAINED_SIZE
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import org.skepsun.kototoro.tracker.ui.debug.TrackDebugItem
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NO_ID = 0L

/**
 * Post-update-check summary scoped to tracked favourite works.
 */
data class FavouriteUpdatesSummary(
    val worksWithUpdates: Int,
    val newChapters: Int,
)

/**
 * Counts pending updates from raw favourite track rows.
 */
internal fun summarizeFavouriteTracks(tracks: List<TrackEntity>): FavouriteUpdatesSummary = FavouriteUpdatesSummary(
    worksWithUpdates = tracks.count { it.newChapters > 0 },
    newChapters = tracks.sumOf { it.newChapters.coerceAtLeast(0) },
)

@Reusable
class TrackingRepository @Inject constructor(
    private val db: MangaDatabase,
    private val settings: AppSettings,
    private val progressUpdateUseCase: ProgressUpdateUseCase,
    private val contentDataRepository: ContentDataRepository,
) {

    private val isGcCalled = AtomicBoolean(false)

    suspend fun getNewChaptersCount(mangaId: Long): Int {
        return db.getTracksDao().findNewChapters(mangaId)
    }

    suspend fun getNewChaptersCounts(mangaIds: Collection<Long>): Map<Long, Int> {
        if (mangaIds.isEmpty()) return emptyMap()
        val distinctMangaIds = mangaIds.distinct()
        return db.getTracksDao().findNewChapters(distinctMangaIds)
            .associate { it.mangaId to it.count }
    }

    fun observeNewChaptersCount(mangaId: Long): Flow<Int> {
        return db.getTracksDao().observeNewChapters(mangaId)
    }

    fun observeUpdatedContentCount(): Flow<Int> {
        return db.getTracksDao().observeUpdateContentCount()
            .distinctUntilChanged()
            .onStart { gcIfNeeded() }
    }

    fun observeUnreadUpdatesCount(): Flow<Int> {
        return db.getTracksDao().observeUnreadWorkCount()
    }

    fun observeUpdatedContent(
        limit: Int,
        filterOptions: Set<ListFilterOption>,
        contentTypes: Collection<String>? = null,
    ): Flow<List<ContentTracking>> {
        return db.getTracksDao().observeUpdatedContent(limit, filterOptions.toCriteria(), contentTypes)
            .mapLatest { tracks ->
                tracks.mapNotNull { it.toContentTracking() }
            }.distinctUntilChanged()
            .onStart { gcIfNeeded() }
    }

    suspend fun getTracks(offset: Int, limit: Int): List<ContentTracking> {
        return db.getTracksDao().findAll(offset = offset, limit = limit)
            .mapNotNull { it.toContentTracking() }
    }

    suspend fun getFavouriteUpdatesSummary(): FavouriteUpdatesSummary {
        val trackedCategoryIds = db.getFavouriteCategoriesDao().findAll()
            .filter { it.track }
            .map { it.categoryId.toLong() }
            .toSet()
        if (trackedCategoryIds.isEmpty()) {
            return FavouriteUpdatesSummary(worksWithUpdates = 0, newChapters = 0)
        }
        val favouriteEntries = db.getFavouritesDao().findAllActiveEntries()
            .filter { it.categoryId in trackedCategoryIds }
        val mangaIds = favouriteEntries.map { it.mangaId }.distinct()
        if (mangaIds.isEmpty()) {
            return FavouriteUpdatesSummary(worksWithUpdates = 0, newChapters = 0)
        }
        return summarizeFavouriteTracks(db.getTracksDao().findByEntityIds(mangaIds))
    }

    fun observeTrackDebugItems(): Flow<List<TrackDebugItem>> {
        return db.getTracksDao().observeAll()
            .mapLatest { tracks -> resolveTrackDebugItems(tracks) }
            .onStart { gcIfNeeded() }
    }

    @Deprecated("")
    suspend fun getTrack(manga: Content): ContentTracking {
        return getTrackOrNull(manga) ?: ContentTracking(
            anchorMangaId = manga.id,
            entityId = null,
            preferredLocalMangaId = manga.id,
            manga = manga,
            lastChapterId = NO_ID,
            lastCheck = null,
            lastChapterDate = null,
            newChapters = 0,
        )
    }

    suspend fun getTrackOrNull(manga: Content): ContentTracking? {
        val track = db.getTracksDao().find(manga.id) ?: return null
        return ContentTracking(
            anchorMangaId = manga.id,
            entityId = null,
            preferredLocalMangaId = manga.id,
            manga = resolveDisplayTrackingContent(manga.id, manga),
            lastChapterId = track.lastChapterId,
            lastCheck = track.lastCheckTime.toInstantOrNull(),
            lastChapterDate = track.lastChapterDate.toInstantOrNull(),
            newChapters = track.newChapters,
        )
    }

    suspend fun updateTrack(manga: Content, updates: MangaUpdates): TrackEntity = db.withTransaction {
        if (updates is MangaUpdates.Success) {
            val owner = contentDataRepository.findContentById(manga.id, withChapters = false)
            if (owner != null && !owner.isLocal) {
                // Feed and details must read the same committed snapshot. A remote check for a
                // local owner carries its id, so never replace that owner's files/source.
                contentDataRepository.updateContentSnapshotAtAnchor(updates.manga, manga.id)
            }
        }
        val prev = getOrCreateTrack(manga.id)
        val entity = prev.mergeWith(updates, manga.id)
        db.getTracksDao().upsert(entity)
        if (updates is MangaUpdates.Success && updates.isNotEmpty()) {
            val chapters = trackLogChapters(updates.newChapters.map { it.name })
            val now = System.currentTimeMillis()
            val duplicate = db.getTrackLogsDao().findDuplicate(
                mangaId = manga.id,
                chapters = chapters,
                createdAt = now,
            )
            if (duplicate == null) {
                db.getTrackLogsDao().insert(
                    TrackLogEntity(
                        mangaId = manga.id,
                        chapters = chapters,
                        createdAt = now,
                        isUnread = true,
                    ),
                )
                db.getTrackLogsDao().trim(TRACK_LOG_RETAINED_SIZE)
            }
        }
        entity
    }

    suspend fun markAsRead(id: Long) {
        db.getTrackLogsDao().markAsRead(id)
    }

    suspend fun clearLog() {
        db.getTrackLogsDao().clear()
    }

    suspend fun markLogsAsReadByMangaId(mangaId: Long) {
        db.getTrackLogsDao().markUnreadAsReadByOwner(mangaId)
    }

    suspend fun markAllUpdatesAsRead() = db.withTransaction {
        db.getTracksDao().clearCounters()
        db.getTrackLogsDao().clear()
    }

    suspend fun markUpdateAsRead(mangaId: Long) {
        db.withTransaction {
            db.getTracksDao().clearCounter(mangaId)
            db.getTrackLogsDao().markUnreadAsReadByOwner(mangaId)
        }
    }

    suspend fun clearCounter(mangaId: Long) = db.withTransaction {
        if (db.getTracksDao().find(mangaId) != null) {
            clearTrackUpdates(mangaId)
        }
    }

    suspend fun getLogsCount(): Int = db.getTrackLogsDao().count()

    suspend fun clearLogs() = db.getTrackLogsDao().clear()

    suspend fun clearCounters() = db.withTransaction {
        for (mangaId in db.getTracksDao().findAllIds()) {
            clearTrackUpdates(mangaId)
        }
    }

    suspend fun clearUpdates(mangaIds: Collection<Long>) {
        if (mangaIds.isEmpty()) {
            return
        }
        db.withTransaction {
            for (mangaId in mangaIds) {
                clearTrackUpdates(mangaId)
            }
        }
    }

    suspend fun clearReadUpdates(mangaId: Long) {
        db.getTrackLogsDao().markUnreadAsReadByOwner(mangaId)
    }

    private suspend fun clearTrackUpdates(mangaId: Long) {
        db.getTracksDao().clearCounter(mangaId)
        db.getTrackLogsDao().markUnreadAsReadByOwner(mangaId)
    }

    suspend fun gc() = db.withTransaction {
        db.getTracksDao().gc()
        db.getTrackLogsDao().deleteOrphans()
        db.getTrackLogsDao().gc()
    }

    suspend fun mergeWith(tracking: ContentTracking) {
        val existing = db.getTracksDao().find(tracking.anchorMangaId)
        val entity = TrackEntity(
            mangaId = tracking.anchorMangaId,
            lastChapterId = tracking.lastChapterId,
            newChapters = tracking.newChapters,
            lastCheckTime = tracking.lastCheck?.toEpochMilli() ?: 0L,
            lastChapterDate = tracking.lastChapterDate?.toEpochMilli() ?: 0L,
            lastResult = TrackEntity.RESULT_EXTERNAL_MODIFICATION,
            lastError = null,
        )
        db.withTransaction {
            db.getTracksDao().upsert(entity)
            if (tracking.newChapters == 0 && existing?.newChapters != 0) {
                db.getTrackLogsDao().markUnreadAsReadByOwner(entity.mangaId)
            }
        }
    }

    suspend fun getCategoriesCount(): IntArray {
        val categories = db.getFavouriteCategoriesDao().findAll()
        return intArrayOf(
            categories.count { it.track },
            categories.size,
        )
    }

    suspend fun updateTracks() = db.withTransaction {
        syncTrackAnchors()
    }

    private suspend fun getOrCreateTrack(mangaId: Long): TrackEntity {
        return db.getTracksDao().find(mangaId) ?: TrackEntity.create(
            mangaId = mangaId,
        )
    }

    private fun TrackEntity.mergeWith(updates: MangaUpdates, anchorMangaId: Long): TrackEntity {
        // Row update rules are shared with the Windows tracker (core-domain TrackRules).
        return when (updates) {
            is MangaUpdates.Failure -> afterFailedCheck(updates.error?.toString(), System.currentTimeMillis())

            is MangaUpdates.Success -> afterSuccessfulCheck(
                anchorMangaId = anchorMangaId,
                lastChapterId = updates.manga.getChapters(updates.branch).lastOrNull()?.id ?: NO_ID,
                newChapterCount = updates.newChapters.size,
                isValid = updates.isValid,
                lastChapterDate = updates.lastChapterDate(),
                now = System.currentTimeMillis(),
            )
        }
    }

    private suspend fun syncTrackAnchors(): Int {
        val dao = db.getTracksDao()
        val existingIds = dao.findAllIds().toMutableSet()
        val requestedIds = currentTrackAnchorIds()
        val desiredIds = db.getMangaDao().findEntitiesByIds(requestedIds)
            .mapTo(LinkedHashSet(), MangaEntity::id)
        for (mangaId in desiredIds) {
            if (!existingIds.remove(mangaId)) {
                dao.upsert(
                    TrackEntity.create(
                        mangaId = mangaId,
                    ),
                )
            }
        }
        for (mangaId in existingIds) {
            dao.delete(mangaId)
        }
        return desiredIds.size
    }

    private suspend fun currentTrackAnchorIds(): List<Long> {
        val ids = LinkedHashSet<Long>()
        if (AppSettings.TRACK_HISTORY in settings.trackSources) {
            ids += db.getHistoryDao().findActiveMangaIds()
        }
        if (AppSettings.TRACK_FAVOURITES in settings.trackSources) {
            val trackedCategoryIds = db.getFavouriteCategoriesDao().findAll()
                .filter { it.track }
                .map { it.categoryId.toLong() }
                .toSet()
            if (trackedCategoryIds.isNotEmpty()) {
                val favouriteEntries = db.getFavouritesDao().findAllActiveEntries()
                ids += favouriteEntries
                    .filter { it.categoryId in trackedCategoryIds }
                    .map { it.mangaId }
            }
        }
        return ids.toList()
    }

    private suspend fun resolveDisplayTrackingContent(anchorMangaId: Long, fallback: Content): Content {
        return contentDataRepository.findDisplayContentById(anchorMangaId, withChapters = false) ?: fallback
    }

    private suspend fun resolveTrackDebugItems(tracks: List<TrackEntity>): List<TrackDebugItem> {
        if (tracks.isEmpty()) {
            return emptyList()
        }
        val fallbackByAnchorId = buildFallbackContentByAnchorId(tracks.map(TrackEntity::mangaId))
        return tracks.mapNotNull { track ->
            val fallbackContent = fallbackByAnchorId[track.mangaId] ?: return@mapNotNull null
            TrackDebugItem(
                manga = fallbackContent,
                lastChapterId = track.lastChapterId,
                newChapters = track.newChapters,
                lastCheckTime = track.lastCheckTime.toInstantOrNull(),
                lastChapterDate = track.lastChapterDate.toInstantOrNull(),
                lastResult = track.lastResult,
                lastError = track.lastError,
            )
        }
    }

    private suspend fun buildFallbackContentByAnchorId(anchorIds: Collection<Long>): Map<Long, Content> {
        if (anchorIds.isEmpty()) {
            return emptyMap()
        }
        val mangaEntities = db.getMangaDao().findEntitiesByIds(anchorIds)
        if (mangaEntities.isEmpty()) {
            return emptyMap()
        }
        val tagRelationsByMangaId = db.getMangaDao().findTagRelationsByMangaIds(mangaEntities.map(MangaEntity::id))
            .groupBy(MangaTagsEntity::mangaId)
        val tagIds = tagRelationsByMangaId.values.flatten().map(MangaTagsEntity::tagId).distinct()
        val tagsById = db.getTagsDao().findByIds(tagIds).associateBy(TagEntity::id)
        return mangaEntities.associate { manga ->
            val tags = tagRelationsByMangaId[manga.id].orEmpty().mapNotNull { relation ->
                tagsById[relation.tagId]
            }
            manga.id to manga.toContent(tags.toContentTags(), null)
        }
    }

    internal suspend fun gcIfNeeded() {
        if (isGcCalled.compareAndSet(false, true)) {
            gc()
        }
    }

    private suspend fun TrackEntity.toContentTracking(): ContentTracking? {
        val content = db.getMangaDao().find(mangaId)?.toContent() ?: return null
        return ContentTracking(
            anchorMangaId = mangaId,
            entityId = null,
            preferredLocalMangaId = mangaId,
            manga = content,
            lastChapterId = lastChapterId,
            lastCheck = lastCheckTime.toInstantOrNull(),
            lastChapterDate = lastChapterDate.toInstantOrNull(),
            newChapters = newChapters,
        )
    }
}
