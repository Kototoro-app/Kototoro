package org.skepsun.kototoro.sync.google.domain

import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncAuth
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.SyncContent
import org.skepsun.kototoro.sync.google.data.model.SyncEntityBindingRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityGraph
import org.skepsun.kototoro.sync.google.data.model.SyncEntityPrefsRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRecord
import org.skepsun.kototoro.sync.google.data.model.SyncEntityRelationRecord
import org.skepsun.kototoro.sync.google.data.model.SyncFeedState
import org.skepsun.kototoro.sync.google.data.model.SyncFavouriteCategory
import org.skepsun.kototoro.sync.google.data.model.SyncTrack
import org.skepsun.kototoro.sync.google.data.model.SyncTrackLog
import org.skepsun.kototoro.sync.google.data.model.SyncWorkFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncWorkHistory
import org.skepsun.kototoro.sync.google.data.model.SyncWorkState
import org.skepsun.kototoro.sync.google.data.model.SyncWorkStats
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.RelationRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingStateOrNull
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.tracker.data.TRACK_LOG_RETAINED_SIZE
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleDriveSyncResult {
	data object Success : GoogleDriveSyncResult
	data class AuthorizationRequired(val error: GoogleDriveSyncAuthorizationException) : GoogleDriveSyncResult
	data class Error(val message: String?, val retryable: Boolean = true) : GoogleDriveSyncResult
}

