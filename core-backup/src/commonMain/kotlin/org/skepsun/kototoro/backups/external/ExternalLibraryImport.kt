package org.skepsun.kototoro.backups.external

import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.SourceOriginEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.parsers.model.ContentType
import kotlin.time.Clock

/** How a source the import can bind records to is provided by the platform. */
enum class ExternalSourceKind {
    /** Built-in or parser plugin sources. */
    NATIVE,

    /** Installed Mihon / Tsundoku extension sources (`MIHON_<id>`, `TSUNDOKU_<id>`). */
    MIHON,

    /** Installed Aniyomi extension sources (`ANIYOMI_<id>`). */
    ANIYOMI,

    /** JSON (TVBox / CMS) sources. */
    JSON,
}

/** One installed source under one of its names (its key or its display title). */
data class ExternalSourceCandidate(val sourceName: String, val name: String, val kind: ExternalSourceKind)

/** The platform's installed sources, consulted once per import. */
fun interface ExternalSourceCatalog {
    suspend fun candidates(): List<ExternalSourceCandidate>
}

/** Runs the whole import in one database transaction (Android: `withTransaction`; others: a writer connection). */
interface ExternalImportTransaction {
    suspend fun <R> run(block: suspend () -> R): R
}

/**
 * Writes another app's backup (Mihon, Aniyomi, Usagi, Venera) into the library: favourites with their categories,
 * reading history, and `source_origins` labels for extensions that are not installed. Identity and source matching
 * follow Android's original rules, so the same backup yields the same rows on every platform.
 */
