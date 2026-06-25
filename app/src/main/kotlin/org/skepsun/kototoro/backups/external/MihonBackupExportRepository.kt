package org.skepsun.kototoro.backups.external

import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.protobuf.ProtoBuf
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.ChapterEntity
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryMembership
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.core.util.progress.Progress
import java.io.OutputStream
import java.util.zip.GZIPOutputStream
import javax.inject.Inject
import kotlin.math.floor

data class MihonBackupExportSummary(
    val exportedCount: Int,
)

@Reusable
class MihonBackupExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
) {

    suspend fun export(
        output: OutputStream,
        progress: FlowCollector<Progress>? = null,
    ): MihonBackupExportSummary {
        progress?.emit(Progress.INDETERMINATE)

        val workState = database.readExternalBackupWorkState()
        val favouriteEntries = workState.favouriteEntries
        val candidateIds = workState.candidateMangaIds

        val mangaById = database.getMangaDao().findEntitiesByIds(candidateIds).associateBy(MangaEntity::id)
        val exportedMangaIds = candidateIds.filter { mangaById[it]?.source.toMihonSourceIdOrNull() != null }
        if (exportedMangaIds.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_mihon_backup_empty))
        }

        val historyByMangaId = workState.historyByMangaId
            .filterKeys { it in exportedMangaIds }
        val chaptersByMangaId = database.getChaptersDao()
            .findAllByMangaIds(exportedMangaIds)
            .groupBy(ChapterEntity::mangaId)
        val favoriteEntriesByMangaId = workState.favouriteEntriesByMangaId
        val categoryMembershipsByMangaId = workState.categoryMembershipsByMangaId
            .filterKeys { it in exportedMangaIds }
        val categoriesById = database.getFavouriteCategoriesDao()
            .findAll()
            .associateBy { it.categoryId.toLong() }
        val categoryOrderById = categoriesById.mapValues { (_, category) -> category.sortKey.toLong() }
        val exportedCategoryIds = categoryMembershipsByMangaId.values
            .flatten()
            .map(FavouriteCategoryMembership::categoryId)
            .distinct()
        val tagTitlesByMangaId = loadTagTitlesByMangaId(exportedMangaIds)

        val total = exportedMangaIds.size
        progress?.emit(Progress(0, total))
        val exportedManga = ArrayList<MihonBackupManga>(total)
        exportedMangaIds.forEachIndexed { index, mangaId ->
            val manga = mangaById[mangaId] ?: return@forEachIndexed
            val sourceId = manga.source.toMihonSourceIdOrNull() ?: return@forEachIndexed
            val favourites = favoriteEntriesByMangaId[mangaId].orEmpty()
            val history = historyByMangaId[mangaId]
            val chapters = chaptersByMangaId[mangaId].orEmpty()
            val historyBackup = MihonBackupExportMapper.buildHistory(chapters, history)
            val chapterBackups = MihonBackupExportMapper.buildChapters(chapters, history)
            val isFavorite = favourites.isNotEmpty()
            if (!isFavorite && historyBackup == null) {
                progress?.emit(Progress(index + 1, total))
                return@forEachIndexed
            }
            val createdAt = favourites.minOfOrNull(FavouriteEntity::createdAt) ?: 0L
            val favoriteModifiedAt = favourites.maxOfOrNull(FavouriteEntity::updatedAt)?.takeIf { it > 0L }
            val lastModifiedAt = maxOf(
                favoriteModifiedAt ?: 0L,
                history?.updatedAt ?: 0L,
                createdAt,
            )
            exportedManga += MihonBackupManga(
                source = sourceId,
                url = manga.url,
                title = manga.title,
                artist = null,
                author = manga.authors?.takeIf { it.isNotBlank() },
                description = null,
                genre = tagTitlesByMangaId[mangaId].orEmpty(),
                status = manga.state?.toIntOrNull() ?: 0,
                thumbnailUrl = manga.coverUrl.ifBlank { null },
                dateAdded = createdAt,
                favorite = isFavorite,
                chapters = chapterBackups,
                categories = MihonBackupExportMapper.mapCategoryOrders(
                    categoryMemberships = categoryMembershipsByMangaId[mangaId].orEmpty(),
                    categoryOrderById = categoryOrderById,
                ),
                history = listOfNotNull(historyBackup),
                lastModifiedAt = lastModifiedAt,
                favoriteModifiedAt = favoriteModifiedAt,
            )
            progress?.emit(Progress(index + 1, total))
        }

        if (exportedManga.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_mihon_backup_empty))
        }

        val backup = MihonBackup(
            backupManga = exportedManga,
            backupCategories = exportedCategoryIds.mapNotNull(categoriesById::get)
                .sortedBy(FavouriteCategoryEntity::sortKey)
                .map { category ->
                    MihonBackupCategory(
                        name = category.title,
                        order = category.sortKey.toLong(),
                        id = category.categoryId.toLong(),
                    )
                },
        )
        GZIPOutputStream(output).use { gzip ->
            gzip.write(ProtoBuf.encodeToByteArray(MihonBackup.serializer(), backup))
        }
        return MihonBackupExportSummary(exportedCount = exportedManga.size)
    }

    private suspend fun loadTagTitlesByMangaId(mangaIds: List<Long>): Map<Long, List<String>> {
        val tagRelations = database.getMangaDao().findTagRelationsByMangaIds(mangaIds)
        if (tagRelations.isEmpty()) {
            return emptyMap()
        }
        val tagsById = database.getTagsDao()
            .findByIds(tagRelations.map { it.tagId }.distinct())
            .associateBy { it.id }
        return tagRelations.groupBy { it.mangaId }
            .mapValues { (_, relations) ->
                relations.mapNotNull { relation -> tagsById[relation.tagId]?.title }
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .distinct()
            }
    }
}

