package org.skepsun.kototoro.desktop.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.backups.domain.BackupSection
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.library.LibraryBackup
import org.skepsun.kototoro.core.library.LibraryBackupOutput
import java.nio.file.Files
import java.nio.file.Path
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Library transport over shared backup DTOs and the existing v84 tables; merge never clears local tables. */
class DesktopLibraryBackup(database: MangaDatabase) {
    private val shared = LibraryBackup(database)

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
        require(!Files.exists(target)) { DesktopRuntimeStrings.text("runtime.backup.targetExists", "the backup file already exists; choose a new name") }
        val temporary = Files.createTempFile(requireNotNull(target.parent), ".kototoro-backup-", ".tmp")
        try {
            ZipOutputStream(BackupLimitedOutput(Files.newOutputStream(temporary), DesktopBackupArchive.MAX_ARCHIVE_BYTES)).use { zip ->
                shared.export(BackupZipWriter(zip), "org.skepsun.kototoro", 1, System.currentTimeMillis())
            }
            currentCoroutineContext().ensureActive()
            Files.move(temporary, target)
        } finally { Files.deleteIfExists(temporary) }
    }

    suspend fun restore(archive: DesktopBackupArchive): Int = withContext(Dispatchers.IO) { shared.restore(archive) }
}

private class BackupZipWriter(private val zip: ZipOutputStream) : LibraryBackupOutput {
    private val payload = BackupLimitedOutput(zip, DesktopBackupArchive.MAX_EXPANDED_BYTES)
    override fun beginSection(section: BackupSection) = zip.putNextEntry(ZipEntry(section.entryName))
    override fun write(text: String) = payload.write(text.encodeToByteArray())
    override fun endSection() = zip.closeEntry()
}

/** Enforces the same limits as import before publishing an archive, including ZIP metadata bytes. */
internal class BackupLimitedOutput(private val output: OutputStream, private val limit: Long) : OutputStream() {
    private var count = 0L
    override fun write(value: Int) {
        require(count < limit) { DesktopRuntimeStrings.text("runtime.backup.tooLarge", "the backup data exceeds the size limit; no file was written") }
        output.write(value)
        count++
    }
    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        require(length.toLong() <= limit - count) { DesktopRuntimeStrings.text("runtime.backup.tooLarge", "the backup data exceeds the size limit; no file was written") }
        output.write(bytes, offset, length)
        count += length
    }
    override fun flush() = output.flush()
    override fun close() = output.close()
}
