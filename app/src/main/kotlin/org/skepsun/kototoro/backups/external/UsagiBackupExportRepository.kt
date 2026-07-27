package org.skepsun.kototoro.backups.external

import android.content.Context
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.serializer
import org.skepsun.kototoro.R
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.db.entity.MangaEntity
import org.skepsun.kototoro.core.db.entity.TagEntity
import org.skepsun.kototoro.favourites.data.FavouriteCategoryEntity
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.core.util.progress.Progress
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.inject.Inject

data class UsagiBackupExportSummary(
    val exportedCount: Int,
)

@Reusable
class UsagiBackupExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: MangaDatabase,
) {

    private val json = Json {
        allowSpecialFloatingPointValues = true
        coerceInputValues = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    suspend fun export(
        output: OutputStream,
        progress: FlowCollector<Progress>? = null,
    ): UsagiBackupExportSummary {
        progress?.emit(Progress.INDETERMINATE)

        val workState = database.readExternalBackupWorkState()

        // 1. Gather all candidate manga IDs from favourites, history, bookmarks, scrobblings, and stats
        val bookmarksDump = database.getBookmarksDao().dump().toList()
        val scrobblingsDump = database.getScrobblingDao().dumpEnabled().toList()
        val statsDump = database.getWorkStatsDao().dumpEnabled().toList()

        val bookmarkMangaIds = bookmarksDump.map { it.first.manga.id }
        val scrobblingMangaIds = scrobblingsDump.map { it.mangaId }
        val statMangaIds = statsDump.map { it.anchorMangaId }

        val allCandidateIds = (
            workState.candidateMangaIds +
                bookmarkMangaIds +
                scrobblingMangaIds +
                statMangaIds
            ).distinct()

        if (allCandidateIds.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_usagi_backup_empty))
        }

        // 2. Query MangaEntity objects and filter out non-manga entries (e.g. Light Novels, Anime/Videos)
        val mangaById = database.getMangaDao().findEntitiesByIds(allCandidateIds)
            .filter { isMangaMedia(it) }
            .associateBy(MangaEntity::id)

        val validMangaIds = mangaById.keys
        if (validMangaIds.isEmpty()) {
            throw IllegalStateException(context.getString(R.string.export_usagi_backup_empty))
        }

        progress?.emit(Progress(0, validMangaIds.size))

        // 3. Load tags for valid exported manga entries
        val tagTitlesByMangaId = loadTagEntitiesByMangaId(validMangaIds.toList())

        // Map MangaEntity -> UsagiMangaBackup
        val usagiMangaMap = mangaById.mapValues { (mangaId, manga) ->
            val tags = tagTitlesByMangaId[mangaId].orEmpty().mapTo(LinkedHashSet()) { tag ->
                UsagiTagBackup(
                    id = tag.id,
                    title = tag.title,
                    key = tag.key,
                    source = tag.source,
                    isPinned = tag.isPinned,
                )
            }
            UsagiMangaBackup(
                id = manga.id,
                title = manga.title,
                altTitles = manga.altTitles,
                url = manga.url,
                publicUrl = manga.publicUrl,
                rating = manga.rating,
                isNsfw = manga.isNsfw,
                contentRating = manga.contentRating,
                coverUrl = manga.coverUrl,
                largeCoverUrl = manga.largeCoverUrl,
                state = manga.state,
                authors = manga.authors,
                source = manga.source,
                tags = tags,
            )
        }

        // 4. Categories
        val categories = database.getFavouriteCategoriesDao().findAll().map { cat ->
            UsagiCategoryBackup(
                categoryId = cat.categoryId,
                createdAt = cat.createdAt,
                sortKey = cat.sortKey,
                title = cat.title,
                order = cat.order,
                track = cat.track,
                isVisibleInLibrary = cat.isVisibleInLibrary,
            )
        }

        // 5. Favourites
        val favouriteBackups = workState.favouriteEntries.mapNotNull { fav ->
            val mangaBackup = usagiMangaMap[fav.mangaId] ?: return@mapNotNull null
            UsagiFavouriteBackup(
                mangaId = fav.mangaId,
                categoryId = fav.categoryId.toLong(),
                sortKey = fav.sortKey,
                isPinned = fav.isPinned,
                createdAt = fav.createdAt,
                manga = mangaBackup,
            )
        }

        // 6. History
        val historyBackups = workState.historyEntries.mapNotNull { hist ->
            val mangaBackup = usagiMangaMap[hist.mangaId] ?: return@mapNotNull null
            UsagiHistoryBackup(
                mangaId = hist.mangaId,
                createdAt = hist.createdAt,
                updatedAt = hist.updatedAt,
                chapterId = hist.chapterId,
                page = hist.page,
                scroll = hist.scroll,
                percent = hist.percent,
                chaptersCount = hist.chaptersCount,
                manga = mangaBackup,
            )
        }

        // 7. Bookmarks
        val bookmarkBackups = bookmarksDump.mapNotNull { (mangaWithTags, bookmarks) ->
            val mangaBackup = usagiMangaMap[mangaWithTags.manga.id] ?: return@mapNotNull null
            val bookmarkItems = bookmarks.map { b ->
                UsagiBookmarkItem(
                    mangaId = b.mangaId,
                    pageId = b.pageId,
                    chapterId = b.chapterId,
                    page = b.page,
                    scroll = b.scroll,
                    imageUrl = b.imageUrl,
                    createdAt = b.createdAt,
                    percent = b.percent,
                )
            }
            UsagiBookmarkBackup(
                manga = mangaBackup.copy(tags = emptySet()),
                tags = mangaBackup.tags,
                bookmarks = bookmarkItems,
            )
        }

        // 8. Sources
        val exportedSources = mangaById.values.map { it.source }.distinct().mapIndexed { index, sourceName ->
            UsagiSourceBackup(
                source = sourceName,
                sortKey = index,
                lastUsedAt = System.currentTimeMillis(),
                addedIn = 1,
                isPinned = false,
                isEnabled = true,
            )
        }

        // 9. Scrobblings (Tracking)
        val scrobblingBackups = scrobblingsDump.mapNotNull { scrobble ->
            if (scrobble.mangaId !in validMangaIds) return@mapNotNull null
            UsagiScrobblingBackup(
                scrobbler = scrobble.scrobbler,
                id = scrobble.id,
                mangaId = scrobble.mangaId,
                targetId = scrobble.targetId,
                status = scrobble.status,
                chapter = scrobble.chapter,
                comment = scrobble.comment,
                rating = scrobble.rating,
            )
        }

        // 10. Statistics
        val statisticBackups = statsDump.mapNotNull { stat ->
            if (stat.anchorMangaId !in validMangaIds) return@mapNotNull null
            UsagiStatisticBackup(
                mangaId = stat.anchorMangaId,
                startedAt = stat.startedAt,
                duration = stat.duration,
                pages = stat.pages,
            )
        }

        progress?.emit(Progress(validMangaIds.size, validMangaIds.size))

        // Write Zip Backup
        ZipOutputStream(output).use { zip ->
            zip.writeJsonArray("index", listOf(UsagiBackupIndex()))
            zip.writeJsonArray("categories", categories)
            zip.writeJsonArray("favourites", favouriteBackups)
            zip.writeJsonArray("history", historyBackups)
            zip.writeJsonArray("bookmarks", bookmarkBackups)
            zip.writeJsonArray("sources", exportedSources)
            zip.writeJsonArray("scrobbling", scrobblingBackups)
            zip.writeJsonArray("statistics", statisticBackups)
        }

        return UsagiBackupExportSummary(exportedCount = usagiMangaMap.size)
    }

    private fun isMangaMedia(manga: MangaEntity): Boolean {
        val type = manga.contentType?.uppercase() ?: return true
        return type !in NON_MANGA_CONTENT_TYPES
    }

    private suspend fun loadTagEntitiesByMangaId(mangaIds: List<Long>): Map<Long, List<TagEntity>> {
        val tagRelations = database.getMangaDao().findTagRelationsByMangaIds(mangaIds)
        if (tagRelations.isEmpty()) {
            return emptyMap()
        }
        val tagsById = database.getTagsDao()
            .findByIds(tagRelations.map { it.tagId }.distinct())
            .associateBy { it.id }
        return tagRelations.groupBy { it.mangaId }
            .mapValues { (_, relations) ->
                relations.mapNotNull { relation -> tagsById[relation.tagId] }
            }
    }

    private inline fun <reified T> ZipOutputStream.writeJsonArray(
        entryName: String,
        data: List<T>,
    ) {
        putNextEntry(ZipEntry(entryName))
        write("[".toByteArray())
        data.forEachIndexed { index, item ->
            if (index > 0) {
                write(",".toByteArray())
            }
            json.encodeToStream(serializer<T>(), item, this)
        }
        write("]".toByteArray())
        closeEntry()
        flush()
    }

    private companion object {
        private val NON_MANGA_CONTENT_TYPES = setOf(
            "NOVEL",
            "HENTAI_NOVEL",
            "VIDEO",
            "HENTAI_VIDEO",
        )
    }
}
