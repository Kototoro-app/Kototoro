package org.skepsun.kototoro.backups.data

import android.content.Context
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
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
import org.skepsun.kototoro.backups.data.model.WorkFavouriteBackup
import org.skepsun.kototoro.backups.data.model.WorkHistoryBackup
import org.skepsun.kototoro.backups.data.model.WorkStatisticBackup
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaPrefsEntity
import org.skepsun.kototoro.core.db.entity.ExternalExtensionRepoEntity
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.CompositeResult
import org.skepsun.kototoro.core.util.progress.Progress
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityPrefsRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.RelationRecord
import org.skepsun.kototoro.entitygraph.data.findWorkEntityIdByLocalMangaId
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.toEntityBindingStateOrNull
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.stats.data.WorkStatsEntity
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.data.TapGridSettings
import org.skepsun.kototoro.scrobbling.common.data.ScrobblingEntity
import org.skepsun.kototoro.scrobbling.common.data.upsertScrobbling
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepository
import org.skepsun.kototoro.settings.sources.unified.UnifiedRecommendedRepositories
import org.skepsun.kototoro.settings.sources.unified.UnifiedSourceKind
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject

private const val TAG = "BackupRepo"
private val RESTORE_PROTECTED_BINDING_STATES = setOf(
    EntityBindingState.MANUAL,
    EntityBindingState.CANDIDATE,
    EntityBindingState.REJECTED,
)

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: ContentSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
) {

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

    suspend fun createBackup(
        output: ZipOutputStream,
        progress: FlowCollector<Progress>?,
    ) {
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, BackupSection.entries.size)
        for (section in BackupSection.entries) {
            when (section) {
                BackupSection.INDEX -> output.writeJsonArray(
                    section = BackupSection.INDEX,
                    data = flowOf(BackupIndex()),
                    serializer = serializer(),
                )

                BackupSection.HISTORY -> output.writeJsonArray(
                    section = BackupSection.HISTORY,
                    data = database.getHistoryDao().dump().map {
                        HistoryBackup(
                            entity = it,
                        )
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
                    data = database.getFavouritesDao().dump().map {
                        FavouriteBackup(
                            entity = it,
                        )
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
                    },
                    serializer = serializer(),
                )

                BackupSection.SOURCES -> output.writeJsonArray(
                    section = BackupSection.SOURCES,
                    data = database.getSourcesDao().dumpEnabled().map { SourceBackup(it) },
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
                    data = database.getScrobblingDao().dumpEnabled().map { ScrobblingBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.STATS -> output.writeJsonArray(
                    section = BackupSection.STATS,
                    data = database.getStatsDao().dumpEnabled().map { StatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_HISTORY -> output.writeJsonArray(
                    section = BackupSection.WORK_HISTORY,
                    data = database.getWorkHistoryDao().dump().map { WorkHistoryBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_FAVOURITES -> output.writeJsonArray(
                    section = BackupSection.WORK_FAVOURITES,
                    data = database.getWorkFavouritesDao().dump().map { WorkFavouriteBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.WORK_STATS -> output.writeJsonArray(
                    section = BackupSection.WORK_STATS,
                    data = database.getWorkStatsDao().dumpEnabled().map { WorkStatisticBackup(it) },
                    serializer = serializer(),
                )

                BackupSection.SAVED_FILTERS -> {
                    val sources = mangaSourcesRepository.getEnabledSources()
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
                    data = database.getEntityGraphDao().dumpBindings().asFlow(),
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

    suspend fun restoreBackup(
        input: ZipInputStream,
        sections: Set<BackupSection>,
        progress: FlowCollector<Progress>?,
        restoreMode: RestoreMode = RestoreMode.MERGE,
    ): RestoreBackupResult {
        val effectiveSections = sections.withImplicitRestoreSections()
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, effectiveSections.size)
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        val archiveSections = linkedSetOf<BackupSection>()
        val restoredSections = linkedSetOf<BackupSection>()
        val entityIdMapping = LinkedHashMap<Long, Long>()
        var backupIndex: BackupIndex? = null
        var restoreContext = resolveRestoreSemanticContext(null)
        if (restoreMode == RestoreMode.SNAPSHOT_REPLACE) {
            clearRestoreTargets(effectiveSections)
        }
        while (entry != null) {
            val section = BackupSection.of(entry)
            if (section != null) {
                archiveSections.add(section)
            }
            if (section in effectiveSections) {
                if (section != null) {
                    restoredSections.add(section)
                }
                result += when (section) {
                    BackupSection.INDEX -> {
                        backupIndex = input.readBackupIndex()
                        restoreContext = resolveRestoreSemanticContext(backupIndex)
                        CompositeResult.EMPTY
                    }
                    BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
                        // Legacy history sections restore the projection snapshot first, then
                        // normalize the record into work-owned history when entity bindings exist.
                        upsertContent(it.manga, restoreContext)
                        val legacy = it.toEntity()
                        getHistoryDao().upsert(legacy)
                        upsertWorkHistoryFromLegacy(legacy)
                    }

                    BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
                        getFavouriteCategoriesDao().upsert(it.toEntity())
                    }

                    BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
                        // Legacy favourites sections restore the projection snapshot first, then
                        // project collection state into work-owned favourites.
                        upsertContent(it.manga, restoreContext)
                        val legacy = it.toEntity()
                        getFavouritesDao().mergeWithTimestamp(legacy)
                        upsertWorkFavouriteFromLegacy(legacy)
                    }

                    BackupSection.SETTINGS -> input.readMap().let {
                        settings.upsertAll(it)
                        CompositeResult.success()
                    }

                    BackupSection.SETTINGS_READER_GRID -> input.readMap().let {
                        tapGridSettings.upsertAll(it)
                        CompositeResult.success()
                    }

                    BackupSection.BOOKMARKS -> input.readJsonArray<BookmarkBackup>(serializer()).restoreToDb {
                        // Bookmarks remain projection-anchored content data. Entity/work state
                        // comes from graph/work sections and is not embedded here.
                        upsertContent(it.manga, restoreContext)
                        getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                    }

                    BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
                        getSourcesDao().upsert(it.toEntity())
                    }

                    BackupSection.EXTENSION_REPOS -> input.readJsonArray<ExtensionRepoBackup>(serializer()).restoreToDb {
                        getExternalExtensionRepoDao().upsert(it.toEntity())
                    }

                    BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb {
                        upsertScrobbling(it.toEntity())
                    }

                    BackupSection.STATS -> input.readJsonArray<StatisticBackup>(serializer()).restoreToDb {
                        val legacy = it.toEntity()
                        getStatsDao().upsert(legacy)
                        upsertWorkStatsFromLegacy(legacy)
                    }

                    BackupSection.WORK_HISTORY -> input.readJsonArray<WorkHistoryBackup>(serializer()).restoreToDb {
                        getWorkHistoryDao().upsert(it.toEntity())
                    }

                    BackupSection.WORK_FAVOURITES -> input.readJsonArray<WorkFavouriteBackup>(serializer()).restoreToDb {
                        getWorkFavouritesDao().upsert(it.toEntity())
                    }

                    BackupSection.WORK_STATS -> input.readJsonArray<WorkStatisticBackup>(serializer()).restoreToDb {
                        getWorkStatsDao().upsert(it.toEntity())
                    }

                    BackupSection.SAVED_FILTERS -> input.readJsonArray<PersistableFilter>(serializer())
                        .restoreWithoutTransaction {
                            savedFiltersRepository.save(it)
                        }

                    BackupSection.AUTH -> input.readMap().let {
                        restoreAuth(it)
                        CompositeResult.success()
                    }

                    BackupSection.ENTITY_GRAPH_ENTITIES -> input.readJsonArray<EntityRecord>(serializer()).restoreToDb {
                        restoreEntityRecord(it, entityIdMapping)
                    }

                    BackupSection.ENTITY_GRAPH_BINDINGS -> input.readJsonArray<EntityBindingRecord>(serializer()).restoreToDb {
                        restoreEntityBinding(it, entityIdMapping, restoreContext)
                    }

                    BackupSection.ENTITY_GRAPH_RELATIONS -> input.readJsonArray<RelationRecord>(serializer()).restoreToDb {
                        restoreEntityRelation(it, entityIdMapping)
                    }

                    BackupSection.ENTITY_GRAPH_PREFS -> input.readJsonArray<EntityPrefsRecord>(serializer()).restoreToDb {
                        restoreEntityPrefs(it, entityIdMapping, restoreContext)
                    }

                    null -> CompositeResult.EMPTY // skip unknown entries
                }
                progress?.emit(commonProgress)
                commonProgress++
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        val legacyJarReposImported = restoreLegacyJarRepositoriesIfNeeded(effectiveSections, archiveSections, restoredSections)
        normalizeRestoredWorkState(
            requestedSections = effectiveSections,
            archiveSections = archiveSections,
            restoreContext = restoreContext,
        )
        progress?.emit(commonProgress)
        return RestoreBackupResult(
            result = result,
            legacyJarReposImported = legacyJarReposImported,
            backupIndex = backupIndex,
        )
    }

    fun resolveRestoreSemanticContext(backupIndex: BackupIndex?): RestoreSemanticContext {
        return RestoreSemanticContext(
            transportGeneration = backupIndex?.transportGeneration ?: BackupIndex.WRITER_GENERATION_V1,
            semanticSchemaVersion = backupIndex?.semanticSchemaVersion ?: 1,
        )
    }

    private suspend fun normalizeRestoredWorkState(
        requestedSections: Set<BackupSection>,
        archiveSections: Set<BackupSection>,
        restoreContext: RestoreSemanticContext,
    ) {
        val hasAuthoritativeWorkHistory = restoreContext.isAuthoritativeWorkSchema &&
            BackupSection.WORK_HISTORY in archiveSections
        val hasAuthoritativeWorkFavourites = restoreContext.isAuthoritativeWorkSchema &&
            BackupSection.WORK_FAVOURITES in archiveSections
        val hasAuthoritativeWorkStats = restoreContext.isAuthoritativeWorkSchema &&
            BackupSection.WORK_STATS in archiveSections

        database.withTransaction {
            if (BackupSection.SCROBBLING in requestedSections) {
                database.normalizeRestoredScrobblingState()
            }
            if (!hasAuthoritativeWorkHistory &&
                (BackupSection.HISTORY in requestedSections || BackupSection.WORK_HISTORY in requestedSections)
            ) {
                database.getHistoryDao().findAllIds().forEach { mangaId ->
                    database.getHistoryDao().find(mangaId)?.let { history ->
                        database.upsertWorkHistoryFromLegacy(history)
                    }
                }
            }
            if (!hasAuthoritativeWorkFavourites &&
                (BackupSection.FAVOURITES in requestedSections || BackupSection.WORK_FAVOURITES in requestedSections)
            ) {
                database.getFavouritesDao().findAll().forEach { favouriteContent ->
                    favouriteContent.favourite.let { favourite ->
                        database.upsertWorkFavouriteFromLegacy(favourite)
                    }
                }
            }
            if (!hasAuthoritativeWorkStats &&
                (BackupSection.STATS in requestedSections || BackupSection.WORK_STATS in requestedSections)
            ) {
                database.getStatsDao().dumpEnabled().collect { stats ->
                    database.upsertWorkStatsFromLegacy(stats)
                }
            }
        }
    }

    private suspend fun clearRestoreTargets(sections: Set<BackupSection>) {
        database.withTransaction {
            if (BackupSection.HISTORY in sections) {
                database.getWorkHistoryDao().clear()
                database.getHistoryDao().clear()
                database.getTracksDao().clear()
                database.getTrackLogsDao().clear()
            }
            if (BackupSection.FAVOURITES in sections) {
                database.getWorkFavouritesDao().deleteAll()
                database.getFavouritesDao().clear()
                database.getFavouriteCategoriesDao().deleteAll()
                database.getTracksDao().clear()
                database.getTrackLogsDao().clear()
            }
            if (BackupSection.BOOKMARKS in sections) {
                database.getBookmarksDao().deleteAll()
            }
            if (BackupSection.SCROBBLING in sections) {
                database.getScrobblingDao().deleteAll()
            }
            if (BackupSection.STATS in sections) {
                database.getWorkStatsDao().clear()
                database.getStatsDao().clear()
            }
            if (BackupSection.SOURCES in sections) {
                database.getSourcesDao().disableAllSources()
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
    }

    private fun Set<BackupSection>.withImplicitRestoreSections(): Set<BackupSection> {
        val expanded = LinkedHashSet(this)
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
            BackupSection.WORK_HISTORY in expanded ||
            BackupSection.WORK_FAVOURITES in expanded ||
            BackupSection.WORK_STATS in expanded
        ) {
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
        getMangaDao().upsert(manga.toEntity(), tags)
        if (restoreContext.isLegacySemanticSchema && manga.hasLegacyPrefsPayload()) {
            // Legacy embedded prefs are import hints only.
            // Do not rebuild work-owned state shadows in projection prefs during restore.
            // Current authoritative owner state must come from ENTITY_GRAPH_PREFS / WORK_* sections
            // or subsequent normalization, not from embedded content payloads.
        }
    }

    private suspend fun MangaDatabase.findEntityIdByLocalMangaId(mangaId: Long): Long? {
        return getEntityGraphDao().findWorkEntityIdByLocalMangaId(mangaId)
    }

    private suspend fun MangaDatabase.upsertWorkHistoryFromLegacy(
        history: org.skepsun.kototoro.history.data.HistoryEntity,
    ) {
        val entityId = findEntityIdByLocalMangaId(history.mangaId) ?: return
        val anchorMangaId = resolveExistingLocalAnchorForEntity(entityId) ?: history.mangaId
        getWorkHistoryDao().upsert(
            WorkHistoryEntity(
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
        )
    }

    private suspend fun MangaDatabase.upsertWorkFavouriteFromLegacy(
        favourite: org.skepsun.kototoro.favourites.data.FavouriteEntity,
    ) {
        val entityId = findEntityIdByLocalMangaId(favourite.mangaId) ?: return
        getWorkFavouritesDao().upsert(
            WorkFavouriteEntity(
                entityId = entityId,
                categoryId = favourite.categoryId,
                sortKey = favourite.sortKey,
                isPinned = favourite.isPinned,
                createdAt = favourite.createdAt,
                deletedAt = favourite.deletedAt,
                updatedAt = favourite.updatedAt,
            ),
        )
    }

    private suspend fun MangaDatabase.upsertWorkStatsFromLegacy(
        stats: org.skepsun.kototoro.stats.data.StatsEntity,
    ) {
        val entityId = findEntityIdByLocalMangaId(stats.mangaId) ?: return
        val anchorMangaId = resolveExistingLocalAnchorForEntity(entityId) ?: stats.mangaId
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
            val entityId = entity.entityId ?: findEntityIdByLocalMangaId(entity.mangaId)
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
        val entityId = findEntityIdByLocalMangaId(mangaId) ?: return null
        return resolveExistingLocalAnchorForEntity(entityId) ?: mangaId
    }

    private suspend fun MangaDatabase.resolveExistingLocalAnchorForEntity(entityId: Long): Long? {
        getEntityGraphDao().findEntityPrefs(entityId)?.preferredLocalMangaId
            ?.takeIf { preferredId -> getMangaDao().contains(preferredId) }
            ?.let { return it }
        return getEntityGraphDao().findActiveBindingsByEntity(entityId)
            .firstNotNullOfOrNull { binding ->
                when (binding.source) {
                    "local_manga", "0" -> binding.externalId.toLongOrNull()
                        ?.takeIf { localId -> getMangaDao().contains(localId) }
                    else -> null
                }
            }
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

    private suspend fun MangaDatabase.restoreEntityRecord(
        remote: EntityRecord,
        entityIdMapping: MutableMap<Long, Long>,
    ) {
        val dao = getEntityGraphDao()
        val trimmedName = remote.primaryName.trim()
        val computedHash = computeNameHash(trimmedName)
        val existing = dao.findEntity(remote.id)
            ?.takeIf { it.type == remote.type }
            ?: dao.findEntityByTypeAndPrimaryName(remote.type, trimmedName)
        val localId = if (existing == null) {
            val newRecord = EntityRecord(
                type = remote.type,
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
                // Hash collision — another entity already has this name hash. Try to merge.
                dao.findEntityByTypeAndNameHash(remote.type, computedHash)?.id
                    ?: dao.insertEntity(
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
        entityIdMapping: Map<Long, Long>,
        restoreContext: RestoreSemanticContext,
    ) {
        val localEntityId = entityIdMapping[remote.entityId]
        if (localEntityId == null) {
            Log.w(TAG, "restore: skip entity_binding for unmapped entityId=${remote.entityId}, source=${remote.source}, externalId=${remote.externalId}")
            return
        }
        val dao = getEntityGraphDao()
        val existing = dao.findBinding(remote.source, remote.externalId)
        if (existing != null && existing.shouldKeepOverRestored(remote)) {
            Log.d(TAG, "restore: keep local entity_binding source=${remote.source}, externalId=${remote.externalId}")
            return
        }
        dao.upsertBinding(remote.normalizedForRestore(localEntityId, restoreContext))
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

    private suspend inline fun <T> Sequence<T>.restoreToDb(crossinline block: suspend MangaDatabase.(T) -> Unit): CompositeResult {
        return fold(CompositeResult.EMPTY) { result, item ->
            result + runCatchingCancellable {
                database.withTransaction {
                    database.block(item)
                }
            }
        }
    }

    private suspend inline fun <T> Sequence<T>.restoreWithoutTransaction(crossinline block: suspend (T) -> Unit): CompositeResult {
        return fold(CompositeResult.EMPTY) { result, item ->
            result + runCatchingCancellable {
                block(item)
            }
        }
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
