@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.DecodeSequenceMode
import kotlinx.serialization.json.decodeToSequence
import org.skepsun.kototoro.backups.data.model.BackupIndex
import org.skepsun.kototoro.core.library.LibraryBackup
import org.skepsun.kototoro.core.library.LibraryBackupInput
import org.skepsun.kototoro.core.library.LibraryBackupJson
import org.skepsun.kototoro.backups.domain.BackupSection
import java.io.Closeable
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

internal val DesktopBackupJson = LibraryBackupJson

/** Owns a private copy, so changing the selected file cannot change a confirmed restore. */
class DesktopBackupArchive private constructor(
    private val snapshot: Path,
    private val zip: ZipFile,
    val filename: String,
    val index: BackupIndex,
    val counts: Map<BackupSection, Int>,
    val otherEntries: List<String>,
) : Closeable, LibraryBackupInput {
    private val closed = AtomicBoolean()

    override suspend fun <T> rows(section: BackupSection, serializer: DeserializationStrategy<T>, consume: suspend (T) -> Unit) {
        check(!closed.get()) { DesktopRuntimeStrings.text("runtime.backup.previewClosed", "the preview was closed; choose the file again") }
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
        internal val supported = LibraryBackup.supportedSections

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
                        require(copied <= MAX_ARCHIVE_BYTES) { DesktopRuntimeStrings.text("runtime.backup.archiveTooLarge", "the backup archive exceeds the 512 MiB limit") }
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
                            names.add(entry.name.lowercase())) { DesktopRuntimeStrings.text("runtime.backup.invalidSections", "the backup has duplicate or invalid section names") }
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            expanded += read
                            require(expanded <= MAX_EXPANDED_BYTES) { DesktopRuntimeStrings.text("runtime.backup.expandedTooLarge", "the expanded backup exceeds the 1 GiB limit") }
                        }
                        input.closeEntry()
                    }
                }
                opened = ZipFile(snapshot.toFile())
                val centralNames = opened.entries().asSequence().map { it.name.lowercase() }.toList()
                require(centralNames.size == names.size && centralNames.toSet() == names) { DesktopRuntimeStrings.text("runtime.backup.directoryMismatch", "the backup ZIP's directory does not match its contents") }
                val indexEntry = opened.entries().asSequence().singleOrNull {
                    BackupSection.of(it.name) == BackupSection.INDEX
                } ?: throw IllegalArgumentException(DesktopRuntimeStrings.text("runtime.backup.noIndex", "the chosen file has no Kototoro/Kotatsu backup index"))
                val indices = opened.getInputStream(indexEntry).use {
                    DesktopBackupJson.decodeToSequence(it, BackupIndex.serializer(), DecodeSequenceMode.ARRAY_WRAPPED).take(2).toList()
                }
                require(indices.size == 1) { DesktopRuntimeStrings.text("runtime.backup.invalidIndexCount", "the backup index count is invalid") }
                val index = indices.single()
                LibraryBackup.requireSupported(index)
                val archive = DesktopBackupArchive(snapshot, opened, path.fileName.toString(), index, emptyMap(), emptyList())
                val counts = LibraryBackup.count(archive, names.mapNotNull(BackupSection::of).toSet())
                return DesktopBackupArchive(snapshot, opened, path.fileName.toString(), index, counts, names.filter {
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