@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	private val settings: GoogleDriveSyncSettings,
	private val auth: GoogleDriveSyncAuth,
	private val api: GoogleDriveSyncApi,
	private val database: MangaDatabase,
	private val favouritesRepository: FavouritesRepository,
	private val historyRepository: HistoryRepository,
	private val trackingRepository: TrackingRepository,
) {

	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()
	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	fun onSignedIn(email: String?, displayName: String?) {
		settings.accountEmail = email?.ifBlank { null } ?: "Google Drive"
		settings.accountName = displayName
	}

	fun shouldSyncOnStart(now: Long = System.currentTimeMillis()): Boolean {
		return settings.isSignedIn &&
			settings.isSyncOnStart &&
			now - settings.lastSyncAttemptTimestamp >= GoogleDriveSyncSettings.START_SYNC_COOLDOWN_MS
	}

	suspend fun sync(): GoogleDriveSyncResult {
		if (!settings.isSignedIn) {
			return GoogleDriveSyncResult.AuthorizationRequired(GoogleDriveSyncAuthorizationException())
		}
		if (!syncMutex.tryLock()) {
			return GoogleDriveSyncResult.Success
		}
		isSyncing.value = true
		settings.lastSyncAttemptTimestamp = System.currentTimeMillis()
		return try {
			val token = auth.requireAccessToken()
			performSync(token)
			settings.lastSyncTimestamp = System.currentTimeMillis()
			settings.lastSyncError = null
			settings.isDirty = false
			GoogleDriveSyncResult.Success
		} catch (e: GoogleDriveSyncAuthorizationException) {
			settings.lastSyncError = e.message
			GoogleDriveSyncResult.AuthorizationRequired(e)
		} catch (e: GoogleDriveSyncSchemaException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "sync failed: schema", e)
			GoogleDriveSyncResult.Error(e.message, retryable = false)
		} catch (e: Exception) {
			settings.lastSyncError = e.message ?: e.javaClass.simpleName
			Log.e(TAG, "sync failed: ${settings.lastSyncError}", e)
			GoogleDriveSyncResult.Error(settings.lastSyncError)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	suspend fun deleteRemoteData(): GoogleDriveSyncResult = try {
		val token = auth.requireAccessToken()
		api.findSyncFiles(token).forEach { file ->
			runCatching { api.delete(token, file.id) }
		}
		settings.lastSyncTimestamp = 0L
		settings.lastSyncError = null
		settings.isDirty = false
		GoogleDriveSyncResult.Success
	} catch (e: GoogleDriveSyncAuthorizationException) {
		GoogleDriveSyncResult.AuthorizationRequired(e)
	} catch (e: Exception) {
		GoogleDriveSyncResult.Error(e.message)
	}

	fun signOut() {
		settings.clearAccount()
	}

	private suspend fun performSync(token: String) {
		runSyncStep("normalize favourites") {
			favouritesRepository.normalizeWorkFavouritesForSync()
		}
		runSyncStep("normalize history") {
			historyRepository.normalizeWorkHistoryForSync()
		}
		runSyncStep("normalize tracks") {
			trackingRepository.normalizeTracksForSync()
		}
		var attempt = 0
		while (true) {
			val files = runSyncStep("list drive files") {
				api.findSyncFiles(token)
			}
			val canonical = files.firstOrNull()
			val baseVersion = canonical?.version
			val decoded = ArrayList<GoogleDriveSyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val snapshot = runSyncStep("download ${file.id}") {
					decodeSnapshot(api.download(token, file.id))
				}
				if (snapshot != null) {
					decoded += snapshot
					decodedIds += file.id
				}
			}
			val remote = runSyncStep("merge remote snapshots") {
				GoogleDriveSyncMerger.combine(decoded)
			}
			val local = runSyncStep("build local snapshot") {
				buildLocalSnapshot()
			}
			Log.d(TAG, "sync local=${local.debugSummary()} remote=${remote?.debugSummary()} files=${files.size}")
			val merged = runSyncStep("merge local remote") {
				GoogleDriveSyncMerger.mergeSnapshots(local, remote)
			}
			Log.d(TAG, "sync merged=${merged.debugSummary()}")
			runSyncStep("apply database") {
				applyToDatabase(merged)
			}
			Log.d(TAG, "sync applied=${buildLocalSnapshot().debugSummary()} ${database.localDatabaseSummary()}")
			val upload = merged.copyForUpload(syncedAt = System.currentTimeMillis())
			if (canonical != null && baseVersion != null && attempt < MAX_CONFLICT_RETRIES) {
				val currentVersion = runSyncStep("check drive version") {
					api.getFileVersion(token, canonical.id)
				}
				if (currentVersion != null && currentVersion != baseVersion) {
					attempt++
					continue
				}
			}
			val payload = json.encodeToString(GoogleDriveSyncSnapshot.serializer(), upload).encodeToByteArray()
			val fileId = runSyncStep("upload snapshot") {
				api.upload(token, payload, canonical?.id)
			}
			files.filter { it.id != fileId && it.id in decodedIds }.forEach { duplicate ->
				runCatching { api.delete(token, duplicate.id) }
			}
			return
		}
	}

	private suspend fun <T> runSyncStep(name: String, block: suspend () -> T): T {
		Log.d(TAG, "sync step start: $name")
		return try {
			block().also {
				Log.d(TAG, "sync step done: $name")
			}
		} catch (e: Exception) {
			Log.e(TAG, "sync step failed: $name", e)
			throw e
		}
	}

	private suspend fun buildLocalSnapshot(): GoogleDriveSyncSnapshot {
		val tracks = database.getTracksDao().dump()
		val logs = database.getTrackLogsDao().dump()
		val workHistory = database.getWorkHistoryDao().dump().toList()
		val workFavourites = database.getWorkFavouritesDao().dump().toList()
		val workStats = database.getWorkStatsDao().dumpEnabled().toList()
		val entityGraphDao = database.getEntityGraphDao()
		val entityRecords = entityGraphDao.dumpEntities()
		val entityBindings = entityGraphDao.dumpBindings()
		val entityPrefs = entityGraphDao.dumpPrefs()
		val referencedEntityIds = (
			tracks.mapNotNull { it.entityId } +
				logs.mapNotNull { it.entityId } +
				workHistory.map { it.entityId } +
				workFavourites.map { it.entityId } +
				workStats.map { it.entityId } +
				entityPrefs.map { it.entityId }
			).toSet()
		val boundContentIds = entityBindings
			.asSequence()
			.filter { it.entityId in referencedEntityIds }
			.filter { it.source == LOCAL_MANGA_SOURCE || it.source == LEGACY_LOCAL_MANGA_SOURCE }
			.mapNotNull { it.externalId.toLongOrNull() }
			.toList()
		val contentIds = (
			tracks.map { it.mangaId } +
				logs.map { it.mangaId } +
				workHistory.map { it.anchorMangaId } +
				workStats.map { it.anchorMangaId } +
				entityPrefs.mapNotNull { it.preferredLocalMangaId } +
				boundContentIds
			).distinct()
		return GoogleDriveSyncSnapshot(
			deviceId = settings.deviceId,
			syncedAt = System.currentTimeMillis(),
			entityGraph = SyncEntityGraph(
				entities = entityRecords.map {
					SyncEntityRecord(
						id = it.id,
						type = it.type,
						primaryName = it.primaryName,
						nameHash = it.nameHash,
						aliases = it.aliases,
						createdAt = it.createdAt,
						lastAccessed = it.lastAccessed,
						accessCount = it.accessCount,
					)
				},
				bindings = entityBindings.map {
					SyncEntityBindingRecord(
						entityId = it.entityId,
						source = it.source,
						externalId = it.externalId,
						confidence = it.confidence,
						sourceKind = it.sourceKind,
						state = it.state,
						createdBy = it.createdBy,
						isPrimary = it.isPrimary,
						updatedAt = it.updatedAt,
					)
				},
				relations = entityGraphDao.dumpRelations().map {
					SyncEntityRelationRecord(
						fromEntityId = it.fromEntityId,
						toEntityId = it.toEntityId,
						type = it.type,
						createdAt = it.createdAt,
					)
				},
				prefs = entityPrefs.map {
					SyncEntityPrefsRecord(
						entityId = it.entityId,
						preferredLocalMangaId = it.preferredLocalMangaId,
						titleOverride = it.titleOverride,
						coverUrlOverride = it.coverUrlOverride,
						contentRatingOverride = it.contentRatingOverride,
						readingStatus = it.readingStatus,
						metadataSourceKind = it.metadataSourceKind,
						metadataBindingSource = it.metadataBindingSource,
						metadataBindingExternalId = it.metadataBindingExternalId,
						metadataSourceService = it.metadataSourceService,
						metadataSourceRemoteId = it.metadataSourceRemoteId,
						updatedAt = it.updatedAt,
					)
				},
			),
			content = database.getMangaDao().findEntitiesByIds(contentIds).map(::SyncContent),
			work = SyncWorkState(
				categories = database.getFavouriteCategoriesDao().dump().map(::SyncFavouriteCategory),
				history = workHistory.map(::SyncWorkHistory),
				favourites = workFavourites.map(::SyncWorkFavourite),
				stats = workStats.map(::SyncWorkStats),
			),
			feed = SyncFeedState(
				tracks = tracks.map(::SyncTrack),
				logs = logs.map(::SyncTrackLog),
			),
		)
	}

	private suspend fun applyToDatabase(snapshot: GoogleDriveSyncSnapshot) {
		database.withTransaction {
			val mapping = database.restoreSyncAnchors(snapshot)
			database.restoreSyncWork(snapshot, mapping)
			snapshot.feed.tracks.forEach { track ->
				database.mergeTrack(track.toEntity().mapWith(mapping))
			}
			snapshot.feed.logs.forEach { log ->
				database.mergeTrackLog(log.toEntity().mapWith(mapping))
			}
			database.getTrackLogsDao().gc()
			database.getTrackLogsDao().trim(TRACK_LOG_RETAINED_SIZE)
		}
	}

	private suspend fun MangaDatabase.restoreSyncAnchors(snapshot: GoogleDriveSyncSnapshot): SyncIdMapping {
		val mangaIdMapping = LinkedHashMap<Long, Long>()
		val categoryIdMapping = LinkedHashMap<Long, Long>()
		var nextImportedMangaId = minOf(getMangaDao().findMinId() ?: 0L, 0L) - 1L
		snapshot.content.forEach { content ->
			val existing = content.publicUrl
				.takeIf { it.isNotBlank() }
				?.let { getMangaDao().findByPublicUrl(it)?.manga }
			val local = existing ?: run {
				val localId = if (getMangaDao().contains(content.id)) {
					nextImportedMangaId--
				} else {
					content.id
				}
				content.toEntity(localId)
			}
			if (existing == null) {
				getMangaDao().upsert(local)
			}
			mangaIdMapping[content.id] = local.id
		}

		snapshot.work.categories.forEach { category ->
			val existing = getFavouriteCategoriesDao().findIncludingDeleted(category.id)
			if (existing == null || category.deletedAt >= existing.deletedAt) {
				getFavouriteCategoriesDao().upsert(category.toEntity())
			}
			categoryIdMapping[category.id] = category.id
		}

		val entityIdMapping = LinkedHashMap<Long, Long>()
		snapshot.entityGraph.entities.forEach { remote ->
			val localId = restoreSyncEntity(
				EntityRecord(
					id = remote.id,
					type = remote.type,
					primaryName = remote.primaryName,
					nameHash = remote.nameHash,
					aliases = remote.aliases,
					createdAt = remote.createdAt,
					lastAccessed = remote.lastAccessed,
					accessCount = remote.accessCount,
				),
			)
			entityIdMapping[remote.id] = localId
		}

		snapshot.entityGraph.bindings.forEach { remote ->
			val localEntityId = entityIdMapping[remote.entityId] ?: return@forEach
			val localExternalId = if (remote.source == LOCAL_MANGA_SOURCE || remote.source == LEGACY_LOCAL_MANGA_SOURCE) {
				remote.externalId.toLongOrNull()?.let(mangaIdMapping::get)?.toString() ?: return@forEach
			} else {
				remote.externalId
			}
			val existing = getEntityGraphDao().findBinding(remote.source, localExternalId)
			if (existing != null && existing.shouldKeepOverSync(remote)) {
				return@forEach
			}
			getEntityGraphDao().upsertBinding(
				EntityBindingRecord(
					entityId = localEntityId,
					source = remote.source,
					externalId = localExternalId,
					confidence = remote.confidence,
					isPrimary = false,
					sourceKind = remote.sourceKind,
					state = remote.state,
					createdBy = remote.createdBy,
					updatedAt = remote.updatedAt,
				),
			)
		}

		snapshot.entityGraph.prefs.forEach { remote ->
			val localEntityId = entityIdMapping[remote.entityId] ?: return@forEach
			val localPreferredId = remote.preferredLocalMangaId?.let(mangaIdMapping::get)
			val local = getEntityGraphDao().findEntityPrefs(localEntityId)
			val candidate = EntityPrefsRecord(
				entityId = localEntityId,
				preferredLocalMangaId = localPreferredId,
				titleOverride = remote.titleOverride,
				coverUrlOverride = remote.coverUrlOverride,
				contentRatingOverride = remote.contentRatingOverride,
				readingStatus = remote.readingStatus,
				metadataSourceKind = remote.metadataSourceKind,
				metadataBindingSource = remote.metadataBindingSource,
				metadataBindingExternalId = remote.metadataBindingExternalId,
				metadataSourceService = remote.metadataSourceService,
				metadataSourceRemoteId = remote.metadataSourceRemoteId,
				updatedAt = remote.updatedAt,
			)
			if (local == null || candidate.updatedAt >= local.updatedAt) {
				getEntityGraphDao().upsertPrefsRecord(candidate)
			}
		}

		snapshot.entityGraph.relations.forEach { remote ->
			val localFromId = entityIdMapping[remote.fromEntityId] ?: return@forEach
			val localToId = entityIdMapping[remote.toEntityId] ?: return@forEach
			if (localFromId == localToId) {
				return@forEach
			}
			getEntityGraphDao().insertRelation(
				RelationRecord(
					fromEntityId = localFromId,
					toEntityId = localToId,
					type = remote.type,
					weight = 1f,
					createdAt = remote.createdAt,
				),
			)
		}

		snapshot.entityGraph.bindings
			.filter { it.source == LOCAL_MANGA_SOURCE || it.source == LEGACY_LOCAL_MANGA_SOURCE }
			.forEach { binding ->
				val localMangaId = binding.externalId.toLongOrNull()?.let(mangaIdMapping::get) ?: return@forEach
				val localBinding = getEntityGraphDao().findActiveBinding(LOCAL_MANGA_SOURCE, localMangaId.toString())
					?: getEntityGraphDao().findActiveBinding(LEGACY_LOCAL_MANGA_SOURCE, localMangaId.toString())
				if (localBinding != null) {
					entityIdMapping[binding.entityId] = localBinding.entityId
				}
		}
		return SyncIdMapping(mangaIdMapping, entityIdMapping, categoryIdMapping)
	}

	private suspend fun MangaDatabase.restoreSyncWork(snapshot: GoogleDriveSyncSnapshot, mapping: SyncIdMapping) {
		snapshot.work.history.forEach { remote ->
			val localEntityId = mapping.entityIds[remote.entityId] ?: return@forEach
			val localMangaId = mapping.mangaIds[remote.anchorMangaId] ?: return@forEach
			val local = getWorkHistoryDao().find(localEntityId)
			if (local == null || remote.updatedAt >= local.updatedAt) {
				getWorkHistoryDao().upsert(remote.toEntity(localEntityId, localMangaId))
			}
			val legacyCandidate = HistoryEntity(
				mangaId = localMangaId,
				createdAt = remote.createdAt,
				updatedAt = remote.updatedAt,
				chapterId = remote.chapterId,
				page = remote.page,
				scroll = remote.scroll,
				percent = remote.percent,
				deletedAt = remote.deletedAt,
				chaptersCount = remote.chaptersCount,
				parentChapterId = remote.parentChapterId,
			)
			val legacyLocal = getHistoryDao().findIncludingDeleted(localMangaId)
			if (legacyLocal == null || legacyCandidate.updatedAt >= legacyLocal.updatedAt) {
				getHistoryDao().upsertIncludingDeleted(
					legacyCandidate.copy(createdAt = minOf(legacyLocal?.createdAt ?: legacyCandidate.createdAt, legacyCandidate.createdAt)),
				)
			}
		}
		snapshot.work.favourites.forEach { remote ->
			val localEntityId = mapping.entityIds[remote.entityId] ?: return@forEach
			val localCategoryId = mapping.categoryIds[remote.categoryId] ?: return@forEach
			val local = getWorkFavouritesDao().find(localEntityId, localCategoryId)
			if (local == null || remote.updatedAt >= local.updatedAt) {
				getWorkFavouritesDao().upsert(remote.toEntity(localEntityId, localCategoryId))
			}
			val localMangaId = findLegacyFavouriteMangaId(localEntityId) ?: return@forEach
			getFavouritesDao().mergeWithTimestamp(
				FavouriteEntity(
					mangaId = localMangaId,
					categoryId = localCategoryId,
					sortKey = remote.sortKey,
					isPinned = remote.isPinned,
					createdAt = remote.createdAt,
					deletedAt = remote.deletedAt,
					updatedAt = remote.updatedAt,
				),
			)
		}
		snapshot.work.stats.forEach { remote ->
			val localEntityId = mapping.entityIds[remote.entityId] ?: return@forEach
			val localMangaId = mapping.mangaIds[remote.anchorMangaId] ?: return@forEach
			getWorkStatsDao().upsert(remote.toEntity(localEntityId, localMangaId))
		}
	}

	private suspend fun MangaDatabase.findLegacyFavouriteMangaId(entityId: Long): Long? {
		getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
			?.takeIf { getMangaDao().contains(it) }
			?.let { return it }
		return getEntityGraphDao().findActiveBindingsByEntity(entityId)
			.asSequence()
			.filter { it.source == LOCAL_MANGA_SOURCE || it.source == LEGACY_LOCAL_MANGA_SOURCE }
			.mapNotNull { it.externalId.toLongOrNull() }
			.firstOrNull { getMangaDao().contains(it) }
	}

	private fun EntityBindingRecord.shouldKeepOverSync(
		remote: org.skepsun.kototoro.sync.google.data.model.SyncEntityBindingRecord,
	): Boolean {
		val localState = state.toEntityBindingStateOrNull()
		val remoteState = remote.state.toEntityBindingStateOrNull()
		if (updatedAt > 0L && (remote.updatedAt <= 0L || updatedAt > remote.updatedAt)) {
			return true
		}
		if (localState in SYNC_PROTECTED_BINDING_STATES && remoteState !in SYNC_PROTECTED_BINDING_STATES) {
			return true
		}
		return localState == EntityBindingState.MANUAL && remoteState != EntityBindingState.MANUAL
	}

	private suspend fun MangaDatabase.restoreSyncEntity(remote: EntityRecord): Long {
		val dao = getEntityGraphDao()
		val trimmedName = remote.primaryName.trim()
		val computedHash = computeNameHash(trimmedName)
		val existing = dao.findEntity(remote.id)
			?.takeIf { it.type == remote.type }
			?: dao.findEntityByTypeAndPrimaryName(remote.type, trimmedName)
		if (existing == null) {
			val newRecord = remote.copy(
				id = 0L,
				primaryName = trimmedName,
				nameHash = computedHash,
				aliases = encodeStringList(mergeAliases(trimmedName, decodeStringList(remote.aliases)).drop(1)),
				createdAt = remote.createdAt.coerceAtLeast(0L),
				lastAccessed = remote.lastAccessed.coerceAtLeast(0L),
				accessCount = remote.accessCount.coerceAtLeast(1),
			)
			val insertedId = dao.insertEntityIgnore(newRecord)
			if (insertedId != -1L) {
				return insertedId
			}
			return dao.findEntityByTypeAndNameHash(remote.type, computedHash)?.id
				?: dao.insertEntity(newRecord.copy(nameHash = remote.id.takeIf { it > 0L } ?: -(remote.id + 1)))
		}
		val mergedNames = mergeAliases(
			existing.primaryName,
			decodeStringList(existing.aliases) + listOf(trimmedName) + decodeStringList(remote.aliases),
		)
		val newPrimary = mergedNames.firstOrNull() ?: existing.primaryName
		dao.upsertEntityRecord(
			existing.copy(
				primaryName = newPrimary,
				nameHash = computeNameHash(newPrimary),
				aliases = encodeStringList(mergedNames.drop(1)),
				createdAt = minOf(existing.createdAt, remote.createdAt.coerceAtLeast(0L)),
				lastAccessed = maxOf(existing.lastAccessed, remote.lastAccessed.coerceAtLeast(0L)),
				accessCount = maxOf(existing.accessCount, remote.accessCount.coerceAtLeast(1)),
			),
		)
		return existing.id
	}

	private fun TrackEntity.mapWith(mapping: SyncIdMapping): TrackEntity {
		val localMangaId = mapping.mangaIds[mangaId] ?: mangaId
		val localEntityId = entityId?.let { mapping.entityIds[it] ?: it }
		return TrackEntity(
			ownerId = org.skepsun.kototoro.tracker.data.resolveTrackOwnerId(localEntityId, localMangaId),
			mangaId = localMangaId,
			entityId = localEntityId,
			lastChapterId = lastChapterId,
			newChapters = newChapters,
			lastCheckTime = lastCheckTime,
			lastChapterDate = lastChapterDate,
			lastResult = lastResult,
			lastError = lastError,
		)
	}

	private fun TrackLogEntity.mapWith(mapping: SyncIdMapping): TrackLogEntity {
		val localMangaId = mapping.mangaIds[mangaId] ?: mangaId
		val localEntityId = entityId?.let { mapping.entityIds[it] ?: it }
		return TrackLogEntity(
			ownerId = org.skepsun.kototoro.tracker.data.resolveTrackOwnerId(localEntityId, localMangaId),
			mangaId = localMangaId,
			entityId = localEntityId,
			chapters = chapters,
			createdAt = createdAt,
			isUnread = isUnread,
		)
	}

	private suspend fun MangaDatabase.mergeTrack(remote: TrackEntity) {
		if (!getMangaDao().contains(remote.mangaId)) {
			return
		}
		val dao = getTracksDao()
		val local = dao.findByOwnerId(remote.ownerId)
		if (local == null) {
			dao.upsert(remote)
			return
		}
		dao.upsert(local.mergeWith(remote))
	}

	private fun TrackEntity.mergeWith(remote: TrackEntity): TrackEntity {
		val newer = if (remote.isNewerThan(this)) remote else this
		val mergedLastError = when {
			newer.lastResult == TrackEntity.RESULT_FAILED -> newer.lastError
			lastResult == TrackEntity.RESULT_FAILED && remote.lastResult != TrackEntity.RESULT_FAILED -> remote.lastError
			else -> null
		}
		return TrackEntity(
			ownerId = ownerId,
			mangaId = mangaId,
			entityId = entityId ?: remote.entityId,
			lastChapterId = newer.lastChapterId,
			newChapters = if (newChapters == 0 || remote.newChapters == 0) 0 else maxOf(newChapters, remote.newChapters),
			lastCheckTime = maxOf(lastCheckTime, remote.lastCheckTime),
			lastChapterDate = maxOf(lastChapterDate, remote.lastChapterDate),
			lastResult = newer.lastResult,
			lastError = mergedLastError,
		)
	}

	private fun TrackEntity.isNewerThan(other: TrackEntity): Boolean {
		return when {
			lastChapterDate != other.lastChapterDate -> lastChapterDate > other.lastChapterDate
			lastCheckTime != other.lastCheckTime -> lastCheckTime > other.lastCheckTime
			else -> lastChapterId > other.lastChapterId
		}
	}

	private suspend fun MangaDatabase.mergeTrackLog(remote: TrackLogEntity) {
		if (!getMangaDao().contains(remote.mangaId)) {
			return
		}
		val dao = getTrackLogsDao()
		val existing = dao.findDuplicate(
			ownerId = remote.ownerId,
			mangaId = remote.mangaId,
			entityId = remote.entityId,
			chapters = remote.chapters,
			createdAt = remote.createdAt,
		)
		if (existing == null) {
			dao.insert(remote)
		} else if (existing.isUnread && !remote.isUnread) {
			dao.markAsRead(existing.id)
			getTracksDao().clearCounter(existing.mangaId)
		} else if (!existing.isUnread) {
			getTracksDao().clearCounter(existing.mangaId)
		}
	}

	private fun decodeSnapshot(bytes: ByteArray): GoogleDriveSyncSnapshot? {
		val text = bytes.decodeToString()
		if (text.isBlank()) {
			return null
		}
		val version = runCatching {
			json.decodeFromString(SchemaProbe.serializer(), text).schemaVersion
		}.getOrNull()
		if (version != null && version > GoogleDriveSyncSnapshot.SCHEMA_VERSION) {
			throw GoogleDriveSyncSchemaException(version)
		}
		return runCatching {
			json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), text)
		}.getOrNull()
	}

	private fun GoogleDriveSyncSnapshot.debugSummary(): String {
		return "content=${content.size} entities=${entityGraph.entities.size} bindings=${entityGraph.bindings.size} " +
			"relations=${entityGraph.relations.size} prefs=${entityGraph.prefs.size} categories=${work.categories.size} " +
			"history=${work.history.size} favourites=${work.favourites.size} stats=${work.stats.size} " +
			"tracks=${feed.tracks.size} logs=${feed.logs.size}"
	}

	private suspend fun MangaDatabase.localDatabaseSummary(): String {
		return "legacyFavourites=${getFavouritesDao().countActive()} legacyHistory=${getHistoryDao().getCount()}"
	}

	private fun GoogleDriveSyncSnapshot.copyForUpload(syncedAt: Long): GoogleDriveSyncSnapshot {
		return GoogleDriveSyncSnapshot(
			schemaVersion = schemaVersion,
			deviceId = settings.deviceId,
			syncedAt = syncedAt,
			entityGraph = entityGraph,
			content = content,
			work = work,
			feed = feed,
			config = config,
		)
	}

	@Serializable
	private class SchemaProbe(
		@SerialName("schema") val schemaVersion: Int = 0,
	)

	private companion object {
		const val LOCAL_MANGA_SOURCE = "local_manga"
		const val LEGACY_LOCAL_MANGA_SOURCE = "0"
		const val MAX_CONFLICT_RETRIES = 3
		const val TAG = "GoogleDriveSync"
		val SYNC_PROTECTED_BINDING_STATES = setOf(
			EntityBindingState.MANUAL,
			EntityBindingState.CANDIDATE,
			EntityBindingState.REJECTED,
		)
	}

	private data class SyncIdMapping(
		val mangaIds: Map<Long, Long>,
		val entityIds: Map<Long, Long>,
		val categoryIds: Map<Long, Long>,
	)
}
