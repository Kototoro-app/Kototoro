package org.skepsun.kototoro.tracker.domain

import androidx.annotation.VisibleForTesting
import androidx.room.withTransaction
import dagger.Reusable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.onStart
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.TABLE_ENTITY_GRAPH_BINDING
import org.skepsun.kototoro.core.db.TABLE_ENTITY_PREFERENCES
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaTagsEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.db.entity.toContentTags
import org.skepsun.kototoro.core.db.entity.toEntity
import org.skepsun.kototoro.core.db.entity.toEntities
import org.skepsun.kototoro.core.parser.ContentDataRepository
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.prefs.observeAsFlow
import org.skepsun.kototoro.core.util.ext.mapItems
import org.skepsun.kototoro.core.util.ext.toInstantOrNull
import org.skepsun.kototoro.details.domain.ProgressUpdateUseCase
import org.skepsun.kototoro.list.domain.ListFilterOption
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.util.ifZero
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.TRACK_LOG_RETAINED_SIZE
import org.skepsun.kototoro.tracker.data.canBeClearedBy
import org.skepsun.kototoro.tracker.data.resolveTrackOwnerId
import org.skepsun.kototoro.tracker.domain.model.ContentTracking
import org.skepsun.kototoro.tracker.domain.model.MangaUpdates
import org.skepsun.kototoro.tracker.domain.model.TrackingLogItem
import org.skepsun.kototoro.tracker.ui.debug.TrackDebugItem
import org.skepsun.kototoro.work.domain.WorkAggregate
import org.skepsun.kototoro.work.domain.WorkAggregateRepository
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkMigrationState
import org.skepsun.kototoro.work.domain.WorkResolver
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

private const val NO_ID = 0L

