package org.skepsun.kototoro.reader.translate.data

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import eu.kanade.tachiyomi.network.await
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.skepsun.kototoro.core.network.BaseHttpClient
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class OnnxModelManager @Inject constructor(
	@ApplicationContext private val context: Context,
	@BaseHttpClient
	private val okHttpClient: OkHttpClient,
	private val appSettings: org.skepsun.kototoro.core.prefs.AppSettings,
) {

	data class DownloadProgress(
		val downloadedBytes: Long,
		val totalBytes: Long,
	)

	private data class RemoteFileInfo(
		val totalBytes: Long,
		val supportsRanges: Boolean,
	)

	private val mutex = Mutex()
	private val downloadClient: OkHttpClient by lazy {
		okHttpClient.newBuilder()
			.callTimeout(0, TimeUnit.MILLISECONDS)
			.readTimeout(0, TimeUnit.MILLISECONDS)
			.writeTimeout(0, TimeUnit.MILLISECONDS)
			.connectTimeout(30, TimeUnit.SECONDS)
			.retryOnConnectionFailure(true)
			.build()
	}

	fun getModelDir(modelId: String): File {
		return File(File(context.getExternalFilesDir(null), ROOT_DIR), modelId)
	}

	fun isModelDownloaded(modelId: String): Boolean {
		val modelDir = getModelDir(modelId)
		val marker = File(modelDir, INSTALLED_MARKER)
		if (!marker.isFile) return false
		val model = OnnxOfficialModelCatalog.findById(modelId) ?: return true
		if (model.files.isEmpty()) return true
		return isInstalledManifestValid(marker, model, modelDir)
	}

	suspend fun ensureModelReady(
		model: OnnxOfficialModel,
		onProgress: ((DownloadProgress) -> Unit)? = null,
	): String = mutex.withLock {
		val modelId = model.id
		val modelDir = getModelDir(modelId)
		val marker = File(modelDir, INSTALLED_MARKER)
		if (marker.isFile && isModelContentComplete(model, modelDir)) {
			marker.writeText(buildInstalledManifest(model, modelDir))
			return@withLock modelDir.absolutePath
		}

		modelDir.mkdirs()
		marker.delete()
		if (!model.archiveUrl.isNullOrBlank()) {
			val archiveFile = File(modelDir, "package.zip")
			downloadFile(model.archiveUrl, archiveFile, onProgress)
			verifySha256(archiveFile, model.sha256.orEmpty())
			unzipToDir(archiveFile, modelDir)
			archiveFile.delete()
		} else {
			check(model.files.isNotEmpty()) { "ONNX model has no downloadable content: ${model.id}" }
			for ((index, file) in model.files.withIndex()) {
				val target = File(modelDir, file.fileName)
				target.parentFile?.mkdirs()
				val downloadUrl = if (modelId == "manga_bubble_yolo_hf_main" && file.fileName == "yolo26s.onnx") {
					appSettings.readerTranslationBubbleYoloUrl.takeIf { it.isNotBlank() } ?: file.downloadUrl
				} else {
					file.downloadUrl
				}
				downloadFile(downloadUrl, target) { progress ->
					if (progress.totalBytes > 0) {
						val weight = 1.0 / model.files.size
						val done = index + progress.downloadedBytes.toDouble() / progress.totalBytes.toDouble()
						val mergedTotal = model.files.size.toLong() * 100L
						val mergedDone = (done * 100.0).toLong()
						onProgress?.invoke(DownloadProgress(mergedDone, mergedTotal))
					} else {
						onProgress?.invoke(progress)
					}
				}
				verifySha256(target, file.sha256.orEmpty())
			}
		}
		marker.writeText(buildInstalledManifest(model, modelDir))
		modelDir.absolutePath
	}

	private fun isInstalledManifestValid(marker: File, model: OnnxOfficialModel, modelDir: File): Boolean {
		return runCatching {
			val root = JSONObject(marker.readText())
			if (root.optString("modelId") != model.id) return false
			val files = root.optJSONArray("files") ?: return false
			val sizeByName = buildMap {
				for (i in 0 until files.length()) {
					val item = files.optJSONObject(i) ?: continue
					put(item.optString("name"), item.optLong("size", -1L))
				}
			}
			model.files.all { file ->
				val target = File(modelDir, file.fileName)
				val expectedSize = sizeByName[file.fileName] ?: return@all false
				expectedSize >= 0L && target.isFile && target.length() == expectedSize
			}
		}.getOrDefault(false)
	}

	private fun buildInstalledManifest(model: OnnxOfficialModel, modelDir: File): String {
		return JSONObject()
			.put("modelId", model.id)
			.put("version", model.version)
			.put(
				"files",
				JSONArray().apply {
					model.files.forEach { file ->
						val target = File(modelDir, file.fileName)
						put(
							JSONObject()
								.put("name", file.fileName)
								.put("size", if (target.isFile) target.length() else -1L),
						)
					}
				},
			)
			.toString()
	}

	private suspend fun isModelContentComplete(model: OnnxOfficialModel, modelDir: File): Boolean {
		if (model.files.isEmpty()) return true
		return model.files.all { file ->
			val target = File(modelDir, file.fileName)
			if (!target.isFile) return@all false
			val finalUrl = resolveDownloadUrl(file.downloadUrl)
			val remoteInfo = fetchRemoteFileInfo(finalUrl)
			remoteInfo.totalBytes <= 0L || target.length() == remoteInfo.totalBytes
		}
	}

	private suspend fun downloadFile(
		downloadUrl: String,
		targetFile: File,
		onProgress: ((DownloadProgress) -> Unit)?,
	) {
		val finalUrl = resolveDownloadUrl(downloadUrl)
		targetFile.parentFile?.mkdirs()
		val remoteInfo = fetchRemoteFileInfo(finalUrl)
		var resumeFromConvertedTarget = false
		if (targetFile.isFile) {
			if (remoteInfo.totalBytes <= 0L || targetFile.length() == remoteInfo.totalBytes) {
				onProgress?.invoke(DownloadProgress(targetFile.length(), remoteInfo.totalBytes.takeIf { it > 0L } ?: targetFile.length()))
				return
			}
			if (remoteInfo.supportsRanges && targetFile.length() < remoteInfo.totalBytes) {
				val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
				if (!partFile.isFile) {
					check(targetFile.renameTo(partFile)) {
						"Failed to convert incomplete model file to resumable part: ${targetFile.name}"
					}
					resumeFromConvertedTarget = true
				} else {
					targetFile.delete()
				}
			} else {
				targetFile.delete()
			}
		}
		if (!resumeFromConvertedTarget && remoteInfo.supportsRanges && remoteInfo.totalBytes >= MULTIPART_MIN_BYTES) {
			downloadFileMultipart(finalUrl, targetFile, remoteInfo.totalBytes, onProgress)
		} else {
			downloadFileSingle(finalUrl, targetFile, remoteInfo, onProgress)
		}
	}

	private fun resolveDownloadUrl(downloadUrl: String): String {
		return when (appSettings.huggingFaceMirror) {
			org.skepsun.kototoro.core.prefs.AppSettings.HuggingFaceMirror.HF_MIRROR -> downloadUrl.replaceFirst("https://huggingface.co", "https://hf-mirror.com")
			else -> downloadUrl
		}
	}

	private suspend fun fetchRemoteFileInfo(downloadUrl: String): RemoteFileInfo {
		val headInfo = runCatching {
			val request = Request.Builder().url(downloadUrl).head().build()
			downloadClient.newCall(request).await().use { response ->
				if (!response.isSuccessful) return@use null
				val length = response.header("Content-Length")?.toLongOrNull()
					?: response.body?.contentLength()?.takeIf { it > 0L }
					?: -1L
				val supportsRanges = response.header("Accept-Ranges")
					?.contains("bytes", ignoreCase = true) == true
				RemoteFileInfo(length, supportsRanges)
			}
		}.getOrNull()
		if (headInfo?.supportsRanges == true) return headInfo

		val probeInfo = runCatching {
			val request = Request.Builder()
				.url(downloadUrl)
				.header("Range", "bytes=0-0")
				.build()
			downloadClient.newCall(request).await().use { response ->
				if (response.code != HTTP_PARTIAL_CONTENT) return@use null
				val total = parseContentRangeTotal(response.header("Content-Range")).takeIf { it > 0L }
					?: headInfo?.totalBytes
					?: -1L
				RemoteFileInfo(total, supportsRanges = total > 0L)
			}
		}.getOrNull()
		return probeInfo ?: headInfo ?: RemoteFileInfo(-1L, supportsRanges = false)
	}

	private suspend fun downloadFileSingle(
		downloadUrl: String,
		targetFile: File,
		remoteInfo: RemoteFileInfo,
		onProgress: ((DownloadProgress) -> Unit)?,
	) {
		val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
		var attempt = 0
		while (attempt < DOWNLOAD_RETRY_COUNT) {
			val resumeFrom = if (remoteInfo.supportsRanges && partFile.isFile) partFile.length() else 0L
			val requestBuilder = Request.Builder().url(downloadUrl)
			if (resumeFrom > 0L) {
				requestBuilder.header("Range", "bytes=$resumeFrom-")
			}
			val request = requestBuilder.build()
			try {
				downloadClient.newCall(request).await().use { response ->
					if (resumeFrom > 0L && response.code != HTTP_PARTIAL_CONTENT) {
						partFile.delete()
						error("Server ignored resume request: HTTP ${response.code}")
					}
					if (!response.isSuccessful) {
						error("Download ONNX model failed: HTTP ${response.code} ${response.message}. URL: $downloadUrl")
					}
					val body = response.body ?: error("ONNX model response body is empty")
					val totalBytes = resolveTotalBytes(response.header("Content-Range"), body.contentLength(), resumeFrom, remoteInfo.totalBytes)
					copyResponseBodyToFile(
						body = body,
						targetFile = partFile,
						append = resumeFrom > 0L,
						initialBytes = resumeFrom,
						totalBytes = totalBytes,
						onProgress = onProgress,
					)
					completePartialFile(partFile, targetFile)
					onProgress?.invoke(DownloadProgress(targetFile.length(), totalBytes.takeIf { it > 0L } ?: targetFile.length()))
					return
				}
			} catch (e: Exception) {
				attempt++
				if (attempt >= DOWNLOAD_RETRY_COUNT) throw e
				delay(RETRY_DELAY_MS * attempt)
			}
		}
	}

	private suspend fun downloadFileMultipart(
		downloadUrl: String,
		targetFile: File,
		totalBytes: Long,
		onProgress: ((DownloadProgress) -> Unit)?,
	) = coroutineScope {
		val partDir = File(targetFile.parentFile, "${targetFile.name}.parts")
		partDir.mkdirs()
		val chunks = buildChunks(totalBytes)
		val downloadedBytes = AtomicLong(chunks.sumOf { chunk ->
			val file = chunkPartFile(partDir, chunk.index)
			file.length().coerceAtMost(chunk.length)
		})
		val lastNotifiedBytes = AtomicLong(-1L)
		onProgress?.invoke(DownloadProgress(downloadedBytes.get(), totalBytes))
		chunks.map { chunk ->
			async(Dispatchers.IO) {
				downloadChunk(
					downloadUrl = downloadUrl,
					partDir = partDir,
					chunk = chunk,
					totalBytes = totalBytes,
					downloadedBytes = downloadedBytes,
					lastNotifiedBytes = lastNotifiedBytes,
					onProgress = onProgress,
				)
			}
		}.awaitAll()
		mergeChunks(partDir, chunks.size, targetFile)
		partDir.deleteRecursively()
		onProgress?.invoke(DownloadProgress(totalBytes, totalBytes))
	}

	private data class DownloadChunk(
		val index: Int,
		val start: Long,
		val endInclusive: Long,
	) {
		val length: Long = endInclusive - start + 1L
	}

	private fun buildChunks(totalBytes: Long): List<DownloadChunk> {
		val chunkCount = MODEL_DOWNLOAD_THREADS.coerceAtMost((totalBytes / MULTIPART_MIN_BYTES).toInt().coerceAtLeast(1))
		val chunkSize = (totalBytes + chunkCount - 1L) / chunkCount
		return List(chunkCount) { index ->
			val start = index * chunkSize
			val end = minOf(totalBytes - 1L, start + chunkSize - 1L)
			DownloadChunk(index, start, end)
		}
	}

	private suspend fun downloadChunk(
		downloadUrl: String,
		partDir: File,
		chunk: DownloadChunk,
		totalBytes: Long,
		downloadedBytes: AtomicLong,
		lastNotifiedBytes: AtomicLong,
		onProgress: ((DownloadProgress) -> Unit)?,
	) {
		val partFile = chunkPartFile(partDir, chunk.index)
		var attempt = 0
		while (attempt < DOWNLOAD_RETRY_COUNT) {
			val existing = partFile.length().coerceAtMost(chunk.length)
			if (existing == chunk.length) return
			val rangeStart = chunk.start + existing
			val request = Request.Builder()
				.url(downloadUrl)
				.header("Range", "bytes=$rangeStart-${chunk.endInclusive}")
				.build()
			try {
				downloadClient.newCall(request).await().use { response ->
					if (response.code != HTTP_PARTIAL_CONTENT) {
						error("Chunk download failed: HTTP ${response.code} ${response.message}. URL: $downloadUrl")
					}
					val body = response.body ?: error("ONNX model chunk response body is empty")
					runInterruptible(Dispatchers.IO) {
						FileOutputStream(partFile, true).use { output ->
							body.byteStream().use { input ->
								val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
								while (true) {
									val read = input.read(buffer)
									if (read <= 0) break
									output.write(buffer, 0, read)
									val current = downloadedBytes.addAndGet(read.toLong())
									notifyProgressThrottled(current, totalBytes, lastNotifiedBytes, onProgress)
								}
							}
						}
					}
				}
				if (partFile.length() == chunk.length) return
				error("Chunk size mismatch: index=${chunk.index}, expected=${chunk.length}, actual=${partFile.length()}")
			} catch (e: Exception) {
				attempt++
				if (attempt >= DOWNLOAD_RETRY_COUNT) throw e
				delay(RETRY_DELAY_MS * attempt)
			}
		}
	}

	private suspend fun copyResponseBodyToFile(
		body: okhttp3.ResponseBody,
		targetFile: File,
		append: Boolean,
		initialBytes: Long,
		totalBytes: Long,
		onProgress: ((DownloadProgress) -> Unit)?,
	) {
		runInterruptible(Dispatchers.IO) {
			FileOutputStream(targetFile, append).use { output ->
				body.byteStream().use { input ->
					val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
					var downloaded = initialBytes
					var lastNotified = initialBytes - 1L
					onProgress?.invoke(DownloadProgress(downloaded, totalBytes))
					while (true) {
						val read = input.read(buffer)
						if (read <= 0) break
						output.write(buffer, 0, read)
						downloaded += read
						if (downloaded - lastNotified >= PROGRESS_NOTIFY_BYTES || downloaded == totalBytes) {
							lastNotified = downloaded
							onProgress?.invoke(DownloadProgress(downloaded, totalBytes))
						}
					}
					onProgress?.invoke(DownloadProgress(downloaded, totalBytes))
				}
			}
		}
	}

	private fun notifyProgressThrottled(
		downloadedBytes: Long,
		totalBytes: Long,
		lastNotifiedBytes: AtomicLong,
		onProgress: ((DownloadProgress) -> Unit)?,
	) {
		if (onProgress == null) return
		while (true) {
			val last = lastNotifiedBytes.get()
			if (downloadedBytes - last < PROGRESS_NOTIFY_BYTES && downloadedBytes != totalBytes) return
			if (lastNotifiedBytes.compareAndSet(last, downloadedBytes)) {
				onProgress(DownloadProgress(downloadedBytes, totalBytes))
				return
			}
		}
	}

	private suspend fun completePartialFile(partFile: File, targetFile: File) {
		runInterruptible(Dispatchers.IO) {
			val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
			tempFile.delete()
			check(partFile.renameTo(tempFile)) { "Failed to finalize partial model file: ${partFile.name}" }
			targetFile.delete()
			check(tempFile.renameTo(targetFile)) { "Failed to install model file: ${targetFile.name}" }
		}
	}

	private suspend fun mergeChunks(partDir: File, chunkCount: Int, targetFile: File) {
		runInterruptible(Dispatchers.IO) {
			val tempFile = File(targetFile.parentFile, "${targetFile.name}.tmp")
			tempFile.delete()
			FileOutputStream(tempFile).use { output ->
				for (index in 0 until chunkCount) {
					FileInputStream(chunkPartFile(partDir, index)).use { input ->
						input.copyTo(output)
					}
				}
			}
			targetFile.delete()
			check(tempFile.renameTo(targetFile)) { "Failed to install model file: ${targetFile.name}" }
		}
	}

	private fun chunkPartFile(partDir: File, index: Int): File {
		return File(partDir, "chunk-$index.part")
	}

	private fun resolveTotalBytes(
		contentRange: String?,
		contentLength: Long,
		resumeFrom: Long,
		remoteTotalBytes: Long,
	): Long {
		return parseContentRangeTotal(contentRange).takeIf { it > 0L }
			?: remoteTotalBytes.takeIf { it > 0L }
			?: contentLength.takeIf { it > 0L }?.let { it + resumeFrom }
			?: -1L
	}

	private fun parseContentRangeTotal(contentRange: String?): Long {
		if (contentRange.isNullOrBlank()) return -1L
		return contentRange.substringAfterLast('/', "")
			.takeUnless { it == "*" }
			?.toLongOrNull()
			?: -1L
	}

	private suspend fun verifySha256(file: File, expected: String) {
		val normalizedExpected = expected.trim().lowercase()
		if (normalizedExpected.isBlank()) return
		val actual = runInterruptible(Dispatchers.IO) {
			val digest = MessageDigest.getInstance("SHA-256")
			FileInputStream(file).use { input ->
				val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
				while (true) {
					val read = input.read(buffer)
					if (read <= 0) break
					digest.update(buffer, 0, read)
				}
			}
			digest.digest().joinToString("") { b -> "%02x".format(b) }
		}
		check(actual == normalizedExpected) {
			"ONNX model checksum mismatch: expected=$normalizedExpected actual=$actual"
		}
	}

	private suspend fun unzipToDir(zipFile: File, targetDir: File) {
		runInterruptible(Dispatchers.IO) {
			ZipInputStream(FileInputStream(zipFile)).use { zipInput ->
				var entry = zipInput.nextEntry
				while (entry != null) {
					val outFile = File(targetDir, entry.name).canonicalFile
					check(outFile.path.startsWith(targetDir.canonicalPath)) {
						"Blocked zip-slip path: ${entry.name}"
					}
					if (entry.isDirectory) {
						outFile.mkdirs()
					} else {
						outFile.parentFile?.mkdirs()
						FileOutputStream(outFile).use { output ->
							val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
							while (true) {
								val read = zipInput.read(buffer)
								if (read <= 0) break
								output.write(buffer, 0, read)
							}
						}
					}
					zipInput.closeEntry()
					entry = zipInput.nextEntry
				}
			}
		}
	}
	fun deleteModel(modelId: String): Boolean {
		val dir = File(context.getExternalFilesDir(null), "$ROOT_DIR/$modelId")
		return dir.deleteRecursively()
	}

	private companion object {
		const val ROOT_DIR = "models/translation_onnx"
		const val INSTALLED_MARKER = ".installed"
		const val HTTP_PARTIAL_CONTENT = 206
		const val DOWNLOAD_RETRY_COUNT = 5
		const val RETRY_DELAY_MS = 1_500L
		const val MODEL_DOWNLOAD_THREADS = 4
		const val MULTIPART_MIN_BYTES = 16L * 1024L * 1024L
		const val PROGRESS_NOTIFY_BYTES = 256L * 1024L
	}
}
