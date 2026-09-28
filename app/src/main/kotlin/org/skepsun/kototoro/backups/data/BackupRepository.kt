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
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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
import org.skepsun.kototoro.backups.data.model.ContentBackup
import org.skepsun.kototoro.backups.data.model.ExtensionRepoBackup
import org.skepsun.kototoro.backups.data.model.FavouriteBackup
import org.skepsun.kototoro.backups.data.model.HistoryBackup
import org.skepsun.kototoro.backups.data.model.ScrobblingBackup
import org.skepsun.kototoro.backups.data.model.SourceBackup
import org.skepsun.kototoro.backups.data.model.SourceOriginBackup
import org.skepsun.kototoro.backups.data.model.StatisticBackup
import org.skepsun.kototoro.backups.data.model.TrackBackup
import org.skepsun.kototoro.backups.data.model.TrackLogBackup
import org.skepsun.kototoro.backups.data.model.WorkFavouriteBackup
import org.skepsun.kototoro.backups.data.model.WorkHistoryBackup
import org.skepsun.kototoro.backups.data.model.WorkStatisticBackup
import org.skepsun.kototoro.backups.domain.BackupRestoreFormat
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.backups.domain.SourceOriginMaterializer
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointDao
import org.skepsun.kototoro.core.db.entity.RestoreCheckpointEntity
import org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.progress.Progress
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.parsers.model.ContentSource
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import org.skepsun.kototoro.tracker.data.TrackEntity
import org.skepsun.kototoro.tracker.data.TrackLogEntity
import org.skepsun.kototoro.tracker.data.canBeClearedBy
import org.skepsun.kototoro.tracker.data.isNewerThan
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackNewChapters
import org.skepsun.kototoro.tracker.data.normalizeTrackFeedState
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

private val DEFERRED_RESTORE_ORDER = listOf(
    BackupSection.CATEGORIES,
    BackupSection.CONTENTS,
    BackupSection.HISTORY,
    BackupSection.FAVOURITES,
    BackupSection.BOOKMARKS,
    BackupSection.STATS,
    BackupSection.WORK_HISTORY,
    BackupSection.WORK_FAVOURITES,
    BackupSection.WORK_STATS,
    BackupSection.TRACKS,
    BackupSection.TRACK_LOGS,
)

private val MAPPING_SNAPSHOT_SECTIONS = setOf(
    BackupSection.CATEGORIES,
)

private val BackupSection.requiresDeferredRestore: Boolean
    get() = this in DEFERRED_RESTORE_ORDER

/** The table a section restores into; legacy WORK_* sections share the manga tables. */
private val BackupSection.restoreTarget: BackupSection
    get() = when (this) {
        BackupSection.WORK_HISTORY -> BackupSection.HISTORY
        BackupSection.WORK_FAVOURITES -> BackupSection.FAVOURITES
        BackupSection.WORK_STATS -> BackupSection.STATS
        else -> this
    }

@Serializable
private data class RestoreMappingSnapshotDto(
    val legacyCategoryIdMapping: Map<String, Long> = emptyMap(),
)

/**
 * 恢复会话 checkpoint 跟踪器：在同一行里持久化「已完成节」与「跨节映射快照」，
 * 使被打断的恢复（进程被杀 / 崩溃 / 取消）能以相同的 restore_id 断点续传。
 */
