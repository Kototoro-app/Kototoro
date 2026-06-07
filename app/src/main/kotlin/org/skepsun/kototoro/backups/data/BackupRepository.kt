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
import org.skepsun.kototoro.entitygraph.data.decodeStringList
import org.skepsun.kototoro.entitygraph.data.encodeStringList
import org.skepsun.kototoro.entitygraph.data.mergeAliases
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.extensions.repo.ExternalExtensionType
import org.skepsun.kototoro.explore.data.ContentSourcesRepository
import org.skepsun.kototoro.filter.data.PersistableFilter
import org.skepsun.kototoro.filter.data.SavedFiltersRepository
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.reader.domain.ReaderColorFilter
import org.skepsun.kototoro.reader.data.TapGridSettings
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

@Reusable
class BackupRepository @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val database: MangaDatabase,
    private val settings: AppSettings,
    private val tapGridSettings: TapGridSettings,
    private val mangaSourcesRepository: ContentSourcesRepository,
    private val savedFiltersRepository: SavedFiltersRepository,
) {

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
    )

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
                            prefs = database.getPreferencesDao().find(it.manga.id),
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
                            prefs = database.getPreferencesDao().find(it.manga.id),
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
                            prefs = database.getPreferencesDao().find(it.first.manga.id),
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
    ): RestoreBackupResult {
        progress?.emit(Progress.INDETERMINATE)
        var commonProgress = Progress(0, sections.size)
        var entry = input.nextEntry
        var result = CompositeResult.EMPTY
        val archiveSections = linkedSetOf<BackupSection>()
        val restoredSections = linkedSetOf<BackupSection>()
        val entityIdMapping = LinkedHashMap<Long, Long>()
        while (entry != null) {
            val section = BackupSection.of(entry)
            if (section != null) {
                archiveSections.add(section)
            }
            if (section in sections) {
                if (section != null) {
                    restoredSections.add(section)
                }
                result += when (section) {
                    BackupSection.INDEX -> CompositeResult.EMPTY // useless in our case
                    BackupSection.HISTORY -> input.readJsonArray<HistoryBackup>(serializer()).restoreToDb {
                        upsertContent(it.manga)
                        getHistoryDao().upsert(it.toEntity())
                    }

                    BackupSection.CATEGORIES -> input.readJsonArray<CategoryBackup>(serializer()).restoreToDb {
                        getFavouriteCategoriesDao().upsert(it.toEntity())
                    }

                    BackupSection.FAVOURITES -> input.readJsonArray<FavouriteBackup>(serializer()).restoreToDb {
                        upsertContent(it.manga)
                        getFavouritesDao().mergeWithTimestamp(it.toEntity())
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
                        upsertContent(it.manga)
                        getBookmarksDao().upsert(it.bookmarks.map { b -> b.toEntity() })
                    }

                    BackupSection.SOURCES -> input.readJsonArray<SourceBackup>(serializer()).restoreToDb {
                        getSourcesDao().upsert(it.toEntity())
                    }

                    BackupSection.EXTENSION_REPOS -> input.readJsonArray<ExtensionRepoBackup>(serializer()).restoreToDb {
                        getExternalExtensionRepoDao().upsert(it.toEntity())
                    }

                    BackupSection.SCROBBLING -> input.readJsonArray<ScrobblingBackup>(serializer()).restoreToDb {
                        getScrobblingDao().upsert(it.toEntity())
                    }

                    BackupSection.STATS -> input.readJsonArray<StatisticBackup>(serializer()).restoreToDb {
                        getStatsDao().upsert(it.toEntity())
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
                        restoreEntityBinding(it, entityIdMapping)
                    }

                    BackupSection.ENTITY_GRAPH_RELATIONS -> input.readJsonArray<RelationRecord>(serializer()).restoreToDb {
                        restoreEntityRelation(it, entityIdMapping)
                    }

                    BackupSection.ENTITY_GRAPH_PREFS -> input.readJsonArray<EntityPrefsRecord>(serializer()).restoreToDb {
                        restoreEntityPrefs(it, entityIdMapping)
                    }

                    null -> CompositeResult.EMPTY // skip unknown entries
                }
                progress?.emit(commonProgress)
                commonProgress++
            }
            input.closeEntry()
            entry = input.nextEntry
        }
        val legacyJarReposImported = restoreLegacyJarRepositoriesIfNeeded(sections, archiveSections, restoredSections)
        progress?.emit(commonProgress)
        return RestoreBackupResult(
            result = result,
            legacyJarReposImported = legacyJarReposImported,
        )
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

    private suspend fun MangaDatabase.upsertContent(manga: ContentBackup) {
        val tags = manga.tags.map { it.toEntity() }
        getTagsDao().upsert(tags)
        getMangaDao().upsert(manga.toEntity(), tags)
        if (manga.hasPrefsPayload()) {
            val dao = getPreferencesDao()
            val existing = dao.find(manga.id)
            dao.upsert(
                if (existing == null) {
                    newPrefsEntity(manga)
                } else {
                    existing.copy(
                        titleOverride = manga.titleOverride,
                        coverUrlOverride = manga.coverUrlOverride,
                        contentRatingOverride = manga.contentRatingOverride,
                        metadataSourceKind = manga.metadataSourceKind,
                        metadataSourceService = manga.metadataSourceService,
                        metadataSourceRemoteId = manga.metadataSourceRemoteId,
                    )
                },
            )
        }
    }

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
    ) {
        val localEntityId = entityIdMapping[remote.entityId]
        if (localEntityId == null) {
            Log.w(TAG, "restore: skip entity_binding for unmapped entityId=${remote.entityId}, source=${remote.source}, externalId=${remote.externalId}")
            return
        }
        getEntityGraphDao().upsertBinding(
            remote.copy(
                entityId = localEntityId,
                isPrimary = false,
            ),
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
    ) {
        val localEntityId = entityIdMapping[remote.entityId] ?: return
        val dao = getEntityGraphDao()
        val local = dao.findEntityPrefs(localEntityId)
        val candidate = remote.copy(entityId = localEntityId)
        if (local == null || candidate.updatedAt >= local.updatedAt) {
            dao.upsertPrefsRecord(candidate)
        }
    }

    private fun newPrefsEntity(manga: ContentBackup) = MangaPrefsEntity(
        mangaId = manga.id,
        mode = -1,
        cfBrightness = ReaderColorFilter.EMPTY.brightness,
        cfContrast = ReaderColorFilter.EMPTY.contrast,
        cfInvert = ReaderColorFilter.EMPTY.isInverted,
        cfGrayscale = ReaderColorFilter.EMPTY.isGrayscale,
        cfBookEffect = ReaderColorFilter.EMPTY.isBookBackground,
        titleOverride = manga.titleOverride,
        coverUrlOverride = manga.coverUrlOverride,
        contentRatingOverride = manga.contentRatingOverride,
        metadataSourceKind = manga.metadataSourceKind,
        metadataSourceService = manga.metadataSourceService,
        metadataSourceRemoteId = manga.metadataSourceRemoteId,
        readingStatus = null,
        ignoredTrackingSuggestionService = null,
        ignoredTrackingSuggestionRemoteId = null,
    )

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
