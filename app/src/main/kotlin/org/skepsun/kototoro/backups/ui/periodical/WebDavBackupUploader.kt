package org.skepsun.kototoro.backups.ui.periodical

import android.util.Log
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.writeFully
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import org.skepsun.kototoro.backups.webdav.WebDavBackupCatalog
import org.skepsun.kototoro.backups.webdav.WebDavBackupClient
import org.skepsun.kototoro.backups.webdav.WebDavBackupFile
import org.skepsun.kototoro.backups.webdav.WebDavEndpoint
import org.skepsun.kototoro.core.prefs.AppSettings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class BackupFileInfo(
    val name: String,
    val lastModified: Date,
    val size: Long,
    val dataVersion: Int? = null,
    val writerGeneration: Int = RemoteNamespace.V1.writerGeneration,
    val namespace: RemoteNamespace = RemoteNamespace.V1,
)

class WebDavBackupUploader @Inject constructor(
    private val settings: AppSettings,
    private val client: WebDavBackupClient,
) {
    private fun endpoint() = WebDavEndpoint(
        checkNotNull(settings.backupWebDavServerUrl) { "WebDAV server URL not set in settings" },
        settings.backupWebDavRemotePath ?: "",
        authorization = {
            val user = settings.backupWebDavUsername
            val password = settings.backupWebDavPassword
            if (!user.isNullOrEmpty() && password != null) Credentials.basic(user, password) else null
        },
    )

    suspend fun uploadBackup(
        file: File,
        targetVersion: Int = settings.backupWebDavDataVersion,
        namespace: RemoteNamespace = RemoteNamespace.V3,
    ) {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val name = WebDavBackupCatalog.remoteName(targetVersion, namespace, timestamp)
        Log.d(TAG, "uploadBackup: PUT $name")
        client.upload(endpoint(), name, file.length()) { channel ->
            withContext(Dispatchers.IO) {
                // Open a fresh stream for every upload attempt.
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        channel.writeFully(buffer, 0, count)
                    }
                }
            }
        }
        try {
            trimRemote(settings.periodicalBackupRemoteMaxCount, namespace)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV remote trim failed after upload", e)
        }
    }

    suspend fun sendTestConnection() = client.probe(endpoint())

    suspend fun listAllBackupFiles(): List<BackupFileInfo> = listRemoteFiles().map { it.toFileInfo() }

    suspend fun listBackupFiles(namespace: RemoteNamespace = RemoteNamespace.V3): List<BackupFileInfo> =
        listRemoteFiles().filter { it.namespace == namespace }.map { it.toFileInfo() }

    suspend fun downloadBackup(
        fileName: String,
        destinationFile: File,
        namespace: RemoteNamespace = RemoteNamespace.V3,
    ) {
        Log.d(TAG, "downloadBackup($namespace): GET $fileName, dest=$destinationFile")
        client.download(endpoint(), fileName) { channel ->
            withContext(Dispatchers.IO) {
                destinationFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
        }
    }

    suspend fun getLatestBackup(namespace: RemoteNamespace = RemoteNamespace.V3): BackupFileInfo? =
        listRemoteFiles().firstOrNull { it.namespace == namespace }?.toFileInfo()

    suspend fun getLatestBackup(): BackupFileInfo? = WebDavBackupCatalog.latest(listRemoteFiles())?.toFileInfo()

    suspend fun deleteRemote(fileName: String, namespace: RemoteNamespace = RemoteNamespace.V3) {
        Log.d(TAG, "deleteRemote($namespace): DELETE $fileName")
        client.delete(endpoint(), fileName)
    }

    suspend fun trimRemote(maxCount: Int, namespace: RemoteNamespace = RemoteNamespace.V3) {
        if (maxCount <= 0) return
        WebDavBackupCatalog.retired(listRemoteFiles(), maxCount, namespace).forEach { file ->
            try {
                deleteRemote(file.name, namespace)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to delete remote backup ${file.name}", e)
            }
        }
    }

    private suspend fun listRemoteFiles(): List<WebDavBackupFile> = client.list(endpoint()).also { files ->
        Log.d(TAG, "listAllBackupFiles: backups=${files.size}")
    }

    private fun WebDavBackupFile.toFileInfo() = BackupFileInfo(
        name, Date(lastModifiedMillis), size, dataVersion, writerGeneration, namespace,
    )

    private companion object {
        const val TAG = "WebDavBackupUploader"
    }
}
