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

@Reusable
class AniyomiBackupExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
) {

    suspend fun export(
        output: OutputStream,
        progress: FlowCollector<Progress>? = null,
    ): MihonBackupExportSummary {
        progress?.emit(Progress.INDETERMINATE)

        val workState = database.readExternalBackupWorkState()
        val candidateIds = workState.candidateMangaIds
        val mangaById = database.getMangaDao().findEntitiesByIds(candidateIds).associateBy(MangaEntity::id)
        val mangaIds = candidateIds.filter { mangaById[it]?.source.toSourceIdOrNull(MIHON_SOURCE_PREFIX) != null }
        val animeIds = candidateIds.filter { mangaById[it]?.source.toSourceIdOrNull(ANIYOMI_SOURCE_PREFIX) != null }

        if (mangaIds.isEmpty() && animeIds.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_aniyomi_backup_empty))
        }

        val exportedIds = (mangaIds + animeIds).distinct()
        val historyByMangaId = workState.historyByMangaId
            .filterKeys { it in exportedIds }
        val chaptersByMangaId = database.getChaptersDao()
            .findAllByMangaIds(exportedIds)
            .groupBy(ChapterEntity::mangaId)
        val favoriteEntriesByMangaId = workState.favouriteEntriesByMangaId
        val categoryMembershipsByMangaId = workState.categoryMembershipsByMangaId
            .filterKeys { it in exportedIds }
        val categoriesById = database.getFavouriteCategoriesDao()
            .findAll()
            .associateBy { it.categoryId.toLong() }
        val categoryOrderById = categoriesById.mapValues { (_, category) -> category.sortKey.toLong() }
        val exportedCategoryIds = categoryMembershipsByMangaId.values
            .flatten()
            .map(FavouriteCategoryMembership::categoryId)
            .distinct()
        val tagTitlesByMangaId = loadTagTitlesByMangaId(exportedIds)

        val total = exportedIds.size
        progress?.emit(Progress(0, total))
        var processed = 0

        val backupManga = ArrayList<AniyomiBackupManga>(mangaIds.size)
        mangaIds.forEach { mangaId ->
            val manga = mangaById[mangaId] ?: return@forEach
            val sourceId = manga.source.toSourceIdOrNull(MIHON_SOURCE_PREFIX) ?: return@forEach
            val favourites = favoriteEntriesByMangaId[mangaId].orEmpty()
            val history = historyByMangaId[mangaId]
            val chapters = chaptersByMangaId[mangaId].orEmpty()
            val historyBackup = MihonBackupExportMapper.buildHistory(chapters, history)
            val chapterBackups = MihonBackupExportMapper.buildChapters(chapters, history)
            val isFavorite = favourites.isNotEmpty()
            if (isFavorite || historyBackup != null) {
                val createdAt = favourites.minOfOrNull(FavouriteEntity::createdAt) ?: 0L
                val favoriteModifiedAt = favourites.maxOfOrNull(FavouriteEntity::updatedAt)?.takeIf { it > 0L }
                val lastModifiedAt = maxOf(
                    favoriteModifiedAt ?: 0L,
                    history?.updatedAt ?: 0L,
                    createdAt,
                )
                backupManga += AniyomiBackupManga(
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
            }
            processed++
            progress?.emit(Progress(processed, total))
        }

        val backupAnime = ArrayList<AniyomiBackupAnime>(animeIds.size)
        animeIds.forEach { mangaId ->
            val manga = mangaById[mangaId] ?: return@forEach
            val sourceId = manga.source.toSourceIdOrNull(ANIYOMI_SOURCE_PREFIX) ?: return@forEach
            val favourites = favoriteEntriesByMangaId[mangaId].orEmpty()
            val history = historyByMangaId[mangaId]
            val episodes = chaptersByMangaId[mangaId].orEmpty()
            val historyBackup = history?.let { currentHistory ->
                MihonBackupExportMapper.buildHistory(episodes, currentHistory)?.let {
                    AniyomiBackupHistory(
                        url = it.url,
                        lastRead = it.lastRead,
                    )
                }
            }
            val episodeBackups = AniyomiBackupExportMapper.buildEpisodes(episodes, history)
            val isFavorite = favourites.isNotEmpty()
            if (isFavorite || historyBackup != null) {
                val createdAt = favourites.minOfOrNull(FavouriteEntity::createdAt) ?: 0L
                val favoriteModifiedAt = favourites.maxOfOrNull(FavouriteEntity::updatedAt)?.takeIf { it > 0L }
                val lastModifiedAt = maxOf(
                    favoriteModifiedAt ?: 0L,
                    history?.updatedAt ?: 0L,
                    createdAt,
                )
                backupAnime += AniyomiBackupAnime(
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
                    episodes = episodeBackups,
                    categories = MihonBackupExportMapper.mapCategoryOrders(
                        categoryMemberships = categoryMembershipsByMangaId[mangaId].orEmpty(),
                        categoryOrderById = categoryOrderById,
                    ),
                    history = listOfNotNull(historyBackup),
                    lastModifiedAt = lastModifiedAt,
                    favoriteModifiedAt = favoriteModifiedAt,
                )
            }
            processed++
            progress?.emit(Progress(processed, total))
        }

        if (backupManga.isEmpty() && backupAnime.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_aniyomi_backup_empty))
        }

        val backup = AniyomiBackup(
            backupManga = backupManga,
            backupCategories = exportedCategoryIds.mapNotNull(categoriesById::get)
                .sortedBy(FavouriteCategoryEntity::sortKey)
                .map { category ->
                    MihonBackupCategory(
                        name = category.title,
                        order = category.sortKey.toLong(),
                        id = category.categoryId.toLong(),
                    )
                },
            backupAnime = backupAnime,
        )
        GZIPOutputStream(output).use { gzip ->
            gzip.write(ProtoBuf.encodeToByteArray(AniyomiBackup.serializer(), backup))
        }
        return MihonBackupExportSummary(exportedCount = backupManga.size + backupAnime.size)
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

internal object AniyomiBackupExportMapper {

    fun buildEpisodes(
        episodes: List<ChapterEntity>,
        history: HistoryEntity?,
    ): List<AniyomiBackupEpisode> {
        if (episodes.isEmpty()) {
            return emptyList()
        }
        val seenCount = MihonBackupExportMapper.resolveReadCountForExport(episodes, history)
        return episodes.mapIndexed { index, episode ->
            AniyomiBackupEpisode(
                url = episode.url,
                name = episode.title,
                seen = index < seenCount,
            )
        }
    }
}

private fun String?.toSourceIdOrNull(prefix: String): Long? {
    return this
        ?.takeIf { it.startsWith(prefix) }
        ?.removePrefix(prefix)
        ?.toLongOrNull()
}

private const val MIHON_SOURCE_PREFIX = "MIHON_"
private const val ANIYOMI_SOURCE_PREFIX = "ANIYOMI_"
