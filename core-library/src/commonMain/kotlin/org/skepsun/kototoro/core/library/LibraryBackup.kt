package org.skepsun.kototoro.core.library

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.Json
import org.skepsun.kototoro.backups.data.model.*
import org.skepsun.kototoro.backups.data.dumpReadingStatistics
import org.skepsun.kototoro.backups.data.restoreReadingStatistic
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import org.skepsun.kototoro.tracker.data.mergeRestoredTrack
import org.skepsun.kototoro.tracker.data.mergeRestoredTrackLog

/** Same decoding rules as Android: newer writers may add fields, older ones may omit them. */
val LibraryBackupJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Platform archive reader; each section is one JSON array, as in Android/Kotatsu backups. */
interface LibraryBackupInput {
    suspend fun <T> rows(section: BackupSection, serializer: DeserializationStrategy<T>, consume: suspend (T) -> Unit)
}

/** Platform archive writer; [write] receives UTF-8 JSON fragments of the currently open section. */
interface LibraryBackupOutput {
    fun beginSection(section: BackupSection)
    fun write(text: String)
    fun endSection()
}

/** Library transport over shared backup DTOs and the existing v84 tables; merge never clears local tables. */
class LibraryBackup(private val database: MangaDatabase) {

    suspend fun export(output: LibraryBackupOutput, appId: String, appVersion: Int, now: Long) {
        database.useWriterConnection { connection -> connection.immediateTransaction {
            write(output, BackupSection.INDEX, flowOf(BackupIndex(appId, appVersion,
                BackupIndex.WRITER_GENERATION_V3, BackupIndex.CURRENT_SYNC_SCHEMA_VERSION, createdAt = now)),
                BackupIndex.serializer())
            write(output, BackupSection.CATEGORIES,
                database.getFavouriteCategoriesDao().findAll().asFlow().map(::CategoryBackup), CategoryBackup.serializer())
            write(output, BackupSection.FAVOURITES,
                database.getFavouritesDao().dump().map(::FavouriteBackup), FavouriteBackup.serializer())
            write(output, BackupSection.HISTORY,
                database.getHistoryDao().dump().map(::HistoryBackup), HistoryBackup.serializer())
            write(output, BackupSection.SOURCES,
                database.getSourcesDao().dumpEnabled().map(::SourceBackup), SourceBackup.serializer())
            val ids = (database.getHistoryDao().findActiveMangaIds() +
                database.getFavouritesDao().findAllActiveEntries().map { it.mangaId }).toMutableSet()
            write(output, BackupSection.BOOKMARKS, database.getBookmarksDao().dump().map { (manga, bookmarks) ->
                ids += manga.manga.id
                BookmarkBackup(manga, bookmarks)
            }, BookmarkBackup.serializer())
            write(output, BackupSection.STATS, database.dumpReadingStatistics().map {
                ids += it.mangaId
                it
            }, StatisticBackup.serializer())
            write(output, BackupSection.SOURCE_ORIGINS,
                database.getSourceOriginsDao().findAll().asFlow().map(SourceOriginBackup::fromEntity),
                SourceOriginBackup.serializer())
            write(output, BackupSection.EXTENSION_REPOS,
                database.getExternalExtensionRepoDao().getAll().asFlow().map(::ExtensionRepoBackup),
                ExtensionRepoBackup.serializer())
            // Update checks travel with their works, like Android's tracks / track_logs sections.
            val tracks = database.getTracksDao().dump()
            val logs = database.getTrackLogsDao().dump()
            ids += tracks.map { it.mangaId }
            ids += logs.map { it.mangaId }
            write(output, BackupSection.TRACKS, tracks.asFlow().map(::TrackBackup), TrackBackup.serializer())
            write(output, BackupSection.TRACK_LOGS, logs.asFlow().map(::TrackLogBackup), TrackLogBackup.serializer())
            write(output, BackupSection.CONTENTS, flow {
                for (chunk in ids.chunked(100)) {
                    database.getMangaDao().findWithTagsByIds(chunk).forEach { emit(ContentBackup(it)) }
                }
            }, ContentBackup.serializer())
        } }
    }

