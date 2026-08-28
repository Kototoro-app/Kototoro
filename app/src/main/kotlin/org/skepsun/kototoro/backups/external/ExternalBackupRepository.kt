package org.skepsun.kototoro.backups.external

import android.content.Context
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.ProjectionIdentityKeys
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.entitygraph.data.EntityBindingRecord
import org.skepsun.kototoro.entitygraph.data.EntityRecord
import org.skepsun.kototoro.entitygraph.data.computeNameHash
import org.skepsun.kototoro.entitygraph.domain.EntityBindingCreatedBy
import org.skepsun.kototoro.entitygraph.domain.EntityBindingSourceKind
import org.skepsun.kototoro.entitygraph.domain.EntityBindingState
import org.skepsun.kototoro.entitygraph.domain.EntityType
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.aniyomi.AniyomiExtensionManager
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.parsers.model.ContentType
import javax.inject.Inject

@Reusable
class ExternalBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val mihonExtensionManager: MihonExtensionManager,
    private val aniyomiExtensionManager: AniyomiExtensionManager,
) {

    suspend fun import(payload: ExternalBackupPayload): ExternalBackupImportSummary {
        if (payload.records.isEmpty()) return ExternalBackupImportSummary(0, 0)
        return database.withTransaction {
            val dao = database.getEntityGraphDao()
            val sourceMatcher = SourceMatcher(context, database, mihonExtensionManager, aniyomiExtensionManager)
            val externalCategories = ensureImportedCategories(payload.favoriteCategories)
            val defaultCategoryId = ensureDefaultCategoryId(externalCategories.values)
            val now = System.currentTimeMillis()

            // Pass 1 (memory only): resolve sources and merge duplicate manga ids.
            val pendingById = LinkedHashMap<Long, BulkImportEntry>()
            val failedRecords = ArrayList<ExternalBackupFailedRecord>()
            val missingSourceNames = LinkedHashSet<String>()
            val uninstalledSources = LinkedHashMap<String, UninstalledSourceAggregator>()
            for (record in payload.records) {
                val resolvedRecord = when (val result = sourceMatcher.resolve(record)) {
                    is SourceResolveResult.Resolved -> result.record
                    is SourceResolveResult.MissingFixedSource -> {
                        failedRecords += record.toFailedRecord(result.expectedSourceNames)
                        missingSourceNames += result.expectedSourceNames
                        continue
                    }
                    SourceResolveResult.Unmatched -> {
                        failedRecords += record.toFailedRecord()
                        continue
                    }
                    is SourceResolveResult.UnmatchedExtensionSource -> {
                        // The extension for this source is not installed: import anyway with
                        // the verbatim key (same as before), but track it for the summary and
                        // register a source_origins row so the UI can label it later.
                        uninstalledSources.getOrPut(result.record.sourceName) {
                            UninstalledSourceAggregator(
                                contentType = result.record.contentType.name,
                                sourceId = result.record.sourceName
                                    .substringAfter('_', "").takeIf { it.toLongOrNull() != null },
                            )
                        }.also { aggregator ->
                            aggregator.recordCount++
                            if (aggregator.displayName == null) {
                                aggregator.displayName = result.record.sourceDisplayName
                            }
                        }
                        result.record
                    }
                }
                val mangaId = generateContentId(resolvedRecord)
                val existing = pendingById[mangaId]
                if (existing == null) {
                    pendingById[mangaId] = BulkImportEntry(resolvedRecord, mangaId)
                } else {
                    existing.mergeFrom(resolvedRecord)
                }
            }
            val pending = pendingById.values.toList()

            // Pass 2 (memory + chunked batch queries): anchor each record to a WORK entity.
            // Exact-name matches attach to the existing entity; everything else becomes a
            // provisional entity (binding createdBy=IMPORT) merged later by phase 2.
            // Pass 2 (memory + chunked batch queries): anchor each record to a WORK entity.
            // Local-binding owners take precedence; exact-name matches attach to the
            // existing entity; everything else becomes a provisional entity (binding
            // createdBy=IMPORT) merged later by phase 2.
            val localBindingByMangaId = HashMap<Long, Long>()
            pending.map { it.mangaId.toString() }.distinct().chunked(MAX_BATCH_QUERY_PARAMS).forEach { chunk ->
                dao.findActiveBindingsBySources(
                    sources = listOf("local_manga", "0"),
                    externalIds = chunk,
                ).forEach { binding ->
                    binding.externalId.toLongOrNull()?.let { mangaId ->
                        localBindingByMangaId.putIfAbsent(mangaId, binding.entityId)
                    }
                }
            }
            val nameHashes = pending.map { computeNameHash(it.title) }.distinct()
            val existingByHash = LinkedHashMap<Long, List<EntityRecord>>()
            nameHashes.chunked(MAX_BATCH_QUERY_PARAMS).forEach { chunk ->
                dao.findEntitiesByTypeAndNameHashes(EntityType.WORK.name, chunk)
                    .filter { it.type == EntityType.WORK.name }
                    .groupBy { it.nameHash }
                    .forEach { (hash, records) -> existingByHash.merge(hash, records) { old, new -> old + new } }
            }
            val newEntities = planWorkEntityAssignment(
                entries = pending,
                existingEntitiesByHash = existingByHash,
                now = now,
                localBindingByMangaId = localBindingByMangaId,
            )

            // Pass 3 (bulk writes, single transaction):
            var favorites = 0
            var historyRows = 0
            val insertedIds = dao.insertEntities(newEntities)
            var insertIndex = 0
            val tagsById = LinkedHashMap<Long, TagEntity>()
            val favourites = ArrayList<WorkFavouriteEntity>()
            val histories = ArrayList<WorkHistoryEntity>()
            val bindings = ArrayList<EntityBindingRecord>(pending.size * 2)
            for (entry in pending) {
                if (entry.isNewEntity) {
                    entry.entityId = insertedIds[insertIndex++]
                }
                entry.tags.forEach { tag -> tagsById.putIfAbsent(tag.id, tag) }
            }
            database.getTagsDao().upsert(tagsById.values.toList())
            for (entry in pending) {
                database.getMangaDao().upsert(entry.mangaEntity, entry.tags)
            }
            for (entry in pending) {
                val entityId = entry.entityId
                val resolved = entry.record
                if (resolved.isFavorite) {
                    val favoriteTimestamp = resolved.favoriteTimestamp ?: now
                    val targetCategoryIds = resolved.favoriteCategoryOrders
                        .mapNotNull(externalCategories::get)
                        .ifEmpty { listOf(defaultCategoryId.toLong()) }
                    targetCategoryIds.distinct().forEach { categoryId ->
                        favourites += WorkFavouriteEntity(
                            entityId = entityId,
                            categoryId = categoryId,
                            anchorMangaId = entry.mangaId,
                            createdAt = favoriteTimestamp,
                            sortKey = 0,
                            deletedAt = 0L,
                            isPinned = false,
                            updatedAt = favoriteTimestamp,
                        )
                        favorites++
                    }
                }
                if (resolved.historyTimestamp != null && !resolved.historyChapterUrl.isNullOrBlank()) {
                    val chapterId = generateChapterId(resolved, resolved.historyChapterUrl)
                    val percent = resolved.progressPercent?.coerceIn(PROGRESS_NONE, 1f) ?: PROGRESS_NONE
                    histories += WorkHistoryEntity(
                        entityId = entityId,
                        anchorMangaId = entry.mangaId,
                        createdAt = resolved.historyTimestamp,
                        updatedAt = resolved.historyTimestamp,
                        chapterId = chapterId,
                        page = 0,
                        scroll = 0f,
                        percent = percent,
                        deletedAt = 0L,
                        chaptersCount = resolved.chaptersCount.coerceAtLeast(0),
                        parentChapterId = null,
                    )
                    historyRows++
                }
                bindings += EntityBindingRecord(
                    entityId = entityId,
                    source = "local_manga",
                    externalId = entry.mangaId.toString(),
                    confidence = 1f,
                    isPrimary = entry.isNewEntity,
                    sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
                    state = EntityBindingState.CONFIRMED.name,
                    createdBy = EntityBindingCreatedBy.IMPORT.name,
                    updatedAt = now,
                )
                val projectionKey = ProjectionIdentityKeys.bindingKey(
                    url = resolved.url,
                    publicUrl = resolved.publicUrl,
                )
                if (projectionKey != null) {
                    bindings += EntityBindingRecord(
                        entityId = entityId,
                        source = resolved.sourceName,
                        externalId = projectionKey,
                        confidence = 1f,
                        isPrimary = false,
                        sourceKind = EntityBindingSourceKind.READING_SOURCE.name,
                        state = EntityBindingState.CONFIRMED.name,
                        createdBy = EntityBindingCreatedBy.IMPORT.name,
                        updatedAt = now,
                    )
                }
            }
            database.getWorkFavouritesDao().upsert(favourites)
            database.getWorkHistoryDao().upsert(histories)
            dao.upsertBindings(bindings)

            registerUninstalledSourceOrigins(uninstalledSources, now)

            ExternalBackupImportSummary(
                favoritesImported = favorites,
                historyImported = historyRows,
                failedCount = failedRecords.size,
                failedTitles = failedRecords.map { it.title }.distinct(),
                failedRecords = failedRecords.distinctBy { it.title to it.sourceCandidates to it.expectedSourceNames },
                missingSourceNames = missingSourceNames.toList(),
                uninstalledSources = uninstalledSources.map { (key, aggregator) ->
                    ExternalBackupUninstalledSource(
                        sourceKey = key,
                        displayName = aggregator.displayName,
                        recordCount = aggregator.recordCount,
                    )
                },
            )
        }
    }

    private class UninstalledSourceAggregator(
        val contentType: String,
        val sourceId: String?,
        var displayName: String? = null,
        var recordCount: Int = 0,
    )

    /**
     * Persists a `source_origins` row for every imported source whose extension is not
     * installed, so the UI can show the human-readable name from the backup instead of a
     * generic "Mihon"/"Aniyomi" label. Existing richer rows (e.g. restored backups with a
     * repository locator) are preserved; only missing display names are filled in.
     */
    private suspend fun registerUninstalledSourceOrigins(
        uninstalledSources: Map<String, UninstalledSourceAggregator>,
        now: Long,
    ) {
        if (uninstalledSources.isEmpty()) return
        val dao = database.getSourceOriginsDao()
        for ((sourceKey, aggregator) in uninstalledSources) {
            val existing = dao.getByKey(sourceKey)
            val displayName = aggregator.displayName ?: existing?.displayName
            dao.upsert(
                SourceOriginEntity(
                    sourceKey = sourceKey,
                    kind = if (sourceKey.startsWith("ANIYOMI_")) "ANIYOMI" else "MIHON",
                    displayName = displayName,
                    contentType = aggregator.contentType,
                    sourceId = aggregator.sourceId ?: existing?.sourceId,
                    repositoryUrl = existing?.repositoryUrl,
                    repositoryName = existing?.repositoryName,
                    locator = existing?.locator,
                    versionName = existing?.versionName,
                    versionCode = existing?.versionCode,
                    lastSeenAt = now,
                    updatedAt = now,
                ),
            )
        }
    }

    private fun ExternalBackupContentRecord.toFailedRecord(
        expectedSourceNames: List<String> = emptyList(),
    ): ExternalBackupFailedRecord {
        return ExternalBackupFailedRecord(
            title = title.ifBlank { url },
            sourceCandidates = (sourceCandidates + sourceName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct(),
            expectedSourceNames = expectedSourceNames.distinct(),
        )
    }

    private suspend fun ensureImportedCategories(
        categories: List<ExternalBackupFavoriteCategoryRecord>,
    ): Map<Long, Long> {
        if (categories.isEmpty()) return emptyMap()
        val categoriesDao = database.getFavouriteCategoriesDao()
        val existingByTitle = categoriesDao.findAll().associateBy { it.title }
        val importedIds = LinkedHashMap<Long, Long>(categories.size)
        var nextSortKey = categoriesDao.getNextSortKey()
        categories.forEach { category ->
            val localCategoryId = existingByTitle[category.name]?.categoryId?.toLong()
                ?: categoriesDao.insert(
                    FavouriteCategoryEntity(
                        categoryId = 0,
                        createdAt = System.currentTimeMillis(),
                        sortKey = nextSortKey++,
                        title = category.name,
                        order = ListSortOrder.NEWEST.name,
                        track = false,
                        isVisibleInLibrary = true,
                        deletedAt = 0L,
                    ),
                )
            ExternalBackupCategoryMapper.putImportedCategoryKeys(importedIds, category, localCategoryId)
        }
        return importedIds
    }

    private suspend fun ensureDefaultCategoryId(existingImportedCategoryIds: Collection<Long>): Int {
        val categories = database.getFavouriteCategoriesDao().findAll()
        categories.firstOrNull { it.categoryId.toLong() !in existingImportedCategoryIds }?.let { return it.categoryId }
        val now = System.currentTimeMillis()
        val category = FavouriteCategoryEntity(
            categoryId = 0,
            createdAt = now,
            sortKey = 0,
            title = context.getString(R.string.favourites),
            order = ListSortOrder.NEWEST.name,
            track = false,
            isVisibleInLibrary = true,
            deletedAt = 0L,
        )
        val insertedId = database.getFavouriteCategoriesDao().insert(category)
        return insertedId.toInt()
    }

    private fun generateContentId(record: ExternalBackupContentRecord): Long {
        return "${record.sourceName}|manga|${record.url}".hashCode().toLong() and Long.MAX_VALUE
    }

    private fun generateChapterId(record: ExternalBackupContentRecord, chapterUrl: String): Long {
        val type = when (record.app.family) {
            ExternalBackupFamily.MANGA -> "chapter"
            ExternalBackupFamily.ANIME -> if (record.contentType == ContentType.VIDEO) "episode" else "chapter"
        }
        return "${record.sourceName}|$type|$chapterUrl".hashCode().toLong() and Long.MAX_VALUE
    }

    private class SourceMatcher(
        private val context: Context,
        private val database: MangaDatabase,
        private val mihonExtensionManager: MihonExtensionManager,
        private val aniyomiExtensionManager: AniyomiExtensionManager,
    ) {
        private var cachedCandidates: List<SourceCandidate>? = null
        private var cachedAniyomiSources: List<SourceCandidate>? = null

        suspend fun resolve(record: ExternalBackupContentRecord): SourceResolveResult {
            if (record.sourceName.startsWith("MIHON_") || record.sourceName.startsWith("ANIYOMI_")) {
                // Mihon-family forks (TachiyomiSY, Komikku, Neko, ...) bundle some sources
                // in-app with ids that never match a Mihon extension. Remap those to the
                // native Kotatsu parser source when available; everything else stays
                // verbatim as MIHON_<id> / ANIYOMI_<id>.
                resolveMihonFamilySource(record)?.let { return it }
                return when {
                    isInstalledExtensionSource(record.sourceName) ->
                        SourceResolveResult.Resolved(record)

                    else -> SourceResolveResult.UnmatchedExtensionSource(record)
                }
            }
            if (record.app != ExternalBackupApp.VENERA) {
                return SourceResolveResult.Resolved(record)
            }
            val names = (record.sourceCandidates + record.sourceName)
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map(::normalizeSourceName)
                .toSet()
            if (names.isEmpty()) {
                return SourceResolveResult.Unmatched
            }
            val candidates = candidates()
            resolveFixedMapping(names, candidates)?.let { result ->
                return when (result) {
                    is FixedSourceResolveResult.Found -> SourceResolveResult.Resolved(
                        record.copy(sourceName = result.candidate.sourceName),
                    )
                    is FixedSourceResolveResult.Missing -> SourceResolveResult.MissingFixedSource(
                        result.expectedSourceNames,
                    )
                }
            }
            val matches = candidates.filter { it.normalizedName in names }
            val native = matches.filter { it.kind == SourceKind.NATIVE }.distinctBy { it.sourceName }
            val json = matches.filter { it.kind == SourceKind.JSON }.distinctBy { it.sourceName }
            val resolved = when {
                native.size == 1 -> native.first()
                native.size > 1 -> null
                json.size == 1 -> json.first()
                else -> null
            } ?: return SourceResolveResult.Unmatched
            return SourceResolveResult.Resolved(record.copy(sourceName = resolved.sourceName))
        }

        /**
         * Remaps a `MIHON_<id>` source that comes from a Mihon-family fork built-in source
         * to the matching native Kotatsu parser source.
         *
         * Returns `null` (meaning "keep the raw MIHON_<id> key") when there is nothing to
         * remap or when the native source is not currently available — the record must
         * still import instead of failing.
         */
        private suspend fun resolveMihonFamilySource(
            record: ExternalBackupContentRecord,
        ): SourceResolveResult? {
            val sourceId = record.sourceName.removePrefix("MIHON_").toLongOrNull()
                ?: return null
            val natives = candidates()

            // 1) Authoritative fork-built-in id table (TachiyomiSY, Komikku).
            MihonForkBuiltinSources.nativeSourceNameForId(sourceId)?.let { nativeName ->
                resolveNativeOrNull(record, nativeName, natives)?.let { return it }
            }

            // 2) Never disturb a source that already resolves to an installed Mihon
            //    extension — those ids are ecosystem-stable and already linked.
            if (natives.any { it.sourceName == record.sourceName && it.kind == SourceKind.MIHON }) {
                return null
            }

            // 3) URL-shape inference for built-ins that are not enumerated (e.g. Neko's
            //    MangaDex ids are MD5-derived per language and kept out of the table).
            val inferredName = MihonForkBuiltinSources.nativeSourceNameForMihonUrl(
                url = record.url.ifBlank { record.publicUrl },
            ) ?: return null
            return resolveNativeOrNull(record, inferredName, natives)
        }

        private fun resolveNativeOrNull(
            record: ExternalBackupContentRecord,
            nativeName: String,
            natives: List<SourceCandidate>,
        ): SourceResolveResult? {
            val matches = natives
                .filter { it.sourceName == nativeName && it.kind == SourceKind.NATIVE }
                .distinctBy { it.sourceName }
            if (matches.size != 1) {
                return null
            }
            return SourceResolveResult.Resolved(
                record.copy(sourceName = matches.first().sourceName, sourceDisplayName = null),
            )
        }

        private suspend fun isInstalledExtensionSource(sourceName: String): Boolean {
            return when {
                sourceName.startsWith("ANIYOMI_") ->
                    aniyomiSources().any { it.sourceName == sourceName }

                else -> candidates().any { it.sourceName == sourceName && it.kind == SourceKind.MIHON }
            }
        }

        private suspend fun aniyomiSources(): List<SourceCandidate> {
            cachedAniyomiSources?.let { return it }
            val sources = aniyomiExtensionManager.getAniyomiAnimeSources()
                .flatMap { source ->
                    listOf(
                        SourceCandidate(source.name, normalizeSourceName(source.name), SourceKind.MIHON),
                        SourceCandidate(source.name, normalizeSourceName(source.displayName), SourceKind.MIHON),
                    )
                }
                .filter { it.normalizedName.isNotEmpty() }
            cachedAniyomiSources = sources
            return sources
        }

        private fun resolveFixedMapping(
            normalizedNames: Set<String>,
            candidates: List<SourceCandidate>,
        ): FixedSourceResolveResult? {
            val mappedSources = normalizedNames
                .flatMap { VENERA_SOURCE_MAP[it].orEmpty() }
                .distinct()
            if (mappedSources.isEmpty()) {
                return null
            }
            for (source in mappedSources) {
                val matches = candidates
                    .filter { it.sourceName == source.sourceName && it.kind == source.kind }
                    .distinctBy { it.sourceName }
                if (matches.size == 1) {
                    return FixedSourceResolveResult.Found(matches.first())
                }
            }
            return FixedSourceResolveResult.Missing(mappedSources.map { it.displayName })
        }

        private suspend fun candidates(): List<SourceCandidate> {
            cachedCandidates?.let { return it }
            val native = buildList {
                GlobalExtensionManager.contentSources.value.forEach { source ->
                    add(SourceCandidate(source.name, normalizeSourceName(source.name), SourceKind.NATIVE))
                    add(SourceCandidate(source.name, normalizeSourceName(source.getTitle(context)), SourceKind.NATIVE))
                }
                GlobalExtensionManager.mangaSources.value.forEach { source ->
                    val wrapped = org.skepsun.kototoro.core.parser.kotatsu.KotatsuParserSource(source)
                    add(SourceCandidate(wrapped.name, normalizeSourceName(wrapped.name), SourceKind.NATIVE))
                    add(SourceCandidate(wrapped.name, normalizeSourceName(wrapped.title), SourceKind.NATIVE))
                }
            }
            val mihon = mihonExtensionManager.getMihonMangaSources()
                .flatMap { source ->
                    listOf(
                        SourceCandidate(source.name, normalizeSourceName(source.name), SourceKind.MIHON),
                        SourceCandidate(source.name, normalizeSourceName(source.displayName), SourceKind.MIHON),
                    )
                }
            val json = database.getJsonSourceDao().observeEnabledSummaries()
                .first()
                .flatMap { source ->
                    listOf(
                        SourceCandidate(source.id, normalizeSourceName(source.id), SourceKind.JSON),
                        SourceCandidate(source.id, normalizeSourceName(source.name), SourceKind.JSON),
                    )
                }
            return (native + mihon + json)
                .filter { it.normalizedName.isNotEmpty() }
                .also { cachedCandidates = it }
        }
    }

    private enum class SourceKind {
        NATIVE,
        MIHON,
        JSON,
    }

    private data class FixedSourceTarget(
        val sourceName: String,
        val kind: SourceKind,
        val displayName: String = sourceName,
    )

    private data class SourceCandidate(
        val sourceName: String,
        val normalizedName: String,
        val kind: SourceKind,
    )

    private sealed interface SourceResolveResult {
        data class Resolved(val record: ExternalBackupContentRecord) : SourceResolveResult
        data class MissingFixedSource(val expectedSourceNames: List<String>) : SourceResolveResult
        data object Unmatched : SourceResolveResult

        /**
         * A `MIHON_<id>` / `ANIYOMI_<id>` source whose extension is not installed and which
         * could not be remapped to a native parser source. The record still imports with the
         * verbatim key; the import reports it and registers a `source_origins` row.
         */
        data class UnmatchedExtensionSource(val record: ExternalBackupContentRecord) : SourceResolveResult
    }

    private sealed interface FixedSourceResolveResult {
        data class Found(val candidate: SourceCandidate) : FixedSourceResolveResult
        data class Missing(val expectedSourceNames: List<String>) : FixedSourceResolveResult
    }

    private companion object {
        private const val MAX_BATCH_QUERY_PARAMS = 500
        private val SOURCE_NORMALIZE_REGEX = Regex("[\\s\\p{Punct}_\\-]+")
        private val VENERA_SOURCE_MAP = buildVeneraSourceMap()

        private fun normalizeSourceName(value: String): String {
            return value.lowercase()
                .replace(SOURCE_NORMALIZE_REGEX, "")
        }

        private const val COPYMANGA_COPY20_SOURCE = "MIHON_6696312508930833206"

        private fun buildVeneraSourceMap(): Map<String, List<FixedSourceTarget>> {
            val entries = listOf(
                venera(
                    aliases = listOf("copy_manga", "拷贝漫画"),
                    targets = listOf(FixedSourceTarget(COPYMANGA_COPY20_SOURCE, SourceKind.MIHON, "CopyManga (CopyManga Copy20)")),
                ),
                venera("Komiic", "Komiic", "KOMIIC"),
                venera("baozi", "包子漫画", "BAOZIMH"),
                venera("picacg", "Picacg", "哔咔漫画", "PICACG"),
                venera("nhentai", "nhentai", "NHENTAI"),
                venera("wnacg", "紳士漫畫", "WNACG"),
                venera("ehentai", "e-hentai", "ehentai", "EXHENTAI"),
                venera("jm", "禁漫天堂", "JMCOMIC"),
                venera("manga_dex", "MangaDex", "MANGADEX"),
                venera("shonen_jump_plus", "少年ジャンプ＋", "SHONEN_JUMP_PLUS"),
                venera("hitomi", "hitomi.la", "HITOMILA"),
                venera("comick", "comick", "COMICK", "COMICK_FUN"),
                venera("ykmh", "优酷漫画", "YKMH"),
                venera("zaimanhua", "再漫画", "ZAIMANHUA"),
                venera("ManHuaGui", "漫画柜", "MANHUAGUI"),
                venera("lanraragi", "Lanraragi", "LANRARAGI"),
                venera("comic_walker", "カドコミ", "COMIC_WALKER"),
                venera("mh1234", "漫画1234", "MH1234"),
                venera("ccc", "CCC追漫台", "CCC_"),
                venera("goda", "GoDa漫画", "GODA"),
                venera("mh18", "18漫画", "MH18"),
                venera("mxs", "漫小肆", "MXS_"),
                venera("hcomic", "H-Comic", "HCOMIC"),
                venera("hot_manga", "热辣漫画", "HOTCOMICS"),
                venera("baihehui", "百合会", "BAIHEHUI"),
            )
            return buildMap {
                for ((aliases, sourceNames) in entries) {
                    aliases.forEach { alias ->
                        put(normalizeSourceName(alias), sourceNames)
                    }
                }
            }
        }

        private fun venera(vararg values: String): Pair<List<String>, List<FixedSourceTarget>> {
            val sourceNames = values.filter { it.uppercase() == it && it.any(Char::isLetter) }
            val aliases = values.filterNot { it in sourceNames }
            return venera(
                aliases = aliases,
                targets = sourceNames.map { FixedSourceTarget(it, SourceKind.NATIVE) },
            )
        }

        private fun venera(
            aliases: List<String>,
            targets: List<FixedSourceTarget>,
        ): Pair<List<String>, List<FixedSourceTarget>> {
            return aliases to targets
        }
    }
}

internal object ExternalBackupCategoryMapper {

    fun putImportedCategoryKeys(
        target: MutableMap<Long, Long>,
        category: ExternalBackupFavoriteCategoryRecord,
        localCategoryId: Long,
    ) {
        // Stored order keys must always win: Mihon exports manga.category as the
        // category ORDER (MangaBackupCreator writes categoriesForManga.map { it.order },
        // and MangaRestorer resolves via backupCategories.associateBy { it.order }).
        target[category.order] = localCategoryId
        // Keep the raw DB id mapping as a fallback for previously exported or
        // non-standard backups, but never let it clobber an order key. Otherwise a
        // category whose id happens to equal another category's order silently
        // re-routes that group's members (groups merge and the losing group stays empty).
        if (category.id != category.order && !target.containsKey(category.id)) {
            target[category.id] = localCategoryId
        }
    }
}
