package org.skepsun.kototoro.backups.data

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.webkit.CookieManager
import androidx.collection.ArrayMap
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.collectIndexed
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.R
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.data.model.BookmarkBackup
import org.skepsun.kototoro.backups.data.model.CategoryBackup
import org.skepsun.kototoro.backups.data.model.FavouriteBackup
import org.skepsun.kototoro.backups.data.model.HistoryBackup
import org.skepsun.kototoro.backups.data.model.ContentBackup
import org.skepsun.kototoro.backups.data.model.ExtensionRepoBackup
import org.skepsun.kototoro.backups.data.model.ScrobblingBackup
import org.skepsun.kototoro.backups.data.model.SourceBackup
import org.skepsun.kototoro.backups.data.model.StatisticBackup
import org.skepsun.kototoro.backups.data.model.TrackBackup
import org.skepsun.kototoro.backups.data.model.TrackLogBackup
import org.skepsun.kototoro.backups.data.model.WorkFavouriteBackup
import org.skepsun.kototoro.backups.data.model.WorkHistoryBackup
import org.skepsun.kototoro.backups.data.model.WorkStatisticBackup
import org.skepsun.kototoro.backups.domain.BackupRestoreFormat
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.progress.Progress
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.RelationRecord
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingStateOrNull
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.favourites.data.mergeRestoredWorkFavourites
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.canBeClearedBy
import org.skepsun.kototoro.tracker.data.isNewerThan
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackNewChapters
import org.skepsun.kototoro.tracker.data.normalizeTrackFeedState
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import org.skepsun.kototoro.work.domain.WorkResolver
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

private const val TAG = "BackupRepo"
private const val RESTORE_TRANSACTION_BATCH_SIZE = 100
private const val RESTORE_PLACEHOLDER_SOURCE = "RESTORE_PLACEHOLDER"
private val RESTORE_PROTECTED_BINDING_STATES = setOf(
    EntityBindingState.MANUAL,
    EntityBindingState.CANDIDATE,
    EntityBindingState.REJECTED,
)
private val ACTIVE_RESTORE_BINDING_STATES = setOf(
    EntityBindingState.MANUAL,
    EntityBindingState.CONFIRMED,
    EntityBindingState.LEGACY,
)
private val DEFERRED_RESTORE_ORDER = listOf(
    BackupSection.ENTITY_GRAPH_BINDINGS,
    BackupSection.ENTITY_GRAPH_PREFS,
    BackupSection.ENTITY_GRAPH_RELATIONS,
    BackupSection.HISTORY,
    BackupSection.FAVOURITES,
    BackupSection.BOOKMARKS,
    BackupSection.STATS,
    BackupSection.PROJECTIONS,
    BackupSection.WORK_HISTORY,
    BackupSection.WORK_FAVOURITES,
    BackupSection.WORK_STATS,
    BackupSection.TRACKS,
    BackupSection.TRACK_LOGS,
)
private val WORK_STATE_RESTORE_SECTIONS = setOf(
    BackupSection.WORK_HISTORY,
    BackupSection.WORK_FAVOURITES,
    BackupSection.WORK_STATS,
    BackupSection.TRACKS,
    BackupSection.TRACK_LOGS,
)
private val BackupSection.requiresDeferredRestore: Boolean
    get() = this in DEFERRED_RESTORE_ORDER

private data class RestoredLocalBindingEvidence(
    val remoteMangaId: Long,
    val remoteEntityId: Long,
)

private enum class ProjectionKeyKind {
    URL,
    PUBLIC_URL,
}

private data class ProjectionKeyParts(
    val kind: ProjectionKeyKind,
    val value: String,
)