    private suspend fun <T> write(output: LibraryBackupOutput, section: BackupSection, rows: Flow<T>,
        serializer: SerializationStrategy<T>) {
        output.beginSection(section)
        output.write("[")
        var first = true
        rows.collect { row ->
            currentCoroutineContext().ensureActive()
            if (!first) output.write(",")
            first = false
            output.write(LibraryBackupJson.encodeToString(serializer, row))
        }
        output.write("]")
        output.endSection()
    }

    suspend fun restore(archive: LibraryBackupInput): Int = database.useWriterConnection { connection ->
        connection.immediateTransaction {
            var restored = 0
            val anchors = hashSetOf<Long>()
            val categories = linkedMapOf<Long, Long>()
            val dao = database.getFavouriteCategoriesDao()
            archive.rows(BackupSection.CATEGORIES, CategoryBackup.serializer()) { backup ->
                val candidate = backup.toEntity()
                require(backup.categoryId.toLong() !in categories) { "备份包含重复收藏分类" }
                val sameTitle = dao.findAll().firstOrNull { it.title == candidate.title }
                val target = when {
                    sameTitle != null -> sameTitle.categoryId.toLong()
                    else -> dao.insert(candidate.copy(categoryId = 0, sortKey = dao.getNextSortKey()))
                }
                require(target in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "本地收藏分类编号超出范围" }
                categories[backup.categoryId.toLong()] = target
                restored++
            }
            archive.rows(BackupSection.CONTENTS, ContentBackup.serializer()) { saveContent(it); anchors += it.id; restored++ }
            suspend fun favourite(candidate: FavouriteEntity) {
                val category = categories[candidate.categoryId]
                    ?: throw IllegalArgumentException("备份收藏缺少对应分类")
                require(candidate.mangaId in database.getMangaDao()) { "备份收藏缺少对应作品" }
                val remote = candidate.copy(categoryId = category)
                val local = database.getFavouritesDao().find(remote.mangaId, remote.categoryId)
                if (local == null || remote.updatedAt >= local.updatedAt) database.getFavouritesDao().upsert(remote)
                restored++
            }
            suspend fun history(row: HistoryEntity) {
                require(row.mangaId in database.getMangaDao()) { "备份历史缺少对应作品" }
                require(row.page >= 0 && row.scroll.isFinite() && row.scroll >= 0 && row.chaptersCount >= 0) {
                    "备份阅读进度无效"
                }
                // Android writers never validated percent: float overshoot (1.0000001) and novel offsets leak in.
                val remote = row.copy(percent = normalizeBackupPercent(row.percent))
                val local = database.getHistoryDao().findIncludingDeleted(remote.mangaId)
                if (local == null || remote.updatedAt >= local.updatedAt) database.getHistoryDao().upsertSync(remote)
                restored++
            }
            archive.rows(BackupSection.FAVOURITES, FavouriteBackup.serializer()) {
                require(it.mangaId == it.manga.id) { "备份收藏作品编号不一致" }
                saveContent(it.manga); favourite(it.toEntity())
            }
            archive.rows(BackupSection.HISTORY, HistoryBackup.serializer()) {
                require(it.mangaId == it.manga.id) { "备份历史作品编号不一致" }
                saveContent(it.manga); history(it.toEntity())
            }
            archive.rows(BackupSection.WORK_FAVOURITES, WorkFavouriteBackup.serializer()) {
                // Old work state is projected onto its anchor, without restoring the removed entity graph.
                val anchor = it.anchorMangaId
                if (anchor != null && anchor in anchors) favourite(it.toFavouriteEntity(anchor))
                else require(it.deletedAt != 0L) { "旧备份收藏缺少作品锚点" }
            }
            archive.rows(BackupSection.WORK_HISTORY, WorkHistoryBackup.serializer()) {
                if (it.anchorMangaId in anchors) history(it.toHistoryEntity())
                else require(it.deletedAt != 0L) { "旧备份历史缺少作品锚点" }
            }
            archive.rows(BackupSection.BOOKMARKS, BookmarkBackup.serializer()) { backup ->
                saveContent(backup.manga, backup.tags.ifEmpty { backup.manga.tags })
                for (row in backup.bookmarks) {
                    currentCoroutineContext().ensureActive()
                    require(row.mangaId == backup.manga.id) { "备份书签作品编号不一致" }
                    require(row.page >= 0 && row.scroll >= 0) { "备份书签位置无效" }
                    val remote = row.toEntity().let { it.copy(percent = normalizeBackupPercent(it.percent)) }
                    val local = database.getBookmarksDao().find(remote.mangaId, remote.pageId)
                    require(local == null || local.chapterId == remote.chapterId && local.page == remote.page) {
                        "备份书签编号与本地页面冲突"
                    }
                    if (local == null || remote.createdAt >= local.createdAt) {
                        database.getBookmarksDao().upsert(listOf(remote))
                    }
                    restored++
                }
            }
            suspend fun statistic(remote: StatsEntity) {
                require(remote.mangaId in database.getMangaDao()) { "备份阅读统计缺少对应作品" }
                require(remote.duration >= 0 && remote.pages >= 0) { "备份阅读统计无效" }
                val local = database.getStatsDao().find(remote.mangaId, remote.startedAt)
                // Session counters have no update timestamp; merge maxima rather than add duplicate totals.
                database.getStatsDao().upsert(remote.copy(duration = maxOf(remote.duration, local?.duration ?: 0),
                    pages = maxOf(remote.pages, local?.pages ?: 0)))
                restored++
            }
            archive.rows(BackupSection.STATS, StatisticBackup.serializer()) {
                database.restoreReadingStatistic(it)
                restored++
            }
            archive.rows(BackupSection.WORK_STATS, WorkStatisticBackup.serializer()) {
                // Removed work ownership is read only as a legacy projection anchor.
                if (it.anchorMangaId in anchors) statistic(it.toStatsEntity())
            }
            archive.rows(BackupSection.SOURCES, SourceBackup.serializer()) {
                database.getSourcesDao().upsert(it.toEntity()); restored++
            }
            archive.rows(BackupSection.SOURCE_ORIGINS, SourceOriginBackup.serializer()) {
                require(it.sourceKey.isNotBlank()) { "备份来源分组无效" }
                val local = database.getSourceOriginsDao().getByKey(it.sourceKey)
                if (local == null || it.updatedAt >= local.updatedAt) database.getSourceOriginsDao().upsert(it.toEntity())
                restored++
            }
            archive.rows(BackupSection.EXTENSION_REPOS, ExtensionRepoBackup.serializer()) {
                require(it.baseUrl.isNotBlank()) { "备份扩展仓库地址无效" }
                database.getExternalExtensionRepoDao().upsert(it.toEntity()); restored++
            }
            // Rows of works the archive does not carry are skipped rather than failing the whole merge.
            archive.rows(BackupSection.TRACKS, TrackBackup.serializer()) {
                if (it.mangaId in database.getMangaDao()) { database.mergeRestoredTrack(it.toEntity()); restored++ }
            }
            archive.rows(BackupSection.TRACK_LOGS, TrackLogBackup.serializer()) {
                if (it.mangaId in database.getMangaDao()) { database.mergeRestoredTrackLog(it.toEntity()); restored++ }
            }
            restored
        }
    }

