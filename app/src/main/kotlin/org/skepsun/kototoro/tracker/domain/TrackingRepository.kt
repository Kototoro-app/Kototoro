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
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.toInstantOrNull
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.list.domain.toCriteria
import org.skepsun.kototoro.parsers.model.ContentState
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.TrackQueueEntry
import org.skepsun.kototoro.tracker.data.TRACK_LOG_RETAINED_SIZE
import org.skepsun.kototoro.tracker.data.clearUnrecordedTrackCounters
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

    /**
     * Up to [limit] tracks to check, least recently checked first; finished titles are left out when the user asked
     * to skip them. Sources still cooling down or challenged last time are kept from filling the batch
     * ([selectTrackBatch]).
     */
    suspend fun getTracks(
        limit: Int,
        cooldowns: Map<String, Long>,
        challenged: Set<String>,
        respectSchedule: Boolean,
        now: Long = System.currentTimeMillis(),
    ): List<ContentTracking> {
        val dao = db.getTracksDao()
        val queue = if (settings.isTrackerSkipCompleted) {
            dao.findQueueExceptState(ContentState.FINISHED.name)
        } else {
            dao.findQueue()
        }
        return selectTrackBatch(
            queue = queue,
            sourceOf = TrackQueueEntry::source,
            limit = limit,
            cooldowns = cooldowns,
            challenged = challenged,
            now = now,
            due = { entry -> !respectSchedule || isTrackDue(entry.nextCheckTime, now) },
        )
            .mapNotNull { entry -> dao.find(entry.mangaId)?.toContentTracking() }
    }

    /** How many tracks [getTracks] can return, which the check interval is spread over. */
    suspend fun getCheckedTracksCount(): Int {
        val dao = db.getTracksDao()
        return if (settings.isTrackerSkipCompleted) {
            dao.getTracksCountExceptState(ContentState.FINISHED.name)
        } else {
            dao.getTracksCount()
        }
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

    /**
     * Stores a remote work's checked details and chapter list at [anchorMangaId], so feed and details read the same
     * committed snapshot; storing the list records its new chapters ([storeChapterSnapshot]).
     */
    suspend fun storeCheckedSnapshot(manga: Content, anchorMangaId: Long): Pair<Content, ChapterSnapshotResult?> =
        contentDataRepository.storeCheckedSnapshot(manga, anchorMangaId)

    /**
     * Records the outcome of a check. A remote work's snapshot and new chapters were stored by
     * [storeCheckedSnapshot] already; a remote check for a local owner carries its id, so that owner's files/source
     * are never replaced, and it has no stored chapter list to find the new chapters in.
     */
    suspend fun updateTrack(manga: Content, updates: MangaUpdates): TrackEntity = db.withTransaction {
        val owner = if (updates is MangaUpdates.Success) {
            contentDataRepository.findContentById(manga.id, withChapters = false)
        } else {
            null
        }
        val prev = getOrCreateTrack(manga.id)
        val entity = prev.mergeWith(updates, manga.id)
        db.getTracksDao().upsert(entity)
        val now = System.currentTimeMillis()
        db.updateTrackSchedule(manga.id, succeeded = updates is MangaUpdates.Success, now = now)
        if (updates is MangaUpdates.Success) {
            if (owner?.isLocal == true) {
                db.recordUpdateChapters(manga.id, updates.newChapters.withIndex().toEntities(manga.id), now)
            } else {
                db.recordUpdateBatch(manga.id, updates.newChapters.mapTo(HashSet()) { it.id }, now)
            }
        }
        entity
    }

    /**
     * Returns false only for legacy logs that have no reliable chapter identities. [readerChapters] is the list being
     * read, used for a work without a stored chapter list (a local copy of a remote work).
     */
    suspend fun updateReadChapter(
        mangaId: Long,
        chapterId: Long,
        percent: Float? = null,
        readerChapters: List<ContentChapter>? = null,
    ): Boolean = db.withTransaction {
        if (db.getTrackLogsDao().findByOwner(mangaId).none { it.chapterMetadata.isNotBlank() }) return@withTransaction false
        val chapters = db.getChaptersDao().findAll(mangaId)
            .ifEmpty { readerChapters?.withIndex()?.toEntities(mangaId).orEmpty() }
        val current = chapters.firstOrNull { it.chapterId == chapterId } ?: return@withTransaction true
        val branch = chapters.filter { it.branch == current.branch }
        val index = branch.indexOfFirst { it.chapterId == chapterId }
        val progress = percent ?: db.getHistoryDao().find(mangaId)?.percent ?: return@withTransaction true
        if (progress * branch.size + 0.0001f >= index + 1) {
            db.completeUpdateChapter(mangaId, chapterId, System.currentTimeMillis(), chapters)
        }
        true
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
        db.completeAllPendingUpdates(System.currentTimeMillis())
    }

    suspend fun markUpdateAsRead(mangaId: Long) {
        db.withTransaction {
            db.completePendingUpdates(mangaId, System.currentTimeMillis())
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
        db.completePendingUpdates(mangaId, System.currentTimeMillis())
    }

    suspend fun gc() = db.withTransaction {
        db.getTracksDao().gc()
        db.getTrackLogsDao().deleteOrphans()
        db.getTrackScheduleDao().gc()
        db.clearUnrecordedTrackCounters()
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
        val schedules = db.getTrackScheduleDao().findByIds(tracks.map(TrackEntity::mangaId)).associateBy { it.mangaId }
        return tracks.mapNotNull { track ->
            val fallbackContent = fallbackByAnchorId[track.mangaId] ?: return@mapNotNull null
            val schedule = schedules[track.mangaId]
            TrackDebugItem(
                manga = fallbackContent,
                lastChapterId = track.lastChapterId,
                newChapters = track.newChapters,
                lastCheckTime = track.lastCheckTime.toInstantOrNull(),
                lastChapterDate = track.lastChapterDate.toInstantOrNull(),
                lastResult = track.lastResult,
                lastError = track.lastError,
                failCount = schedule?.failCount ?: 0,
                nextCheckTime = schedule?.nextCheckTime?.toInstantOrNull(),
            )
        }.sortedByDescending { item ->
            // Works that keep failing first, the rest in check order.
            if (item.failCount >= PERSISTENT_FAILURE_COUNT) item.failCount else 0
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