private data class RestoredProjectionResolution(
    val localMangaId: Long,
    val nextRestoredProjectionId: Long,
    val created: Boolean,
)

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: ContentSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
    private val workResolver: WorkResolver,
) {

    enum class ExportFormat(
        val sections: List<BackupSection>,
    ) {
        KOTOTORO(BackupSection.entries),
        KOTATSU(BackupRestoreFormat.KOTATSU_COMPATIBLE_SECTIONS),
    }

    enum class RestoreMode {
        MERGE,
        SNAPSHOT_REPLACE,
    }

    private fun logAuth(msg: String) = runCatching { println("[BackupAuth] $msg") }

    private val json = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
        useAlternativeNames = false
    }

    data class RestoreBackupResult(
        val result: CompositeResult,
        val legacyJarReposImported: Boolean,
        val backupIndex: BackupIndex?,
    )

    data class RestoreSemanticContext(
        val transportGeneration: Int,
        val semanticSchemaVersion: Int,
    ) {

        val isLegacySemanticSchema: Boolean
            get() = semanticSchemaVersion < BackupIndex.CURRENT_SYNC_SCHEMA_VERSION

        val isAuthoritativeWorkSchema: Boolean
            get() = transportGeneration >= BackupIndex.WRITER_GENERATION_V3 &&
                semanticSchemaVersion >= BackupIndex.CURRENT_SYNC_SCHEMA_VERSION
    }

    private fun dumpWorkProjectionSnapshots(): Flow<ContentBackup> = flow {
        val anchorIds = LinkedHashSet<Long>()
        anchorIds += database.getWorkHistoryDao().findActiveAnchorMangaIds()
        anchorIds += database.getWorkFavouritesDao().findActive()
            .mapNotNull { it.anchorMangaId }
        anchorIds += database.getWorkStatsDao().findAnchorMangaIds()
        if (anchorIds.isEmpty()) {
            Log.d(TAG, "dump projections: no work anchors")
            return@flow
        }
        var emitted = 0
        anchorIds.chunked(RESTORE_TRANSACTION_BATCH_SIZE).forEach { chunk ->
            database.getMangaDao().findWithTagsByIds(chunk)
                .forEach { projection ->
                    emitted++
                    emit(ContentBackup(projection))
                }
        }
        Log.d(TAG, "dump projections: anchors=${anchorIds.size} emitted=$emitted")
    }

    private fun dumpEntityBindingsForBackup(): Flow<EntityBindingRecord> = flow {
        val existingBindings = database.getEntityGraphDao().dumpBindings()
        val emittedKeys = LinkedHashSet<Pair<String, String>>()
        existingBindings.forEach { binding ->
            emittedKeys += binding.source to binding.externalId
            emit(binding)
        }

        val localBindingsByMangaId = existingBindings
            .asSequence()
            .filter { it.source.isLocalMangaBindingSource() }
            .mapNotNull { binding ->
                val mangaId = binding.externalId.toLongOrNull() ?: return@mapNotNull null
                mangaId to binding
            }
            .associateBy({ it.first }, { it.second })
        if (localBindingsByMangaId.isEmpty()) {
            Log.d(TAG, "dump entity bindings: existing=${existingBindings.size} syntheticProjection=0")
            return@flow
        }

        var syntheticProjectionCount = 0
        localBindingsByMangaId.keys.chunked(RESTORE_TRANSACTION_BATCH_SIZE).forEach { chunk ->
            database.getMangaDao().findEntitiesByIds(chunk).forEach { manga ->
                val localBinding = localBindingsByMangaId[manga.id] ?: return@forEach
                val projectionKey = ProjectionIdentityKeys.bindingKey(manga.url, manga.publicUrl) ?: return@forEach
                val key = manga.source to projectionKey
                if (!emittedKeys.add(key)) {
                    return@forEach
                }
                syntheticProjectionCount++
                emit(
                    EntityBindingRecord(
                        entityId = localBinding.entityId,
                        source = manga.source,
                        externalId = projectionKey,
                        confidence = 1f,
                        isPrimary = false,
                        sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
                        state = EntityBindingState.LEGACY.name,
                        createdBy = EntityBindingCreatedBy.SYNC.name,
                        updatedAt = localBinding.updatedAt,
                    ),
                )
            }
        }
        Log.d(
            TAG,
            "dump entity bindings: existing=${existingBindings.size} " +
                "syntheticProjection=$syntheticProjectionCount",
        )
    }

    private suspend fun requireBackupExportableWorkSnapshot() {
        if (settings.isWorkMigrationSyncWriteBlocked || settings.requiresWorkMigrationNormalization) {
            throw IllegalStateException(
                "Refusing backup creation: Work identity normalization is not complete.",
            )
        }
        repairActiveDanglingWorkFavouriteCategories()
        val dao = database.getEntityGraphDao()
        val missingSyncId = dao.findWorkEntityIdsMissingSyncId().firstOrNull()
        if (missingSyncId != null) {
            throw IllegalStateException(
                "Refusing backup creation: WORK entity $missingSyncId has no sync_id.",
            )
        }
        val activeFavouriteWithoutAnchor = database.getWorkFavouritesDao().findActiveWithoutAnchor(limit = 1)
            .firstOrNull()
        if (activeFavouriteWithoutAnchor != null) {
            throw IllegalStateException(
                "Refusing backup creation: active work favourite has no projection anchor " +
                    "entityId=${activeFavouriteWithoutAnchor.entityId} " +
                    "categoryId=${activeFavouriteWithoutAnchor.categoryId}.",
            )
        }
        val anchorIds = (
            database.getWorkHistoryDao().findActiveAnchorMangaIds() +
                database.getWorkFavouritesDao().findActiveAnchorMangaIds() +
                database.getWorkStatsDao().findAnchorMangaIds()
            ).filterTo(LinkedHashSet<Long>()) { it > 0L }
        if (anchorIds.isNotEmpty()) {
            val existingAnchorIds = database.getMangaDao().findEntitiesByIds(anchorIds)
                .mapTo(LinkedHashSet<Long>()) { it.id }
            val missingAnchor = anchorIds.firstOrNull { it !in existingAnchorIds }
            if (missingAnchor != null) {
                throw IllegalStateException(
                    "Refusing backup creation: work state references missing projection anchor $missingAnchor.",
                )
            }
        }
    }

    private suspend fun repairActiveDanglingWorkFavouriteCategories() {
        val workFavouritesDao = database.getWorkFavouritesDao()
        if (workFavouritesDao.countActiveDanglingCategoryRefs() == 0) {
            return
        }
        database.withTransaction {
            val targetCategoryId = database.ensureBackupFallbackCategoryId()
            val repaired = database.getWorkFavouritesDao().repairActiveDanglingCategoryRefs(targetCategoryId)
            if (repaired > 0) {
                Log.w(
                    TAG,
                    "backup creation repaired $repaired active work favourite category refs " +
                        "to categoryId=$targetCategoryId",
                )
            }
        }
    }

    private suspend fun MangaDatabase.ensureBackupFallbackCategoryId(): Long {
        getFavouriteCategoriesDao().findAll().firstOrNull()?.let { return it.categoryId.toLong() }
        val now = System.currentTimeMillis()
        return getFavouriteCategoriesDao().insert(
            FavouriteCategoryEntity(
                categoryId = 0,
                createdAt = now,
                sortKey = 0,
                title = appContext.getString(R.string.favourites),
                order = ListSortOrder.NEWEST.name,
                track = false,
                isVisibleInLibrary = true,
                deletedAt = 0L,
            ),
        )
    }

    suspend fun createBackup(
        output: ZipOutputStream,
        progress: FlowCollector<Progress>?,
        format: ExportFormat = ExportFormat.KOTOTORO,
    ) {
        if (format == ExportFormat.KOTOTORO) {
            requireBackupExportableWorkSnapshot()
        }
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, format.sections.size)
        val exportedAt = System.currentTimeMillis()
        val kotatsuSourceNames = if (format == ExportFormat.KOTATSU) {
            mangaSourcesRepository.getAllAvailableSourcesUnfiltered()
                .filterIsInstance<KotatsuParserSource>()
                .mapTo(LinkedHashSet()) { it.name }
        } else {
            emptySet()
        }
        for (section in format.sections) {
            when (section) {
                BackupSection.INDEX -> output.writeJsonArray(
                    section = BackupSection.INDEX,
                    data = flowOf(
                        when (format) {
                            ExportFormat.KOTOTORO -> BackupIndex(
                                deviceId = settings.backupDeviceId,
                                dataVersion = settings.backupWebDavDataVersion + 1,
                                exportedAt = exportedAt,
                            )
                            ExportFormat.KOTATSU -> BackupIndex.forKotatsuCompatibility(exportedAt)
                        },
                    ),
                    serializer = serializer(),
                )

                BackupSection.HISTORY -> output.writeJsonArray(
                    section = BackupSection.HISTORY,
                    data = if (format == ExportFormat.KOTATSU) {
                        dumpKotatsuHistory(kotatsuSourceNames)
                    } else {
                        emptyFlow()
                    },
                    serializer = serializer(),
                )

                BackupSection.CATEGORIES -> output.writeJsonArray(
                    section = BackupSection.CATEGORIES,
                    data = database.getFavouriteCategoriesDao().findAll().asFlow().map { CategoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.FAVOURITES,
                    data = if (format == ExportFormat.KOTATSU) {
                        dumpKotatsuFavourites(kotatsuSourceNames)
                    } else {
                        emptyFlow()
                    },
                    serializer = serializer(),
                )

                BackupSection.SETTINGS -> output.writeString(
                    section = BackupSection.SETTINGS,
                    data = dumpSettings(),
                )

                BackupSection.SETTINGS_READER_GRID -> output.writeString(
                    section = BackupSection.SETTINGS_READER_GRID,
                    data = dumpReaderGridSettings(),
                )

                BackupSection.BOOKMARKS -> output.writeJsonArray(
                    section = BackupSection.BOOKMARKS,
                    data = database.getBookmarksDao().dump().map {
                        BookmarkBackup(
                            manga = it.first,
                            entities = it.second,
                        )
                    }.filter { format != ExportFormat.KOTATSU || it.manga.source in kotatsuSourceNames },
                    serializer = serializer(),
                )

                BackupSection.SOURCES -> output.writeJsonArray(
                    section = BackupSection.SOURCES,
                    data = database.getSourcesDao().dumpEnabled()
                        .filter { format != ExportFormat.KOTATSU || it.source in kotatsuSourceNames }
                        .map { SourceBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.EXTENSION_REPOS -> {
                    val repos = buildList {
                        for (type in org.skepsun.kototoro.extensions.repo.ExternalExtensionType.entries) {
                            addAll(database.getExternalExtensionRepoDao().getByType(type))
                        }
                    }
                    output.writeJsonArray(
                        section = BackupSection.EXTENSION_REPOS,
                        data = repos.asFlow().map { ExtensionRepoBackup(it) },
                        serializer = serializer(),
                    )
                }

                BackupSection.SCROBBLING -> output.writeJsonArray(
                    section = BackupSection.SCROBBLING,
                    data = database.getScrobblingDao().dumpEnabled()
                        .filter { scrobbling ->
                            format != ExportFormat.KOTATSU ||
                                database.getMangaDao().find(scrobbling.mangaId)
                                    ?.manga
                                    ?.source
                                    ?.let { it in kotatsuSourceNames } == true
                        }
                        .map { ScrobblingBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.TRACKS -> output.writeJsonArray(
                    section = BackupSection.TRACKS,
                    data = database.getTracksDao().dump().asFlow().map { TrackBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.TRACK_LOGS -> output.writeJsonArray(
                    section = BackupSection.TRACK_LOGS,
                    data = database.getTrackLogsDao().dump().asFlow().map { TrackLogBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.PROJECTIONS -> output.writeJsonArray(
                    section = BackupSection.PROJECTIONS,
                    data = dumpWorkProjectionSnapshots(),
                    serializer = serializer(),
                )

                BackupSection.STATS -> output.writeJsonArray(
                    section = BackupSection.STATS,
                    data = if (format == ExportFormat.KOTATSU) {
                        dumpKotatsuStats(kotatsuSourceNames)
                    } else {
                        emptyFlow()
                    },
                    serializer = serializer(),
                )

                BackupSection.WORK_HISTORY -> output.writeJsonArray(
                    section = BackupSection.WORK_HISTORY,
                    data = database.getWorkHistoryDao().dump().map { WorkHistoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.WORK_FAVOURITES,
                    data = database.getWorkFavouritesDao().dumpWithActiveCategories().map { WorkFavouriteBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_STATS -> output.writeJsonArray(
                    section = BackupSection.WORK_STATS,
                    data = database.getWorkStatsDao().dumpEnabled().map { WorkStatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SAVED_FILTERS -> {
                    val sources = mangaSourcesRepository.getEnabledSources().filter { source ->
                        format != ExportFormat.KOTATSU || source.name in kotatsuSourceNames
                    }
                    val filters = sources.flatMap { source ->
                        savedFiltersRepository.getAll(source)
                    }
                    output.writeJsonArray(
                        section = BackupSection.SAVED_FILTERS,
                        data = filters.asFlow(),
                        serializer = serializer(),
                    )
                }

                BackupSection.AUTH -> output.writeString(
                    section = BackupSection.AUTH,
                    data = dumpAuth(),
                )

                BackupSection.ENTITY_GRAPH_ENTITIES -> output.writeJsonArray(
                    section = BackupSection.ENTITY_GRAPH_ENTITIES,
                    data = database.getEntityGraphDao().dumpEntities().asFlow(),
                    serializer = serializer(),
                )

                BackupSection.ENTITY_GRAPH_BINDINGS -> output.writeJsonArray(
                    section = BackupSection.ENTITY_GRAPH_BINDINGS,
                    data = dumpEntityBindingsForBackup(),
                    serializer = serializer(),
                )

                BackupSection.ENTITY_GRAPH_RELATIONS -> output.writeJsonArray(
                    section = BackupSection.ENTITY_GRAPH_RELATIONS,
                    data = database.getEntityGraphDao().dumpRelations().asFlow(),
                    serializer = serializer(),
                )

                BackupSection.ENTITY_GRAPH_PREFS -> output.writeJsonArray(
                    section = BackupSection.ENTITY_GRAPH_PREFS,
                    data = database.getEntityGraphDao().dumpPrefs().asFlow(),
                    serializer = serializer(),
                )
            }
            progress?.emit(commonProgress)
            commonProgress++
        }
        progress?.emit(commonProgress)
    }

    private fun dumpKotatsuHistory(kotatsuSourceNames: Set<String>): Flow<HistoryBackup> {
        return database.getWorkHistoryDao().dump()
            .filter { it.deletedAt == 0L }
            .mapNotNull { history ->
                database.getMangaDao().find(history.anchorMangaId)
                    ?.takeIf { it.manga.source in kotatsuSourceNames }
                    ?.let { manga ->
                        HistoryBackup(history, manga)
                    }
            }
    }

    private fun dumpKotatsuFavourites(kotatsuSourceNames: Set<String>): Flow<FavouriteBackup> {
        return database.getWorkFavouritesDao().dumpWithActiveCategories()
            .filter { it.deletedAt == 0L && it.anchorMangaId != null }
            .mapNotNull { favourite ->
                val anchorMangaId = favourite.anchorMangaId ?: return@mapNotNull null
                database.getMangaDao().find(anchorMangaId)
                    ?.takeIf { it.manga.source in kotatsuSourceNames }
                    ?.let { manga ->
                        FavouriteBackup(favourite, manga)
                    }
            }
    }

    private fun dumpKotatsuStats(kotatsuSourceNames: Set<String>): Flow<StatisticBackup> {
        return database.getWorkStatsDao().dumpEnabled().mapNotNull { statistic ->
            val manga = database.getMangaDao().find(statistic.anchorMangaId)
            if (manga?.manga?.source?.let { it in kotatsuSourceNames } == true) {
                StatisticBackup(statistic)
            } else {
                null
            }
        }
    }

    suspend fun restoreBackup(
        input: ZipInputStream,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?,
        restoreMode: RestoreMode = RestoreMode.MERGE,
    ): RestoreBackupResult {
        val restoreStartedAt = SystemClock.elapsedRealtime()
        val effectiveSections = sections.withImplicitRestoreSections()
        Log.d(TAG, "restoreBackup: start mode=$restoreMode sections=${effectiveSections.joinToString()}")
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, effectiveSections.size)
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        val archiveSections = linkedSetOf<BackupSection>()
        val restoredSections = linkedSetOf<BackupSection>()
        val entityIdMapping = LinkedHashMap<Long, Long>()
        val legacyCategoryIdMapping = LinkedHashMap<Long, Long>()
        val deferredEntries = LinkedHashMap<BackupSection, ByteArray>()
        val remoteLocalBindings = ArrayList<RestoredLocalBindingEvidence>()
        val projectionAnchorMapping = LinkedHashMap<Long, Long>()
        var projectionAnchorsReconciled = false
        var backupIndex: BackupIndex? = null
        var restoreContext = resolveRestoreSemanticContext(null)
        if (restoreMode == RestoreMode.SNAPSHOT_REPLACE) {
            val clearStartedAt = SystemClock.elapsedRealtime()
            clearRestoreTargets(effectiveSections)
            Log.d(TAG, "restoreBackup: clearRestoreTargets elapsedMs=${SystemClock.elapsedRealtime() - clearStartedAt}")
        }
        database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        try {
        suspend fun restoreSection(section: BackupSection?, sectionInput: InputStream): CompositeResult {
            val sectionStartedAt = SystemClock.elapsedRealtime()
            val sectionResult = when (section) {
                BackupSection.INDEX -> {
                    backupIndex = sectionInput.readBackupIndex()
                    restoreContext = resolveRestoreSemanticContext(backupIndex)
                    CompositeResult.EMPTY
                }
                BackupSection.HISTORY -> sectionInput.readJsonArray<HistoryBackup>(serializer()).restoreToDb("HISTORY") {
                    upsertContent(it.manga, restoreContext)
                    if (restoreContext.isLegacySemanticSchema) {
                        upsertWorkHistoryFromLegacy(it.toEntity(), restoreMode)
                    }
                }

                BackupSection.CATEGORIES -> sectionInput.readJsonArray<CategoryBackup>(serializer()).restoreToDb("CATEGORIES") {
                    if (restoreContext.isLegacySemanticSchema && restoreMode == RestoreMode.MERGE) {
                        restoreLegacyCategory(it, legacyCategoryIdMapping)
                    } else {
                        getFavouriteCategoriesDao().upsert(it.toEntity())
                    }
                }

                BackupSection.FAVOURITES -> sectionInput.readJsonArray<FavouriteBackup>(serializer()).restoreToDb("FAVOURITES") {
                    upsertContent(it.manga, restoreContext)
                    if (restoreContext.isLegacySemanticSchema) {
                        val favourite = it.toEntity().let { entity ->
                            if (restoreMode == RestoreMode.MERGE) {
                                val localCategoryId = legacyCategoryIdMapping[entity.categoryId]
                                    ?: throw IllegalStateException(
                                        "Legacy favourite category ${entity.categoryId} was not restored before favourites.",
                                    )
                                entity.copy(categoryId = localCategoryId)
                            } else {
                                entity
                            }
                        }
                        upsertWorkFavouriteFromLegacy(favourite, restoreMode)
                    }
                }

                BackupSection.SETTINGS -> sectionInput.readMap().let { map ->
                    val isKototoro = backupIndex?.appId == org.skepsun.kototoro.BuildConfig.APPLICATION_ID
                    val finalMap = if (isKototoro) {
                        map
                    } else {
                        map.toMutableMap().apply {
                            this[AppSettings.KEY_DOWNLOADS_MAX_ACTIVE_SERIES] = 5
                        }
                    }
                    settings.upsertAll(finalMap)
                    CompositeResult.success()
                }

                BackupSection.SETTINGS_READER_GRID -> sectionInput.readMap().let {
                    tapGridSettings.upsertAll(it)
                    CompositeResult.success()
                }

                BackupSection.BOOKMARKS -> sectionInput.readJsonArray<BookmarkBackup>(serializer()).restoreToDb("BOOKMARKS") {
                    // Bookmarks remain projection-anchored content data. Entity/work state
                    // comes from graph/work sections and is not embedded here.
                    upsertContent(it.manga, restoreContext)
                    getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                }

                BackupSection.SOURCES -> sectionInput.readJsonArray<SourceBackup>(serializer()).restoreToDb("SOURCES") {
                    getSourcesDao().upsert(it.toEntity())
                }

                BackupSection.EXTENSION_REPOS -> sectionInput.readJsonArray<ExtensionRepoBackup>(serializer()).restoreToDb("EXTENSION_REPOS") {
                    getExternalExtensionRepoDao().upsert(it.toEntity())
                }

                BackupSection.SCROBBLING -> sectionInput.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb("SCROBBLING") {
                    upsertScrobbling(it.toEntity(), workResolver)
                }

                BackupSection.TRACKS -> sectionInput.readJsonArray<TrackBackup>(serializer()).restoreToDb("TRACKS") {
                    mergeTrack(it.toEntity(entityIdMapping))
                }

                BackupSection.TRACK_LOGS -> sectionInput.readJsonArray<TrackLogBackup>(serializer()).restoreToDb("TRACK_LOGS") {
                    mergeTrackLog(it.toEntity(entityIdMapping))
                }

                BackupSection.STATS -> sectionInput.readJsonArray<StatisticBackup>(serializer()).restoreToDb("STATS") {
                    if (restoreContext.isLegacySemanticSchema) {
                        upsertWorkStatsFromLegacy(it.toEntity())
                    }
                }

                BackupSection.PROJECTIONS -> sectionInput.readJsonArray<ContentBackup>(serializer()).restoreToDb("PROJECTIONS") {
                    upsertContent(it, restoreContext)
                }

                BackupSection.WORK_HISTORY -> sectionInput.readJsonArray<WorkHistoryBackup>(serializer()).restoreToDb("WORK_HISTORY", failFast = true) {
                    restoreWorkHistoryFromBackup(restoreEntity(it, entityIdMapping, projectionAnchorMapping), restoreMode)
                }

                BackupSection.WORK_FAVOURITES -> sectionInput.readJsonArray<WorkFavouriteBackup>(serializer()).restoreToDb("WORK_FAVOURITES", failFast = true) {
                    restoreWorkFavourite(restoreEntity(it, entityIdMapping, projectionAnchorMapping))
                }

                BackupSection.WORK_STATS -> sectionInput.readJsonArray<WorkStatisticBackup>(serializer()).restoreToDb("WORK_STATS", failFast = true) {
                    restoreWorkStats(restoreEntity(it, entityIdMapping, projectionAnchorMapping))
                }

                BackupSection.SAVED_FILTERS -> sectionInput.readJsonArray<PersistableFilter>(serializer())
                    .restoreWithoutTransaction("SAVED_FILTERS") {
                        savedFiltersRepository.save(it)
                    }

                BackupSection.AUTH -> sectionInput.readMap().let {
                    restoreAuth(it)
                    CompositeResult.success()
                }

                BackupSection.ENTITY_GRAPH_ENTITIES -> sectionInput.readJsonArray<EntityRecord>(serializer()).restoreToDb("ENTITY_GRAPH_ENTITIES") {
                    restoreEntityRecord(it, entityIdMapping)
                }

                BackupSection.ENTITY_GRAPH_BINDINGS -> sectionInput.readJsonArray<EntityBindingRecord>(serializer()).restoreToDb("ENTITY_GRAPH_BINDINGS") {
                    restoreEntityBinding(it, entityIdMapping, restoreContext, remoteLocalBindings)
                }

                BackupSection.ENTITY_GRAPH_RELATIONS -> sectionInput.readJsonArray<RelationRecord>(serializer()).restoreToDb("ENTITY_GRAPH_RELATIONS") {
                    restoreEntityRelation(it, entityIdMapping)
                }

                BackupSection.ENTITY_GRAPH_PREFS -> sectionInput.readJsonArray<EntityPrefsRecord>(serializer()).restoreToDb("ENTITY_GRAPH_PREFS") {
                    restoreEntityPrefs(it, entityIdMapping, restoreContext)
                }

                null -> CompositeResult.EMPTY // skip unknown entries
            }
            if (section != null) {
                restoredSections.add(section)
                Log.d(TAG, "restoreBackup: section=$section elapsedMs=${SystemClock.elapsedRealtime() - sectionStartedAt}")
            }
            return sectionResult
        }

        while (entry != null) {
            val section = BackupSection.of(entry)
            if (section != null) {
                archiveSections.add(section)
            }
            if (section in effectiveSections) {
                if (section != null && section.requiresDeferredRestore) {
                    val bytes = input.readBytes()
                    if (deferredEntries.put(section, bytes) != null) {
                        Log.w(TAG, "restoreBackup: duplicate deferred section=$section; using last entry")
                    }
                    Log.d(TAG, "restoreBackup: defer section=$section bytes=${bytes.size}")
                } else {
                    result += restoreSection(section, input)
                    progress?.emit(commonProgress)
                    commonProgress++
                }
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        for (section in DEFERRED_RESTORE_ORDER) {
            val bytes = deferredEntries[section] ?: continue
            if (!projectionAnchorsReconciled && section in WORK_STATE_RESTORE_SECTIONS) {
                projectionAnchorMapping += database.reconcileRestoredProjectionAnchors(remoteLocalBindings, entityIdMapping)
                projectionAnchorsReconciled = true
            }
            result += restoreSection(section, ByteArrayInputStream(bytes))
            progress?.emit(commonProgress)
            commonProgress++
        }
        val legacyRepoStartedAt = SystemClock.elapsedRealtime()
        Log.d(
            TAG,
            "restoreBackup: archiveSections=${archiveSections.joinToString()} " +
                "restoredSections=${restoredSections.joinToString()} " +
                "deferredSections=${deferredEntries.keys.joinToString()} " +
                "entityIdMappingSize=${entityIdMapping.size} " +
                "entityIdRemaps=${entityIdMapping.count { (oldId, newId) -> oldId != newId }}",
        )
        logMissingAuthoritativeWorkSections(effectiveSections, archiveSections, restoredSections)
        val legacyJarReposImported = restoreLegacyJarRepositoriesIfNeeded(effectiveSections, archiveSections, restoredSections)
        Log.d(TAG, "restoreBackup: restoreLegacyJarRepositoriesIfNeeded elapsedMs=${SystemClock.elapsedRealtime() - legacyRepoStartedAt}")
        val contentTypeBackfillStartedAt = SystemClock.elapsedRealtime()
        mangaSourcesRepository.getAllAvailableSourcesUnfiltered()
        Log.d(
            TAG,
            "restoreBackup: projectionContentTypeBackfill elapsedMs=" +
                (SystemClock.elapsedRealtime() - contentTypeBackfillStartedAt),
        )
        val normalizeStartedAt = SystemClock.elapsedRealtime()
        normalizeRestoredWorkState(
            requestedSections = effectiveSections,
            entityIdMapping = entityIdMapping,
        )
        Log.d(TAG, "restoreBackup: normalizeRestoredWorkState elapsedMs=${SystemClock.elapsedRealtime() - normalizeStartedAt}")
        val trimStartedAt = SystemClock.elapsedRealtime()
        trimRestoredTrackLogs(effectiveSections)
        Log.d(TAG, "restoreBackup: trimRestoredTrackLogs elapsedMs=${SystemClock.elapsedRealtime() - trimStartedAt}")
        progress?.emit(commonProgress)
        Log.d(TAG, "restoreBackup: complete totalMs=${SystemClock.elapsedRealtime() - restoreStartedAt}")
        return RestoreBackupResult(
            result = result,
            legacyJarReposImported = legacyJarReposImported,
            backupIndex = backupIndex,
        )
        } finally {
            database.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = ON")
        }
    }

    fun resolveRestoreSemanticContext(backupIndex: BackupIndex?): RestoreSemanticContext {
        return RestoreSemanticContext(
            transportGeneration = backupIndex?.transportGeneration ?: BackupIndex.WRITER_GENERATION_V1,
            semanticSchemaVersion = backupIndex?.semanticSchemaVersion ?: 1,
        )
    }

    private suspend fun normalizeRestoredWorkState(
        requestedSections: Set<BackupSection>,
        entityIdMapping: MutableMap<Long, Long>,
    ) {
        database.withTransaction {
            database.logRestoredWorkState("beforeNormalize", requestedSections, entityIdMapping)
            if (entityIdMapping.isEmpty() && (
                BackupSection.WORK_HISTORY in requestedSections ||
                BackupSection.WORK_FAVOURITES in requestedSections ||
                BackupSection.WORK_STATS in requestedSections
            )) {
                Log.w(TAG, "restore work normalize: entityIdMapping is empty for requested work sections")
            }
            if (BackupSection.SCROBBLING in requestedSections) {
                database.normalizeRestoredScrobblingState()
            }
            database.logRestoredWorkState("afterNormalize", requestedSections, entityIdMapping)
        }
    }

    private suspend fun MangaDatabase.logRestoredWorkState(
        stage: String,
        requestedSections: Set<BackupSection>,
        entityIdMapping: Map<Long, Long>,
    ) {
        val shouldLogHistory = BackupSection.WORK_HISTORY in requestedSections
        val shouldLogFavourites = BackupSection.WORK_FAVOURITES in requestedSections
        if (!shouldLogHistory && !shouldLogFavourites) {
            return
        }
        val historyActive = if (shouldLogHistory) getWorkHistoryDao().countActive() else -1
        val historyDangling = if (shouldLogHistory) getWorkHistoryDao().countDanglingEntityRefs() else -1
        val favouritesActive = if (shouldLogFavourites) getWorkFavouritesDao().countActive() else -1
        val favouriteWorks = if (shouldLogFavourites) getWorkFavouritesDao().countActiveWorks() else -1
        val favouritesDangling = if (shouldLogFavourites) getWorkFavouritesDao().countDanglingEntityRefs() else -1
        Log.d(
            TAG,
            "restore work state[$stage]: mappingSize=${entityIdMapping.size} " +
                "remaps=${entityIdMapping.count { (oldId, newId) -> oldId != newId }} " +
                "historyActive=$historyActive historyDangling=$historyDangling " +
                "favouritesActive=$favouritesActive favouriteWorks=$favouriteWorks " +
                "favouritesDangling=$favouritesDangling",
        )
    }

    private fun logMissingAuthoritativeWorkSections(
        requestedSections: Set<BackupSection>,
        archiveSections: Set<BackupSection>,
        restoredSections: Set<BackupSection>,
    ) {
        listOf(BackupSection.WORK_HISTORY, BackupSection.WORK_FAVOURITES, BackupSection.WORK_STATS)
            .filter { it in requestedSections && it !in archiveSections }
            .forEach { section ->
                Log.w(
                    TAG,
                    "restoreBackup: requested $section but archive does not contain ${section.entryName}; " +
                        "legacy sections restored=${BackupSection.HISTORY in restoredSections || BackupSection.FAVOURITES in restoredSections}",
                )
            }
    }

    private suspend fun clearRestoreTargets(sections: Set<BackupSection>) {
        val startedAt = SystemClock.elapsedRealtime()
        val beforeWorkHistoryActive = if (BackupSection.WORK_HISTORY in sections) {
            database.getWorkHistoryDao().countActive()
        } else {
            -1
        }
        val beforeWorkFavouritesActive = if (BackupSection.WORK_FAVOURITES in sections) {
            database.getWorkFavouritesDao().countActive()
        } else {
            -1
        }
        val beforeWorkFavouriteWorks = if (BackupSection.WORK_FAVOURITES in sections) {
            database.getWorkFavouritesDao().countActiveWorks()
        } else {
            -1
        }
        Log.d(
            TAG,
            "clearRestoreTargets: before workHistoryActive=$beforeWorkHistoryActive " +
                "workFavouritesActive=$beforeWorkFavouritesActive workFavouriteWorks=$beforeWorkFavouriteWorks",
        )
        database.withTransaction {
            if (BackupSection.HISTORY in sections) {
                if (BackupSection.WORK_HISTORY in sections) {
                    database.getWorkHistoryDao().clear()
                }
                database.getHistoryDao().clear()
            }
            if (BackupSection.FAVOURITES in sections) {
                if (BackupSection.WORK_FAVOURITES in sections) {
                    database.getWorkFavouritesDao().deleteAll()
                }
                database.getFavouritesDao().clear()
                database.getFavouriteCategoriesDao().deleteAll()
            }
            if (BackupSection.BOOKMARKS in sections) {
                database.getBookmarksDao().deleteAll()
            }
            if (BackupSection.SCROBBLING in sections) {
                database.getScrobblingDao().deleteAll()
            }
            if (BackupSection.TRACKS in sections) {
                database.getTracksDao().clear()
            }
            if (BackupSection.TRACK_LOGS in sections) {
                database.getTrackLogsDao().clear()
            }
            if (BackupSection.STATS in sections) {
                if (BackupSection.WORK_STATS in sections) {
                    database.getWorkStatsDao().clear()
                }
                database.getStatsDao().clear()
            }
            if (BackupSection.EXTENSION_REPOS in sections) {
                database.getExternalExtensionRepoDao().deleteAll()
            }
            if (BackupSection.ENTITY_GRAPH_ENTITIES in sections ||
                BackupSection.ENTITY_GRAPH_BINDINGS in sections ||
                BackupSection.ENTITY_GRAPH_RELATIONS in sections ||
                BackupSection.ENTITY_GRAPH_PREFS in sections
            ) {
                database.getEntityGraphDao().deleteAllBindings()
                database.getEntityGraphDao().deleteAllRelations()
                database.getEntityGraphDao().deleteAllPrefs()
                database.getEntityGraphDao().deleteAllEntities()
            }
        }
        val afterWorkHistoryActive = if (BackupSection.WORK_HISTORY in sections) {
            database.getWorkHistoryDao().countActive()
        } else {
            -1
        }
        val afterWorkFavouritesActive = if (BackupSection.WORK_FAVOURITES in sections) {
            database.getWorkFavouritesDao().countActive()
        } else {
            -1
        }
        val afterWorkFavouriteWorks = if (BackupSection.WORK_FAVOURITES in sections) {
            database.getWorkFavouritesDao().countActiveWorks()
        } else {
            -1
        }
        Log.d(
            TAG,
            "clearRestoreTargets: sections=${sections.joinToString()} " +
                "after workHistoryActive=$afterWorkHistoryActive " +
                "workFavouritesActive=$afterWorkFavouritesActive workFavouriteWorks=$afterWorkFavouriteWorks " +
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
        )
    }

    private suspend fun trimRestoredTrackLogs(sections: Set<BackupSection>) {
        if (BackupSection.TRACKS !in sections && BackupSection.TRACK_LOGS !in sections) {
            return
        }
        database.normalizeTrackFeedState()
    }

    private fun Set<BackupSection>.withImplicitRestoreSections(): Set<BackupSection> {
        val expanded = LinkedHashSet(this)
        expanded += BackupSection.INDEX
        if (BackupSection.HISTORY in this) {
            expanded += BackupSection.WORK_HISTORY
        }
        if (BackupSection.FAVOURITES in this) {
            expanded += BackupSection.WORK_FAVOURITES
        }
        if (BackupSection.STATS in this) {
            expanded += BackupSection.WORK_STATS
        }
        if (
            BackupSection.HISTORY in expanded ||
            BackupSection.FAVOURITES in expanded ||
            BackupSection.BOOKMARKS in expanded ||
            BackupSection.TRACKS in expanded ||
            BackupSection.TRACK_LOGS in expanded ||
            BackupSection.WORK_HISTORY in expanded ||
            BackupSection.WORK_FAVOURITES in expanded ||
            BackupSection.WORK_STATS in expanded
        ) {
            expanded += BackupSection.PROJECTIONS
            expanded += BackupSection.ENTITY_GRAPH_ENTITIES
            expanded += BackupSection.ENTITY_GRAPH_BINDINGS
            expanded += BackupSection.ENTITY_GRAPH_RELATIONS
            expanded += BackupSection.ENTITY_GRAPH_PREFS
        }
        return expanded
    }

    private suspend fun restoreLegacyJarRepositoriesIfNeeded(
        requestedSections: Set<BackupSection>,
        archiveSections: Set<BackupSection>,
        restoredSections: Set<BackupSection>,
    ): Boolean {
        val repoDao = database.getExternalExtensionRepoDao()
        if (!LegacyJarRepoCompat.shouldImport(
                requestedSections = requestedSections,
                archiveSections = archiveSections,
                restoredSections = restoredSections,
                hasExistingJarRepos = repoDao.getByType(ExternalExtensionType.JAR).isNotEmpty(),
            )
        ) {
            return false
        }

        val legacyJarRepos = LegacyJarRepoCompat.buildEntities(now = System.currentTimeMillis())

        legacyJarRepos.forEach { repoDao.upsert(it) }
        return legacyJarRepos.isNotEmpty()
    }

    private suspend fun <T> ZipOutputStream.writeJsonArray(
        section: BackupSection,
        data: Flow<T>,
        serializer: SerializationStrategy<T>,
    ) {
        data.onStart {
            putNextEntry(ZipEntry(section.entryName))
            write("[")
        }.onCompletion { error ->
            if (error == null) {
                write("]")
            }
            closeEntry()
            flush()
        }.collectIndexed { index, value ->
            if (index > 0) {
                write(",")
            }
            json.encodeToStream(serializer, value, this)
        }
    }

    private fun <T> InputStream.readJsonArray(
        serializer: DeserializationStrategy<T>,
    ): Sequence<T> = json.decodeToSequence(this, serializer, DecodeSequenceMode.ARRAY_WRAPPED)

    private fun InputStream.readMap(): Map<String, Any?> {
        val jo = JSONArray(readString()).getJSONObject(0)
        val map = ArrayMap<String, Any?>(jo.length())
        val keys = jo.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = jo.get(key)
        }
        return map
    }

    private fun InputStream.readBackupIndex(): BackupIndex? {
        return readJsonArray(BackupIndex.serializer()).firstOrNull()
    }

    private fun ZipOutputStream.writeString(
        section: BackupSection,
        data: String,
    ) {
        putNextEntry(ZipEntry(section.entryName))
        try {
            write("[")
            write(data)
            write("]")
        } finally {
            closeEntry()
            flush()
        }
    }

    private fun OutputStream.write(str: String) = write(str.toByteArray())

    private fun InputStream.readString(): String = readBytes().decodeToString()

    private suspend fun MangaDatabase.restoreEntity(
        backup: WorkHistoryBackup,
        entityIdMapping: Map<Long, Long>,
        projectionAnchorMapping: Map<Long, Long>,
    ): WorkHistoryEntity {
        val localEntityId = entityIdMapping[backup.entityId] ?: backup.entityId
        return backup.toEntity().copy(
            entityId = localEntityId,
            anchorMangaId = resolveRestoredWorkAnchor(
                remoteAnchorMangaId = backup.anchorMangaId,
                localEntityId = localEntityId,
                projectionAnchorMapping = projectionAnchorMapping,
            ),
        )
    }

    private suspend fun MangaDatabase.restoreEntity(
        backup: WorkFavouriteBackup,
        entityIdMapping: Map<Long, Long>,
        projectionAnchorMapping: Map<Long, Long>,
    ): WorkFavouriteEntity {
        val localEntityId = entityIdMapping[backup.entityId] ?: backup.entityId
        return backup.toEntity().copy(
            entityId = localEntityId,
            anchorMangaId = backup.anchorMangaId?.let {
                resolveRestoredWorkAnchorOrNull(
                    remoteAnchorMangaId = it,
                    localEntityId = localEntityId,
                    projectionAnchorMapping = projectionAnchorMapping,
                    createPlaceholderIfMissing = backup.deletedAt == 0L,
                )
            },
        )
    }

    private suspend fun MangaDatabase.restoreEntity(
        backup: WorkStatisticBackup,
        entityIdMapping: Map<Long, Long>,
        projectionAnchorMapping: Map<Long, Long>,
    ): WorkStatsEntity {
        val localEntityId = entityIdMapping[backup.entityId] ?: backup.entityId
        return backup.toEntity().copy(
            entityId = localEntityId,
            anchorMangaId = resolveRestoredWorkAnchor(
                remoteAnchorMangaId = backup.anchorMangaId,
                localEntityId = localEntityId,
                projectionAnchorMapping = projectionAnchorMapping,
            ),
        )
    }

    private suspend fun MangaDatabase.resolveRestoredWorkAnchor(
        remoteAnchorMangaId: Long,
        localEntityId: Long,
        projectionAnchorMapping: Map<Long, Long>,
        createPlaceholderIfMissing: Boolean = false,
    ): Long {
        return resolveRestoredWorkAnchorOrNull(
            remoteAnchorMangaId = remoteAnchorMangaId,
            localEntityId = localEntityId,
            projectionAnchorMapping = projectionAnchorMapping,
            createPlaceholderIfMissing = createPlaceholderIfMissing,
        ) ?: remoteAnchorMangaId
    }

    private suspend fun MangaDatabase.resolveRestoredWorkAnchorOrNull(
        remoteAnchorMangaId: Long,
        localEntityId: Long,
        projectionAnchorMapping: Map<Long, Long>,
        createPlaceholderIfMissing: Boolean = false,
    ): Long? {
        projectionAnchorMapping[remoteAnchorMangaId]?.let { return it }
        resolveExistingLocalProjectionForEntity(localEntityId)?.let { return it }
        return if (createPlaceholderIfMissing) {
            createRestorePlaceholderProjection(localEntityId, remoteAnchorMangaId)
        } else {
            null
        }
    }

    private fun dumpSettings(): String {
        val map = settings.getAllValues().toMutableMap()
        map.remove(AppSettings.KEY_APP_PASSWORD)
        map.remove(AppSettings.KEY_PROXY_PASSWORD)
        map.remove(AppSettings.KEY_PROXY_LOGIN)
        map.remove(AppSettings.KEY_INCOGNITO_MODE)
        return JSONObject(map).toString()
    }

    private fun dumpReaderGridSettings(): String {
        return JSONObject(tapGridSettings.getAllValues()).toString()
    }

    private fun dumpAuth(): String {
        val root = JSONObject()
        // 1) SharedPreferences cookies（PreferencesCookieJar）
        val prefs = appContext.getSharedPreferences("cookies", Context.MODE_PRIVATE)
        val prefsObj = JSONObject(prefs.all as Map<*, *>)
        root.put("cookies_prefs", prefsObj)

        // 2) WebView/AndroidCookieJar cookie DB（app_webview/Cookies*）
        runCatching { CookieManager.getInstance().flush() }
        val webviewDir = File(appContext.dataDir, "app_webview")
        val webviewMap = JSONObject()
        val keepPrefixes = arrayOf("Cookies", "Cookies-", "Cookies.", "Web Data", "Local Storage")
        webviewDir
            .takeIf { it.exists() }
            ?.walkTopDown()
            ?.filter { file ->
                file.isFile && keepPrefixes.any { prefix -> file.name.startsWith(prefix) }
            }
            ?.forEach { f ->
                val rel = f.relativeTo(webviewDir).path
                runCatching {
                    val b64 = Base64.getEncoder().encodeToString(f.readBytes())
                    webviewMap.put(rel, b64)
                }
            }
        root.put("webview_cookies", webviewMap)
        logAuth(
            "dump prefs=${prefsObj.length()} entries, webview_files=${webviewMap.length()}," +
                " webview_names=${webviewMap.keys().asSequence().joinToString()}"
        )
        return root.toString()
    }

    private fun restoreAuth(map: Map<String, Any?>) {
        // 1) restore prefs cookies
        (map["cookies_prefs"] as? JSONObject)?.let { jo ->
            val prefs = appContext.getSharedPreferences("cookies", Context.MODE_PRIVATE)
            val editor = prefs.edit()
            editor.clear()
            val keys = jo.keys()
            var count = 0
            while (keys.hasNext()) {
                val k = keys.next()
                when (val v = jo.get(k)) {
                    is String -> editor.putString(k, v)
                    is Boolean -> editor.putBoolean(k, v)
                    is Int -> editor.putInt(k, v)
                    is Long -> editor.putLong(k, v)
                    is Double -> editor.putFloat(k, v.toFloat())
                }
                count++
            }
            editor.apply()
            logAuth("restore prefs cookies count=$count")
        }

        // 2) restore WebView cookie DB files
        (map["webview_cookies"] as? JSONObject)?.let { jo ->
            val webviewDir = File(appContext.dataDir, "app_webview")
            webviewDir.mkdirs()
            val keys = jo.keys()
            var restored = 0
            while (keys.hasNext()) {
                val relPath = keys.next()
                val b64 = jo.optString(relPath).takeIf { it.isNotEmpty() } ?: continue
                runCatching {
                    val data = Base64.getDecoder().decode(b64)
                    val outFile = File(webviewDir, relPath)
                    outFile.parentFile?.mkdirs()
                    outFile.writeBytes(data)
                    restored++
                }
            }
            logAuth("restore webview cookie files count=$restored")
            runCatching { CookieManager.getInstance().flush() }
        }
    }

    private suspend fun MangaDatabase.upsertContent(
        manga: ContentBackup,
        restoreContext: RestoreSemanticContext,
    ) {
        val tags = manga.tags.map { it.toEntity() }
        getTagsDao().upsert(tags)
        val entity = manga.toEntity()
        getMangaDao().upsert(entity, tags)
        val identity = workResolver.ensureForProjection(
            content = entity.toContent(tags = emptySet(), chapters = null),
            provenance = WorkIdentityProvenance.RESTORE,
        )
        Log.d(TAG, "restore content: mangaId=${manga.id} title=${manga.title} entityId=${identity.entityId}")
        if (restoreContext.isLegacySemanticSchema && manga.hasLegacyPrefsPayload()) {
            // Legacy embedded prefs are import hints only.
            // Do not rebuild work-owned state shadows in projection prefs during restore.
            // Current authoritative owner state must come from ENTITY_GRAPH_PREFS / WORK_* sections
            // or subsequent normalization, not from embedded content payloads.
        }
    }

    private suspend fun MangaDatabase.resolveWorkEntityIdForLocalManga(mangaId: Long): Long? {
        return getEntityGraphDao().findActiveBinding("local_manga", mangaId.toString())?.entityId
            ?: getEntityGraphDao().findActiveBinding("0", mangaId.toString())?.entityId
    }

    private suspend fun MangaDatabase.upsertWorkHistoryFromLegacy(
        history: org.skepsun.kototoro.history.data.HistoryEntity,
        restoreMode: RestoreMode = RestoreMode.MERGE,
    ): Boolean {
        val entityId = resolveWorkEntityIdForLocalManga(history.mangaId)
        if (entityId == null) {
            Log.w(TAG, "restore legacy history skipped: no work entity for mangaId=${history.mangaId}")
            return false
        }
        val anchorMangaId = resolveExistingLocalProjectionForEntity(entityId) ?: history.mangaId
        restoreWorkHistory(
            entity = WorkHistoryEntity(
                entityId = entityId,
                anchorMangaId = anchorMangaId,
                createdAt = history.createdAt,
                updatedAt = history.updatedAt,
                chapterId = history.chapterId,
                page = history.page,
                scroll = history.scroll,
                percent = history.percent,
                deletedAt = history.deletedAt,
                chaptersCount = history.chaptersCount,
                parentChapterId = history.parentChapterId,
            ),
            restoreMode = restoreMode,
        )
        Log.d(
            TAG,
            "restore legacy history converted: mangaId=${history.mangaId} entityId=$entityId anchorMangaId=$anchorMangaId",
        )
        return true
    }

    private suspend fun MangaDatabase.restoreWorkHistory(
        entity: WorkHistoryEntity,
        restoreMode: RestoreMode,
    ) {
        if (restoreMode == RestoreMode.MERGE) {
            val local = getWorkHistoryDao().find(entity.entityId)
            if (local != null && entity.updatedAt < local.updatedAt) {
                return
            }
        }
        getWorkHistoryDao().upsert(entity)
    }

    private suspend fun MangaDatabase.restoreWorkHistoryFromBackup(
        entity: WorkHistoryEntity,
        restoreMode: RestoreMode,
    ) {
        val localEntityId = resolveWorkEntityIdForLocalManga(entity.anchorMangaId)
            ?: throw IllegalStateException(
                "Refusing restore: work history anchor ${entity.anchorMangaId} has no local WORK identity. " +
                    describeLocalMangaAnchor(entity.anchorMangaId),
            )
        restoreWorkHistory(
            entity = entity.copy(entityId = localEntityId),
            restoreMode = restoreMode,
        )
    }

    private suspend fun MangaDatabase.restoreWorkFavourite(entity: WorkFavouriteEntity) {
        val anchorMangaId = entity.anchorMangaId ?: run {
            if (entity.deletedAt != 0L) {
                getWorkFavouritesDao().upsert(entity)
                return
            }
            throw IllegalStateException(
                "Refusing restore: work favourite entity ${entity.entityId} has no projection anchor.",
            )
        }
        val localEntityId = resolveWorkEntityIdForLocalManga(anchorMangaId)
        if (localEntityId == null) {
            if (entity.deletedAt != 0L) {
                getWorkFavouritesDao().upsert(entity.copy(anchorMangaId = null))
                return
            }
            throw IllegalStateException(
                "Refusing restore: work favourite anchor $anchorMangaId has no local WORK identity. " +
                    describeLocalMangaAnchor(anchorMangaId),
            )
        }
        getWorkFavouritesDao().upsert(entity.copy(entityId = localEntityId))
    }

    private suspend fun MangaDatabase.restoreWorkStats(entity: WorkStatsEntity) {
        val localEntityId = resolveWorkEntityIdForLocalManga(entity.anchorMangaId)
            ?: throw IllegalStateException(
                "Refusing restore: work stats anchor ${entity.anchorMangaId} has no local WORK identity. " +
                    describeLocalMangaAnchor(entity.anchorMangaId),
            )
        getWorkStatsDao().upsert(entity.copy(entityId = localEntityId))
    }

    private suspend fun MangaDatabase.describeLocalMangaAnchor(mangaId: Long): String {
        val dao = getEntityGraphDao()
        val local = dao.findBinding("local_manga", mangaId.toString())
        val legacy = dao.findBinding("0", mangaId.toString())
        return "mangaExists=${mangaId in getMangaDao()} " +
            "local_manga=${local?.let { "entityId=${it.entityId},state=${it.state}" } ?: "missing"} " +
            "legacy=${legacy?.let { "entityId=${it.entityId},state=${it.state}" } ?: "missing"}"
    }

    private suspend fun MangaDatabase.upsertWorkFavouriteFromLegacy(
        favourite: org.skepsun.kototoro.favourites.data.FavouriteEntity,
        restoreMode: RestoreMode,
    ): Boolean {
        val entityId = resolveWorkEntityIdForLocalManga(favourite.mangaId)
        if (entityId == null) {
            Log.w(
                TAG,
                "restore legacy favourite skipped: no work entity for mangaId=${favourite.mangaId} categoryId=${favourite.categoryId}",
            )
            return false
        }
        val anchorMangaId = resolveExistingLocalProjectionForEntity(entityId) ?: favourite.mangaId
        val candidate = WorkFavouriteEntity(
            entityId = entityId,
            categoryId = favourite.categoryId,
            anchorMangaId = anchorMangaId,
            sortKey = favourite.sortKey,
            isPinned = favourite.isPinned,
            createdAt = favourite.createdAt,
            deletedAt = favourite.deletedAt,
            updatedAt = maxOf(favourite.updatedAt, favourite.createdAt),
        )
        val local = if (restoreMode == RestoreMode.MERGE) {
            getWorkFavouritesDao().find(entityId, favourite.categoryId)
        } else {
            null
        }
        getWorkFavouritesDao().upsert(
            local?.let { mergeRestoredWorkFavourites(it, candidate) } ?: candidate,
        )
        Log.d(
            TAG,
            "restore legacy favourite converted: mangaId=${favourite.mangaId} entityId=$entityId " +
                "anchorMangaId=$anchorMangaId categoryId=${favourite.categoryId}",
        )
        return true
    }

    private suspend fun MangaDatabase.restoreLegacyCategory(
        backup: CategoryBackup,
        categoryIdMapping: MutableMap<Long, Long>,
    ) {
        val dao = getFavouriteCategoriesDao()
        val candidate = backup.toEntity()
        val sameTitle = dao.findAll().firstOrNull { it.title == candidate.title }
        val localCategoryId = when {
            sameTitle != null -> sameTitle.categoryId.toLong()
            candidate.categoryId > 0 && dao.findIncludingDeleted(candidate.categoryId.toLong()) == null -> {
                dao.upsert(candidate)
                candidate.categoryId.toLong()
            }
            else -> dao.insert(
                candidate.copy(
                    categoryId = 0,
                    sortKey = dao.getNextSortKey(),
                ),
            )
        }
        categoryIdMapping[candidate.categoryId.toLong()] = localCategoryId
    }

    private suspend fun MangaDatabase.upsertWorkStatsFromLegacy(
        stats: org.skepsun.kototoro.stats.data.StatsEntity,
    ) {
        val entityId = resolveWorkEntityIdForLocalManga(stats.mangaId) ?: return
        val anchorMangaId = resolveExistingLocalProjectionForEntity(entityId) ?: stats.mangaId
        getWorkStatsDao().upsert(
            WorkStatsEntity(
                entityId = entityId,
                anchorMangaId = anchorMangaId,
                startedAt = stats.startedAt,
                duration = stats.duration,
                pages = stats.pages,
            ),
        )
    }

    private suspend fun MangaDatabase.normalizeRestoredScrobblingState() {
        val scrobblingDao = getScrobblingDao()
        val all = scrobblingDao.findAllByScrobblerEntries()
        if (all.isEmpty()) {
            return
        }
        val normalized = LinkedHashMap<ScrobblingRestoreKey, ScrobblingEntity>(all.size)
        all.forEach { entity ->
            val entityId = entity.entityId ?: resolveWorkEntityIdForLocalManga(entity.mangaId)
            val anchorMangaId = resolvePreferredScrobblingAnchor(entity.mangaId) ?: entity.mangaId
            val anchored = if (anchorMangaId == entity.mangaId) {
                if (entity.entityId == entityId) entity else entity.copy(entityId = entityId)
            } else {
                entity.copy(entityId = entityId, mangaId = anchorMangaId)
            }
            val key = ScrobblingRestoreKey(
                scrobbler = anchored.scrobbler,
                targetId = anchored.targetId,
                mediaType = anchored.mediaType,
            )
            val existing = normalized[key]
            normalized[key] = if (existing == null) {
                anchored
            } else {
                choosePreferredScrobblingRestoreEntity(existing, anchored)
            }
        }
        all.forEach { scrobblingDao.delete(it) }
        normalized.values.forEach { scrobblingDao.upsert(it) }
    }

    private suspend fun MangaDatabase.resolvePreferredScrobblingAnchor(mangaId: Long): Long? {
        val entityId = resolveWorkEntityIdForLocalManga(mangaId) ?: return null
        return resolveExistingLocalProjectionForEntity(entityId) ?: mangaId
    }

    private suspend fun MangaDatabase.resolveExistingLocalProjectionForEntity(entityId: Long): Long? {
        val prefs = getEntityGraphDao().findEntityPrefs(entityId)
        val preferredMangaId = prefs?.preferredLocalMangaId
        if (preferredMangaId != null && getMangaDao().contains(preferredMangaId)) {
            return preferredMangaId
        }
        return getEntityGraphDao().findActiveLocalBindingsByEntity(entityId)
            .mapNotNull { it.externalId.toLongOrNull() }
            .firstOrNull { localId -> getMangaDao().contains(localId) }
    }

    private suspend fun MangaDatabase.createRestorePlaceholderProjection(
        entityId: Long,
        remoteAnchorMangaId: Long,
    ): Long {
        resolveExistingLocalProjectionForEntity(entityId)?.let { return it }
        val entity = getEntityGraphDao().findEntity(entityId)
            ?: throw IllegalStateException(
                "Refusing restore: cannot create placeholder projection for missing WORK entity $entityId.",
            )
        val existingPlaceholder = getEntityGraphDao().findActiveBindingsByEntity(entityId)
            .asSequence()
            .filter { it.source == "local_manga" || it.source == "0" }
            .mapNotNull { it.externalId.toLongOrNull() }
            .firstOrNull { localId ->
                val manga = getMangaDao().find(localId)?.manga
                manga?.source == RESTORE_PLACEHOLDER_SOURCE
            }
        if (existingPlaceholder != null) {
            return existingPlaceholder
        }
        var candidateId = (getMangaDao().findMinId() ?: 0L).coerceAtMost(0L) - 1L
        while (candidateId in getMangaDao()) {
            candidateId--
        }
        val placeholderUrl = "restore://work/$entityId/anchor/$remoteAnchorMangaId"
        getMangaDao().upsert(
            MangaEntity(
                id = candidateId,
                title = entity.primaryName.ifBlank { "Restored work $entityId" },
                altTitles = null,
                url = placeholderUrl,
                publicUrl = placeholderUrl,
                rating = -1f,
                isNsfw = false,
                contentRating = null,
                coverUrl = getEntityGraphDao().findEntityPrefs(entityId)?.coverUrlOverride.orEmpty(),
                largeCoverUrl = null,
                state = null,
                authors = null,
                source = RESTORE_PLACEHOLDER_SOURCE,
            ),
            tags = null,
        )
        ensureRestoredLocalMangaBinding(entityId, candidateId)
        Log.d(
            TAG,
            "restore: created placeholder projection mangaId=$candidateId entityId=$entityId " +
                "remoteAnchorMangaId=$remoteAnchorMangaId",
        )
        return candidateId
    }

    private fun choosePreferredScrobblingRestoreEntity(
        left: ScrobblingEntity,
        right: ScrobblingEntity,
    ): ScrobblingEntity {
        return compareValuesBy(
            right,
            left,
            { it.chapter },
            { it.rating },
            { it.comment?.length ?: 0 },
            { it.remoteTitle?.length ?: 0 },
            { it.id },
        ).takeIf { it > 0 }?.let { right } ?: left
    }

    private data class ScrobblingRestoreKey(
        val scrobbler: Int,
        val targetId: Long,
        val mediaType: String,
    )

    private suspend fun MangaDatabase.mergeTrack(remote: TrackEntity) {
        val dao = getTracksDao()
        val local = dao.findByOwnerId(remote.ownerId)
        if (local == null) {
            dao.upsert(remote)
            return
        }
        dao.upsert(local.mergeWithRestored(remote))
    }

    private fun TrackEntity.mergeWithRestored(remote: TrackEntity): TrackEntity {
        val remoteIsNewer = remote.isNewerThan(this)
        val newer = if (remoteIsNewer) remote else this
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

    private suspend fun MangaDatabase.restoreEntityRecord(
        remote: EntityRecord,
        entityIdMapping: MutableMap<Long, Long>,
    ) {
        val dao = getEntityGraphDao()
        val trimmedName = remote.primaryName.trim()
        val syncId = remote.syncId.trim()
        if (remote.type == EntityType.WORK.name && syncId.isEmpty()) {
            throw IllegalStateException(
                "Refusing restore: WORK entity ${remote.id} has no sync_id; restore cannot map it safely.",
            )
        }
        val computedHash = computeNameHash(trimmedName)
        val syncIdOwner = syncId
            .takeIf { it.isNotEmpty() }
            ?.let { dao.findEntityBySyncId(it) }
        val existing = syncIdOwner
            ?.takeIf {
                it.type == remote.type &&
                    (it.contentType == null || remote.contentType == null || it.contentType == remote.contentType)
            }
        val localId = if (existing == null) {
            val restoreSyncId = when {
                syncId.isEmpty() -> java.util.UUID.randomUUID().toString()
                syncIdOwner == null -> syncId
                else -> java.util.UUID.randomUUID().toString()
            }
            val newRecord = EntityRecord(
                type = remote.type,
                contentType = remote.contentType,
                syncId = restoreSyncId,
                primaryName = trimmedName,
                nameHash = computedHash,
                aliases = encodeStringList(mergeAliases(trimmedName, decodeStringList(remote.aliases)).drop(1)),
                createdAt = remote.createdAt.coerceAtLeast(0L),
                lastAccessed = remote.lastAccessed.coerceAtLeast(0L),
                accessCount = remote.accessCount.coerceAtLeast(1),
            )
            val insertedId = dao.insertEntityIgnore(newRecord)
            if (insertedId != -1L) {
                insertedId
            } else {
                dao.insertEntity(
                    newRecord.copy(
                        nameHash = remote.id.takeIf { it > 0L } ?: -(remote.id + 1),
                    ),
                )
            }
        } else {
            val mergedNames = mergeAliases(
                existing.primaryName,
                decodeStringList(existing.aliases) + listOf(trimmedName) + decodeStringList(remote.aliases),
            )
            val newPrimary = mergedNames.firstOrNull() ?: existing.primaryName
            val merged = existing.copy(
                contentType = existing.contentType ?: remote.contentType,
                primaryName = newPrimary,
                nameHash = computeNameHash(newPrimary),
                aliases = encodeStringList(mergedNames.drop(1)),
                createdAt = minOf(existing.createdAt, remote.createdAt.coerceAtLeast(0L)),
                lastAccessed = maxOf(existing.lastAccessed, remote.lastAccessed.coerceAtLeast(0L)),
                accessCount = maxOf(existing.accessCount, remote.accessCount.coerceAtLeast(1)),
            )
            dao.upsertEntityRecord(merged)
            existing.id
        }
        entityIdMapping[remote.id] = localId
    }

    private suspend fun MangaDatabase.restoreEntityBinding(
        remote: EntityBindingRecord,
        entityIdMapping: MutableMap<Long, Long>,
        restoreContext: RestoreSemanticContext,
        remoteLocalBindings: MutableList<RestoredLocalBindingEvidence>,
    ) {
        val localEntityId = entityIdMapping[remote.entityId]
        if (localEntityId == null) {
            Log.w(TAG, "restore: skip entity_binding for unmapped entityId=${remote.entityId}, source=${remote.source}, externalId=${remote.externalId}")
            return
        }
        if (remote.source.isLocalMangaBindingSource()) {
            val remoteMangaId = remote.externalId.toLongOrNull()
            if (remoteMangaId == null) {
                Log.w(TAG, "restore: skip malformed local entity_binding externalId=${remote.externalId}")
                return
            }
            remoteLocalBindings += RestoredLocalBindingEvidence(
                remoteMangaId = remoteMangaId,
                remoteEntityId = remote.entityId,
            )
            if (remoteMangaId !in getMangaDao()) {
                Log.d(
                    TAG,
                    "restore: defer local_manga binding remoteMangaId=$remoteMangaId localEntityId=$localEntityId",
                )
                return
            }
        }
        val dao = getEntityGraphDao()
        val existing = dao.findBinding(remote.source, remote.externalId)
        if (
            existing != null &&
            existing.entityId != localEntityId &&
            existing.state.toEntityBindingStateOrNull() in ACTIVE_RESTORE_BINDING_STATES &&
            remote.isStrongRestoreBinding()
        ) {
            val target = dao.findEntity(existing.entityId)
            if (target?.type == EntityType.WORK.name) {
                entityIdMapping[remote.entityId] = existing.entityId
                Log.d(
                    TAG,
                    "restore: remap remote WORK ${remote.entityId} to local WORK ${existing.entityId} " +
                        "from binding source=${remote.source}, externalId=${remote.externalId}",
                )
                return
            }
        }
        if (existing != null && existing.shouldKeepOverRestored(remote)) {
            Log.d(TAG, "restore: keep local entity_binding source=${remote.source}, externalId=${remote.externalId}")
            return
        }
        dao.upsertBinding(remote.normalizedForRestore(localEntityId, restoreContext))
    }

    private suspend fun MangaDatabase.reconcileRestoredProjectionAnchors(
        remoteLocalBindings: List<RestoredLocalBindingEvidence>,
        entityIdMapping: MutableMap<Long, Long>,
    ): Map<Long, Long> {
        if (remoteLocalBindings.isEmpty()) {
            Log.d(TAG, "restore projection anchors: no remote local bindings")
            return emptyMap()
        }
        val entityIds = remoteLocalBindings.mapNotNull { entityIdMapping[it.remoteEntityId] }.distinct()
        if (entityIds.isEmpty()) {
            Log.d(TAG, "restore projection anchors: no resolved remote local bindings")
            return emptyMap()
        }
        val dao = getEntityGraphDao()
        val localProjectionIdsByEntity = LinkedHashMap<Long, LinkedHashSet<Long>>()
        var nextRestoredProjectionId = (getMangaDao().findMinId() ?: 0L).coerceAtMost(0L) - 1L
        var projectionBindingCount = 0
        var matchedProjectionCount = 0
        var createdProjectionCount = 0
        var attachedLocalBindingCount = 0
        var remappedProjectionOwnerCount = 0
        entityIds.chunked(RESTORE_TRANSACTION_BATCH_SIZE).forEach { chunk ->
            dao.findActiveBindingsByEntities(chunk)
                .asSequence()
                .filter { it.isRestorableProjectionBinding() }
                .forEach { binding ->
                    projectionBindingCount++
                    val resolved = resolveLocalMangaIdByProjectionBinding(
                        binding = binding,
                        nextRestoredProjectionId = nextRestoredProjectionId,
                    ) ?: return@forEach
                    nextRestoredProjectionId = resolved.nextRestoredProjectionId
                    if (resolved.created) {
                        createdProjectionCount++
                    }
                    val localMangaId = resolved.localMangaId
                    matchedProjectionCount++
                    val ownerEntityId = resolveRestoredProjectionOwner(
                        entityId = binding.entityId,
                        localMangaId = localMangaId,
                        entityIdMapping = entityIdMapping,
                    )
                    if (ownerEntityId != binding.entityId) {
                        remappedProjectionOwnerCount++
                    }
                    localProjectionIdsByEntity.getOrPut(ownerEntityId) { linkedSetOf() } += localMangaId
                    if (ensureRestoredLocalMangaBinding(ownerEntityId, localMangaId)) {
                        attachedLocalBindingCount++
                    }
                }
        }

        val mapping = LinkedHashMap<Long, Long>()
        var existingSameIdCount = 0
        var singleProjectionMapCount = 0
        var unresolvedCount = 0
        for (evidence in remoteLocalBindings) {
            val localEntityId = entityIdMapping[evidence.remoteEntityId]
            if (localEntityId == null) {
                Log.w(
                    TAG,
                    "restore projection anchors: skip local binding evidence with unmapped remoteEntityId=${evidence.remoteEntityId}",
                )
                unresolvedCount++
                continue
            }
            if (evidence.remoteMangaId in getMangaDao()) {
                mapping[evidence.remoteMangaId] = evidence.remoteMangaId
                val ownerEntityId = resolveRestoredProjectionOwner(
                    entityId = localEntityId,
                    localMangaId = evidence.remoteMangaId,
                    entityIdMapping = entityIdMapping,
                )
                if (ownerEntityId != localEntityId) {
                    remappedProjectionOwnerCount++
                }
                if (ensureRestoredLocalMangaBinding(ownerEntityId, evidence.remoteMangaId)) {
                    attachedLocalBindingCount++
                }
                existingSameIdCount++
                continue
            }
            val latestLocalEntityId = entityIdMapping[evidence.remoteEntityId] ?: localEntityId
            val localProjectionIds = localProjectionIdsByEntity[latestLocalEntityId].orEmpty()
            val localMangaId = localProjectionIds.singleOrNull()
            if (localMangaId != null) {
                mapping[evidence.remoteMangaId] = localMangaId
                if (ensureRestoredLocalMangaBinding(latestLocalEntityId, localMangaId)) {
                    attachedLocalBindingCount++
                }
                singleProjectionMapCount++
            } else {
                unresolvedCount++
            }
        }
        Log.d(
            TAG,
            "restore projection anchors: remoteLocalBindings=${remoteLocalBindings.size} " +
                "resolvedRemoteLocalBindings=${entityIds.size} " +
                "projectionBindings=$projectionBindingCount matchedProjections=$matchedProjectionCount " +
                "createdProjections=$createdProjectionCount attachedLocalBindings=$attachedLocalBindingCount " +
                "remappedProjectionOwners=$remappedProjectionOwnerCount " +
                "mappingSize=${mapping.size} " +
                "existingSameId=$existingSameIdCount singleProjectionMapped=$singleProjectionMapCount " +
                "unresolved=$unresolvedCount",
        )
        return mapping
    }

    private suspend fun MangaDatabase.resolveLocalMangaIdByProjectionBinding(
        binding: EntityBindingRecord,
        nextRestoredProjectionId: Long,
    ): RestoredProjectionResolution? {
        val key = binding.externalId.toProjectionKeyParts() ?: return null
        val existingId = when (key.kind) {
            ProjectionKeyKind.URL -> getMangaDao().findBySourceAndUrl(binding.source, key.value)?.manga?.id
            ProjectionKeyKind.PUBLIC_URL -> getMangaDao().findBySourceAndPublicUrl(binding.source, key.value)?.manga?.id
        }
        if (existingId != null) {
            return RestoredProjectionResolution(
                localMangaId = existingId,
                nextRestoredProjectionId = nextRestoredProjectionId,
                created = false,
            )
        }
        val entity = getEntityGraphDao().findEntity(binding.entityId) ?: return null
        val prefs = getEntityGraphDao().findEntityPrefs(binding.entityId)
        var candidateId = nextRestoredProjectionId
        while (candidateId in getMangaDao()) {
            candidateId--
        }
        val restored = key.toRestoredMangaEntity(
            id = candidateId,
            source = binding.source,
            title = entity.primaryName,
            coverUrl = prefs?.coverUrlOverride.orEmpty(),
            contentRating = prefs?.contentRatingOverride,
        )
        getMangaDao().upsert(restored, tags = null)
        Log.d(
            TAG,
            "restore projection anchors: created projection mangaId=$candidateId " +
                "source=${binding.source} keyKind=${key.kind} entityId=${binding.entityId}",
        )
        return RestoredProjectionResolution(
            localMangaId = candidateId,
            nextRestoredProjectionId = candidateId - 1L,
            created = true,
        )
    }

    private suspend fun MangaDatabase.resolveRestoredProjectionOwner(
        entityId: Long,
        localMangaId: Long,
        entityIdMapping: MutableMap<Long, Long>,
    ): Long {
        val existing = getEntityGraphDao().findActiveBinding("local_manga", localMangaId.toString())
            ?: return entityId
        if (existing.entityId == entityId) {
            return entityId
        }
        val target = getEntityGraphDao().findEntity(existing.entityId)
        if (target?.type != EntityType.WORK.name) {
            return entityId
        }
        entityIdMapping.entries
            .filter { it.value == entityId }
            .forEach { (remoteEntityId, _) ->
                entityIdMapping[remoteEntityId] = existing.entityId
            }
        Log.d(
            TAG,
            "restore projection anchors: remap local WORK $entityId to ${existing.entityId} " +
                "from local_manga anchor $localMangaId",
        )
        return existing.entityId
    }

    private suspend fun MangaDatabase.ensureRestoredLocalMangaBinding(
        entityId: Long,
        localMangaId: Long,
    ): Boolean {
        val dao = getEntityGraphDao()
        val externalId = localMangaId.toString()
        val existing = dao.findBinding("local_manga", externalId)
        if (existing != null) {
            val existingState = existing.state.toEntityBindingStateOrNull()
            if (existing.entityId == entityId && existingState in ACTIVE_RESTORE_BINDING_STATES) {
                return false
            }
            if (existing.entityId != entityId && existingState in ACTIVE_RESTORE_BINDING_STATES) {
                throw IllegalStateException(
                    "Refusing restore: local_manga anchor $localMangaId is already bound to WORK " +
                        "${existing.entityId}, cannot bind to WORK $entityId.",
                )
            }
        }
        dao.upsertBinding(
            EntityBindingRecord(
                entityId = entityId,
                source = "local_manga",
                externalId = externalId,
                confidence = 1f,
                isPrimary = false,
                sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
                state = EntityBindingState.LEGACY.name,
                createdBy = EntityBindingCreatedBy.SYNC.name,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    private fun EntityBindingRecord.normalizedForRestore(
        localEntityId: Long,
        restoreContext: RestoreSemanticContext,
    ): EntityBindingRecord {
        val restoredState = if (restoreContext.isLegacySemanticSchema) {
            EntityBindingState.LEGACY
        } else {
            state.toEntityBindingStateOrNull() ?: EntityBindingState.LEGACY
        }
        val restoredSourceKind = sourceKind.takeIf { raw ->
            EntityBindingSourceKind.entries.any { it.name == raw }
        } ?: source.toEntityBindingSourceKind().name
        val restoredCreatedBy = if (restoreContext.isLegacySemanticSchema) {
            EntityBindingCreatedBy.SYNC.name
        } else {
            createdBy.takeIf { raw ->
                EntityBindingCreatedBy.entries.any { it.name == raw }
            } ?: EntityBindingCreatedBy.SYNC.name
        }
        return copy(
            entityId = localEntityId,
            isPrimary = false,
            sourceKind = restoredSourceKind,
            state = restoredState.name,
            createdBy = restoredCreatedBy,
        )
    }

    private fun EntityBindingRecord.shouldKeepOverRestored(remote: EntityBindingRecord): Boolean {
        val localState = state.toEntityBindingStateOrNull()
        val remoteState = remote.state.toEntityBindingStateOrNull()
        if (updatedAt > 0L && (remote.updatedAt <= 0L || updatedAt > remote.updatedAt)) {
            return true
        }
        if (localState in RESTORE_PROTECTED_BINDING_STATES && remoteState !in RESTORE_PROTECTED_BINDING_STATES) {
            return true
        }
        return localState == EntityBindingState.MANUAL && remoteState != EntityBindingState.MANUAL
    }

    private fun EntityBindingRecord.isRestorableProjectionBinding(): Boolean {
        if (source.isLocalMangaBindingSource()) {
            return false
        }
        if (sourceKind == EntityBindingSourceKind.TRACKING_SOURCE.name) {
            return false
        }
        return externalId.toProjectionKeyParts() != null
    }

    private fun EntityBindingRecord.isStrongRestoreBinding(): Boolean {
        return isRestorableProjectionBinding()
    }

    private fun String.isLocalMangaBindingSource(): Boolean {
        return this == "local_manga" || this == "0"
    }

    private fun String.toProjectionKeyParts(): ProjectionKeyParts? {
        return when {
            startsWith("url:") -> ProjectionKeyParts(
                kind = ProjectionKeyKind.URL,
                value = removePrefix("url:"),
            )
            startsWith("public_url:") -> ProjectionKeyParts(
                kind = ProjectionKeyKind.PUBLIC_URL,
                value = removePrefix("public_url:"),
            )
            else -> null
        }?.takeIf { it.value.isNotBlank() }
    }

    private fun ProjectionKeyParts.toRestoredMangaEntity(
        id: Long,
        source: String,
        title: String,
        coverUrl: String,
        contentRating: String?,
    ): MangaEntity {
        return MangaEntity(
            id = id,
            title = title.ifBlank { value },
            altTitles = null,
            url = if (kind == ProjectionKeyKind.URL) value else "",
            publicUrl = if (kind == ProjectionKeyKind.PUBLIC_URL) value else "",
            rating = -1f,
            isNsfw = false,
            contentRating = contentRating,
            coverUrl = coverUrl,
            largeCoverUrl = null,
            state = null,
            authors = null,
            source = source,
        )
    }

    private suspend fun MangaDatabase.restoreEntityRelation(
        remote: RelationRecord,
        entityIdMapping: Map<Long, Long>,
    ) {
        val localFromId = entityIdMapping[remote.fromEntityId]
        val localToId = entityIdMapping[remote.toEntityId]
        if (localFromId == null || localToId == null) {
            Log.w(TAG, "restore: skip relation id=${remote.id}, from=${remote.fromEntityId}->${localFromId}, to=${remote.toEntityId}->${localToId}")
            return
        }
        if (localFromId == localToId) {
            return
        }
        getEntityGraphDao().upsertRelationRecord(
            remote.copy(
                id = 0L,
                fromEntityId = localFromId,
                toEntityId = localToId,
            ),
        )
    }

    private suspend fun MangaDatabase.restoreEntityPrefs(
        remote: EntityPrefsRecord,
        entityIdMapping: Map<Long, Long>,
        restoreContext: RestoreSemanticContext,
    ) {
        val localEntityId = entityIdMapping[remote.entityId] ?: return
        val dao = getEntityGraphDao()
        val local = dao.findEntityPrefs(localEntityId)
        val candidate = remote.normalizedForRestore(localEntityId, restoreContext)
        if (restoreContext.isLegacySemanticSchema) {
            if (local == null) {
                dao.upsertPrefsRecord(candidate)
            }
            return
        }
        if (local == null || candidate.updatedAt >= local.updatedAt) {
            dao.upsertPrefsRecord(candidate)
        }
    }

    private fun EntityPrefsRecord.normalizedForRestore(
        localEntityId: Long,
        restoreContext: RestoreSemanticContext,
    ): EntityPrefsRecord {
        return if (restoreContext.isLegacySemanticSchema) {
            copy(
                entityId = localEntityId,
                metadataBindingSource = null,
                metadataBindingExternalId = null,
            )
        } else {
            copy(entityId = localEntityId)
        }
    }

    private suspend fun <T> Sequence<T>.restoreToDb(
        label: String,
        batchSize: Int = RESTORE_TRANSACTION_BATCH_SIZE,
        failFast: Boolean = false,
        block: suspend MangaDatabase.(T) -> Unit,
    ): CompositeResult {
        val startedAt = SystemClock.elapsedRealtime()
        var processed = 0
        var result = CompositeResult.EMPTY
        val batch = ArrayList<T>(batchSize)

        suspend fun flushBatch() {
            if (batch.isEmpty()) return
            val batchStartedAt = SystemClock.elapsedRealtime()
            var batchResult = CompositeResult.EMPTY
            database.withTransaction {
                batch.forEach { item ->
                    if (failFast) {
                        database.block(item)
                        batchResult += CompositeResult.success()
                    } else {
                        batchResult += runCatchingCancellable {
                            database.block(item)
                        }
                    }
                }
            }
            processed += batch.size
            result += batchResult
            Log.d(
                TAG,
                "restoreToDb: label=$label processed=$processed batchSize=${batch.size} " +
                    "failures=${batchResult.failures.size} " +
                    "batchElapsedMs=${SystemClock.elapsedRealtime() - batchStartedAt} " +
                    "totalElapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            batchResult.failures.firstOrNull()?.let { error ->
                Log.w(TAG, "restoreToDb: label=$label first failure", error)
            }
            batch.clear()
        }

        for (item in this) {
            batch += item
            if (batch.size >= batchSize) {
                flushBatch()
            }
        }
        flushBatch()
        Log.d(TAG, "restoreToDb: label=$label complete count=$processed totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return result
    }

    private suspend fun <T> Sequence<T>.restoreWithoutTransaction(
        label: String,
        block: suspend (T) -> Unit,
    ): CompositeResult {
        val startedAt = SystemClock.elapsedRealtime()
        var processed = 0
        val result = fold(CompositeResult.EMPTY) { result, item ->
            processed++
            result + runCatchingCancellable {
                block(item)
            }
        }
        Log.d(TAG, "restoreWithoutTransaction: label=$label complete count=$processed totalMs=${SystemClock.elapsedRealtime() - startedAt}")
        return result
    }
}

internal object LegacyJarRepoCompat {

    fun shouldImport(
        requestedSections: Set<BackupSection>,
        archiveSections: Set<BackupSection>,
        restoredSections: Set<BackupSection>,
        hasExistingJarRepos: Boolean,
    ): Boolean {
        if (BackupSection.SOURCES !in requestedSections) return false
        if (BackupSection.SOURCES !in restoredSections) return false
        if (BackupSection.EXTENSION_REPOS in archiveSections) return false
        if (hasExistingJarRepos) return false
        return true
    }

    fun buildEntities(
        now: Long,
        recommendedRepos: List<UnifiedRecommendedRepository> = UnifiedRecommendedRepositories.byKind(UnifiedSourceKind.JAR),
    ): List<ExternalExtensionRepoEntity> {
        return recommendedRepos.mapNotNull { repo ->
            val normalizedIndexUrl = normalizeIndexUrl(repo.url) ?: return@mapNotNull null
            val baseUrl = normalizedIndexUrl.removeSuffix("/index.min.json")
            ExternalExtensionRepoEntity(
                type = ExternalExtensionType.JAR,
                baseUrl = baseUrl,
                name = "Kototoro: ${repo.name}",
                shortName = repo.name,
                website = baseUrl,
                signingKeyFingerprint = baseUrl.hashCode().toString(16),
                createdAt = now,
                updatedAt = now,
                lastSuccessAt = 0L,
                lastError = null,
                version = null,
            )
        }
    }

    private fun normalizeIndexUrl(input: String): String? {
        val trimmed = input.trim()
        return when {
            trimmed.isEmpty() -> null
            trimmed.endsWith("/index.min.json") -> trimmed
            else -> "$trimmed/index.min.json"
        }
    }
}