    private fun normalizeBackupPercent(percent: Float): Float = when {
        !percent.isFinite() -> BACKUP_PROGRESS_NONE
        percent in -1f..1f -> percent
        percent > 1f && percent <= 1f + PERCENT_OVERSHOOT -> 1f
        else -> BACKUP_PROGRESS_NONE
    }

    private suspend fun saveContent(backup: ContentBackup, tagBackups: Set<TagBackup> = backup.tags) {
        val candidate = backup.toEntity()
        val local = database.getMangaDao().find(candidate.id)?.manga
        require(local == null || (local.source == candidate.source &&
            (local.url == candidate.url || (local.publicUrl.isNotBlank() && local.publicUrl == candidate.publicUrl)))) {
            "备份作品编号与本地记录冲突：${candidate.id}"
        }
        val tags = tagBackups.map { it.toEntity() }
        require(tags.map { it.id }.toSet().size == tags.size) { "备份包含重复标签编号" }
        val oldTags = database.getTagsDao().findByIds(tags.map { it.id }).associateBy { it.id }
        val stableTags = tags.map { tag ->
            val previous = oldTags[tag.id]
            require(previous == null || (previous.source == tag.source && previous.key == tag.key)) { "备份标签编号冲突" }
            tag.copy(isPinned = tag.isPinned || previous?.isPinned == true)
        }
        database.getTagsDao().upsert(stableTags)
        // The Android wire format has no opaque source data/description/chapters; retain existing details.
        database.getMangaDao().upsert(candidate.copy(description = local?.description, sourceData = local?.sourceData), stableTags)
    }

