@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeToSequence
import org.skepsun.kototoro.backups.data.model.*
import org.skepsun.kototoro.backups.domain.BackupSection
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal val DesktopBackupJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

/** Owns a private copy, so changing the selected file cannot change a confirmed restore. */
class DesktopBackupArchive private constructor(
    private val snapshot: Path,
    private val zip: ZipFile,
    val filename: String,
    val index: BackupIndex,
    val counts: Map<BackupSection, Int>,
    val otherEntries: List<String>,
) : Closeable {
    private val closed = AtomicBoolean()

    internal suspend fun <T> rows(section: BackupSection, serializer: DeserializationStrategy<T>, consume: suspend (T) -> Unit) {
        check(!closed.get()) { "备份预览已经关闭，请重新选择文件" }
        val entry = zip.entries().asSequence().firstOrNull { BackupSection.of(it.name) == section } ?: return
        zip.getInputStream(entry).use { input ->
            for (row in DesktopBackupJson.decodeToSequence(input, serializer, DecodeSequenceMode.ARRAY_WRAPPED)) {
                currentCoroutineContext().ensureActive()
                consume(row)
            }
        }
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        try { zip.close() } finally { Files.deleteIfExists(snapshot) }
    }

    companion object {
        internal const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
        internal const val MAX_EXPANDED_BYTES = 1024L * 1024 * 1024
        internal val supported = setOf(BackupSection.CONTENTS, BackupSection.CATEGORIES, BackupSection.FAVOURITES,
            BackupSection.HISTORY, BackupSection.SOURCES, BackupSection.WORK_FAVOURITES, BackupSection.WORK_HISTORY,
            BackupSection.BOOKMARKS, BackupSection.STATS, BackupSection.WORK_STATS)

        internal suspend fun open(path: Path): DesktopBackupArchive {
            val snapshot = Files.createTempFile("kototoro-backup-", ".zip")
            var opened: ZipFile? = null
            try {
                Files.newInputStream(path).use { source -> Files.newOutputStream(snapshot).use { output ->
                    val buffer = ByteArray(65536)
                    var copied = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = source.read(buffer)
                        if (read < 0) break
                        copied += read
                        require(copied <= MAX_ARCHIVE_BYTES) { "备份压缩文件超过 512 MiB 限制" }
                        output.write(buffer, 0, read)
                    }
                } }
                val names = linkedSetOf<String>()
                // ZipInputStream reads every payload/CRC, including unsupported entries, without extracting paths.
                ZipInputStream(Files.newInputStream(snapshot)).use { input ->
                    val buffer = ByteArray(65536)
                    var expanded = 0L
                    while (true) {
                        val entry = input.nextEntry ?: break
                        require(!entry.isDirectory && entry.name.length <= 128 &&
                            '/' !in entry.name && '\\' !in entry.name && names.size < 256 &&
                            names.add(entry.name.lowercase())) { "备份包含重复或无效的节名" }
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            expanded += read
                            require(expanded <= MAX_EXPANDED_BYTES) { "备份解压数据超过 1 GiB 限制" }
                        }
                        input.closeEntry()
                    }
                }
                opened = ZipFile(snapshot.toFile())
                val centralNames = opened.entries().asSequence().map { it.name.lowercase() }.toList()
                require(centralNames.size == names.size && centralNames.toSet() == names) { "备份 ZIP 目录与内容不一致" }
                val indexEntry = opened.entries().asSequence().singleOrNull {
                    BackupSection.of(it.name) == BackupSection.INDEX
                } ?: throw IllegalArgumentException("所选文件没有 Kototoro/Kotatsu 备份索引")
                val indices = opened.getInputStream(indexEntry).use {
                    DesktopBackupJson.decodeToSequence(it, BackupIndex.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).take(2).toList()
                }
                require(indices.size == 1) { "备份索引数量无效" }
                val index = indices.single()
                require(index.semanticSchemaVersion in 1..BackupIndex.CURRENT_SYNC_SCHEMA_VERSION &&
                    index.transportGeneration in 1..BackupIndex.WRITER_GENERATION_V3) { "备份版本尚不支持" }
                val archive = DesktopBackupArchive(snapshot, opened, path.fileName.toString(), index, emptyMap(), emptyList())
                val counts = linkedMapOf<BackupSection, Int>()
                suspend fun <T> count(section: BackupSection, serializer: DeserializationStrategy<T>, size: (T) -> Int = { 1 }) {
                    if (section.entryName !in names) return
                    var count = 0
                    archive.rows(section, serializer) { count = Math.addExact(count, size(it)) }
                    counts[section] = count
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
                require(counts.isNotEmpty()) { "备份没有可恢复的图书馆数据或来源节" }
                return DesktopBackupArchive(snapshot, opened, path.fileName.toString(), index, counts.toMap(), names.filter {
                    BackupSection.of(it) !in supported && BackupSection.of(it) != BackupSection.INDEX
                })
            } catch (error: Throwable) {
                try { opened?.close() } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                try { Files.deleteIfExists(snapshot) } catch (cleanup: Throwable) { error.addSuppressed(cleanup) }
                throw error
            }
        }
    }
}
