package org.skepsun.kototoro.backups.external

import android.content.Context
import androidx.room.withTransaction
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.core.db.entity.toContent
import org.skepsun.kototoro.core.extensions.GlobalExtensionManager
import org.skepsun.kototoro.core.model.getTitle
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.WorkFavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.history.data.WorkHistoryEntity
import org.skepsun.kototoro.list.domain.ListSortOrder
import org.skepsun.kototoro.mihon.MihonExtensionManager
import org.skepsun.kototoro.list.domain.ReadingProgress.Companion.PROGRESS_NONE
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.work.domain.WorkResolver
import org.skepsun.kototoro.work.domain.WorkIdentity
import org.skepsun.kototoro.work.domain.WorkIdentityProvenance
import javax.inject.Inject

@Reusable
class ExternalBackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
    private val mihonExtensionManager: MihonExtensionManager,
    private val workResolver: WorkResolver,
) {

    suspend fun import(payload: ExternalBackupPayload): ExternalBackupImportSummary {
        if (payload.records.isEmpty()) return ExternalBackupImportSummary(0, 0)
        return database.withTransaction {
            val sourceMatcher = SourceMatcher(context, database, mihonExtensionManager)
            val externalCategories = ensureImportedCategories(payload.favoriteCategories)
            val defaultCategoryId = ensureDefaultCategoryId(externalCategories.values)
            var favoritesImported = 0
            var historyImported = 0
            val failedRecords = ArrayList<ExternalBackupFailedRecord>()
            val missingSourceNames = LinkedHashSet<String>()
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
                }
                val mangaId = generateContentId(resolvedRecord)
                upsertContent(mangaId, resolvedRecord)
                val workIdentity = resolveImportedWorkIdentity(mangaId) ?: continue
                if (resolvedRecord.isFavorite) {
                    val favoriteTimestamp = resolvedRecord.favoriteTimestamp ?: System.currentTimeMillis()
                    val targetCategoryIds = resolvedRecord.favoriteCategoryOrders
                        .mapNotNull(externalCategories::get)
                        .ifEmpty { listOf(defaultCategoryId.toLong()) }
                    targetCategoryIds.distinct().forEach { categoryId ->
                        database.getWorkFavouritesDao().upsert(
                            WorkFavouriteEntity(
                                entityId = workIdentity.entityId ?: return@forEach,
                                categoryId = categoryId,
                                anchorMangaId = mangaId,
                                createdAt = favoriteTimestamp,
                                sortKey = 0,
                                deletedAt = 0L,
                                isPinned = false,
                                updatedAt = favoriteTimestamp,
                            ),
                        )
                        favoritesImported++
                    }
                }
                if (resolvedRecord.historyTimestamp != null && !resolvedRecord.historyChapterUrl.isNullOrBlank()) {
                    val chapterId = generateChapterId(resolvedRecord, resolvedRecord.historyChapterUrl)
                    val chaptersCount = resolvedRecord.chaptersCount.coerceAtLeast(0)
                    val percent = resolvedRecord.progressPercent?.coerceIn(PROGRESS_NONE, 1f) ?: PROGRESS_NONE
                    val history = HistoryEntity(
                        mangaId = mangaId,
                        createdAt = resolvedRecord.historyTimestamp,
                        updatedAt = resolvedRecord.historyTimestamp,
                        chapterId = chapterId,
                        page = 0,
                        scroll = 0f,
                        percent = percent,
                        deletedAt = 0L,
                        chaptersCount = chaptersCount,
                        parentChapterId = null,
                    )
                    workIdentity.entityId?.let { entityId ->
                        val anchorMangaId = workIdentity.preferredMangaId ?: selectExistingLocalAnchor(entityId) ?: mangaId
                        database.getWorkHistoryDao().upsert(
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
                    historyImported++
                }
            }
            ExternalBackupImportSummary(
                favoritesImported = favoritesImported,
                historyImported = historyImported,
                failedCount = failedRecords.size,
                failedTitles = failedRecords.map { it.title }.distinct(),
                failedRecords = failedRecords.distinctBy { it.title to it.sourceCandidates to it.expectedSourceNames },
                missingSourceNames = missingSourceNames.toList(),
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

    private suspend fun resolveImportedWorkIdentity(mangaId: Long): WorkIdentity? {
        val manga = database.getMangaDao().find(mangaId)?.toContent() ?: return null
        return workResolver.ensureForProjection(
            content = manga,
            provenance = WorkIdentityProvenance.IMPORT,
        )
    }

    private suspend fun upsertContent(mangaId: Long, record: ExternalBackupContentRecord) {
        val tags = record.tags
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .map { tag ->
                TagEntity(
                    id = "${record.sourceName}|tag|${tag.lowercase()}".hashCode().toLong() and Long.MAX_VALUE,
                    title = tag,
                    key = tag.lowercase().replace(" ", "_"),
                    source = record.sourceName,
                    isPinned = false,
                )
            }
        database.getTagsDao().upsert(tags)
        database.getMangaDao().upsert(
            MangaEntity(
                id = mangaId,
                title = record.title.ifBlank { record.url },
                altTitles = null,
                url = record.url,
                publicUrl = record.publicUrl.ifBlank { record.url },
                rating = -1f,
                isNsfw = false,
                contentRating = null,
                coverUrl = record.coverUrl.orEmpty(),
                largeCoverUrl = record.coverUrl,
                state = null,
                authors = record.authors,
                source = record.sourceName,
            ),
            tags = tags,
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

    private suspend fun selectExistingLocalAnchor(entityId: Long): Long? {
        val identity = workResolver.resolveByEntityId(entityId) ?: return null
        val preferredMangaId = identity.preferredMangaId
        if (preferredMangaId != null && database.getMangaDao().contains(preferredMangaId)) {
            return preferredMangaId
        }
        return identity.localMangaIds.firstOrNull { localId ->
            database.getMangaDao().contains(localId)
        }
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
    ) {
        private var cachedCandidates: List<SourceCandidate>? = null

        suspend fun resolve(record: ExternalBackupContentRecord): SourceResolveResult {
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
    }

    private sealed interface FixedSourceResolveResult {
        data class Found(val candidate: SourceCandidate) : FixedSourceResolveResult
        data class Missing(val expectedSourceNames: List<String>) : FixedSourceResolveResult
    }

    private companion object {
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
        // Mihon manga.category values reference category order during restore.
        target[category.order] = localCategoryId
        // Keep id mapping as a fallback for previously exported or non-standard backups.
        if (category.id != category.order) {
            target[category.id] = localCategoryId
        }
    }
}