@Reusable
class TrackingRepository @Inject constructor(
	private val db: MangaDatabase,
	private val settings: AppSettings,
	private val progressUpdateUseCase: ProgressUpdateUseCase,
	private val contentDataRepository: ContentDataRepository,
	private val workResolver: WorkResolver,
	private val workAggregateRepository: WorkAggregateRepository,
) {

	private var isGcCalled = AtomicBoolean(false)

	suspend fun getNewChaptersCount(mangaId: Long): Int {
		val anchorMangaId = resolvePersistableTrackAnchorMangaId(mangaId) ?: return 0
		return db.getTracksDao().findNewChapters(anchorMangaId)
	}

	suspend fun getNewChaptersCounts(mangaIds: Collection<Long>): Map<Long, Int> {
		if (mangaIds.isEmpty()) return emptyMap()
		val anchorByRequestedId = mangaIds.distinct().associateWith { mangaId ->
			resolvePersistableTrackAnchorMangaId(mangaId)
		}
		val countsByAnchorId = db.getTracksDao().findNewChapters(anchorByRequestedId.values.filterNotNull().distinct())
			.associate { it.mangaId to it.count }
		return anchorByRequestedId.mapValues { (_, anchorId) ->
			anchorId?.let(countsByAnchorId::get) ?: 0
		}
	}

	fun observeNewChaptersCount(mangaId: Long): Flow<Int> {
		return db.invalidationTracker.createFlow(
			tables = arrayOf(
				TABLE_ENTITY_GRAPH_BINDING,
				TABLE_ENTITY_PREFERENCES,
			),
			emitInitialState = true,
		).mapLatest {
			resolveTrackAnchorMangaIdsForRead(mangaId)
		}.distinctUntilChanged().flatMapLatest { anchorIds ->
			when {
				anchorIds.isEmpty() -> flow { emit(0) }
				anchorIds.size == 1 -> db.getTracksDao().observeNewChapters(anchorIds.first())
				else -> combine(anchorIds.map(db.getTracksDao()::observeNewChapters)) { counts ->
					counts.sum()
				}
			}
		}
	}

	fun observeUpdatedContentCount(): Flow<Int> {
		return db.getTracksDao().observeUpdateContentCount()
			.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	fun observeUnreadUpdatesCount(): Flow<Int> {
		val lastOpenTimeFlow = settings.observeAsFlow(AppSettings.KEY_FEED_LAST_OPEN_TIME) { feedLastOpenTime }
		return lastOpenTimeFlow.flatMapLatest { lastOpenTime ->
			db.getTracksDao().observeUnreadWorkCount(lastOpenTime)
		}
	}

	fun observeUpdatedContent(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<ContentTracking>> {
		return db.getTracksDao().observeUpdatedContent(limit, filterOptions)
			.mapLatest { tracks ->
				workAggregateRepository.buildTrackingAggregates(tracks)
					.mapNotNull { aggregate -> aggregate.toContentTracking() }
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	fun observeAllTracks(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<ContentTracking>> {
		return db.getTracksDao().observeAllTracks(limit, filterOptions)
			.mapLatest { tracks ->
				workAggregateRepository.buildTrackingAggregates(tracks)
					.mapNotNull { aggregate -> aggregate.toContentTracking() }
			}.distinctUntilChanged()
			.onStart { gcIfNotCalled() }
	}

	fun observeAllTrackingLogItems(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<TrackingLogItem>> {
		return observeAllTracks(limit, filterOptions)
			.mapLatest { tracks -> resolveAllTrackingLogItems(tracks) }
	}

	suspend fun getTracks(offset: Int, limit: Int): List<ContentTracking> {
		return workAggregateRepository
			.buildTrackingAggregates(db.getTracksDao().findAll(offset = offset, limit = limit))
			.mapNotNull { aggregate -> aggregate.toContentTracking() }
	}

	fun observeTrackDebugItems(): Flow<List<TrackDebugItem>> {
		return db.getTracksDao().observeAll()
			.mapLatest { tracks -> resolveTrackDebugItems(tracks) }
			.onStart { gcIfNotCalled() }
	}

	@Deprecated("")
	suspend fun getTrack(manga: Content): ContentTracking {
		val anchorMangaId = resolvePersistableTrackAnchorMangaId(manga.id)
		val entityId = if (anchorMangaId != null) {
			resolveTrackIdentity(anchorMangaId).entityId
		} else {
			resolveTrackIdentity(manga.id).entityId
		}
		return getTrackOrNull(manga) ?: ContentTracking(
			anchorMangaId = anchorMangaId ?: manga.id,
			entityId = entityId,
			preferredLocalMangaId = entityId?.let { resolveExistingTrackAnchorForEntity(it) } ?: anchorMangaId,
			manga = manga,
			lastChapterId = NO_ID,
			lastCheck = null,
			lastChapterDate = null,
			newChapters = 0,
		)
	}

	suspend fun getTrackOrNull(manga: Content): ContentTracking? {
		val anchorMangaId = resolvePersistableTrackAnchorMangaId(manga.id) ?: return null
		val track = db.getTracksDao().find(anchorMangaId) ?: return null
		val entityId = track.entityId ?: resolveTrackIdentity(anchorMangaId).entityId
		val fallbackContent = if (anchorMangaId == manga.id) manga else db.getMangaDao().find(anchorMangaId)?.toContent() ?: manga
		return ContentTracking(
			anchorMangaId = anchorMangaId,
			entityId = entityId,
			preferredLocalMangaId = entityId?.let { resolveExistingTrackAnchorForEntity(it) } ?: anchorMangaId,
			manga = resolveDisplayTrackingContent(anchorMangaId, fallbackContent),
			lastChapterId = track.lastChapterId,
			lastCheck = track.lastCheckTime.toInstantOrNull(),
			lastChapterDate = track.lastChapterDate.toInstantOrNull(),
			newChapters = track.newChapters,
		)
	}

	suspend fun getExecutionTrackingContent(track: ContentTracking): Content {
		return getExecutionTrackingContentOrNull(track.anchorMangaId) ?: track.manga
	}

	suspend fun getExecutionTrackingContentOrNull(anchorMangaId: Long): Content? {
		return contentDataRepository.findContentById(anchorMangaId, withChapters = true)
			?: db.getMangaDao().find(anchorMangaId)?.toContent()
	}

	suspend fun hasValidTrackingIdentity(track: ContentTracking): Boolean {
		val entityId = track.entityId ?: return false
		val identity = resolveTrackIdentity(track.anchorMangaId)
		return identity.migrationState == WorkMigrationState.VALID && identity.entityId == entityId
	}

	@VisibleForTesting
	suspend fun deleteTrack(mangaId: Long) {
		val anchorMangaId = resolvePersistableTrackAnchorMangaId(mangaId) ?: return
		db.getTracksDao().delete(anchorMangaId)
	}

	fun observeTrackingLog(limit: Int, filterOptions: Set<ListFilterOption>): Flow<List<TrackingLogItem>> {
		return db.getTrackLogsDao().observeAll(limit, filterOptions)
			.mapLatest { items ->
				resolveDisplayTrackingLogItems(items)
			}
			.onStart { gcIfNotCalled() }
	}

	suspend fun getLogsCount() = db.getTrackLogsDao().count()

	suspend fun clearLogs() = db.getTrackLogsDao().clear()

	suspend fun clearCounters() = db.withTransaction {
		for (id in currentTrackAnchorIds()) {
			clearTrackUpdates(id)
		}
	}

	suspend fun markAsRead(trackLogId: Long) = db.withTransaction {
		val log = db.getTrackLogsDao().find(trackLogId) ?: return@withTransaction
		db.getTrackLogsDao().markAsRead(trackLogId)
		db.getTracksDao().findByOwnerId(log.ownerId)
			?.takeIf { it.canBeClearedBy(log) }
			?.let { clearTrackUpdates(it.mangaId) }
	}

	suspend fun gc() = db.withTransaction {
		syncTrackAnchors()
		db.getTrackLogsDao().repairWorkIdentities()
		db.getTrackLogsDao().deleteOrphans()
		db.getTrackLogsDao().ensureUnreadUpdateLogs()
		db.getTracksDao().insertTracksFromUnreadLogs()
		db.getTracksDao().restoreCountersFromUnreadLogs()
		db.getTrackLogsDao().gc()
		db.getTrackLogsDao().trim(TRACK_LOG_RETAINED_SIZE)
	}

	suspend fun normalizeTracksForSync() {
		db.withTransaction {
			syncTrackAnchors()
		}
	}

	suspend fun saveUpdates(updates: MangaUpdates) {
		db.withTransaction {
			val entityId = updates.entityId ?: return@withTransaction
			val anchorMangaId = updates.anchorMangaId
			val identity = resolveTrackIdentity(anchorMangaId)
			if (
				identity.migrationState != WorkMigrationState.VALID ||
				identity.entityId != entityId ||
				!db.getMangaDao().contains(anchorMangaId)
			) {
				return@withTransaction
			}
			val track = getOrCreateTrack(anchorMangaId).mergeWith(updates, anchorMangaId)
			db.getTracksDao().upsert(track)
			
			val resolvedManga = contentDataRepository.updateProjectionSnapshotAtAnchor(
				manga = updates.manga,
				anchorMangaId = anchorMangaId,
			)

			if (updates is MangaUpdates.Success && updates.isValid && updates.newChapters.isNotEmpty()) {
				progressUpdateUseCase(resolvedManga)
				val logEntity = TrackLogEntity(
					ownerId = resolveTrackOwnerId(entityId, anchorMangaId),
					mangaId = anchorMangaId,
					entityId = entityId,
					chapters = updates.newChapters.joinToString("\n") { x -> x.name },
					createdAt = System.currentTimeMillis(),
					isUnread = true,
				)
				db.getTrackLogsDao().insert(logEntity)
			}
		}
	}

	suspend fun clearUpdates(ids: Collection<Long>) {
			when {
				ids.isEmpty() -> return
				ids.size == 1 -> {
					val anchorMangaId = resolvePersistableTrackAnchorMangaId(ids.single()) ?: return
					db.withTransaction {
						clearTrackUpdates(anchorMangaId)
					}
				}
				else -> db.withTransaction {
					for (id in resolvePersistableTrackAnchorMangaIds(ids)) {
						clearTrackUpdates(id)
					}
				}
			}
	}

	suspend fun clearReadUpdates(mangaId: Long) = db.withTransaction {
		for (anchorMangaId in resolveTrackAnchorMangaIdsForRead(mangaId)) {
			markTrackLogsAsRead(anchorMangaId)
		}
	}

	private suspend fun markTrackLogsAsRead(anchorMangaId: Long) {
		val track = db.getTracksDao().find(anchorMangaId)
		track?.let {
			db.getTrackLogsDao().markUnreadAsReadByOwner(it.ownerId)
		}
	}

	private suspend fun clearTrackUpdates(anchorMangaId: Long) {
		val track = db.getTracksDao().find(anchorMangaId)
		db.getTracksDao().clearCounter(anchorMangaId)
		track?.let {
			db.getTrackLogsDao().markUnreadAsReadByOwner(it.ownerId)
		}
	}

	suspend fun mergeWith(tracking: ContentTracking) {
		val anchorMangaId = resolvePersistableTrackAnchorMangaId(tracking.anchorMangaId) ?: return
		val entityId = resolveTrackIdentity(anchorMangaId).entityId
		val existing = db.getTracksDao().find(anchorMangaId)
		val entity = TrackEntity(
			ownerId = resolveTrackOwnerId(entityId, anchorMangaId),
			mangaId = anchorMangaId,
			entityId = entityId,
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
				db.getTrackLogsDao().markUnreadAsReadByOwner(entity.ownerId)
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
			entityId = resolveTrackIdentity(mangaId).entityId,
		)
	}

	private fun TrackEntity.mergeWith(updates: MangaUpdates, anchorMangaId: Long): TrackEntity {
		return when (updates) {
			is MangaUpdates.Failure -> TrackEntity(
				ownerId = resolveTrackOwnerId(entityId, mangaId),
				mangaId = mangaId,
				entityId = entityId,
				lastChapterId = lastChapterId,
				newChapters = newChapters,
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = lastChapterDate,
				lastResult = TrackEntity.RESULT_FAILED,
				lastError = updates.error?.toString(),
			)

			is MangaUpdates.Success -> TrackEntity(
				ownerId = resolveTrackOwnerId(entityId, anchorMangaId),
				mangaId = anchorMangaId,
				entityId = entityId,
				lastChapterId = updates.manga.getChapters(updates.branch).lastOrNull()?.id ?: NO_ID,
				newChapters = if (updates.isValid) {
					if (updates.newChapters.isNotEmpty()) {
						updates.newChapters.size
					} else {
						newChapters
					}
				} else {
					0
				},
				lastCheckTime = System.currentTimeMillis(),
				lastChapterDate = updates.lastChapterDate().ifZero { lastChapterDate },
				lastResult = if (updates.isNotEmpty()) TrackEntity.RESULT_HAS_UPDATE else TrackEntity.RESULT_NO_UPDATE,
				lastError = null,
			)
		}
	}

	private suspend fun resolvePersistableTrackAnchorMangaId(mangaId: Long): Long? {
		if (db.getMangaDao().contains(mangaId)) {
			val identity = resolveTrackIdentity(mangaId)
			return resolveExistingTrackAnchor(identity) ?: mangaId
		}
		return resolveExistingTrackAnchor(resolveTrackIdentity(mangaId))
	}

	private suspend fun resolvePersistableTrackAnchorMangaIds(mangaIds: Iterable<Long>): List<Long> {
		val resolved = LinkedHashSet<Long>()
		for (mangaId in mangaIds) {
			resolvePersistableTrackAnchorMangaId(mangaId)?.let(resolved::add)
		}
		return resolved.toList()
	}

	private suspend fun resolveTrackAnchorMangaIdsForRead(mangaId: Long): List<Long> {
		val identity = resolveTrackIdentity(mangaId)
		if (identity.entityId == null) {
			return listOfNotNull(resolvePersistableTrackAnchorMangaId(mangaId))
		}
		val localBindingIds = identity.localMangaIds.toCollection(LinkedHashSet())
		identity.preferredMangaId?.let(localBindingIds::add)
		return if (localBindingIds.isEmpty()) {
			listOfNotNull(resolvePersistableTrackAnchorMangaId(mangaId))
		} else {
			resolvePersistableTrackAnchorMangaIds(localBindingIds)
		}
	}

	private suspend fun syncTrackAnchors(): Int {
		val dao = db.getTracksDao()
		val existingIds = dao.findAllIds().toMutableSet()
		val desiredIds = currentTrackAnchorIds()
			.filter { mangaId -> db.getMangaDao().contains(mangaId) }
			.toMutableSet()
		for (mangaId in desiredIds) {
			if (!existingIds.remove(mangaId) && db.getMangaDao().contains(mangaId)) {
				dao.upsert(
					TrackEntity.create(
						mangaId = mangaId,
						entityId = resolveTrackIdentity(mangaId).entityId,
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
			ids += db.getWorkHistoryDao().findActiveAnchorMangaIds()
		}
		if (AppSettings.TRACK_FAVOURITES in settings.trackSources) {
			val trackedEntityIds = db.getWorkFavouritesDao().findTrackedEntityIds()
			for (entityId in trackedEntityIds) {
				resolveExistingTrackAnchorForEntity(entityId)?.let(ids::add)
			}
		}
		return ids.toList()
	}

	private suspend fun resolveExistingTrackAnchorForEntity(entityId: Long): Long? {
		return resolveExistingTrackAnchor(workResolver.resolveByEntityId(entityId))
	}

	private suspend fun resolveExistingTrackAnchor(identity: WorkIdentity?): Long? {
		if (identity?.entityId == null) {
			return null
		}
		val preferredMangaId = identity.preferredMangaId
		if (preferredMangaId != null && db.getMangaDao().contains(preferredMangaId)) {
			return preferredMangaId
		}
		return identity.localMangaIds.firstOrNull { localId ->
			db.getMangaDao().contains(localId)
		}
	}

	private suspend fun resolveTrackIdentity(mangaId: Long): WorkIdentity {
		return workResolver.resolveByMangaId(mangaId)
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

	private suspend fun resolveDisplayTrackingLogItems(items: List<TrackLogEntity>): List<TrackingLogItem> {
		if (items.isEmpty()) {
			return emptyList()
		}
		val anchorIds = items.map(TrackLogEntity::mangaId)
		val fallbackByAnchorId = buildFallbackContentByAnchorId(anchorIds)
		val entityIdsByAnchorId = anchorIds.distinct().associateWith { anchorId ->
			resolveTrackIdentity(anchorId).entityId
		}
		val preferredLocalIdsByEntity = entityIdsByAnchorId.values
			.filterNotNull()
			.distinct()
			.associateWith { entityId -> resolveExistingTrackAnchorForEntity(entityId) }
		return items.mapNotNull { item ->
			val entityId = entityIdsByAnchorId[item.mangaId]
			val fallbackContent = fallbackByAnchorId[item.mangaId] ?: return@mapNotNull null
			TrackingLogItem(
				id = item.id,
				anchorMangaId = item.mangaId,
				entityId = entityId,
				preferredLocalMangaId = entityId?.let(preferredLocalIdsByEntity::get),
				manga = resolveDisplayTrackingContent(item.mangaId, fallbackContent),
				chapters = item.chapters.split('\n').filterNot { x -> x.isEmpty() },
				createdAt = java.time.Instant.ofEpochMilli(item.createdAt),
				isNew = item.isUnread,
			)
		}
	}

	private suspend fun resolveAllTrackingLogItems(tracks: List<ContentTracking>): List<TrackingLogItem> {
		if (tracks.isEmpty()) {
			return emptyList()
		}
		val chapters = db.getChaptersDao()
			.findAllByMangaIds(tracks.map { it.manga.id })
		val trackOwnerIds = tracks.map { track -> resolveTrackOwnerId(track.entityId, track.manga.id) }
		val unreadOwnerIds = db.getTrackLogsDao().findUnreadOwnerIds(trackOwnerIds).toSet()
		return TrackingLogItemMapper.fromAllTrackedContent(tracks, chapters, unreadOwnerIds)
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

	private suspend fun gcIfNotCalled() {
		if (isGcCalled.compareAndSet(false, true)) {
			gc()
		}
	}

	private fun WorkAggregate.toContentTracking(): ContentTracking? {
		val tracking = tracking ?: return null
		val displayProjection = displayProjection ?: return null
		return ContentTracking(
			anchorMangaId = tracking.anchorMangaId,
			entityId = identity.entityId,
			preferredLocalMangaId = identity.preferredMangaId ?: tracking.anchorMangaId,
			manga = displayProjection,
			lastChapterId = tracking.lastChapterId,
			lastCheck = tracking.lastCheckTime.toInstantOrNull(),
			lastChapterDate = tracking.lastChapterDate.toInstantOrNull(),
			newChapters = tracking.newChapters,
		)
	}
}