private class RestoreCheckpointSession(
    private val dao: RestoreCheckpointDao,
    private val id: String,
    private val mode: BackupRepository.RestoreMode,
    private val sections: Set<BackupSection>,
    private val json: Json,
    legacyCategoryIdMapping: MutableMap<Long, Long>,
) {

    private val scopeLegacyCategoryIdMapping = legacyCategoryIdMapping

    /** 已完成并落盘的节（处理顺序）。 */
    val done = LinkedHashSet<BackupSection>()

    /** checkpoint 里已有的已完成节数量（用于 resume 后的起始进度）。 */
    var startedDoneCount = 0
        private set

    val isResuming: Boolean
        get() = startedDoneCount > 0

    private fun encodeDone(): String {
        return json.encodeToString(done.toList().map { it.name })
    }

    private fun encodeMapping(): String {
        return json.encodeToString(
            RestoreMappingSnapshotDto(
                legacyCategoryIdMapping = scopeLegacyCategoryIdMapping.entries
                    .associate { (k, v) -> k.toString() to v },
            ),
        )
    }

    suspend fun load(): Boolean {
        val row = dao.findById(id) ?: return false
        if (row.mode != mode.name) return false
        val storedSections = runCatching {
            json.decodeFromString<List<String>>(row.sectionsJson).mapNotNullTo(linkedSetOf()) {
                runCatching { BackupSection.valueOf(it) }.getOrNull()
            }
        }.getOrDefault(emptySet())
        if (storedSections != sections) return false
        val storedDone = runCatching {
            json.decodeFromString<List<String>>(row.doneJson).mapNotNullTo(linkedSetOf()) {
                runCatching { BackupSection.valueOf(it) }.getOrNull()
            }
        }.getOrDefault(emptySet())
        done += storedDone.filterTo(linkedSetOf()) { it in sections }
        startedDoneCount = done.size
        row.mappingJson?.let { raw ->
            runCatching {
                json.decodeFromString<RestoreMappingSnapshotDto>(raw)
            }.onSuccess { snapshot ->
                snapshot.legacyCategoryIdMapping.forEach { (k, v) ->
                    scopeLegacyCategoryIdMapping[k.toLongOrNull() ?: return@forEach] = v
                }
            }
        }
        return true
    }

    suspend fun advance(section: BackupSection, mappingChanged: Boolean = false) {
        done += section
        val hasMappingSource = mappingChanged || MAPPING_SNAPSHOT_SECTIONS.any { it in done }
        dao.upsert(
            RestoreCheckpointEntity(
                id = id,
                mode = mode.name,
                sectionsJson = json.encodeToString(sections.map { it.name }),
                doneJson = encodeDone(),
                mappingJson = if (hasMappingSource) encodeMapping() else null,
                updatedAt = System.currentTimeMillis(),
            ),
        )
    }

    suspend fun complete() {
        dao.deleteById(id)
    }
}

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: ContentSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
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
        val resumedSections: Int = 0,
    )

    data class RestoreSemanticContext(
        val transportGeneration: Int,
        val semanticSchemaVersion: Int,
    ) {
        val isLegacySemanticSchema: Boolean
            get() = semanticSchemaVersion < BackupIndex.LEGACY_SEMANTIC_SCHEMA_BOUNDARY

        /** A current-writer, current-schema snapshot whose merged result may be re-uploaded. */
        val isAuthoritativeSchema: Boolean
            get() = transportGeneration >= BackupIndex.WRITER_GENERATION_V3 && !isLegacySemanticSchema
    }

    private fun dumpContentSnapshots(): Flow<ContentBackup> = flow {
        val anchorIds = LinkedHashSet<Long>()
        anchorIds += database.getHistoryDao().findAllEntriesIncludingDeleted().map { it.mangaId }
        anchorIds += database.getFavouritesDao().findAllEntriesIncludingDeleted().map { it.mangaId }
        if (anchorIds.isEmpty()) {
            return@flow
        }
        anchorIds.chunked(RESTORE_TRANSACTION_BATCH_SIZE).forEach { chunk ->
            database.getMangaDao().findWithTagsByIds(chunk).forEach { content ->
                emit(ContentBackup(content))
            }
        }
    }

    suspend fun createBackup(
        output: ZipOutputStream,
        progress: FlowCollector<Progress>?,
        format: ExportFormat = ExportFormat.KOTOTORO,
    ) {
        progress?.emit(Progress.INDETERMINATE)
        val installedSources = if (format == ExportFormat.KOTOTORO) {
            mangaSourcesRepository.getAllAvailableSourcesUnfiltered()
        } else {
            null
        }
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
                    data = database.getHistoryDao().dump()
                        .filter { it.history.deletedAt == 0L }
                        .filter { format != ExportFormat.KOTATSU || it.manga.source in kotatsuSourceNames }
                        .map { HistoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.CATEGORIES -> output.writeJsonArray(
                    section = BackupSection.CATEGORIES,
                    data = database.getFavouriteCategoriesDao().findAll().map(::CategoryBackup).asFlow(),
                    serializer = serializer(),
                )

                BackupSection.FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.FAVOURITES,
                    data = database.getFavouritesDao().dump()
                        .filter { it.favourite.deletedAt == 0L }
                        .filter { format != ExportFormat.KOTATSU || it.manga.source in kotatsuSourceNames }
                        .map { FavouriteBackup(it) },
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

                BackupSection.SOURCE_ORIGINS -> output.writeJsonArray(
                    section = BackupSection.SOURCE_ORIGINS,
                    data = materializeSourceOrigins(installedSources),
                    serializer = serializer(),
                )

                BackupSection.EXTENSION_REPOS -> {
                    val repos = buildList {
                        for (type in ExternalExtensionType.entries) {
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

                BackupSection.CONTENTS -> output.writeJsonArray(
                    section = BackupSection.CONTENTS,
                    data = dumpContentSnapshots(),
                    serializer = serializer(),
                )

                BackupSection.STATS -> output.writeJsonArray(
                    section = BackupSection.STATS,
                    data = database.getStatsDao().dumpEnabled()
                        .filter { stat ->
                            format != ExportFormat.KOTATSU ||
                                database.getMangaDao().find(stat.mangaId)?.manga?.source in kotatsuSourceNames
                        }
                        .map { StatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_HISTORY,
                BackupSection.WORK_FAVOURITES,
                BackupSection.WORK_STATS,
                BackupSection.ENTITY_GRAPH_ENTITIES,
                BackupSection.ENTITY_GRAPH_BINDINGS,
                BackupSection.ENTITY_GRAPH_RELATIONS,
                BackupSection.ENTITY_GRAPH_PREFS -> output.writeJsonArray(
                    section = section,
                    data = emptyFlow<String>(),
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
            }
            progress?.emit(commonProgress)
            commonProgress++
        }
        progress?.emit(commonProgress)
    }

    private suspend fun materializeSourceOrigins(
        installedSources: List<ContentSource>? = null,
    ): Flow<SourceOriginBackup> = flow {
        val now = System.currentTimeMillis()
        val existing = database.getSourceOriginsDao().findAll().associateBy { it.sourceKey }
        val installedByName = (installedSources ?: mangaSourcesRepository.getAllAvailableSourcesUnfiltered())
            .associateBy { it.name }
        val keys = LinkedHashSet<String>()
        keys += existing.keys
        keys += database.getSourcesDao().findAll().map { it.source }
        keys += installedByName.keys
        keys += database.getMangaDao().distinctSources()
        keys.sorted().forEach { key ->
            val origin = existing[key]
                ?: SourceOriginMaterializer.minimalOrigin(key, installedByName[key], now)
            emit(SourceOriginBackup.fromEntity(origin))
        }
    }

    private suspend fun materializeSourceOriginsIntoDb() {
        val now = System.currentTimeMillis()
        val existing = database.getSourceOriginsDao().findAll().associateBy { it.sourceKey }
        val installedByName = mangaSourcesRepository.getAllAvailableSourcesUnfiltered()
            .associateBy { it.name }
        val keys = LinkedHashSet<String>()
        keys += database.getSourcesDao().findAll().map { it.source }
        keys += database.getMangaDao().distinctSources()
        keys -= existing.keys
        keys.sorted().forEach { key ->
            database.getSourceOriginsDao().upsert(
                SourceOriginMaterializer.minimalOrigin(key, installedByName[key], now),
            )
        }
    }

    suspend fun restoreBackup(
        input: ZipInputStream,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?,
        restoreMode: RestoreMode = RestoreMode.MERGE,
        checkpointId: String? = null,
    ): RestoreBackupResult {
        val restoreStartedAt = SystemClock.elapsedRealtime()
        val effectiveSections = sections.withImplicitRestoreSections()
        Log.d(TAG, "restoreBackup: start mode=$restoreMode sections=${effectiveSections.joinToString()}")
        progress?.emit(Progress.INDETERMINATE)
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        val archiveSections = linkedSetOf<BackupSection>()
        val restoredSections = linkedSetOf<BackupSection>()
        val legacyCategoryIdMapping = LinkedHashMap<Long, Long>()
        val deferredEntries = LinkedHashMap<BackupSection, ByteArray>()
        var backupIndex: BackupIndex? = null
        var restoreContext = resolveRestoreSemanticContext(null)
        val checkpoint = checkpointId?.takeIf { it.isNotBlank() }?.let { id ->
            RestoreCheckpointSession(
                dao = database.getRestoreCheckpointDao(),
                id = id,
                mode = restoreMode,
                sections = effectiveSections,
                json = json,
                legacyCategoryIdMapping = legacyCategoryIdMapping,
            ).also { it.load() }
        }
        if (checkpoint?.isResuming == true) {
            Log.i(
                TAG,
                "restoreBackup: RESUMING from " +
                    checkpoint.startedDoneCount + "/" + effectiveSections.size + " sections",
            )
        }
        var commonProgress = Progress(
            checkpoint?.startedDoneCount?.coerceIn(0, effectiveSections.size) ?: 0,
            effectiveSections.size,
        )
        progress?.emit(commonProgress)
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
                    BackupSection.CATEGORIES -> sectionInput.readJsonArray<CategoryBackup>(serializer()).restoreToDb("CATEGORIES") {
                        if (restoreContext.isLegacySemanticSchema && restoreMode == RestoreMode.MERGE) {
                            restoreLegacyCategory(it, legacyCategoryIdMapping)
                        } else {
                            getFavouriteCategoriesDao().upsert(it.toEntity())
                        }
                    }

                    BackupSection.CONTENTS -> sectionInput.readJsonArray<ContentBackup>(serializer()).restoreToDb("CONTENTS") {
                        upsertContent(it, restoreContext)
                    }

                    BackupSection.HISTORY -> sectionInput.readJsonArray<HistoryBackup>(serializer()).restoreToDb("HISTORY") {
                        upsertContent(it.manga, restoreContext)
                        val history = it.toEntity()
                        if (restoreMode == RestoreMode.MERGE) {
                            val local = getHistoryDao().find(history.mangaId)
                            if (local == null || history.updatedAt >= local.updatedAt) {
                                getHistoryDao().upsertSync(history)
                            }
                        } else {
                            getHistoryDao().upsertSync(history)
                        }
                    }

                    BackupSection.FAVOURITES -> sectionInput.readJsonArray<FavouriteBackup>(serializer()).restoreToDb("FAVOURITES") {
                        upsertContent(it.manga, restoreContext)
                        val candidate = it.toEntity()
                        val targetCategoryId = legacyCategoryIdMapping[candidate.categoryId] ?: candidate.categoryId
                        val mappedCandidate = candidate.copy(categoryId = targetCategoryId)
                        if (restoreMode == RestoreMode.MERGE) {
                            val local = getFavouritesDao().find(mappedCandidate.mangaId, mappedCandidate.categoryId)
                            val merged = if (local != null) {
                                if (mappedCandidate.updatedAt >= local.updatedAt) mappedCandidate else local
                            } else {
                                mappedCandidate
                            }
                            getFavouritesDao().upsert(merged)
                        } else {
                            getFavouritesDao().upsert(mappedCandidate)
                        }
                    }

                    BackupSection.SETTINGS -> sectionInput.readMap().let { map ->
                        val isKototoro = backupIndex?.appId == org.skepsun.kototoro.BuildConfig.APPLICATION_ID
                        val finalMap = if (isKototoro) {
                            map
                        } else {
                            map.toMutableMap().apply {
                                this[AppSettings.KEY_DOWNLOADS_MAX_ACTIVE_SERIES] = AppSettings.DOWNLOADS_MAX_ACTIVE_SERIES_DEFAULT
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
                        upsertContent(it.manga, restoreContext)
                        getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                    }

                    BackupSection.SOURCES -> sectionInput.readJsonArray<SourceBackup>(serializer()).restoreToDb("SOURCES") {
                        getSourcesDao().upsert(it.toEntity())
                    }

                    BackupSection.SOURCE_ORIGINS -> sectionInput.readJsonArray<SourceOriginBackup>(serializer()).restoreToDb(
                        "SOURCE_ORIGINS",
                    ) {
                        getSourceOriginsDao().upsert(it.toEntity())
                    }

                    BackupSection.EXTENSION_REPOS -> sectionInput.readJsonArray<ExtensionRepoBackup>(serializer()).restoreToDb("EXTENSION_REPOS") {
                        getExternalExtensionRepoDao().upsert(it.toEntity())
                    }

                    BackupSection.SCROBBLING -> sectionInput.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb("SCROBBLING") {
                        getScrobblingDao().upsert(it.toEntity())
                    }

                    BackupSection.TRACKS -> sectionInput.readJsonArray<TrackBackup>(serializer()).restoreToDb("TRACKS") {
                        mergeTrack(it.toEntity())
                    }

                    BackupSection.TRACK_LOGS -> sectionInput.readJsonArray<TrackLogBackup>(serializer()).restoreToDb("TRACK_LOGS") {
                        mergeTrackLog(it.toEntity())
                    }

                    BackupSection.STATS -> sectionInput.readJsonArray<StatisticBackup>(serializer()).restoreToDb("STATS") {
                        getStatsDao().upsert(it.toEntity())
                    }

                    // Legacy WORK_* rows land on their anchor manga. CONTENTS is restored first,
                    // so a missing anchor means the row has nothing to attach to: skip it rather than
                    // leave a dangling foreign key. (0 and negative ids are valid manga ids.)
                    BackupSection.WORK_HISTORY -> sectionInput.readJsonArray<WorkHistoryBackup>(serializer()).restoreToDb("WORK_HISTORY") {
                        if (it.anchorMangaId in getMangaDao()) {
                            // upsertSync keeps the row's deleted_at: legacy snapshots carry
                            // tombstones, which a plain upsert would resurrect as active history.
                            val history = it.toHistoryEntity()
                            if (restoreMode == RestoreMode.MERGE) {
                                val local = getHistoryDao().find(history.mangaId)
                                if (local == null || history.updatedAt >= local.updatedAt) {
                                    getHistoryDao().upsertSync(history)
                                }
                            } else {
                                getHistoryDao().upsertSync(history)
                            }
                        }
                    }

                    BackupSection.WORK_FAVOURITES -> sectionInput.readJsonArray<WorkFavouriteBackup>(serializer()).restoreToDb("WORK_FAVOURITES") {
                        val anchorMangaId = it.anchorMangaId
                        if (anchorMangaId != null && anchorMangaId in getMangaDao()) {
                            val targetCategoryId = legacyCategoryIdMapping[it.categoryId] ?: it.categoryId
                            val fav = it.toFavouriteEntity(targetMangaId = anchorMangaId, targetCategoryId = targetCategoryId)
                            if (restoreMode == RestoreMode.MERGE) {
                                val local = getFavouritesDao().find(fav.mangaId, fav.categoryId)
                                val merged = if (local != null) {
                                    if (fav.updatedAt >= local.updatedAt) fav else local
                                } else {
                                    fav
                                }
                                getFavouritesDao().upsert(merged)
                            } else {
                                getFavouritesDao().upsert(fav)
                            }
                        }
                    }

                    BackupSection.WORK_STATS -> sectionInput.readJsonArray<WorkStatisticBackup>(serializer()).restoreToDb("WORK_STATS") {
                        if (it.anchorMangaId in getMangaDao()) {
                            getStatsDao().upsert(it.toStatsEntity())
                        }
                    }

                    BackupSection.SAVED_FILTERS -> sectionInput.readJsonArray<PersistableFilter>(serializer())
                        .restoreWithoutTransaction("SAVED_FILTERS") {
                            savedFiltersRepository.save(it)
                        }

                    BackupSection.AUTH -> sectionInput.readMap().let {
                        restoreAuth(it)
                        CompositeResult.success()
                    }

                    BackupSection.ENTITY_GRAPH_ENTITIES,
                    BackupSection.ENTITY_GRAPH_BINDINGS,
                    BackupSection.ENTITY_GRAPH_RELATIONS,
                    BackupSection.ENTITY_GRAPH_PREFS -> {
                        sectionInput.drain()
                        CompositeResult.EMPTY
                    }

                    null -> CompositeResult.EMPTY
                }
                if (section != null) {
                    restoredSections.add(section)
                    Log.d(
                        TAG,
                        "restoreSection: section=" + section +
                            " failures=" + sectionResult.failures.size +
                            " elapsedMs=" + (SystemClock.elapsedRealtime() - sectionStartedAt),
                    )
                }
                return sectionResult
            }

            val initiallyDone = checkpoint?.done?.toSet().orEmpty()
            // Legacy and current sections share tables (HISTORY + WORK_HISTORY -> history, ...).
            // Each table is cleared once, before the first section writing it; tables of sections
            // already completed by a resumed checkpoint hold restored data and must not be cleared.
            val clearedTargets = initiallyDone.mapTo(HashSet()) { it.restoreTarget }
            while (entry != null) {
                val section = BackupSection.of(entry)
                if (section != null) {
                    archiveSections.add(section)
                }
                if (section != null && section !in effectiveSections) {
                    input.closeEntry()
                    entry = input.nextEntry
                    continue
                }
                if (section != null && section in initiallyDone) {
                    restoredSections.add(section)
                    Log.d(TAG, "restoreBackup: skip done section=" + section)
                    input.closeEntry()
                    entry = input.nextEntry
                    continue
                }
                if (section?.requiresDeferredRestore == true) {
                    deferredEntries[section] = input.readBytes()
                } else {
                    if (section != null && restoreMode == RestoreMode.SNAPSHOT_REPLACE &&
                        clearedTargets.add(section.restoreTarget)
                    ) {
                        clearRestoreTargets(effectiveSections, actOn = setOf(section))
                    }
                    result += restoreSection(section, input)
                    section?.let { checkpoint?.advance(it) }
                    progress?.emit(commonProgress)
                    commonProgress++
                }
                input.closeEntry()
                entry = input.nextEntry
            }
            for (section in DEFERRED_RESTORE_ORDER) {
                val bytes = deferredEntries[section] ?: continue
                if (section in initiallyDone) {
                    restoredSections.add(section)
                    Log.d(TAG, "restoreBackup: skip done deferred section=" + section)
                    continue
                }
                if (restoreMode == RestoreMode.SNAPSHOT_REPLACE && clearedTargets.add(section.restoreTarget)) {
                    clearRestoreTargets(effectiveSections, actOn = setOf(section))
                }
                result += restoreSection(section, ByteArrayInputStream(bytes))
                checkpoint?.advance(section)
                progress?.emit(commonProgress)
                commonProgress++
            }
            checkpoint?.complete()
            if (BackupSection.SOURCE_ORIGINS !in archiveSections && BackupSection.SOURCES in restoredSections) {
                materializeSourceOriginsIntoDb()
            }
            val legacyJarReposImported = restoreLegacyJarRepositoriesIfNeeded(effectiveSections, archiveSections, restoredSections)
            trimRestoredTrackLogs(restoredSections)
            Log.i(
                TAG,
                "restoreBackup: SUCCESS elapsedMs=" + (SystemClock.elapsedRealtime() - restoreStartedAt) +
                    " failures=" + result.failures.size,
            )
            return RestoreBackupResult(
                result = result,
                legacyJarReposImported = legacyJarReposImported,
                backupIndex = backupIndex,
                resumedSections = checkpoint?.startedDoneCount ?: 0,
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

    private fun OutputStream.write(str: String) = write(str.toByteArray())

    private fun InputStream.readString(): String = readBytes().decodeToString()

    private fun InputStream.drain() {
        copyTo(OutputStream.nullOutputStream())
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
        val prefs = appContext.getSharedPreferences("cookies", Context.MODE_PRIVATE)
        val prefsObj = JSONObject(prefs.all as Map<*, *>)
        root.put("cookies_prefs", prefsObj)

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
                " webview_names=${webviewMap.keys().asSequence().joinToString()}",
        )
        return root.toString()
    }

    private fun restoreAuth(map: Map<String, Any?>) {
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

    private suspend fun MangaDatabase.mergeTrack(remote: TrackEntity) {
        val dao = getTracksDao()
        val local = dao.find(remote.mangaId)
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

    private suspend fun clearRestoreTargets(
        sections: Set<BackupSection>,
        actOn: Set<BackupSection> = sections,
    ) {
        if (actOn.isEmpty()) {
            return
        }
        database.withTransaction {
            if (BackupSection.HISTORY in actOn || BackupSection.WORK_HISTORY in actOn) {
                database.getHistoryDao().clear()
            }
            if (BackupSection.CATEGORIES in actOn) {
                database.getFavouriteCategoriesDao().deleteAll()
            }
            if (BackupSection.FAVOURITES in actOn || BackupSection.WORK_FAVOURITES in actOn) {
                database.getFavouritesDao().clear()
            }
            if (BackupSection.BOOKMARKS in actOn) {
                database.getBookmarksDao().deleteAll()
            }
            if (BackupSection.SCROBBLING in actOn) {
                database.getScrobblingDao().deleteAll()
            }
            if (BackupSection.TRACKS in actOn) {
                database.getTracksDao().clear()
            }
            if (BackupSection.TRACK_LOGS in actOn) {
                database.getTrackLogsDao().clear()
            }
            if (BackupSection.STATS in actOn || BackupSection.WORK_STATS in actOn) {
                database.getStatsDao().clear()
            }
            if (BackupSection.EXTENSION_REPOS in actOn) {
                database.getExternalExtensionRepoDao().deleteAll()
            }
            if (BackupSection.SOURCE_ORIGINS in actOn) {
                database.getSourceOriginsDao().deleteAll()
            }
        }
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
            expanded += BackupSection.CONTENTS
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
        val result = fold(CompositeResult.EMPTY) { res, item ->
            processed++
            res + runCatchingCancellable {
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
