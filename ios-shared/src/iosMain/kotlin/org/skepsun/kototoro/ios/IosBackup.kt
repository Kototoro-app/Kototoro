package org.skepsun.kototoro.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.library.LibraryBackup
import org.skepsun.kototoro.core.library.LibraryBackupInput
import org.skepsun.kototoro.core.library.LibraryBackupJson
import org.skepsun.kototoro.core.library.LibraryBackupOutput

data class IosBackupSectionCount(val section: String, val count: Int)

/** A verified in-memory snapshot; changing the picked file afterwards cannot change a confirmed restore. */
class IosBackupPreview internal constructor(
    internal val zip: BackupZipReader,
    val filename: String,
    val createdAt: Long,
    val appId: String,
    val counts: List<IosBackupSectionCount>,
    /** Android sections such as settings or tracker accounts, which iOS keeps unchanged. */
    val skippedSections: List<String>,
) : LibraryBackupInput {
    /** Set only while a restore reads the rows; counts them against [counts] for its progress. */
    internal var progress: ProgressThrottle? = null
    private var consumed = 0L

    override suspend fun <T> rows(section: BackupSection, serializer: DeserializationStrategy<T>,
        consume: suspend (T) -> Unit) {
        val entry = zip.entries.firstOrNull { BackupSection.of(it.name) == section } ?: return
        val rows = LibraryBackupJson.parseToJsonElement(zip.read(entry).decodeToString()) as? JsonArray
            ?: throw IllegalArgumentException(localized("备份节格式无效：%@", entry.name))
        val total = counts.sumOf { it.count.toLong() }
        for (row in rows) {
            currentCoroutineContext().ensureActive()
            consume(LibraryBackupJson.decodeFromJsonElement(serializer, row))
            progress?.report(section.entryName, ++consumed, maxOf(total, consumed))
        }
    }

    internal suspend fun <T> reading(listener: IosProgressListener?, block: suspend () -> T): T {
        progress = ProgressThrottle(listener)
        consumed = 0
        try { return block() } finally { progress = null }
    }
}

internal class IosBackupService(private val library: IosLibrary) {
    private val backup by lazy { LibraryBackup(library.database, localizeError = { localized(it) }) }

    suspend fun preview(path: String): IosBackupPreview = withContext(Dispatchers.IO) {
        val zip = BackupZipReader.open(path)
        val indexEntry = zip.entries.singleOrNull { BackupSection.of(it.name) == BackupSection.INDEX }
            ?: throw IllegalArgumentException(localized("所选文件没有 Kototoro/Kotatsu 备份索引"))
        val indices = LibraryBackupJson.decodeFromString(ListSerializer(BackupIndex.serializer()),
            zip.read(indexEntry).decodeToString())
        require(indices.size == 1) { localized("备份索引数量无效") }
        val index = indices.single()
        LibraryBackup.requireSupported(index, localizeError = { localized(it) })
        val sections = zip.entries.mapNotNull { BackupSection.of(it.name) }.toSet()
        val draft = IosBackupPreview(zip, path.substringAfterLast('/'), index.createdAt, index.appId, emptyList(), emptyList())
        val counts = LibraryBackup.count(draft, sections, localizeError = { localized(it) })
        IosBackupPreview(zip, draft.filename, index.createdAt, index.appId,
            counts.map { (section, count) -> IosBackupSectionCount(section.entryName, count) },
            zip.entries.map { it.name }.filter {
                val section = BackupSection.of(it)
                section !in LibraryBackup.supportedSections && section != BackupSection.INDEX
            })
    }

    suspend fun restore(preview: IosBackupPreview, listener: IosProgressListener?): Int = withContext(Dispatchers.IO) {
        preview.reading(listener) { backup.restore(preview) }
    }

    /** [progress] hears each section as it starts, counting sections; the export does not know their number. */
    suspend fun export(path: String, now: Long, progress: ProgressThrottle) = withContext(Dispatchers.IO) {
        var sections = 0L
        BackupZipWriter(path).use { zip ->
            backup.export(object : LibraryBackupOutput {
                override fun beginSection(section: BackupSection) {
                    progress.report(section.entryName, sections++, 0)
                    zip.begin(section.entryName)
                }
                override fun write(text: String) = zip.write(text.encodeToByteArray())
                override fun endSection() = zip.end()
            }, "org.skepsun.kototoro", 1, now)
            currentCoroutineContext().ensureActive()
            zip.finish()
        }
    }
}