    companion object {
        private const val PERCENT_OVERSHOOT = 0.1f

        val supportedSections = setOf(BackupSection.CONTENTS, BackupSection.CATEGORIES, BackupSection.FAVOURITES,
            BackupSection.HISTORY, BackupSection.SOURCES, BackupSection.WORK_FAVOURITES, BackupSection.WORK_HISTORY,
            BackupSection.BOOKMARKS, BackupSection.STATS, BackupSection.WORK_STATS, BackupSection.SOURCE_ORIGINS,
            BackupSection.EXTENSION_REPOS, BackupSection.TRACKS, BackupSection.TRACK_LOGS)

        fun requireSupported(index: BackupIndex) {
            require(index.semanticSchemaVersion in 1..BackupIndex.CURRENT_SYNC_SCHEMA_VERSION &&
                index.transportGeneration in 1..BackupIndex.WRITER_GENERATION_V3) { "备份版本尚不支持" }
        }

        /** Reads every present section once, so a malformed row fails the preview rather than the merge. */
        suspend fun count(input: LibraryBackupInput, present: Set<BackupSection>): Map<BackupSection, Int> {
            val counts = linkedMapOf<BackupSection, Int>()
            suspend fun <T> count(section: BackupSection, serializer: DeserializationStrategy<T>, size: (T) -> Int = { 1 }) {
                if (section !in present) return
                var count = 0L
                input.rows(section, serializer) {
                    count += size(it)
                    require(count <= Int.MAX_VALUE) { "备份记录数量超出范围" }
                }
                counts[section] = count.toInt()
            }
            count(BackupSection.CONTENTS, ContentBackup.serializer())
            count(BackupSection.CATEGORIES, CategoryBackup.serializer())
            count(BackupSection.FAVOURITES, FavouriteBackup.serializer())
            count(BackupSection.HISTORY, HistoryBackup.serializer())
            count(BackupSection.SOURCES, SourceBackup.serializer())
            count(BackupSection.WORK_FAVOURITES, WorkFavouriteBackup.serializer())
            count(BackupSection.WORK_HISTORY, WorkHistoryBackup.serializer())
            count(BackupSection.BOOKMARKS, BookmarkBackup.serializer()) { it.bookmarks.size }
            count(BackupSection.STATS, StatisticBackup.serializer())
            count(BackupSection.WORK_STATS, WorkStatisticBackup.serializer())
            count(BackupSection.SOURCE_ORIGINS, SourceOriginBackup.serializer())
            count(BackupSection.EXTENSION_REPOS, ExtensionRepoBackup.serializer())
            count(BackupSection.TRACKS, TrackBackup.serializer())
            count(BackupSection.TRACK_LOGS, TrackLogBackup.serializer())
            require(counts.isNotEmpty()) { "备份没有可恢复的图书馆数据或来源节" }
            return counts
        }
    }
}
