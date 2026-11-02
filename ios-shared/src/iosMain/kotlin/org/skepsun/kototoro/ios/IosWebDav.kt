@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package org.skepsun.kototoro.ios

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import io.ktor.client.plugins.HttpTimeout
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.parseWebDavResources
import org.skepsun.kototoro.core.library.WebDavLibrarySync
import org.skepsun.kototoro.core.library.WebDavSettings
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseeko
import platform.posix.ftello
import platform.posix.fwrite
import platform.posix.remove

/**
 * Android's WebDAV backups on iOS, over core-library's `WebDavLibrarySync` (the request shapes, remote names and
 * retention Windows and Android use): test the server, upload a library backup and retire old ones, list them, and
 * download one into a file the existing backup preview/restore reads.
 */
class IosWebDav internal constructor(private val library: IosLibrary) {

    private suspend fun <T> withSync(block: suspend (WebDavLibrarySync) -> T): T = withContext(Dispatchers.IO) {
        val client = HttpClient(Darwin) {
            install(HttpTimeout) { connectTimeoutMillis = 30_000; requestTimeoutMillis = 300_000 }
        }
        try { block(WebDavLibrarySync(WebDavBackupClient(client, client, ::parseWebDavResources))) } finally { client.close() }
    }

    @Throws(Exception::class)
    suspend fun probe(settings: WebDavSettings) = withSync { it.probe(settings) }

    /** The backups on the server, newest first. */
    @Throws(Exception::class)
    suspend fun list(settings: WebDavSettings): List<IosWebDavFile> = withSync { sync ->
        sync.list(settings).sortedByDescending { it.lastModifiedMillis }
            .map { IosWebDavFile(it.name, it.size, it.lastModifiedMillis) }
    }

    /** Exports the library, uploads it under Android's v3 name stamped [timestamp], and retires old backups. */
    @Throws(Exception::class)
    suspend fun upload(settings: WebDavSettings, timestamp: String): IosWebDavUpload {
        require(settings.configured) { localized("请先填写 WebDAV 服务器地址") }
        val path = temporaryFile("upload")
        try {
            library.exportBackup(path)
            val size = fileSize(path)
            return withSync { sync ->
                val name = sync.remoteName(BACKUP_DATA_VERSION, timestamp)
                sync.upload(settings, name, size) { channel ->
                    // A retried PUT calls the writer again: the file is opened inside it.
                    val file = fopen(path, "rb") ?: error(localized("无法读取备份文件"))
                    try {
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = buffer.usePinned { fread(it.addressOf(0), 1u, BUFFER.convert(), file) }.toInt()
                            if (count <= 0) break
                            channel.writeFully(buffer, 0, count)
                        }
                    } finally { fclose(file) }
                }
                IosWebDavUpload(name, size, sync.trim(settings))
            }
        } finally { remove(path) }
    }

    /** Downloads backup [name] into a temporary file and returns its path; nothing is left behind on failure. */
    @Throws(Exception::class)
    suspend fun download(settings: WebDavSettings, name: String): String {
        val path = temporaryFile("download")
        try {
            withSync { sync ->
                sync.download(settings, name) { channel ->
                    val file = fopen(path, "wb") ?: error(localized("无法写入备份文件"))
                    try {
                        val buffer = ByteArray(BUFFER)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = channel.readAvailable(buffer, 0, BUFFER)
                            if (count < 0) break
                            if (count == 0) continue
                            val written = buffer.usePinned { fwrite(it.addressOf(0), 1u, count.convert(), file) }.toInt()
                            check(written == count) { localized("无法写入备份文件") }
                        }
                    } finally { fclose(file) }
                }
            }
            return path
        } catch (error: Throwable) {
            remove(path)
            throw error
        }
    }

    private fun temporaryFile(kind: String) = NSTemporaryDirectory() + "webdav-$kind-${NSUUID().UUIDString}.zip"

    private fun fileSize(path: String): Long {
        val file = fopen(path, "rb") ?: error(localized("无法读取备份文件"))
        try { check(fseeko(file, 0, SEEK_END) == 0); return ftello(file) } finally { fclose(file) }
    }

    companion object {
        /** The data version in the remote name, as the Windows uploader writes it. */
        const val BACKUP_DATA_VERSION = 1
        private const val BUFFER = 64 * 1024

        /** Settings as the iOS page edits them (Android's keys and defaults). */
        fun settings(serverUrl: String, remotePath: String, username: String, password: String, maxCount: Int,
                     intervalHours: Int) = WebDavSettings(serverUrl.trim(), remotePath.trim(), username, password,
            maxCount.coerceAtLeast(0), intervalHours.coerceAtLeast(0))
    }
}

data class IosWebDavFile(val name: String, val size: Long, val modifiedAt: Long)

data class IosWebDavUpload(val name: String, val size: Long, val removed: List<String>)
