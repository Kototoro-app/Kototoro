@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.runtime

import androidx.room.immediateTransaction
import androidx.room.useWriterConnection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.json.encodeToStream
import org.skepsun.kototoro.backups.data.model.*
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.favourites.data.FavouriteEntity
import org.skepsun.kototoro.history.data.HistoryEntity
import org.skepsun.kototoro.stats.data.StatsEntity
import java.nio.file.Files
import java.nio.file.Path
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Library transport over shared backup DTOs and the existing v84 tables; merge never clears local tables. */
class DesktopLibraryBackup(private val database: MangaDatabase) {
    suspend fun preview(path: Path): DesktopBackupArchive {
        var acquired: DesktopBackupArchive? = null
        try { return withContext(Dispatchers.IO) { DesktopBackupArchive.open(path).also { acquired = it } } }
        catch (error: Throwable) {
            withContext(NonCancellable + Dispatchers.IO) {
                try { acquired?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
            }
            throw error
        }
    }

    suspend fun export(path: Path) = withContext(Dispatchers.IO) {
        val target = path.toAbsolutePath().normalize()
        require(!Files.exists(target)) { "目标文件已存在，请选择新的备份文件名" }
        val temporary = Files.createTempFile(requireNotNull(target.parent), ".kototoro-backup-", ".tmp")
        try {
            ZipOutputStream(BackupLimitedOutput(Files.newOutputStream(temporary), DesktopBackupArchive.MAX_ARCHIVE_BYTES)).use { zip ->
                val output = BackupZipWriter(zip)
                database.useWriterConnection { connection -> connection.immediateTransaction {
                    val now = System.currentTimeMillis()
                    write(output, BackupSection.INDEX, flowOf(BackupIndex("org.skepsun.kototoro", 1,
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
                    write(output, BackupSection.STATS, database.getStatsDao().dump().map {
                        ids += it.mangaId
                        StatisticBackup(it)
                    }, StatisticBackup.serializer())
                    write(output, BackupSection.CONTENTS, flow {
                        for (chunk in ids.chunked(100)) {
                            database.getMangaDao().findWithTagsByIds(chunk).forEach { emit(ContentBackup(it)) }
                        }
                    }, ContentBackup.serializer())
                } }
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target)
        } finally { Files.deleteIfExists(temporary) }
    }

    private suspend fun <T> write(output: BackupZipWriter, section: BackupSection, rows: Flow<T>, serializer: SerializationStrategy<T>) {
        output.zip.putNextEntry(ZipEntry(section.entryName))
        output.payload.write('['.code)
        var first = true
        rows.collect { row ->
            currentCoroutineContext().ensureActive()
            if (!first) output.payload.write(','.code)
            first = false
            DesktopBackupJson.encodeToStream(serializer, row, output.payload)
        }
        output.payload.write(']'.code)
        output.zip.closeEntry()
    }

    suspend fun restore(archive: DesktopBackupArchive): Int = withContext(Dispatchers.IO) {
        database.useWriterConnection { connection -> connection.immediateTransaction {
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
            suspend fun history(remote: HistoryEntity) {
                require(remote.mangaId in database.getMangaDao()) { "备份历史缺少对应作品" }
                require(remote.page >= 0 && remote.scroll.isFinite() && remote.scroll >= 0 && remote.percent.isFinite() &&
                    remote.percent in -1f..1f && remote.chaptersCount >= 0) { "备份阅读进度无效" }
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
                    require(row.page >= 0 && row.scroll >= 0 && row.percent.isFinite() && row.percent in -1f..1f) {
                        "备份书签位置无效"
                    }
                    val remote = row.toEntity()
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
            archive.rows(BackupSection.STATS, StatisticBackup.serializer()) { statistic(it.toEntity()) }
            archive.rows(BackupSection.WORK_STATS, WorkStatisticBackup.serializer()) {
                // Removed work ownership is read only as a legacy projection anchor.
                if (it.anchorMangaId in anchors) statistic(it.toStatsEntity())
            }
            archive.rows(BackupSection.SOURCES, SourceBackup.serializer()) {
                database.getSourcesDao().upsert(it.toEntity()); restored++
            }
            restored
        } }
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
}

private class BackupZipWriter(val zip: ZipOutputStream) {
    val payload = BackupLimitedOutput(zip, DesktopBackupArchive.MAX_EXPANDED_BYTES)
}

/** Enforces the same limits as import before publishing an archive, including ZIP metadata bytes. */
internal class BackupLimitedOutput(private val output: OutputStream, private val limit: Long) : OutputStream() {
    private var count = 0L
    override fun write(value: Int) {
        require(count < limit) { "备份数据超过大小限制，未生成备份文件" }
        output.write(value)
        count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length.toLong() <= limit - count) { "备份数据超过大小限制，未生成备份文件" }
        output.write(bytes, offset, length)
        count += length
    }
    override fun flush() = output.flush()
    override fun close() = output.close()
}
