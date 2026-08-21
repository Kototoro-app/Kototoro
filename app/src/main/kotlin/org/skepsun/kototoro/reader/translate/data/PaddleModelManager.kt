package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.skepsun.kototoro.core.network.BaseHttpClient
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PaddleModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
    @BaseHttpClient
    private val okHttpClient: OkHttpClient,
) {

    data class DownloadProgress(
        val downloadedBytes: Long,
        val totalBytes: Long,
        val stage: String = "",
    )

    private val mutex = Mutex()

    fun isModelDownloaded(version: String): Boolean {
        return getModelDir(version).exists() && hasRequiredModelFiles(getModelDir(version))
    }

    fun isComponentsDownloaded(
        detVersion: String,
        recVersion: String,
        clsVersion: String
    ): Boolean {
        if (detVersion.isBlank() || recVersion.isBlank()) return false
        return getModelDir(detVersion).exists() &&
            getModelDir(recVersion).exists() &&
            (clsVersion.isBlank() || getModelDir(clsVersion).exists())
    }

    suspend fun ensureModelReady(
        version: String,
        zipUrl: String,
        zipSha256: String,
        detVersion: String = "",
        detUrl: String = "",
        detSha256: String = "",
        recVersion: String = "",
        recUrl: String = "",
        recSha256: String = "",
        clsVersion: String = "",
        clsUrl: String = "",
        clsSha256: String = "",
        onProgress: ((DownloadProgress) -> Unit)? = null,
    ): String = mutex.withLock {
        if (detUrl.isNotBlank() || recUrl.isNotBlank() || clsUrl.isNotBlank()) {
            return@withLock ensureTripletModelReady(
                detVersion = detVersion,
                detUrl = detUrl,
                detSha256 = detSha256,
                recVersion = recVersion,
                recUrl = recUrl,
                recSha256 = recSha256,
                clsVersion = clsVersion,
                clsUrl = clsUrl,
                clsSha256 = clsSha256,
                onProgress = onProgress,
            )
        }
        require(version.isNotBlank()) { "Paddle model version is empty" }
        require(zipUrl.isNotBlank()) { "Paddle model download URL is empty" }
        val normalizedSha = zipSha256.trim().lowercase()
        val modelDir = getModelDir(version)
        if (isModelUsable(modelDir, version, normalizedSha)) {
            return@withLock modelDir.absolutePath
        }
        modelDir.deleteRecursively()
        modelDir.mkdirs()
        val archiveFile = File(modelDir, MODEL_ARCHIVE_NAME)
        downloadArchive(zipUrl, archiveFile, stage = "all", onProgress = onProgress)
        if (normalizedSha.isNotBlank()) {
            val downloadedSha = archiveFile.sha256()
            check(downloadedSha == normalizedSha) {
                "Paddle model sha256 mismatch, expected=$normalizedSha actual=$downloadedSha"
            }
        }
        extractToDir(zipUrl, archiveFile, modelDir)
        archiveFile.delete()
        normalizeModelFiles(modelDir)
        writeMetadata(modelDir, version, normalizedSha)
        ensureRequiredModelFiles(modelDir)
        modelDir.absolutePath
    }

    private suspend fun ensureTripletModelReady(
        detVersion: String,
        detUrl: String,
        detSha256: String,
        recVersion: String,
        recUrl: String,
        recSha256: String,
        clsVersion: String,
        clsUrl: String,
        clsSha256: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): String {
        require(detVersion.isNotBlank() && detUrl.isNotBlank()) { "Paddle det model version/url is required" }
        require(recVersion.isNotBlank() && recUrl.isNotBlank()) { "Paddle rec model version/url is required" }

        val detFile = ensureComponentReady(
            component = "det",
            requiredFilename = "det.nb",
            version = detVersion,
            url = detUrl,
            sha256 = detSha256,
            onProgress = onProgress,
        )
        val recFile = ensureComponentReady(
            component = "rec",
            requiredFilename = "rec.nb",
            version = recVersion,
            url = recUrl,
            sha256 = recSha256,
            onProgress = onProgress,
        )
        val clsFile = if (clsVersion.isNotBlank() && clsUrl.isNotBlank()) {
            ensureComponentReady(
                component = "cls",
                requiredFilename = "cls.nb",
                version = clsVersion,
                url = clsUrl,
                sha256 = clsSha256,
                onProgress = onProgress,
            )
        } else {
            null
        }

        val mergedVersion = listOf(detVersion, recVersion, clsVersion.ifBlank { "nocls" }).joinToString("__")
        val modelDir = File(File(context.getExternalFilesDir(null), ROOT_DIR), "triplet_$mergedVersion")
        modelDir.mkdirs()
        detFile.copyTo(File(modelDir, "det.nb"), overwrite = true)
        recFile.copyTo(File(modelDir, "rec.nb"), overwrite = true)
        clsFile?.copyTo(File(modelDir, "cls.nb"), overwrite = true)
        return modelDir.absolutePath
    }

    private suspend fun ensureComponentReady(
        component: String,
        requiredFilename: String,
        version: String,
        url: String,
        sha256: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ): File {
        val normalizedSha = sha256.trim().lowercase()
        val componentDir = File(File(context.getExternalFilesDir(null), ROOT_DIR), "components/$component/$version")
        val targetFile = File(componentDir, requiredFilename)
        if (targetFile.isFile) {
            return targetFile
        }
        componentDir.deleteRecursively()
        componentDir.mkdirs()
        val archiveFile = File(componentDir, MODEL_ARCHIVE_NAME)
        downloadArchive(url, archiveFile, stage = component, onProgress = onProgress)
        if (normalizedSha.isNotBlank()) {
            val downloadedSha = archiveFile.sha256()
            check(downloadedSha == normalizedSha) {
                "Paddle $component model sha256 mismatch, expected=$normalizedSha actual=$downloadedSha"
            }
        }
        Log.d(TAG, "extracting $component model to $componentDir")
        extractToDir(url, archiveFile, componentDir)
        archiveFile.delete()

        Log.d(TAG, "normalizing $component model files in $componentDir")
        normalizeComponentFile(componentDir, requiredFilename)

        if (!targetFile.isFile) {
            val existingFiles = componentDir.walkTopDown().filter { it.isFile }.map { it.name }.toList()
            Log.e(TAG, "Paddle $component model file missing: $requiredFilename. Available files: $existingFiles")
            error("Paddle $component model file missing: $requiredFilename. Please check if the source URL contains a valid .nb file.")
        }
        return targetFile
    }

    private suspend fun downloadArchive(
        archiveUrl: String,
        archiveFile: File,
        stage: String,
        onProgress: ((DownloadProgress) -> Unit)?,
    ) {
        val request = Request.Builder().url(archiveUrl).build()
        okHttpClient.newCall(request).await().use { response ->
            if (!response.isSuccessful) {
                error("Download Paddle OCR model component ($stage) failed: HTTP ${response.code} ${response.message}. URL: $archiveUrl")
            }
            val body = response.body ?: error("Paddle model response body is empty")
            runInterruptible(Dispatchers.IO) {
                val totalBytes = body.contentLength().takeIf { it > 0L } ?: -1L
                FileOutputStream(archiveFile).use { output ->
                    body.byteStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        var downloaded = 0L
                        var lastNotified = -1L
                        onProgress?.invoke(DownloadProgress(0L, totalBytes, stage))
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) {
                                break
                            }
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastNotified >= 256 * 1024 || downloaded == totalBytes) {
                                lastNotified = downloaded
                                onProgress?.invoke(DownloadProgress(downloaded, totalBytes, stage))
                            }
                        }
                        onProgress?.invoke(DownloadProgress(downloaded, totalBytes, stage))
                    }
                }
            }
        }
    }

    private suspend fun normalizeComponentFile(
        modelDir: File,
        requiredFilename: String,
    ) = runInterruptible(Dispatchers.IO) {
        if (File(modelDir, requiredFilename).isFile) {
            return@runInterruptible
        }
        val allFiles = modelDir.walkTopDown().filter { it.isFile }.toList()
        val source = allFiles.firstOrNull { it.name.equals(requiredFilename, ignoreCase = true) }
            ?: allFiles.firstOrNull { it.extension.equals("nb", ignoreCase = true) }
            ?: return@runInterruptible
        source.copyTo(File(modelDir, requiredFilename), overwrite = true)
    }

    private suspend fun extractToDir(
        downloadUrl: String,
        archiveFile: File,
        outputDir: File,
    ) = runInterruptible(Dispatchers.IO) {
        val normalizedUrl = downloadUrl.lowercase(Locale.ROOT)
        Log.d(TAG, "extractToDir: url=$downloadUrl filename=${archiveFile.name}")

        when {
            normalizedUrl.endsWith(".zip") -> {
                Log.d(TAG, "extracting ZIP archive")
                ZipInputStream(FileInputStream(archiveFile)).use { zis ->
                    extractZipEntries(zis, outputDir)
                }
            }

            normalizedUrl.endsWith(".tar.gz") || normalizedUrl.endsWith(".tgz") -> {
                Log.d(TAG, "extracting TAR.GZ archive")
                GZIPInputStream(FileInputStream(archiveFile)).use { gzip ->
                    TarArchiveInputStream(gzip).use { tis ->
                        extractTarEntries(tis, outputDir)
                    }
                }
            }

            normalizedUrl.endsWith(".nb") -> {
                Log.d(TAG, "direct .nb file detected, copying to outputDir")
                // Some URLs might point directly to a .nb file
                archiveFile.copyTo(File(outputDir, archiveFile.name), overwrite = true)
            }

            else -> {
                Log.d(TAG, "attempting TAR extraction (fallback)")
                try {
                    TarArchiveInputStream(FileInputStream(archiveFile)).use { tis ->
                        extractTarEntries(tis, outputDir)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "TAR extraction failed, maybe not a TAR file? Error: ${e.message}")
                    // Final fallback: just keep the file as is in the directory,
                    // maybe it's a raw .nb file without extension
                    archiveFile.copyTo(File(outputDir, archiveFile.name), overwrite = true)
                }
            }
        }
    }

    private fun extractZipEntries(zipInput: ZipInputStream, outputDir: File) {
        var entry = zipInput.nextEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                writeSafeFile(outputDir, entry.name, zipInput)
            }
            zipInput.closeEntry()
            entry = zipInput.nextEntry
        }
    }

    private fun extractTarEntries(tarInput: TarArchiveInputStream, outputDir: File) {
        var entry = tarInput.nextTarEntry
        while (entry != null) {
            if (!entry.isDirectory) {
                writeSafeFile(outputDir, entry.name, tarInput)
            }
            entry = tarInput.nextTarEntry
        }
    }

    private fun writeSafeFile(outputDir: File, entryName: String, input: InputStream) {
        val safeName = entryName.replace('\\', '/').removePrefix("/")
        val outFile = File(outputDir, safeName).canonicalFile
        check(outFile.path.startsWith(outputDir.canonicalPath)) {
            "Invalid archive entry path: $entryName"
        }
        outFile.parentFile?.mkdirs()
        FileOutputStream(outFile).use { fos ->
            input.copyTo(fos)
        }
    }

    private fun ensureRequiredModelFiles(modelDir: File) {
        if (hasRequiredModelFiles(modelDir)) {
            return
        }
        val files = modelDir.walkTopDown().filter { it.isFile }.toList()
        val hasPaddleInference = files.any { it.extension.equals("pdmodel", ignoreCase = true) } &&
            files.any { it.extension.equals("pdiparams", ignoreCase = true) }
        if (hasPaddleInference) {
            error("Downloaded model is Paddle inference format (.pdmodel/.pdiparams), current reader requires Paddle-Lite nb files: det.nb/rec.nb")
        }
        error("Paddle model files missing: det.nb and rec.nb are required")
    }

    private suspend fun normalizeModelFiles(modelDir: File) = runInterruptible(Dispatchers.IO) {
        val allFiles = modelDir.walkTopDown().filter { it.isFile }.toList()
        val allRequiredAndOptional = REQUIRED_FILES + OPTIONAL_FILES
        for (name in allRequiredAndOptional) {
            // Find by exact name or just by extension if it's the only one of its type
            val src = allFiles.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?: if (name == "det.nb") allFiles.firstOrNull { it.name.lowercase().contains("det") && it.extension == "nb" }
                   else if (name == "rec.nb") allFiles.firstOrNull { it.name.lowercase().contains("rec") && it.extension == "nb" }
                   else if (name == "cls.nb") allFiles.firstOrNull { it.name.lowercase().contains("cls") && it.extension == "nb" }
                   else null

            if (src != null) {
                val dst = File(modelDir, name)
                if (src.absolutePath != dst.absolutePath) {
                    src.copyTo(dst, overwrite = true)
                }
            }
        }
    }

    private fun hasRequiredModelFiles(modelDir: File): Boolean {
        return REQUIRED_FILES.all { File(modelDir, it).isFile }
    }

    private fun metadataFile(modelDir: File) = File(modelDir, METADATA_FILE)

    private fun isModelUsable(modelDir: File, version: String, sha256: String): Boolean {
        if (!modelDir.isDirectory || !hasRequiredModelFiles(modelDir)) {
            return false
        }
        val metadataFile = metadataFile(modelDir)
        if (!metadataFile.isFile) {
            return false
        }
        return runCatching {
            val json = JSONObject(metadataFile.readText())
            val metadataVersion = json.optString("version")
            val metadataSha = json.optString("sha256").lowercase()
            metadataVersion == version && (sha256.isBlank() || sha256 == metadataSha)
        }.onFailure {
            it.printStackTraceDebug()
        }.getOrDefault(false)
    }

    private fun writeMetadata(modelDir: File, version: String, sha256: String) {
        val json = JSONObject().apply {
            put("version", version)
            put("sha256", sha256)
        }
        metadataFile(modelDir).writeText(json.toString())
    }

    private fun File.sha256(): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                md.update(buffer, 0, n)
            }
        }
        return md.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun getModelDir(version: String): File {
        return File(File(context.getExternalFilesDir(null), ROOT_DIR), version)
    }

    private companion object {
        const val TAG = "PaddleModelManager"
        const val ROOT_DIR = "models/ocr_paddle"
        const val MODEL_ARCHIVE_NAME = "model.archive"
        const val METADATA_FILE = "metadata.json"
        val REQUIRED_FILES = listOf("det.nb", "rec.nb")
        val OPTIONAL_FILES = listOf("cls.nb")
    }
}