internal object MihonBackupExportMapper {

    fun sourceIdOrNull(sourceName: String): Long? = sourceName.toMihonSourceIdOrNull()

    fun buildChapters(
        chapters: List<ChapterEntity>,
        history: HistoryEntity?,
    ): List<MihonBackupChapter> {
        if (chapters.isEmpty()) {
            return emptyList()
        }
        val readCount = resolveReadCountForExport(chapters, history)
        return chapters.mapIndexed { index, chapter ->
            MihonBackupChapter(
                url = chapter.url,
                name = chapter.title,
                read = index < readCount,
            )
        }
    }

    fun buildHistory(
        chapters: List<ChapterEntity>,
        history: HistoryEntity?,
    ): MihonBackupHistory? {
        history ?: return null
        val chapter = chapters.firstOrNull { it.chapterId == history.chapterId } ?: return null
        return MihonBackupHistory(
            url = chapter.url,
            lastRead = history.updatedAt.takeIf { it > 0L } ?: history.createdAt,
        )
    }

    fun mapCategoryOrders(
        categoryMemberships: List<FavouriteCategoryMembership>,
        categoryOrderById: Map<Long, Long>,
    ): List<Long> {
        return categoryMemberships.mapNotNull { membership ->
            categoryOrderById[membership.categoryId]
        }.distinct()
    }

    fun resolveReadCountForExport(
        chapters: List<ChapterEntity>,
        history: HistoryEntity?,
    ): Int {
        history ?: return 0
        val currentIndex = chapters.indexOfFirst { it.chapterId == history.chapterId }
        if (currentIndex >= 0) {
            if (history.percent >= COMPLETED_THRESHOLD) {
                return (currentIndex + 1).coerceAtMost(chapters.size)
            }
            return currentIndex.coerceAtLeast(0)
        }
        if (history.chaptersCount <= 0 || history.percent <= 0f) {
            return 0
        }
        val estimatedReadCount = floor(history.percent.coerceIn(0f, 1f) * history.chaptersCount).toInt()
        return estimatedReadCount.coerceIn(0, chapters.size)
    }

    private const val COMPLETED_THRESHOLD = 0.999f
}

private fun String?.toMihonSourceIdOrNull(): Long? {
    return this
        ?.takeIf { it.startsWith(MIHON_SOURCE_PREFIX) }
        ?.removePrefix(MIHON_SOURCE_PREFIX)
        ?.toLongOrNull()
}

private const val MIHON_SOURCE_PREFIX = "MIHON_"
