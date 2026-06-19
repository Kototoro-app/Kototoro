package org.skepsun.kototoro.backups.ui.periodical

import android.util.Log
import androidx.annotation.CheckResult
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.Response
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.parsers.util.await
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.system.measureTimeMillis
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

data class BackupFileInfo(
    val name: String,
    val lastModified: Date,
    val size: Long,
    val dataVersion: Int? = null,
    val writerGeneration: Int = RemoteNamespace.V1.writerGeneration,
    val namespace: RemoteNamespace = RemoteNamespace.V1,
)

enum class RemoteNamespace(
    val writerGeneration: Int,
) {
    V1(writerGeneration = 1),
    V2(writerGeneration = 2),
    V3(writerGeneration = 3),
}

class WebDavBackupUploader @Inject constructor(
    private val settings: AppSettings,
    @BaseHttpClient private val baseClient: OkHttpClient,
) {

    companion object {
        private const val TAG = "WebDavBackupUploader"
        private const val WEBDAV_CALL_TIMEOUT_SECONDS = 30L
        private const val WEBDAV_UPLOAD_CALL_TIMEOUT_SECONDS = 120L
    }

    /**
     * WebDAV-specific client with per-call timeout to prevent hanging.
     * The base client has no callTimeout (0 = unlimited), which causes
     * PROPFIND Depth:1 to hang indefinitely on servers that respond slowly
     * or send incomplete/malformed responses.
     */
    private val webDavClient: OkHttpClient by lazy {
        baseClient.newBuilder()
            .callTimeout(WEBDAV_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    private fun requireServerUrl(): String = checkNotNull(settings.backupWebDavServerUrl) {
        "WebDAV server URL not set in settings"
    }

    private fun requireRemotePath(): String = settings.backupWebDavRemotePath ?: ""

    @CheckResult
    private fun basicAuthHeaderOrNull(): String? {
        val user = settings.backupWebDavUsername
        val pass = settings.backupWebDavPassword
        return if (!user.isNullOrEmpty() && pass != null) Credentials.basic(user, pass) else null
    }

    private fun composeUrl(fileName: String?, namespace: RemoteNamespace): String {
        val base = requireServerUrl().trimEnd('/')
        val basePath = requireRemotePath().trim('/').let { if (it.isEmpty()) "" else "/$it" }
        return if (fileName == null) {
            "$base$basePath/"
        } else {
            "$base$basePath/$fileName"
        }
    }

    private fun buildVersionedRemoteName(version: Int, namespace: RemoteNamespace): String {
        val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return when (namespace) {
            RemoteNamespace.V1 -> "kototoro-v${version}-${ts}.zip"
            RemoteNamespace.V2 -> "kototoro-v2-data-v${version}-${ts}.zip"
            RemoteNamespace.V3 -> "kototoro-v3-work-v${version}-${ts}.zip"
        }
    }

    suspend fun uploadBackup(
        file: File,
        targetVersion: Int = settings.backupWebDavDataVersion,
        namespace: RemoteNamespace = RemoteNamespace.V3,
    ) {
        val remoteName = buildVersionedRemoteName(targetVersion, namespace)
        val url = composeUrl(remoteName, namespace)
        val body = file.asRequestBody("application/zip".toMediaTypeOrNull())

        // Upload uses a dedicated client with longer callTimeout
        val uploadClient = baseClient.newBuilder()
            .callTimeout(WEBDAV_UPLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        withRetry(maxAttempts = 3, initialDelayMs = 1000) {
            val builder = Request.Builder().url(url).put(body)
            basicAuthHeaderOrNull()?.let { builder.header("Authorization", it) }
            Log.d(TAG, "uploadBackup: PUT $remoteName")
            val resp = uploadClient.newCall(builder.build()).await()
            if (!resp.isSuccessful) {
                val code = resp.code
                val msg = resp.message
                val detail = responseBodyPreview(resp)
                Log.e(TAG, "WebDAV upload failed: $code $msg. Response: ${detail ?: "<empty>"}")
                resp.close()
                if (detail.isNullOrBlank()) {
                    throw RuntimeException("WebDAV upload failed: $code $msg")
                } else {
                    throw RuntimeException("WebDAV upload failed: $code $msg. Response: $detail")
                }
            }
            resp.close()
        }

        try {
            trimRemote(maxCount = settings.periodicalBackupRemoteMaxCount, namespace = namespace)
        } catch (e: Exception) {
            Log.w(TAG, "WebDAV remote trim failed after upload", e)
        }
    }

    private suspend fun <T> withRetry(maxAttempts: Int = 3, initialDelayMs: Long = 1000, block: suspend () -> T): T {
        var attempt = 0
        var delayMs = initialDelayMs
        var lastError: Exception? = null
        while (attempt < maxAttempts) {
            try {
                return block()
            } catch (e: Exception) {
                lastError = e
                if (attempt == maxAttempts - 1) break
                delay(delayMs)
                delayMs *= 2
                attempt++
            }
        }
        throw lastError ?: RuntimeException("Unknown error in withRetry")
    }

    suspend fun sendTestConnection() {
        // Use PROPFIND Depth: 0 against the directory URL (with trailing slash)
        // Many WebDAV servers do not support HEAD on directories reliably
        val url = composeUrl(null, RemoteNamespace.V3)
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", okhttp3.RequestBody.create("application/xml".toMediaTypeOrNull(), propfindBody))
            .header("Depth", "0")
        basicAuthHeaderOrNull()?.let { builder.header("Authorization", it) }

        Log.d(TAG, "sendTestConnection: PROPFIND Depth:0 → $url")
        val resp = webDavClient.newCall(builder.build()).await()
        Log.d(TAG, "sendTestConnection: response code=${resp.code}")
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw RuntimeException("WebDAV connection failed: $code $msg")
        }
        resp.close()
    }

    suspend fun listBackupFiles(namespace: RemoteNamespace = RemoteNamespace.V3): List<BackupFileInfo> {
        val result = listAllBackupFiles().filter { it.namespace == namespace }
        Log.d(TAG, "listBackupFiles($namespace): found ${result.size} backup files")
        return result
    }

    suspend fun listAllBackupFiles(): List<BackupFileInfo> {
        val url = composeUrl(null, RemoteNamespace.V3)
        val propfindBody = """
            <?xml version="1.0" encoding="utf-8" ?>
            <D:propfind xmlns:D="DAV:">
                <D:prop>
                    <D:displayname/>
                    <D:getlastmodified/>
                    <D:getcontentlength/>
                </D:prop>
            </D:propfind>
        """.trimIndent()

        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", okhttp3.RequestBody.create("application/xml".toMediaTypeOrNull(), propfindBody))
            .header("Depth", "1")
        basicAuthHeaderOrNull()?.let { builder.header("Authorization", it) }

        Log.d(TAG, "listAllBackupFiles: PROPFIND Depth:1 → $url")
        var responseCode = -1
        var networkMs = 0L
        val resp = run {
            var response: Response? = null
            networkMs = measureTimeMillis {
                response = webDavClient.newCall(builder.build()).await()
            }
            response!!
        }
        responseCode = resp.code
        Log.d(TAG, "listAllBackupFiles: response code=$responseCode networkMs=$networkMs")
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            if (code == 404) {
                resp.close()
                Log.d(TAG, "listAllBackupFiles: 404 → empty list")
                return emptyList()
            }
            resp.close()
            throw RuntimeException("WebDAV PROPFIND failed: $code $msg")
        }

        val responseBody = resp.body?.string() ?: ""
        resp.close()

        var parseMs = 0L
        var totalRemoteEntries = 0
        val result = run {
            var parsed = emptyList<BackupFileInfo>()
            parseMs = measureTimeMillis {
                val rawEntries = parseWebDavResponse(responseBody)
                totalRemoteEntries = rawEntries.size
                parsed = rawEntries
                    .filter { isBackupFileName(it.name) }
                    .map { raw ->
                        val namespace = resolveNamespace(raw.name)
                        raw.copy(
                            dataVersion = parseDataVersion(raw.name, namespace),
                            writerGeneration = namespace.writerGeneration,
                            namespace = namespace,
                        )
                    }
                    .sortedByDescending { it.lastModified }
            }
            parsed
        }
        val v3Count = result.count { it.namespace == RemoteNamespace.V3 }
        val v2Count = result.count { it.namespace == RemoteNamespace.V2 }
        val v1Count = result.count { it.namespace == RemoteNamespace.V1 }
        Log.d(
            TAG,
            "listAllBackupFiles: parsed remoteEntries=$totalRemoteEntries backups=${result.size} " +
                "v3=$v3Count v2=$v2Count v1=$v1Count parseMs=$parseMs",
        )
        return result
    }

    suspend fun downloadBackup(
        fileName: String,
        destinationFile: File,
        namespace: RemoteNamespace = RemoteNamespace.V3,
    ) {
        val url = composeUrl(fileName, namespace)
        val builder = Request.Builder().url(url).get()
        basicAuthHeaderOrNull()?.let { builder.header("Authorization", it) }

        // Download uses dedicated client with longer timeout
        val downloadClient = baseClient.newBuilder()
            .callTimeout(WEBDAV_UPLOAD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()

        Log.d(TAG, "downloadBackup: GET $fileName, dest=$destinationFile")
        val resp = downloadClient.newCall(builder.build()).await()
        if (!resp.isSuccessful) {
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw RuntimeException("WebDAV download failed: $code $msg")
        }

        Log.d(TAG, "downloadBackup: response code=${resp.code}, contentLength=${resp.body?.contentLength()}")
        resp.body?.let { body ->
            var totalBytes = 0L
            FileOutputStream(destinationFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        if (totalBytes % (512 * 1024) == 0L) {
                            Log.d(TAG, "downloadBackup: downloaded ${totalBytes / 1024}KB")
                        }
                    }
                }
            }
            Log.d(TAG, "downloadBackup: complete, ${totalBytes / 1024}KB total")
        }
        resp.close()
    }

    suspend fun getLatestBackup(namespace: RemoteNamespace = RemoteNamespace.V3): BackupFileInfo? {
        Log.d(TAG, "getLatestBackup($namespace): listing files...")
        val result = listBackupFiles(namespace).firstOrNull()
        Log.d(TAG, "getLatestBackup($namespace): result=${result?.name ?: "null"}")
        return result
    }

    suspend fun getLatestBackup(): BackupFileInfo? {
        Log.d(TAG, "getLatestBackup: listing files once for V3/V2/V1 selection...")
        val allFiles = listAllBackupFiles()
        val result = listOf(RemoteNamespace.V3, RemoteNamespace.V2, RemoteNamespace.V1)
            .firstNotNullOfOrNull { namespace ->
                allFiles.firstOrNull { it.namespace == namespace }
            }
        Log.d(TAG, "getLatestBackup: result=${result?.name ?: "null"} namespace=${result?.namespace}")
        return result
    }

    private fun parseWebDavResponse(xml: String): List<BackupFileInfo> {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val builder = factory.newDocumentBuilder()
        val document = builder.parse(xml.byteInputStream())

        val responses = document.getElementsByTagNameNS("DAV:", "response")
        val backupFiles = mutableListOf<BackupFileInfo>()

        for (i in 0 until responses.length) {
            val response = responses.item(i) as Element
            val href = response.getElementsByTagNameNS("DAV:", "href").item(0)?.textContent ?: continue

            // Skip directory entries
            if (href.endsWith("/")) continue

            val fileName = href.substringAfterLast("/")
            if (fileName.isEmpty()) continue

            val propstat = response.getElementsByTagNameNS("DAV:", "propstat").item(0) as? Element ?: continue
            val prop = propstat.getElementsByTagNameNS("DAV:", "prop").item(0) as? Element ?: continue

            val lastModifiedStr = prop.getElementsByTagNameNS("DAV:", "getlastmodified").item(0)?.textContent
            val sizeStr = prop.getElementsByTagNameNS("DAV:", "getcontentlength").item(0)?.textContent

            val lastModified = lastModifiedStr?.let { parseWebDavDate(it) } ?: Date(0)
            val size = sizeStr?.toLongOrNull() ?: 0L

            backupFiles.add(
                BackupFileInfo(
                    name = fileName,
                    lastModified = lastModified,
                    size = size,
                ),
            )
        }

        return backupFiles
    }

    private fun parseWebDavDate(dateStr: String): Date {
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.ENGLISH)
            format.parse(dateStr) ?: Date(0)
        } catch (e: Exception) {
            Date(0)
        }
    }

    private fun responseBodyPreview(resp: Response, maxLen: Int = 1024): String? {
        val raw = runCatching { resp.body?.string() }.getOrNull()?.trim().orEmpty()
        if (raw.isEmpty()) return null
        return if (raw.length <= maxLen) raw else raw.take(maxLen) + "..."
    }

    private fun parseDataVersion(fileName: String, namespace: RemoteNamespace): Int? {
        val strict = when (namespace) {
            RemoteNamespace.V1 -> Regex("^kototoro(?:-data)?-v(\\d+)-")
            RemoteNamespace.V2 -> Regex("^kototoro-v2-data-v(\\d+)-")
            RemoteNamespace.V3 -> Regex("^kototoro-v3-work-v(\\d+)-")
        }
        val m1 = strict.find(fileName)
        if (m1 != null) return m1.groupValues.getOrNull(1)?.toIntOrNull()
        if (namespace == RemoteNamespace.V2 || namespace == RemoteNamespace.V3) {
            return null
        }
        val fallback = Regex("-v(\\d+)-")
        val m2 = fallback.find(fileName) ?: return null
        return m2.groupValues.getOrNull(1)?.toIntOrNull()
    }

    private fun isBackupFileName(name: String): Boolean {
        return name.endsWith(".zip", ignoreCase = true)
    }

    private fun resolveNamespace(name: String): RemoteNamespace {
        return when {
            name.startsWith("kototoro-v3-work-v") -> RemoteNamespace.V3
            name.startsWith("kototoro-v2-data-v") -> RemoteNamespace.V2
            else -> RemoteNamespace.V1
        }
    }

    private fun matchesNamespace(name: String, namespace: RemoteNamespace): Boolean {
        return when (namespace) {
            RemoteNamespace.V1 -> !name.startsWith("kototoro-v2-data-v") && !name.startsWith("kototoro-v3-work-v")
            RemoteNamespace.V2 -> name.startsWith("kototoro-v2-data-v")
            RemoteNamespace.V3 -> name.startsWith("kototoro-v3-work-v")
        }
    }

    suspend fun deleteRemote(fileName: String, namespace: RemoteNamespace = RemoteNamespace.V3) {
        val url = composeUrl(fileName, namespace)
        val builder = Request.Builder().url(url).delete()
        basicAuthHeaderOrNull()?.let { builder.header("Authorization", it) }
        Log.d(TAG, "deleteRemote: DELETE $fileName")
        val resp = webDavClient.newCall(builder.build()).await()
        if (!resp.isSuccessful && resp.code != 404) {
            val code = resp.code
            val msg = resp.message
            resp.close()
            throw RuntimeException("WebDAV delete failed: $code $msg")
        }
        resp.close()
    }

    suspend fun trimRemote(maxCount: Int, namespace: RemoteNamespace = RemoteNamespace.V3) {
        if (maxCount <= 0) return
        val files = listBackupFiles(namespace)
        if (files.size <= maxCount) return
        val toDelete = files.drop(maxCount)
        toDelete.forEach { file ->
            runCatching { deleteRemote(file.name, namespace) }
                .onFailure { error ->
                    Log.w(TAG, "Failed to delete remote backup ${file.name}", error)
                }
        }
    }
}
