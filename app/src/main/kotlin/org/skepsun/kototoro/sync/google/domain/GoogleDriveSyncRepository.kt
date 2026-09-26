package org.skepsun.kototoro.sync.google.domain

import android.accounts.Account
import android.content.Context
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.room.withTransaction
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.ireader.IReaderExtensionManager
import org.skepsun.kototoro.tsundoku.TsundokuExtensionManager
import org.skepsun.kototoro.cloudstream.runtime.CloudstreamRuntimeManager
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.BuildConfig
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.db.entity.JsonSourceEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.MangaSourceEntity
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.extensions.runtime.ExternalExtensionLoaderSupport
import org.skepsun.kototoro.extensions.runtime.LocalApkExtensionSupport
import org.skepsun.kototoro.parsers.network.CloudFlareHelper
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncApi
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncAuth
import org.skepsun.kototoro.sync.google.data.GoogleDriveSyncSettings
import org.skepsun.kototoro.sync.google.data.model.GoogleDriveSyncSnapshot
import org.skepsun.kototoro.sync.google.data.model.MAX_SYNC_PACKAGE_SIZE_BYTES
import org.skepsun.kototoro.sync.google.data.model.SyncContent
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionPackage
import org.skepsun.kototoro.sync.google.data.model.SyncExtensionRepo
import org.skepsun.kototoro.sync.google.data.model.SyncFavourite
import org.skepsun.kototoro.sync.google.data.model.SyncFavouriteCategory
import org.skepsun.kototoro.sync.google.data.model.SyncFeedState
import org.skepsun.kototoro.sync.google.data.model.SyncHistory
import org.skepsun.kototoro.sync.google.data.model.SyncJsonSource
import org.skepsun.kototoro.sync.google.data.model.SyncSourceState
import org.skepsun.kototoro.sync.google.data.model.SyncStats
import org.skepsun.kototoro.sync.google.data.model.SyncTrack
import org.skepsun.kototoro.sync.google.data.model.SyncTrackLog
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.favourites.domain.FavouritesRepository
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.HistoryRepository
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.canBeClearedBy
import org.skepsun.kototoro.tracker.data.isNewerThan
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackNewChapters
import org.skepsun.kototoro.tracker.data.normalizeTrackFeedState
import org.skepsun.kototoro.tracker.domain.TrackingRepository
import java.io.File
import java.util.Base64
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
    @ApplicationContext private val context: Context,
    private val settings: GoogleDriveSyncSettings,
    private val appSettings: AppSettings,
    private val auth: GoogleDriveSyncAuth,
    private val api: GoogleDriveSyncApi,
    private val database: MangaDatabase,
    private val favouritesRepository: FavouritesRepository,
    private val historyRepository: HistoryRepository,
    private val trackingRepository: TrackingRepository,
    private val mihonExtensionManager: Lazy<MihonExtensionManager>,
    private val aniyomiExtensionManager: Lazy<AniyomiExtensionManager>,
    private val ireaderExtensionManager: Lazy<IReaderExtensionManager>,
    private val tsundokuExtensionManager: Lazy<TsundokuExtensionManager>,
    private val cloudstreamRuntimeManager: Lazy<CloudstreamRuntimeManager>,
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
        repairAfterSync()
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
            runSyncStep("repair after sync") {
                repairAfterSync()
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
        runSyncStep("apply legacy remote snapshot") {
            applyToDatabase(remote)
        }
        runSyncStep("repair after sync") {
            repairAfterSync()
        }
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

    private suspend fun repairAfterSync() {
        database.pruneLocalSyncResidue()
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
        val history = database.getHistoryDao().findAllEntriesIncludingDeleted()
        val favourites = database.getFavouritesDao().findAllEntriesIncludingDeleted()
        val stats = database.getStatsDao().dump().toList()
        val categories = database.getFavouriteCategoriesDao().dump()
        val contentIds = (
            tracks.map { it.mangaId } +
                logs.map { it.mangaId } +
                history.map { it.mangaId } +
                favourites.map { it.mangaId } +
                stats.map { it.mangaId }
        ).toSet()
        val content = database.findMangaEntitiesByIdsChunked(contentIds).map(::SyncContent)
        return GoogleDriveSyncSnapshot(
            schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
            namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
            semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
            deviceId = settings.deviceId,
            syncedAt = System.currentTimeMillis(),
            content = content,
            categories = categories.map(::SyncFavouriteCategory),
            history = history.map(::SyncHistory),
            favourites = favourites.map(::SyncFavourite),
            stats = stats.map(::SyncStats),
            feed = SyncFeedState(
                tracks = tracks.map(::SyncTrack),
                logs = logs.map(::SyncTrackLog),
            ),
            repositories = dumpRepositories(),
            sourceStates = dumpSourceStates(),
            jsonSources = dumpJsonSources(),
            extensions = dumpExtensionPackages(),
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

    private suspend fun applyToDatabase(snapshot: GoogleDriveSyncSnapshot) {
        val norm = snapshot.normalizeToContentV3()
        database.withTransaction {
            val mangaIdMapping = LinkedHashMap<Long, Long>()
            var nextImportedMangaId = minOf(database.getMangaDao().findMinId() ?: 0L, 0L) - 1L
            runSyncStep("apply content") {
                norm.content.forEach { content ->
                    val existingByProjection = content.findLocalProjection(database)
                    val existingById = database.getMangaDao().find(content.id)?.manga
                    val local = existingByProjection ?: existingById?.takeIf { it.hasSameProjectionIdentity(content) } ?: run {
                        val localId = if (existingById != null || database.getMangaDao().contains(content.id)) {
                            nextImportedMangaId--
                        } else {
                            content.id
                        }
                        content.toEntity(localId)
                    }
                    if (existingByProjection == null && existingById?.id != local.id) {
                        database.getMangaDao().upsert(local)
                    }
                    mangaIdMapping[content.id] = local.id
                }
            }

            val categoryIdMapping = LinkedHashMap<Long, Long>()
            runSyncStep("apply categories") {
                norm.categories.forEach { category ->
                    val existing = database.getFavouriteCategoriesDao().findIncludingDeleted(category.id)
                    if (existing == null || category.deletedAt >= existing.deletedAt) {
                        database.getFavouriteCategoriesDao().upsert(category.toEntity())
                    }
                    categoryIdMapping[category.id] = category.id
                }
            }

            runSyncStep("apply history") {
                norm.history.forEach { remote ->
                    val localMangaId = mangaIdMapping[remote.mangaId] ?: remote.mangaId
                    if (!database.getMangaDao().contains(localMangaId)) return@forEach
                    val local = database.getHistoryDao().find(localMangaId)
                    if (local == null || remote.updatedAt >= local.updatedAt) {
                        database.getHistoryDao().upsertSync(remote.toEntity(localMangaId))
                    }
                }
            }

            runSyncStep("apply favourites") {
                norm.favourites.forEach { remote ->
                    val localMangaId = mangaIdMapping[remote.mangaId] ?: remote.mangaId
                    val localCategoryId = categoryIdMapping[remote.categoryId] ?: remote.categoryId
                    if (!database.getMangaDao().contains(localMangaId)) return@forEach
                    val local = database.getFavouritesDao().find(localMangaId, localCategoryId)
                    if (local == null || remote.updatedAt >= local.updatedAt) {
                        database.getFavouritesDao().upsert(remote.toEntity(localMangaId, localCategoryId))
                    }
                }
            }

            runSyncStep("apply stats") {
                norm.stats.forEach { remote ->
                    val localMangaId = mangaIdMapping[remote.mangaId] ?: remote.mangaId
                    if (!database.getMangaDao().contains(localMangaId)) return@forEach
                    database.getStatsDao().upsert(remote.toEntity(localMangaId))
                }
            }

            runSyncStep("apply feed") {
                norm.feed.tracks.forEach { track ->
                    val localMangaId = mangaIdMapping[track.mangaId] ?: track.mangaId
                    if (database.getMangaDao().contains(localMangaId)) {
                        database.mergeTrack(
                            track.toEntity(localMangaId),
                        )
                    }
                }
                norm.feed.logs.forEach { log ->
                    val localMangaId = mangaIdMapping[log.mangaId] ?: log.mangaId
                    if (database.getMangaDao().contains(localMangaId)) {
                        database.mergeTrackLog(
                            log.toEntity(localMangaId),
                        )
                    }
                }
            }

            runSyncStep("apply repositories") {
                database.restoreRepositories(norm)
            }
            runSyncStep("apply source states") {
                database.restoreSourceStates(norm)
            }
            runSyncStep("apply json sources") {
                database.restoreJsonSources(norm)
            }
            runSyncStep("prune local sync residue") {
                database.pruneLocalSyncResidue()
            }
        }
        runSyncStep("apply extension packages") {
            restoreExtensionPackages(norm)
        }
        runSyncStep("normalize track feed state") {
            database.normalizeTrackFeedState()
        }
    }

    private suspend fun MangaDatabase.pruneLocalSyncResidue() {
        val deletedContent = getMangaDao().cleanupSyncResidue()
        if (deletedContent > 0) {
            Log.d(TAG, "sync pruned unreferenced content=$deletedContent")
        }
    }

    private suspend fun MangaDatabase.mergeTrack(remote: TrackEntity) {
        if (!getMangaDao().contains(remote.mangaId)) {
            return
        }
        val dao = getTracksDao()
        val local = dao.find(remote.mangaId)
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
            mangaId = mangaId,
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
            mangaId = remote.mangaId,
            chapters = remote.chapters,
            createdAt = remote.createdAt,
        )
        if (existing == null) {
            dao.insert(remote)
        } else if (existing.isUnread && !remote.isUnread) {
            dao.markAsRead(existing.id)
            getTracksDao().find(existing.mangaId)
                ?.takeIf { it.canBeClearedBy(remote) }
                ?.let { getTracksDao().clearCounter(existing.mangaId) }
        } else if (!existing.isUnread) {
            getTracksDao().find(existing.mangaId)
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
            requireCurrentProtocol &&
                namespace != GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3 &&
                namespace != GoogleDriveSyncSnapshot.NAMESPACE_WORK_V2 -> {
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
        val norm = normalizeToContentV3()
        val activeFavourites = norm.favourites.filter { it.deletedAt == 0L }
        val activeHistory = norm.history.count { it.deletedAt == 0L }
        return "content=${norm.content.size} categories=${norm.categories.size} " +
            "history=${norm.history.size}/$activeHistory favourites=${norm.favourites.size}/${activeFavourites.size} " +
            "stats=${norm.stats.size} tracks=${norm.feed.tracks.size} logs=${norm.feed.logs.size}"
    }

    private suspend fun MangaDatabase.localDatabaseSummary(): String {
        return "favourites=${getFavouritesDao().findAllActiveEntries().size} history=${getHistoryDao().findAllEntriesIncludingDeleted().size}"
    }

    private suspend fun SyncContent.findLocalProjection(database: MangaDatabase): MangaEntity? {
        if (url.isNotBlank()) {
            database.getMangaDao().findBySourceAndUrl(source, url)?.manga?.let { return it }
            database.getMangaDao().findBySourceAndPublicUrl(source, url)?.manga?.let { return it }
        }
        if (publicUrl.isNotBlank()) {
            database.getMangaDao().findBySourceAndPublicUrl(source, publicUrl)?.manga?.let { return it }
            database.getMangaDao().findBySourceAndUrl(source, publicUrl)?.manga?.let { return it }
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

    private fun GoogleDriveSyncSnapshot.copyForUpload(syncedAt: Long): GoogleDriveSyncSnapshot {
        val norm = normalizeToContentV3()
        return GoogleDriveSyncSnapshot(
            schemaVersion = GoogleDriveSyncSnapshot.SCHEMA_VERSION,
            namespace = GoogleDriveSyncSnapshot.NAMESPACE_CONTENT_V3,
            semanticSchemaVersion = GoogleDriveSyncSnapshot.SEMANTIC_SCHEMA_VERSION,
            deviceId = settings.deviceId,
            syncedAt = syncedAt,
            content = norm.content,
            categories = norm.categories,
            history = norm.history,
            favourites = norm.favourites,
            stats = norm.stats,
            feed = norm.feed,
            config = norm.config,
            repositories = norm.repositories,
            sourceStates = norm.sourceStates,
            jsonSources = norm.jsonSources,
            extensions = norm.extensions,
        )
    }

    private suspend fun dumpRepositories(): List<SyncExtensionRepo> {
        return database.getExternalExtensionRepoDao().getAll().map(::SyncExtensionRepo)
    }

    private suspend fun dumpSourceStates(): List<SyncSourceState> {
        return database.getSourcesDao().findAll().map { SyncSourceState(it) }
    }

    private suspend fun dumpJsonSources(): List<SyncJsonSource> {
        return database.getJsonSourceDao().findAll().mapNotNull { entity ->
            val configBytes = entity.config.toByteArray(Charsets.UTF_8)
            if (configBytes.size > MAX_SYNC_PACKAGE_SIZE_BYTES) {
                null
            } else {
                SyncJsonSource(entity)
            }
        }
    }

    private fun dumpExtensionPackages(): List<SyncExtensionPackage> {
        val results = mutableListOf<SyncExtensionPackage>()

        // 1. Local APKs: mihon, aniyomi, ireader, tsundoku
        val ecosystems = listOf("mihon", "aniyomi", "ireader", "tsundoku")
        for (ecosystem in ecosystems) {
            val apkFiles = LocalApkExtensionSupport.findLocalApkFiles(context, ecosystem)
            for (file in apkFiles) {
                val pkgInfo = ExternalExtensionLoaderSupport.getPackageArchiveInfoOrNull(context.packageManager, file)
                val pkgName = pkgInfo?.packageName ?: file.nameWithoutExtension
                val label = pkgInfo?.applicationInfo?.loadLabel(context.packageManager)?.toString() ?: pkgName
                val versionName = pkgInfo?.versionName
                val versionCode = pkgInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: 0L
                val size = file.length()
                val isPayload = size in 1..MAX_SYNC_PACKAGE_SIZE_BYTES
                val payload = if (isPayload) {
                    runCatching { Base64.getEncoder().encodeToString(file.readBytes()) }.getOrNull()
                } else {
                    null
                }
                results.add(
                    SyncExtensionPackage(
                        packageId = pkgName,
                        name = label,
                        kind = ecosystem,
                        versionName = versionName,
                        versionCode = versionCode,
                        fileName = file.name,
                        sizeBytes = size,
                        isPayloadIncluded = isPayload && payload != null,
                        payloadBase64 = payload,
                    ),
                )
            }
        }

        // 2. JAR plugins
        val pluginsDir = File(context.filesDir, "plugins")
        if (pluginsDir.exists() && pluginsDir.isDirectory) {
            val jarPrefs = context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
            val jarFiles = pluginsDir.listFiles { f -> f.isFile && f.extension.equals("jar", ignoreCase = true) }.orEmpty()
            for (file in jarFiles) {
                val pkgName = file.nameWithoutExtension
                val versionCode = jarPrefs.getLong(pkgName, 0L)
                val repoUrl = jarPrefs.getString("$pkgName:repo", null)
                val size = file.length()
                val isPayload = size in 1..MAX_SYNC_PACKAGE_SIZE_BYTES
                val payload = if (isPayload) {
                    runCatching { Base64.getEncoder().encodeToString(file.readBytes()) }.getOrNull()
                } else {
                    null
                }
                results.add(
                    SyncExtensionPackage(
                        packageId = pkgName,
                        name = pkgName,
                        kind = "jar",
                        versionCode = versionCode,
                        repoUrl = repoUrl,
                        fileName = file.name,
                        sizeBytes = size,
                        isPayloadIncluded = isPayload && payload != null,
                        payloadBase64 = payload,
                    ),
                )
            }
        }

        // 3. Cloudstream plugins
        val csDir = File(File(context.filesDir, "cloudstream"), "plugins")
        if (csDir.exists() && csDir.isDirectory) {
            val csPrefs = context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
            val csFiles = csDir.listFiles { f -> f.isFile && (f.extension.equals("cs3", ignoreCase = true) || f.extension.equals("jar", ignoreCase = true)) }.orEmpty()
            for (file in csFiles) {
                val pkgName = csPrefs.all.entries.firstOrNull { it.key.endsWith(":archive") && it.value == file.name }
                    ?.key?.substringBefore(":archive") ?: file.nameWithoutExtension
                val name = csPrefs.getString("$pkgName:name", pkgName) ?: pkgName
                val repoUrl = csPrefs.getString("$pkgName:repo", null)
                val versionCode = csPrefs.getLong(pkgName, 0L)
                val size = file.length()
                val isPayload = size in 1..MAX_SYNC_PACKAGE_SIZE_BYTES
                val payload = if (isPayload) {
                    runCatching { Base64.getEncoder().encodeToString(file.readBytes()) }.getOrNull()
                } else {
                    null
                }
                results.add(
                    SyncExtensionPackage(
                        packageId = pkgName,
                        name = name,
                        kind = "cloudstream",
                        versionCode = versionCode,
                        repoUrl = repoUrl,
                        fileName = file.name,
                        sizeBytes = size,
                        isPayloadIncluded = isPayload && payload != null,
                        payloadBase64 = payload,
                    ),
                )
            }
        }

        return results
    }

    private suspend fun MangaDatabase.restoreRepositories(snapshot: GoogleDriveSyncSnapshot) {
        snapshot.repositories.forEach { repo ->
            val existing = getExternalExtensionRepoDao().get(repo.type, repo.baseUrl)
            if (existing == null) {
                getExternalExtensionRepoDao().upsert(repo.toEntity())
            } else if (existing.name != repo.name || existing.website != repo.website || existing.signingKeyFingerprint != repo.signingKeyFingerprint) {
                getExternalExtensionRepoDao().upsert(
                    existing.copy(
                        name = repo.name.ifBlank { existing.name },
                        website = repo.website.ifBlank { existing.website },
                        signingKeyFingerprint = repo.signingKeyFingerprint.ifBlank { existing.signingKeyFingerprint },
                        updatedAt = maxOf(existing.updatedAt, repo.updatedAt),
                    ),
                )
            }
        }
    }

    private suspend fun MangaDatabase.restoreSourceStates(snapshot: GoogleDriveSyncSnapshot) {
        snapshot.sourceStates.forEach { state ->
            val existing = getSourcesDao().find(state.source)
            if (existing != null) {
                getSourcesDao().upsert(
                    existing.copy(
                        isEnabled = state.isEnabled,
                        isPinned = state.isPinned,
                        sortKey = state.sortKey,
                        lastUsedAt = maxOf(existing.lastUsedAt, state.usedAt),
                    ),
                )
            } else {
                getSourcesDao().upsert(
                    MangaSourceEntity(
                        source = state.source,
                        isEnabled = state.isEnabled,
                        sortKey = state.sortKey,
                        addedIn = BuildConfig.VERSION_CODE,
                        lastUsedAt = state.usedAt,
                        isPinned = state.isPinned,
                        cfState = CloudFlareHelper.PROTECTION_NOT_DETECTED,
                    ),
                )
            }
        }
    }

    private suspend fun MangaDatabase.restoreJsonSources(snapshot: GoogleDriveSyncSnapshot) {
        snapshot.jsonSources.forEach { remote ->
            val existing = (if (remote.id.isNotBlank()) getJsonSourceDao().getById(remote.id) else null)
                ?: getJsonSourceDao().findByName(remote.name)
            if (existing == null) {
                getJsonSourceDao().insert(remote.toEntity())
            } else if (remote.updatedAt >= existing.updatedAt) {
                getJsonSourceDao().update(
                    existing.copy(
                        name = remote.name,
                        config = remote.config.ifBlank { existing.config },
                        enabled = remote.isEnabled,
                        isPinned = remote.isPinned,
                        iconUrl = remote.iconUrl ?: existing.iconUrl,
                        updatedAt = remote.updatedAt,
                        lastUsedAt = maxOf(existing.lastUsedAt, remote.lastUsedAt),
                    ),
                )
            }
        }
    }

    private suspend fun restoreExtensionPackages(snapshot: GoogleDriveSyncSnapshot) {
        if (snapshot.extensions.isEmpty()) return
        var hasJarUpdated = false
        var hasCloudstreamUpdated = false
        var hasMihonUpdated = false
        var hasAniyomiUpdated = false
        var hasIReaderUpdated = false
        var hasTsundokuUpdated = false

        snapshot.extensions.forEach { ext ->
            if (!ext.isPayloadIncluded || ext.payloadBase64.isNullOrBlank()) {
                return@forEach
            }
            val bytes = runCatching { Base64.getDecoder().decode(ext.payloadBase64) }.getOrNull() ?: return@forEach
            when (ext.kind.lowercase()) {
                "jar" -> {
                    runCatching {
                        val pluginsDir = File(context.filesDir, "plugins").apply { mkdirs() }
                        val targetFile = File(pluginsDir, ext.fileName ?: "${ext.packageId}.jar")
                        val jarPrefs = context.getSharedPreferences("jar_plugin_versions", Context.MODE_PRIVATE)
                        val localVersion = jarPrefs.getLong(ext.packageId, -1L)
                        val remoteVersion = ext.versionCode ?: 0L
                        if (!targetFile.exists() || targetFile.length() == 0L || remoteVersion > localVersion) {
                            targetFile.writeBytes(bytes)
                            jarPrefs.edit()
                                .putLong(ext.packageId, remoteVersion)
                                .apply {
                                    ext.repoUrl?.let { putString("${ext.packageId}:repo", it) }
                                }
                                .apply()
                            hasJarUpdated = true
                        }
                    }.onFailure { Log.e(TAG, "Failed to restore jar extension ${ext.packageId}", it) }
                }
                "cloudstream" -> {
                    runCatching {
                        val csDir = File(File(context.filesDir, "cloudstream"), "plugins").apply { mkdirs() }
                        val fileName = ext.fileName ?: "${ext.packageId}.cs3"
                        val targetFile = File(csDir, fileName)
                        val csPrefs = context.getSharedPreferences("cloudstream_plugin_versions", Context.MODE_PRIVATE)
                        val localVersion = csPrefs.getLong(ext.packageId, -1L)
                        val remoteVersion = ext.versionCode ?: 0L
                        if (!targetFile.exists() || targetFile.length() == 0L || remoteVersion > localVersion) {
                            targetFile.writeBytes(bytes)
                            csPrefs.edit()
                                .putLong(ext.packageId, remoteVersion)
                                .putString("${ext.packageId}:name", ext.name)
                                .putString("${ext.packageId}:archive", fileName)
                                .apply {
                                    ext.repoUrl?.let { putString("${ext.packageId}:repo", it) }
                                }
                                .apply()
                            hasCloudstreamUpdated = true
                        }
                    }.onFailure { Log.e(TAG, "Failed to restore cloudstream extension ${ext.packageId}", it) }
                }
                "mihon", "aniyomi", "ireader", "tsundoku" -> {
                    runCatching {
                        val ecosystem = ext.kind.lowercase()
                        val remoteVersion = ext.versionCode ?: 0L

                        // 1. Check if already installed in system (as a normal APK)
                        val systemInfo = ExternalExtensionLoaderSupport.getPackageInfoOrNull(context.packageManager, ext.packageId)
                        val systemVersion = systemInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L
                        if (systemVersion >= remoteVersion) {
                            // System package already exists with equal or newer version, skip redundant sideload
                            return@runCatching
                        }

                        // 2. Check if already installed in local managed storage
                        val root = LocalApkExtensionSupport.getManagedExtensionsDir(context, ecosystem)
                        val targetDir = File(root, ext.packageId).apply { mkdirs() }
                        val targetFile = File(targetDir, "${ext.packageId}.apk")
                        val existingInfo = if (targetFile.exists() && targetFile.length() > 0L) {
                            ExternalExtensionLoaderSupport.getPackageArchiveInfoOrNull(context.packageManager, targetFile)
                        } else null
                        val localVersion = existingInfo?.let { PackageInfoCompat.getLongVersionCode(it) } ?: -1L
                        if (targetFile.exists() && targetFile.length() > 0L && localVersion >= remoteVersion) {
                            // Local managed APK already exists with equal or newer version, skip redundant write
                            return@runCatching
                        }

                        // 3. Otherwise store/update managed APK
                        val tempFile = File.createTempFile("sync_ext_${ext.packageId}", ".apk", context.cacheDir)
                        try {
                            tempFile.writeBytes(bytes)
                            LocalApkExtensionSupport.storeManagedApk(context, ecosystem, ext.packageId, tempFile)
                            when (ecosystem) {
                                "mihon" -> hasMihonUpdated = true
                                "aniyomi" -> hasAniyomiUpdated = true
                                "ireader" -> hasIReaderUpdated = true
                                "tsundoku" -> hasTsundokuUpdated = true
                            }
                        } finally {
                            tempFile.delete()
                        }
                    }.onFailure { Log.e(TAG, "Failed to restore apk extension ${ext.packageId}", it) }
                }
            }
        }

        if (hasJarUpdated) {
            runCatching { GlobalExtensionManager.initialize(context) }
        }
        if (hasCloudstreamUpdated) {
            runCatching { cloudstreamRuntimeManager.get().initialize() }
        }
        if (hasMihonUpdated) {
            runCatching { mihonExtensionManager.get().loadExtensions() }
        }
        if (hasAniyomiUpdated) {
            runCatching { aniyomiExtensionManager.get().loadExtensions() }
        }
        if (hasIReaderUpdated) {
            runCatching { ireaderExtensionManager.get().loadExtensions() }
        }
        if (hasTsundokuUpdated) {
            runCatching { tsundokuExtensionManager.get().loadExtensions() }
        }
    }

    @Serializable
    private class SchemaProbe(
        @SerialName("schema") val schemaVersion: Int? = null,
        @SerialName("namespace") val namespace: String? = null,
        @SerialName("semantic_schema") val semanticSchemaVersion: Int? = null,
    )

    private companion object {
        const val MAX_CONFLICT_RETRIES = 3
        const val TAG = "GoogleDriveSync"
        private const val SqliteBindParameterChunkSize = 500
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
    }
}
