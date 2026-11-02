package org.skepsun.kototoro.local.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.core.util.MultiMutex
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Owns the local copies needed while a SAF archive parser requires random access.
 *
 * Copies are transient everywhere: they are released as soon as the action that needs random
 * access returns, so a folder of archives never doubles on disk (#575). Copies are also gated,
 * because a scan parses several archives concurrently and the volumes of a comic library are
 * routinely hundreds of megabytes each: keeping four of them alive at once can exhaust the
 * cache volume even though every single copy is temporary.
 */
internal object LocalSafArchiveCache {

    const val DIRECTORY_NAME = "local_saf"

    /** Above this size one copy already owns a large share of a phone's free space. */
    private const val LARGE_ARCHIVE_BYTES = 96L * 1024 * 1024
    private const val SMALL_COPY_PARALLELISM = 4
    private const val LARGE_COPY_PARALLELISM = 1

    /** Left free on the cache volume so a copy can never be the write that fills the device. */
    private const val FREE_SPACE_HEADROOM_BYTES = 32L * 1024 * 1024

    private val smallCopies = Semaphore(SMALL_COPY_PARALLELISM)
    private val largeCopies = Semaphore(LARGE_COPY_PARALLELISM)
    private val locks = MultiMutex<File>()
    private val activeFiles = mutableSetOf<File>()

    fun directory(cacheDir: File): File = File(cacheDir, DIRECTORY_NAME)

    /** Where the copy of [fileName] lives; its path is also part of the archive's content id. */
    fun target(cacheDir: File, fileName: String): File = File(directory(cacheDir), fileName).absoluteFile

    suspend fun <T> withArchive(
        cacheDir: File,
        fileName: String,
        sourceLength: Long,
        sourceModified: Long,
        forceRefresh: Boolean = false,
        availableBytes: (() -> Long)? = null,
        largeThresholdBytes: Long = LARGE_ARCHIVE_BYTES,
        openInput: () -> InputStream,
        action: suspend (File) -> T,
    ): T {
        val target = target(cacheDir, fileName)
        val gate = if (sourceLength > largeThresholdBytes) largeCopies else smallCopies
        return locks.withLock(target) {
            gate.withPermit {
                try {
                    runInterruptible(Dispatchers.IO) {
                        synchronized(activeFiles) {
                            activeFiles += target
                            target.parentFile?.mkdirs()
                            clearUnused(cacheDir)
                        }
                        if (forceRefresh || !target.isFile ||
                            sourceLength >= 0 && target.length() != sourceLength ||
                            sourceModified > 0 && target.lastModified() != sourceModified
                        ) {
                            requireSpaceForCopy(cacheDir, sourceLength, availableBytes)
                            openInput().use { input -> target.outputStream().use(input::copyTo) }
                            require(sourceLength <= 0 || target.length() == sourceLength) { "Incomplete SAF archive copy" }
                            if (sourceModified > 0) target.setLastModified(sourceModified)
                        }
                    }
                    action(target)
                } finally {
                    withContext(NonCancellable + Dispatchers.IO) {
                        synchronized(activeFiles) {
                            target.delete()
                            activeFiles -= target
                        }
                    }
                }
            }
        }
    }

    fun clearUnused(cacheDir: File) = synchronized(activeFiles) {
        directory(cacheDir).listFiles().orEmpty().forEach { file ->
            if (file.isFile && file.absoluteFile !in activeFiles) file.delete()
        }
    }

    private fun requireSpaceForCopy(cacheDir: File, sourceLength: Long, availableBytes: (() -> Long)?) {
        if (sourceLength <= 0) return
        // An explicit provider is authoritative; the platform value is only trusted when measurable.
        val available = availableBytes?.invoke() ?: cacheDir.usableSpace.takeIf { it > 0 } ?: return
        val required = sourceLength + FREE_SPACE_HEADROOM_BYTES
        if (available < required) {
            throw IOException(
                "Not enough free space to open a ${sourceLength / MEBIBYTE} MB archive: " +
                    "only ${available / MEBIBYTE} MB available in the cache volume",
            )
        }
    }

    private const val MEBIBYTE = 1024L * 1024L
}
