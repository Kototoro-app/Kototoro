package org.skepsun.kototoro.sync.google.domain

import android.accounts.Account
import android.util.Log
import androidx.room.withTransaction
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.prefs.AppSettings
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
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.data.RelationRecord
import org.skepsun.kototoro.entitygraph.data.computeProjectionSyncId
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingStateOrNull
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.canBeClearedBy
import org.skepsun.kototoro.tracker.data.isNewerThan
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackNewChapters
import org.skepsun.kototoro.tracker.data.normalizeTrackFeedState
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import org.skepsun.kototoro.work.domain.WorkResolver
import javax.inject.Inject
import javax.inject.Singleton

sealed interface GoogleDriveSyncResult {
	data object Success : GoogleDriveSyncResult
	data class AuthorizationRequired(val error: GoogleDriveSyncAuthorizationException) : GoogleDriveSyncResult
	data class Error(val message: String?, val retryable: Boolean = true) : GoogleDriveSyncResult
	data object Disabled : GoogleDriveSyncResult
}

@Singleton
class GoogleDriveSyncRepository @Inject constructor(
	private val settings: GoogleDriveSyncSettings,
	private val appSettings: AppSettings,
	private val auth: GoogleDriveSyncAuth,
	private val api: GoogleDriveSyncApi,
	private val database: MangaDatabase,
	private val favouritesRepository: FavouritesRepository,
	private val historyRepository: HistoryRepository,
	private val trackingRepository: TrackingRepository,
	private val workResolver: WorkResolver,
) {

	val isSyncing = MutableStateFlow(false)
	private val syncMutex = Mutex()
	private val json = Json {
		encodeDefaults = true
		ignoreUnknownKeys = true
		allowSpecialFloatingPointValues = true
		coerceInputValues = true
	}

	fun onSignedIn(email: String?, displayName: String?, account: Account?) {
		settings.accountEmail = email?.ifBlank { null } ?: "Google Drive"
		settings.accountName = displayName
		settings.googleAccountName = account?.name
	}

	fun shouldSyncOnStart(now: Long = System.currentTimeMillis()): Boolean {
		return settings.isSyncEnabled &&
			settings.isSignedIn &&
			settings.isSyncOnStart &&
			now - settings.lastSyncAttemptTimestamp >= GoogleDriveSyncSettings.START_SYNC_COOLDOWN_MS
	}

	suspend fun sync(): GoogleDriveSyncResult {
		if (!settings.isSyncEnabled) {
			return GoogleDriveSyncResult.Disabled
		}
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
		} catch (e: GoogleDriveSyncProtocolException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "sync failed: protocol", e)
			GoogleDriveSyncResult.Error(e.message, retryable = false)
		} catch (e: GoogleDriveSyncWriteBlockedException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "sync failed: write blocked", e)
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

	suspend fun deleteRemoteData(): GoogleDriveSyncResult {
		if (!settings.isSyncEnabled) {
			return GoogleDriveSyncResult.Disabled
		}
		return try {
			val token = auth.requireAccessToken()
			api.findCurrentSyncFiles(token).forEach { file ->
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
	}

	suspend fun importLegacyRemoteData(): GoogleDriveSyncResult {
		if (!settings.isSyncEnabled) {
			return GoogleDriveSyncResult.Disabled
		}
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
			importLegacyRemoteData(token)
			settings.lastSyncTimestamp = System.currentTimeMillis()
			settings.lastSyncError = null
			settings.isDirty = false
			GoogleDriveSyncResult.Success
		} catch (e: GoogleDriveSyncAuthorizationException) {
			settings.lastSyncError = e.message
			GoogleDriveSyncResult.AuthorizationRequired(e)
		} catch (e: GoogleDriveSyncSchemaException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "legacy sync import failed: schema", e)
			GoogleDriveSyncResult.Error(e.message, retryable = false)
		} catch (e: GoogleDriveSyncProtocolException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "legacy sync import failed: protocol", e)
			GoogleDriveSyncResult.Error(e.message, retryable = false)
		} catch (e: GoogleDriveSyncWriteBlockedException) {
			settings.lastSyncError = e.message
			Log.e(TAG, "legacy sync import failed: write blocked", e)
			GoogleDriveSyncResult.Error(e.message, retryable = false)
		} catch (e: Exception) {
			settings.lastSyncError = e.message ?: e.javaClass.simpleName
			Log.e(TAG, "legacy sync import failed: ${settings.lastSyncError}", e)
			GoogleDriveSyncResult.Error(settings.lastSyncError)
		} finally {
			isSyncing.value = false
			syncMutex.unlock()
		}
	}

	suspend fun signOut() {
		runCatching {
			auth.revokeAccess(settings.googleAccountName?.let { Account(it, GOOGLE_ACCOUNT_TYPE) })
		}
		settings.clearAccount()
	}

	private suspend fun performSync(token: String) {
		normalizeLocalWorkStateForSync()
		var attempt = 0
		while (true) {
			val files = runSyncStep("list drive files") {
				api.findCurrentSyncFiles(token)
			}
			val canonical = files.firstOrNull()
			val baseVersion = canonical?.version
			val decoded = ArrayList<GoogleDriveSyncSnapshot>(files.size)
			val decodedIds = HashSet<String>(files.size)
			for (file in files) {
				val snapshot = runSyncStep("download ${file.id}") {
					decodeCurrentSnapshot(api.download(token, file.id))
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
			val applied = runSyncStep("build upload snapshot") {
				buildLocalSnapshot()
			}
			Log.d(TAG, "sync applied=${applied.debugSummary()} ${database.localDatabaseSummary()}")
			val compactedUpload = runSyncStep("compact upload snapshot") {
				GoogleDriveSyncMerger.mergeSnapshots(applied, null)
			}
			Log.d(TAG, "sync upload=${compactedUpload.debugSummary()}")
			val upload = compactedUpload.copyForUpload(syncedAt = System.currentTimeMillis())
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

	private suspend fun importLegacyRemoteData(token: String) {
		val files = runSyncStep("list legacy drive files") {
			api.findLegacySyncFiles(token)
		}
		val decoded = ArrayList<GoogleDriveSyncSnapshot>(files.size)
		for (file in files) {
			val snapshot = runSyncStep("download legacy ${file.id}") {
				decodeLegacySnapshot(api.download(token, file.id))
			}
			if (snapshot != null) {
				decoded += snapshot
			}
		}
		val remote = runSyncStep("merge legacy remote snapshots") {
			GoogleDriveSyncMerger.combine(decoded)
		} ?: return
		Log.d(TAG, "legacy import remote=${remote.debugSummary()} files=${files.size}")
		appSettings.isWorkMigrationSyncWriteBlocked = true
		appSettings.requiresWorkMigrationNormalization = false
		runSyncStep("apply legacy remote snapshot") {
			applyToDatabase(remote)
		}
		normalizeLocalWorkStateForSync()
		val upload = runSyncStep("build current snapshot after legacy import") {
			GoogleDriveSyncMerger.mergeSnapshots(buildLocalSnapshot(), null)
				.copyForUpload(syncedAt = System.currentTimeMillis())
		}
		Log.d(TAG, "legacy import upload=${upload.debugSummary()} ${database.localDatabaseSummary()}")
		val payload = json.encodeToString(GoogleDriveSyncSnapshot.serializer(), upload).encodeToByteArray()
		val currentFiles = runSyncStep("list current drive files") {
			api.findCurrentSyncFiles(token)
		}
		val current = currentFiles.firstOrNull()
		val fileId = runSyncStep("upload current snapshot") {
			api.upload(token, payload, current?.id)
		}
		currentFiles.filter { it.id != fileId }.forEach { duplicate ->
			runCatching { api.delete(token, duplicate.id) }
		}
	}

	private suspend fun normalizeLocalWorkStateForSync() {
		val favouritesNormalized = runSyncStep("normalize favourites") {
			favouritesRepository.normalizeWorkFavouritesForSync()
		}
		val historyNormalized = runSyncStep("normalize history") {
			historyRepository.normalizeWorkHistoryForSync()
		}
		runSyncStep("normalize tracks") {
			trackingRepository.normalizeTracksForSync()
		}
		val normalized = favouritesNormalized && historyNormalized
		appSettings.requiresWorkMigrationNormalization = !normalized
		if (normalized) {
			appSettings.isWorkMigrationSyncWriteBlocked = false
		}
		if (!normalized || appSettings.isWorkMigrationSyncWriteBlocked) {
			throw GoogleDriveSyncWriteBlockedException()
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
			.filter { it.anchorMangaId != null }
		val workStats = database.getWorkStatsDao().dumpEnabled().toList()
		val entityGraphDao = database.getEntityGraphDao()
		val entityRecords = entityGraphDao.dumpEntities()
		val entityBindings = entityGraphDao.dumpBindings()
		val entityPrefs = entityGraphDao.dumpPrefs()
		val scope = buildAuthoritativeSyncScope(
			tracks = tracks,
			logs = logs,
			workHistory = workHistory,
			workFavourites = workFavourites,
			workStats = workStats,
			entityPrefs = entityPrefs,
			entityBindings = entityBindings,
		)
		return GoogleDriveSyncSnapshot(
			deviceId = settings.deviceId,
			syncedAt = System.currentTimeMillis(),
			entityGraph = SyncEntityGraph(
				entities = entityRecords.filter { it.id in scope.entityIds }.map {
					SyncEntityRecord(
						id = it.id,
						syncId = it.syncId,
						type = it.type,
						contentType = it.contentType,
						primaryName = it.primaryName,
						nameHash = it.nameHash,
						aliases = it.aliases,
						createdAt = it.createdAt,
						lastAccessed = it.lastAccessed,
						accessCount = it.accessCount,
					)
				},
				bindings = scope.bindings.map {
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
				relations = entityGraphDao.dumpRelations()
					.filter { it.fromEntityId in scope.entityIds && it.toEntityId in scope.entityIds }
					.map {
						SyncEntityRelationRecord(
							fromEntityId = it.fromEntityId,
							toEntityId = it.toEntityId,
							type = it.type,
							createdAt = it.createdAt,
						)
					},
				prefs = scope.prefs.map {
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
			content = database.findMangaEntitiesByIdsChunked(scope.contentIds).map(::SyncContent),
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

	private suspend fun MangaDatabase.findMangaEntitiesByIdsChunked(ids: Collection<Long>): List<MangaEntity> {
		if (ids.isEmpty()) {
			return emptyList()
		}
		return ids
			.chunked(SqliteBindParameterChunkSize)
			.flatMap { chunk -> getMangaDao().findEntitiesByIds(chunk) }
	}

	private suspend fun buildAuthoritativeSyncScope(
		tracks: List<TrackEntity>,
		logs: List<TrackLogEntity>,
		workHistory: List<WorkHistoryEntity>,
		workFavourites: List<WorkFavouriteEntity>,
		workStats: List<WorkStatsEntity>,
		entityPrefs: List<EntityPrefsRecord>,
		entityBindings: List<EntityBindingRecord>,
	): AuthoritativeSyncScope {
		val favouriteAnchorIds = workFavourites.mapNotNull { it.anchorMangaId }.toSet()
		val entityIds = (
			tracks.mapNotNull { it.entityId } +
				logs.mapNotNull { it.entityId } +
				workHistory.map { it.entityId } +
				workFavourites.map { it.entityId } +
				workStats.map { it.entityId }
			).toSet()
		val scopedPrefs = entityPrefs.filter { it.entityId in entityIds }
		val contentIds = (
			tracks.map { it.mangaId } +
				logs.map { it.mangaId } +
				workHistory.map { it.anchorMangaId } +
				workStats.map { it.anchorMangaId } +
				scopedPrefs.mapNotNull { it.preferredLocalMangaId } +
				favouriteAnchorIds
			).toSet()
		val bindings = entityBindings
			.filter { it.entityId in entityIds }
			.filter { it.isAuthoritativeSyncBinding(contentIds) }
		return AuthoritativeSyncScope(
			entityIds = entityIds,
			contentIds = contentIds,
			bindings = bindings,
			prefs = scopedPrefs,
		)
	}

	private fun EntityBindingRecord.isAuthoritativeSyncBinding(authoritativeContentIds: Set<Long>): Boolean {
		if (source != LOCAL_MANGA_SOURCE && source != LEGACY_LOCAL_MANGA_SOURCE) {
			return true
		}
		return externalId.toLongOrNull() in authoritativeContentIds
	}

	private suspend fun applyToDatabase(snapshot: GoogleDriveSyncSnapshot) {
		val mapping = database.withTransaction {
			val mapping = runSyncStep("apply anchors") {
				database.restoreSyncAnchors(snapshot)
			}
			runSyncStep("apply work") {
				database.restoreSyncWork(snapshot, mapping)
			}
			mapping
		}
		database.withTransaction {
			runSyncStep("apply feed") {
				snapshot.feed.tracks.forEach { track ->
					track.toEntity().mapWith(database, mapping)?.let { database.mergeTrack(it) }
				}
				snapshot.feed.logs.forEach { log ->
					log.toEntity().mapWith(database, mapping)?.let { database.mergeTrackLog(it) }
				}
			}
			runSyncStep("prune local sync residue") {
				database.pruneLocalSyncResidue()
			}
		}
		runSyncStep("normalize track feed state") {
			database.normalizeTrackFeedState()
		}
	}

	private suspend fun MangaDatabase.restoreSyncAnchors(snapshot: GoogleDriveSyncSnapshot): SyncIdMapping {
		val mangaIdMapping = LinkedHashMap<Long, Long>()
		val categoryIdMapping = LinkedHashMap<Long, Long>()
		var nextImportedMangaId = minOf(getMangaDao().findMinId() ?: 0L, 0L) - 1L
		snapshot.content.forEach { content ->
			val existingByProjection = content.findLocalProjection(this)
			val existingById = getMangaDao().find(content.id)?.manga
			val local = existingByProjection ?: existingById?.takeIf { it.hasSameProjectionIdentity(content) } ?: run {
				val localId = if (existingById != null || getMangaDao().contains(content.id)) {
					nextImportedMangaId--
				} else {
					content.id
				}
				content.toEntity(localId)
			}
			if (existingByProjection == null && existingById?.id != local.id) {
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

		val entityIdMapping = resolveRemoteWorkEntityIds(snapshot, mangaIdMapping)

		snapshot.content.forEach { content ->
			val projectionKey = ProjectionIdentityKeys.bindingKey(content.url, content.publicUrl)
				?: return@forEach
			val localEntityId = content.findLocalEntityIdForProjectionBackfill(
				database = this,
				snapshot = snapshot,
				mangaIdMapping = mangaIdMapping,
				entityIdMapping = entityIdMapping,
			) ?: return@forEach
			if (getEntityGraphDao().findBinding(content.source, projectionKey) == null) {
				getEntityGraphDao().upsertBinding(
					EntityBindingRecord(
						entityId = localEntityId,
						source = content.source,
						externalId = projectionKey,
						confidence = 1f,
						isPrimary = false,
						sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
						state = EntityBindingState.CONFIRMED.name,
						createdBy = EntityBindingCreatedBy.SYNC.name,
						updatedAt = System.currentTimeMillis(),
					),
				)
			}
		}

		snapshot.entityGraph.bindings.forEach { remote ->
			val localEntityId = entityIdMapping[remote.entityId] ?: return@forEach
			val localExternalId = if (remote.source == LOCAL_MANGA_SOURCE || remote.source == LEGACY_LOCAL_MANGA_SOURCE) {
				remote.externalId.toLongOrNull()?.let(mangaIdMapping::get)?.toString() ?: return@forEach
			} else {
				remote.externalId
			}
			val existing = getEntityGraphDao().findBinding(remote.source, localExternalId)
			if (remote.isLocalContentBinding() && existing != null && existing.entityId != localEntityId) {
				Log.d(
					TAG,
					"sync skipped conflicting local content binding: source=${remote.source} " +
						"externalId=$localExternalId localEntity=${existing.entityId} remoteEntity=$localEntityId",
				)
				return@forEach
			}
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

		return SyncIdMapping(mangaIdMapping, entityIdMapping, categoryIdMapping)
	}

	private suspend fun MangaDatabase.resolveRemoteWorkEntityIds(
		snapshot: GoogleDriveSyncSnapshot,
		mangaIdMapping: Map<Long, Long>,
	): MutableMap<Long, Long> {
		val entityIdMapping = LinkedHashMap<Long, Long>()
		val remoteWorkEntityIds = (
			snapshot.work.history.map { it.entityId } +
				snapshot.work.favourites.map { it.entityId } +
				snapshot.work.stats.map { it.entityId } +
				snapshot.feed.tracks.mapNotNull { it.entityId } +
				snapshot.feed.logs.mapNotNull { it.entityId }
			).toSet()
		suspend fun mapByAnchor(remoteEntityId: Long, remoteMangaId: Long?) {
			if (remoteMangaId == null || remoteEntityId in entityIdMapping) {
				return
			}
			val localMangaId = mangaIdMapping[remoteMangaId] ?: return
			val localEntityId = getEntityGraphDao().findActiveBinding(LOCAL_MANGA_SOURCE, localMangaId.toString())?.entityId
				?: getEntityGraphDao().findActiveBinding(LEGACY_LOCAL_MANGA_SOURCE, localMangaId.toString())?.entityId
				?: return
			entityIdMapping[remoteEntityId] = localEntityId
		}
		snapshot.work.history.forEach { mapByAnchor(it.entityId, it.anchorMangaId) }
		snapshot.work.favourites.forEach { mapByAnchor(it.entityId, it.anchorMangaId) }
		snapshot.work.stats.forEach { mapByAnchor(it.entityId, it.anchorMangaId) }
		snapshot.feed.tracks.forEach { mapByAnchor(it.entityId ?: return@forEach, it.mangaId) }
		snapshot.feed.logs.forEach { mapByAnchor(it.entityId ?: return@forEach, it.mangaId) }
		val remoteEntitiesById = snapshot.entityGraph.entities.associateBy { it.id }
		val remoteReadingBindingsByEntityId = snapshot.entityGraph.bindings
			.filter { it.isAuthoritativeProjectionBindingForSync() }
			.groupBy { it.entityId }
		remoteEntitiesById.values.forEach { remote ->
			val syncId = remote.syncId.trim().ifEmpty {
				remoteReadingBindingsByEntityId[remote.id]
					?.singleOrNull()
					?.let { binding -> computeProjectionSyncId(binding.source, binding.externalId) }
					.orEmpty()
			}
			if (syncId.isEmpty()) {
				return@forEach
			}
			val local = getEntityGraphDao().findEntityBySyncId(syncId)
				?.takeIf { it.type == remote.type }
				?: return@forEach
			entityIdMapping[remote.id] = local.id
		}
		val remoteLocalBindingsByEntityId = snapshot.entityGraph.bindings
			.filter { it.isLocalContentBinding() }
			.groupBy { it.entityId }
		var restored = 0
		var skipped = 0
		for (remoteEntityId in remoteWorkEntityIds) {
			if (remoteEntityId in entityIdMapping) {
				continue
			}
			val remote = remoteEntitiesById[remoteEntityId]
			if (remote == null) {
				skipped++
				continue
			}
			if (hasConflictingLocalContentBinding(remoteLocalBindingsByEntityId[remoteEntityId].orEmpty(), mangaIdMapping)) {
				skipped++
				continue
			}
			val localId = restoreGoogleDriveSyncEntityIsolated(
				EntityRecord(
					id = remote.id,
					syncId = remote.syncId,
					type = remote.type,
					contentType = remote.contentType,
					primaryName = remote.primaryName,
					nameHash = remote.nameHash,
					aliases = remote.aliases,
					createdAt = remote.createdAt,
					lastAccessed = remote.lastAccessed,
					accessCount = remote.accessCount,
				),
			)
			entityIdMapping[remoteEntityId] = localId
			restored++
		}
		if (restored > 0 || skipped > 0) {
			Log.d(TAG, "sync remote work entities restored=$restored skipped=$skipped")
		}
		return entityIdMapping
	}

	private suspend fun MangaDatabase.hasConflictingLocalContentBinding(
		remoteBindings: List<SyncEntityBindingRecord>,
		mangaIdMapping: Map<Long, Long>,
	): Boolean {
		for (binding in remoteBindings) {
			val remoteMangaId = binding.externalId.toLongOrNull() ?: return true
			val localMangaId = mangaIdMapping[remoteMangaId] ?: return true
			if (getEntityGraphDao().findBinding(binding.source, localMangaId.toString()) != null) {
				return true
			}
		}
		return false
	}

	private suspend fun MangaDatabase.pruneLocalSyncResidue() {
		val deletedContent = getMangaDao().cleanupSyncResidue()
		if (deletedContent > 0) {
			Log.d(TAG, "sync pruned unreferenced content=$deletedContent")
		}
	}

	private suspend fun MangaDatabase.restoreSyncWork(snapshot: GoogleDriveSyncSnapshot, mapping: SyncIdMapping) {
		snapshot.work.history.forEach { remote ->
			val localMangaId = mapping.mangaIds[remote.anchorMangaId] ?: return@forEach
			val localEntityId = resolveLocalEntityIdForManga(
				mapping = mapping,
				remoteEntityId = remote.entityId,
				localMangaId = localMangaId,
			) ?: return@forEach
			val local = getWorkHistoryDao().find(localEntityId)
			if (local == null || remote.updatedAt >= local.updatedAt) {
				getWorkHistoryDao().upsert(remote.toEntity(localEntityId, localMangaId))
			}
		}
		snapshot.work.favourites.forEach { remote ->
			val localCategoryId = mapping.categoryIds[remote.categoryId] ?: return@forEach
			val localMangaId = remote.anchorMangaId?.let(mapping.mangaIds::get)
			if (localMangaId == null) {
				Log.d(TAG, "sync skipped favourite without authoritative anchor: entityId=${remote.entityId} categoryId=${remote.categoryId}")
				return@forEach
			}
			val localEntityId = resolveLocalEntityIdForManga(
				mapping = mapping,
				remoteEntityId = remote.entityId,
				localMangaId = localMangaId,
			) ?: return@forEach
			val local = getWorkFavouritesDao().find(localEntityId, localCategoryId)
			if (local == null || remote.updatedAt >= local.updatedAt) {
				getWorkFavouritesDao().upsert(remote.toEntity(localEntityId, localCategoryId, localMangaId))
			}
		}
		snapshot.work.stats.forEach { remote ->
			val localMangaId = mapping.mangaIds[remote.anchorMangaId] ?: return@forEach
			val localEntityId = resolveLocalEntityIdForManga(
				mapping = mapping,
				remoteEntityId = remote.entityId,
				localMangaId = localMangaId,
			) ?: return@forEach
			getWorkStatsDao().upsert(remote.toEntity(localEntityId, localMangaId))
		}
	}

	private suspend fun MangaDatabase.resolveLocalEntityIdForManga(
		mapping: SyncIdMapping,
		remoteEntityId: Long,
		localMangaId: Long?,
	): Long? {
		if (localMangaId == null) {
			return mapping.entityIds[remoteEntityId]
		}
		return getEntityGraphDao().findActiveBinding(LOCAL_MANGA_SOURCE, localMangaId.toString())?.entityId
			?: getEntityGraphDao().findActiveBinding(LEGACY_LOCAL_MANGA_SOURCE, localMangaId.toString())?.entityId
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

	private suspend fun TrackEntity.mapWith(database: MangaDatabase, mapping: SyncIdMapping): TrackEntity? {
		val localMangaId = mapping.mangaIds[mangaId] ?: mangaId
		val localEntityId = database.resolveLocalEntityIdForManga(
			mapping = mapping,
			remoteEntityId = entityId ?: 0L,
			localMangaId = localMangaId,
		) ?: return null
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

	private suspend fun TrackLogEntity.mapWith(database: MangaDatabase, mapping: SyncIdMapping): TrackLogEntity? {
		val localMangaId = mapping.mangaIds[mangaId] ?: mangaId
		val localEntityId = database.resolveLocalEntityIdForManga(
			mapping = mapping,
			remoteEntityId = entityId ?: 0L,
			localMangaId = localMangaId,
		) ?: return null
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
			newChapters = mergeRestoredTrackNewChapters(this, remote),
			lastCheckTime = maxOf(lastCheckTime, remote.lastCheckTime),
			lastChapterDate = maxOf(lastChapterDate, remote.lastChapterDate),
			lastResult = newer.lastResult,
			lastError = mergedLastError,
		)
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
			getTracksDao().findByOwnerId(existing.ownerId)
				?.takeIf { it.canBeClearedBy(remote) }
				?.let { getTracksDao().clearCounter(existing.mangaId) }
		} else if (!existing.isUnread) {
			getTracksDao().findByOwnerId(existing.ownerId)
				?.takeIf { it.canBeClearedBy(existing) }
				?.let { getTracksDao().clearCounter(existing.mangaId) }
		}
	}

	private fun decodeCurrentSnapshot(bytes: ByteArray): GoogleDriveSyncSnapshot? {
		return decodeSnapshot(bytes, requireCurrentProtocol = true)
	}

	private fun decodeLegacySnapshot(bytes: ByteArray): GoogleDriveSyncSnapshot? {
		return decodeSnapshot(bytes, requireCurrentProtocol = false)
	}

	private fun decodeSnapshot(bytes: ByteArray, requireCurrentProtocol: Boolean): GoogleDriveSyncSnapshot? {
		val text = bytes.decodeToString()
		if (text.isBlank()) {
			return null
		}
		val probe = runCatching {
			json.decodeFromString(SchemaProbe.serializer(), text)
		}.getOrNull()
		val version = probe?.schemaVersion
		val namespace = probe?.namespace
		val semanticSchemaVersion = probe?.semanticSchemaVersion
		when {
			version == null && requireCurrentProtocol -> throw GoogleDriveSyncProtocolException()
			version != null && version > GoogleDriveSyncSnapshot.SCHEMA_VERSION -> {
				throw GoogleDriveSyncSchemaException(version)
			}
			requireCurrentProtocol && version != GoogleDriveSyncSnapshot.SCHEMA_VERSION -> {
				throw GoogleDriveSyncProtocolException()
			}
			requireCurrentProtocol && namespace != GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2 -> {
				throw GoogleDriveSyncProtocolException()
			}
			requireCurrentProtocol &&
				semanticSchemaVersion != GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION -> {
				throw GoogleDriveSyncProtocolException()
			}
		}
		return runCatching {
			json.decodeFromString(GoogleDriveSyncSnapshot.serializer(), text)
		}.getOrNull()
	}

	private fun GoogleDriveSyncSnapshot.debugSummary(): String {
		val activeFavourites = work.favourites.filter { it.deletedAt == 0L }
		val activeHistory = work.history.count { it.deletedAt == 0L }
		return "content=${content.size} entities=${entityGraph.entities.size} bindings=${entityGraph.bindings.size} " +
			"relations=${entityGraph.relations.size} prefs=${entityGraph.prefs.size} categories=${work.categories.size} " +
			"history=${work.history.size}/$activeHistory favourites=${work.favourites.size}/${activeFavourites.size} " +
			"favouriteEntities=${activeFavourites.map { it.entityId }.distinct().size} stats=${work.stats.size} " +
			"tracks=${feed.tracks.size} logs=${feed.logs.size}"
	}

	private suspend fun MangaDatabase.localDatabaseSummary(): String {
		return "workFavourites=${getWorkFavouritesDao().countActive()} workHistory=${getWorkHistoryDao().countActive()}"
	}

	private suspend fun SyncContent.findLocalProjection(database: MangaDatabase): MangaEntity? {
		if (url.isNotBlank()) {
			database.getMangaDao().findBySourceAndUrl(source, url)?.manga?.let { return it }
		}
		if (publicUrl.isNotBlank()) {
			database.getMangaDao().findBySourceAndPublicUrl(source, publicUrl)?.manga?.let { return it }
		}
		return null
	}

	private fun MangaEntity.hasSameProjectionIdentity(remote: SyncContent): Boolean {
		return ProjectionIdentityKeys.hasSameIdentity(
			source = source,
			url = url,
			publicUrl = publicUrl,
			otherSource = remote.source,
			otherUrl = remote.url,
			otherPublicUrl = remote.publicUrl,
		)
	}

	private suspend fun SyncContent.findLocalEntityIdForProjectionBackfill(
		database: MangaDatabase,
		snapshot: GoogleDriveSyncSnapshot,
		mangaIdMapping: Map<Long, Long>,
		entityIdMapping: Map<Long, Long>,
	): Long? {
		val localMangaId = mangaIdMapping[id] ?: return null
		database.getEntityGraphDao().findActiveBinding(LOCAL_MANGA_SOURCE, localMangaId.toString())?.let {
			return it.entityId
		}
		database.getEntityGraphDao().findActiveBinding(LEGACY_LOCAL_MANGA_SOURCE, localMangaId.toString())?.let {
			return it.entityId
		}
		for (remoteEntityId in snapshot.findRemoteEntityIdsForContent(id)) {
			entityIdMapping[remoteEntityId]?.let { return it }
		}
		return null
	}

	private fun GoogleDriveSyncSnapshot.findRemoteEntityIdsForContent(contentId: Long): List<Long> {
		return buildList {
			entityGraph.bindings.forEach { binding ->
				if (binding.isLocalContentBinding() && binding.externalId.toLongOrNull() == contentId) {
					add(binding.entityId)
				}
			}
			work.history.forEach { if (it.anchorMangaId == contentId) add(it.entityId) }
			work.favourites.forEach { if (it.anchorMangaId == contentId) add(it.entityId) }
			work.stats.forEach { if (it.anchorMangaId == contentId) add(it.entityId) }
			feed.tracks.forEach { if (it.mangaId == contentId) it.entityId?.let(::add) }
			feed.logs.forEach { if (it.mangaId == contentId) it.entityId?.let(::add) }
		}.distinct()
	}

	private fun SyncEntityBindingRecord.isLocalContentBinding(): Boolean {
		return source == LOCAL_MANGA_SOURCE || source == LEGACY_LOCAL_MANGA_SOURCE
	}

	private fun SyncEntityBindingRecord.isAuthoritativeProjectionBindingForSync(): Boolean {
		if (isLocalContentBinding()) {
			return false
		}
		return sourceKind != "TRACKING_SOURCE"
	}

	private fun GoogleDriveSyncSnapshot.copyForUpload(syncedAt: Long): GoogleDriveSyncSnapshot {
		return GoogleDriveSyncSnapshot(
			schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
			namespace = GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2,
			semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
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
		@SerialName("schema") val schemaVersion: Int? = null,
		@SerialName("namespace") val namespace: String? = null,
		@SerialName("semantic_schema") val semanticSchemaVersion: Int? = null,
	)

	private companion object {
		const val LOCAL_MANGA_SOURCE = "local_manga"
		const val LEGACY_LOCAL_MANGA_SOURCE = "0"
		const val MAX_CONFLICT_RETRIES = 3
		const val TAG = "GoogleDriveSync"
		private const val SqliteBindParameterChunkSize = 500
		private const val GOOGLE_ACCOUNT_TYPE = "com.google"
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

	private data class AuthoritativeSyncScope(
		val entityIds: Set<Long>,
		val contentIds: Set<Long>,
		val bindings: List<EntityBindingRecord>,
		val prefs: List<EntityPrefsRecord>,
	)

}