class ExternalLibraryImport(
    private val database: MangaDatabase,
    private val catalog: ExternalSourceCatalog,
    private val transaction: ExternalImportTransaction,
    /** Title of the category created when the library has none (localized by the platform). */
    private val defaultCategoryTitle: String,
    private val isNsfwSource: (String) -> Boolean = { false },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {

    suspend fun import(payload: ExternalBackupPayload): ExternalBackupImportSummary {
        if (payload.records.isEmpty()) return ExternalBackupImportSummary(0, 0)
        return transaction.run {
            val sourceMatcher = SourceMatcher(catalog)
            val externalCategories = ensureImportedCategories(payload.favoriteCategories)
            val defaultCategoryId = ensureDefaultCategoryId(externalCategories.values)
            val now = now()

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
                    pendingById[mangaId] = BulkImportEntry(resolvedRecord, mangaId, isNsfwSource)
                } else {
                    existing.mergeFrom(resolvedRecord)
                }
            }
            val pending = pendingById.values.toList()

            var favorites = 0
            var historyRows = 0
            val tagsById = LinkedHashMap<Long, TagEntity>()
            val favourites = ArrayList<FavouriteEntity>()
            val histories = ArrayList<HistoryEntity>()
            for (entry in pending) {
                entry.tags.forEach { tag -> tagsById.getOrPut(tag.id) { tag } }
            }
            database.getTagsDao().upsert(tagsById.values.toList())
            for (entry in pending) {
                database.getMangaDao().upsert(entry.mangaEntity, entry.tags)
            }
            for (entry in pending) {
                val resolved = entry.record
                if (resolved.isFavorite) {
                    val favoriteTimestamp = resolved.favoriteTimestamp ?: now
                    val targetCategoryIds = resolved.favoriteCategoryOrders
                        .mapNotNull(externalCategories::get)
                        .ifEmpty { listOf(defaultCategoryId.toLong()) }
                    targetCategoryIds.distinct().forEach { categoryId ->
                        favourites += FavouriteEntity(
                            mangaId = entry.mangaId,
                            categoryId = categoryId,
                            sortKey = 0,
                            isPinned = false,
                            createdAt = favoriteTimestamp,
                            deletedAt = 0L,
                            updatedAt = favoriteTimestamp,
                        )
                        favorites++
                    }
                }
                // Locals: the record's public properties cannot be smart-cast.
                val historyTimestamp = resolved.historyTimestamp
                val historyChapterUrl = resolved.historyChapterUrl
                if (historyTimestamp != null && !historyChapterUrl.isNullOrBlank()) {
                    val chapterId = generateChapterId(resolved, historyChapterUrl)
                    val percent = resolved.progressPercent?.coerceIn(PROGRESS_NONE, 1f) ?: PROGRESS_NONE
                    histories += HistoryEntity(
                        mangaId = entry.mangaId,
                        createdAt = historyTimestamp,
                        updatedAt = historyTimestamp,
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
            }
            database.getFavouritesDao().upsert(favourites)
            database.getHistoryDao().upsert(histories)

            registerUninstalledSourceOrigins(uninstalledSources, now)

            ExternalBackupImportSummary(
                favoritesImported = favorites,
                historyImported = historyRows,
                failedCount = failedRecords.size,
                failedTitles = failedRecords.map { it.title }.distinct(),
                failedRecords = failedRecords.distinctBy { Triple(it.title, it.sourceCandidates, it.expectedSourceNames) },
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
                        createdAt = now(),
                        sortKey = nextSortKey++,
                        title = category.name,
                        order = ORDER_NEWEST,
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
        val category = FavouriteCategoryEntity(
            categoryId = 0,
            createdAt = now(),
            sortKey = 0,
            title = defaultCategoryTitle,
            order = ORDER_NEWEST,
            track = false,
            isVisibleInLibrary = true,
            deletedAt = 0L,
        )
        val insertedId = database.getFavouriteCategoriesDao().insert(category)
        return insertedId.toInt()
    }

    private class SourceMatcher(private val catalog: ExternalSourceCatalog) {
        private var cachedCandidates: List<Candidate>? = null

        suspend fun resolve(record: ExternalBackupContentRecord): SourceResolveResult {
            if (record.sourceName.startsWith("MIHON_") || record.sourceName.startsWith("ANIYOMI_")) {
                if (record.sourceName.startsWith("MIHON_")) {
                    val sourceId = record.sourceName.removePrefix("MIHON_").toLongOrNull()
                    val target = sourceId?.let(::targetForNonInstallableForkSource)
                    if (target != null) {
                        resolveNativeSource(record, target)?.let { return it }
                    }
                }
                return when {
                    isInstalledExtensionSource(record.sourceName) -> SourceResolveResult.Resolved(record)
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
            val native = matches.filter { it.kind == ExternalSourceKind.NATIVE }.distinctBy { it.sourceName }
            val json = matches.filter { it.kind == ExternalSourceKind.JSON }.distinctBy { it.sourceName }
            val resolved = when {
                native.size == 1 -> native.first()
                native.size > 1 -> null
                json.size == 1 -> json.first()
                else -> null
            } ?: return SourceResolveResult.Unmatched
            return SourceResolveResult.Resolved(record.copy(sourceName = resolved.sourceName))
        }

        private suspend fun resolveNativeSource(
            record: ExternalBackupContentRecord,
            target: NonInstallableForkTarget,
        ): SourceResolveResult? {
            val matches = candidates()
                .filter { it.sourceName == target.sourceName && it.kind == ExternalSourceKind.NATIVE }
                .distinctBy { it.sourceName }
            if (matches.size != 1) {
                return null
            }
            return SourceResolveResult.Resolved(
                record.copy(
                    sourceName = matches.single().sourceName,
                    sourceDisplayName = null,
                    contentType = target.contentType,
                ),
            )
        }

        private suspend fun isInstalledExtensionSource(sourceName: String): Boolean {
            val kind = if (sourceName.startsWith("ANIYOMI_")) ExternalSourceKind.ANIYOMI else ExternalSourceKind.MIHON
            return candidates().any { it.sourceName == sourceName && it.kind == kind }
        }

        private fun resolveFixedMapping(
            normalizedNames: Set<String>,
            candidates: List<Candidate>,
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

        private suspend fun candidates(): List<Candidate> {
            cachedCandidates?.let { return it }
            return catalog.candidates()
                .map { Candidate(it.sourceName, normalizeSourceName(it.name), it.kind) }
                .filter { it.normalizedName.isNotEmpty() }
                .also { cachedCandidates = it }
        }
    }

    private data class FixedSourceTarget(
        val sourceName: String,
        val kind: ExternalSourceKind,
        val displayName: String = sourceName,
    )

    private data class Candidate(
        val sourceName: String,
        val normalizedName: String,
        val kind: ExternalSourceKind,
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
        data class Found(val candidate: Candidate) : FixedSourceResolveResult
        data class Missing(val expectedSourceNames: List<String>) : FixedSourceResolveResult
    }

    companion object {
        private const val PROGRESS_NONE = -1f
        private const val ORDER_NEWEST = "NEWEST"
        private val SOURCE_NORMALIZE_REGEX = Regex("[\\s\\p{Punct}_\\-]+")
        private val VENERA_SOURCE_MAP = buildVeneraSourceMap()

        /** The persisted manga id of a backup record: Android's original formula, kept bit for bit. */
        fun generateContentId(record: ExternalBackupContentRecord): Long {
            return "${record.sourceName}|manga|${record.url}".hashCode().toLong() and Long.MAX_VALUE
        }

        fun generateChapterId(record: ExternalBackupContentRecord, chapterUrl: String): Long {
            val type = if (record.contentType == ContentType.VIDEO) "episode" else "chapter"
            // Mihon chapter identities include the parent manga URL, matching the loaded chapter list.
            val identity = if (record.sourceName.startsWith("MIHON_") && record.contentType != ContentType.VIDEO) {
                "${record.url}|$chapterUrl"
            } else {
                chapterUrl
            }
            return "${record.sourceName}|$type|$identity".hashCode().toLong() and Long.MAX_VALUE
        }

        private fun normalizeSourceName(value: String): String {
            return value.lowercase()
                .replace(SOURCE_NORMALIZE_REGEX, "")
        }

        private const val COPYMANGA_COPY20_SOURCE = "MIHON_6696312508930833206"

        private fun buildVeneraSourceMap(): Map<String, List<FixedSourceTarget>> {
            val entries = listOf(
                venera(
                    aliases = listOf("copy_manga", "拷贝漫画"),
                    targets = listOf(
                        FixedSourceTarget(COPYMANGA_COPY20_SOURCE, ExternalSourceKind.MIHON, "CopyManga (CopyManga Copy20)"),
                    ),
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
                targets = sourceNames.map { FixedSourceTarget(it, ExternalSourceKind.NATIVE) },
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

/**
 * ExHentai is bundled by TachiyomiSY/Komikku under ids that no Mihon extension publishes.
 * Resolve only those known non-installable ids to Kototoro's JAR source; all other fork
 * sources remain ordinary Mihon keys and continue through extension recovery.
 */
fun targetForNonInstallableForkSource(sourceId: Long): NonInstallableForkTarget? = when (sourceId) {
    6902L,
    6_225_928_719_850_211_219L -> NonInstallableForkTarget("EXHENTAI", ContentType.HENTAI_MANGA)
    else -> null
}

/**
 * A fork source id that cannot be installed, plus the content type Kototoro's replacement
 * source reports for it.
 *
 * The content type must travel with the mapping. A restored record carries the *fork's*
 * value (Komikku stores its built-in ExHentai entries as MANGA), while Kototoro's EXHENTAI
 * source yields HENTAI_MANGA. Left as-is, the two disagree for every restored work, so the
 * first details open re-persists the row and Room's invalidation tracker tears the favourites
 * paging query down - measured on device as 2154 not-yet-converged rows against 43 already
 * converged ones, i.e. a list rebuild on essentially every visit through a restored library.
 */
data class NonInstallableForkTarget(val sourceName: String, val contentType: ContentType)

object ExternalBackupCategoryMapper {

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
