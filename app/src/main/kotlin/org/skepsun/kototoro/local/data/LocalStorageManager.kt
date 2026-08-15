package org.skepsun.kototoro.local.data

import android.Manifest
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.annotation.WorkerThread
import androidx.core.content.ContextCompat
import androidx.core.net.toFile
import dagger.Reusable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okhttp3.Cache
import org.skepsun.kototoro.core.LocalizedAppContext
import org.skepsun.kototoro.core.exceptions.NonFileUriException
import org.skepsun.kototoro.core.prefs.AppSettings
import org.skepsun.kototoro.core.util.ext.computeSize
import org.skepsun.kototoro.core.util.ext.getStorageName
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isReadable
import org.skepsun.kototoro.core.util.ext.isWriteable
import org.skepsun.kototoro.core.util.ext.resolveFile
import org.skepsun.kototoro.core.util.ext.takeIfWriteable
import org.skepsun.kototoro.parsers.util.mapToSet
import java.io.File
import javax.inject.Inject

private const val DIR_NAME = "manga"
private const val DIR_NAME_NOVEL = "novel"
private const val DIR_NAME_VIDEO = "video"
private const val NOMEDIA = ".nomedia"
private const val CACHE_DISK_PERCENTAGE = 0.02
private const val CACHE_SIZE_MIN: Long = 10 * 1024 * 1024 // 10MB
private const val CACHE_SIZE_MAX: Long = 250 * 1024 * 1024 // 250MB

enum class StorageContentKind {
	MANGA,
	NOVEL,
	VIDEO,
}

@Reusable
class LocalStorageManager @Inject constructor(
    @LocalizedAppContext private val context: Context,
    private val settings: AppSettings,
) {

	val contentResolver: ContentResolver
		get() = context.contentResolver

	@WorkerThread
	fun createHttpCache(): Cache {
		val directory = File(context.externalCacheDir ?: context.cacheDir, "http")
		directory.mkdirs()
		val maxSize = settings.httpCacheSizeMb * 1024L * 1024L
		return Cache(directory, maxSize)
	}

	suspend fun computeCacheSize(cache: CacheDir) = withContext(Dispatchers.IO) {
		getCacheDirsFor(cache).sumOf { it.computeSize() }
	}

	suspend fun computeStorageSize(kind: StorageContentKind) = withContext(Dispatchers.IO) {
		getReadableDirs(kind).toSet().sumOf { it.computeSize() }
	}

	suspend fun computeCacheSize() = withContext(Dispatchers.IO) {
		getCacheDirs().sumOf { it.computeSize() } + getCacheDirsFor(CacheDir.VIDEO).sumOf { it.computeSize() }
	}

	suspend fun computeStorageSize() = withContext(Dispatchers.IO) {
		getAllReadableDirs().toSet().sumOf { it.computeSize() }
	}

	suspend fun computeAiModelsSize() = withContext(Dispatchers.IO) {
		var total = 0L
		val externalModels = File(context.getExternalFilesDir(null), "models")
		val internalModels = File(context.filesDir, "models")
		val mlKitDir = File(context.filesDir.parentFile, "no_backup/com.google.mlkit.translate.models")
		
		val dirs = setOf(externalModels, internalModels, mlKitDir)
		for (dir in dirs) {
			if (dir.exists()) {
				total += dir.computeSize()
			}
		}
		total
	}

	suspend fun computeAvailableSize() = runInterruptible(Dispatchers.IO) {
		(
			getConfiguredStorageDirs() +
				getConfiguredNovelStorageDirs() +
				getConfiguredVideoStorageDirs()
			)
			.mapToSet { it.freeSpace }
			.sum()
	}

	suspend fun clearCache(cache: CacheDir) = runInterruptible(Dispatchers.IO) {
		getCacheDirsFor(cache).forEach { it.deleteRecursively() }
	}

	suspend fun getReadableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageDirs()
			.filter { it.isReadable() }
	}

	suspend fun getWriteableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageDirs()
			.filter { it.isWriteable() }
	}

	suspend fun getDefaultWriteableDir(): File? = runInterruptible(Dispatchers.IO) {
		val preferredDir = settings.mangaStorageDir?.takeIfWriteable()
		preferredDir ?: getFallbackStorageDir()?.takeIfWriteable()
	}

	suspend fun getReadableRoots(): List<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageRoots().filter(LocalStorageRoot::isReadable)
	}

	suspend fun getWriteableRoots(): List<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		getConfiguredStorageRoots().filter(LocalStorageRoot::isWriteable)
	}

	suspend fun getDefaultWriteableRoot(): LocalStorageRoot? = runInterruptible(Dispatchers.IO) {
		settings.mangaStorageUri
			?.let { LocalStorageRoot.fromUri(context, it) }
			?.takeIf(LocalStorageRoot::isWriteable)
			?: getFallbackStorageDir()?.let(LocalStorageRoot::fromFile)?.takeIf(LocalStorageRoot::isWriteable)
	}

	suspend fun getAllReadableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		(getConfiguredStorageDirs() + getConfiguredNovelStorageDirs() + getConfiguredVideoStorageDirs())
			.filter { it.isReadable() }
	}

	suspend fun getAllWriteableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		(getConfiguredStorageDirs() + getConfiguredNovelStorageDirs() + getConfiguredVideoStorageDirs())
			.filter { it.isWriteable() }
	}

	suspend fun getNovelReadableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredNovelStorageDirs()
			.filter { it.isReadable() }
	}

	suspend fun getNovelWriteableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredNovelStorageDirs()
			.filter { it.isWriteable() }
	}

	suspend fun getDefaultNovelWriteableDir(): File? = runInterruptible(Dispatchers.IO) {
		val preferredDir = settings.novelStorageDir?.takeIfWriteable()
		preferredDir ?: getFallbackNovelStorageDir()?.takeIfWriteable()
	}

	suspend fun getNovelWriteableRoots(): List<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		getConfiguredNovelStorageRoots().filter(LocalStorageRoot::isWriteable)
	}

	suspend fun getDefaultNovelWriteableRoot(): LocalStorageRoot? = runInterruptible(Dispatchers.IO) {
		settings.novelStorageUri
			?.let { LocalStorageRoot.fromUri(context, it) }
			?.takeIf(LocalStorageRoot::isWriteable)
			?: getFallbackNovelStorageDir()?.let(LocalStorageRoot::fromFile)?.takeIf(LocalStorageRoot::isWriteable)
	}

	suspend fun getVideoWriteableDirs(): List<File> = runInterruptible(Dispatchers.IO) {
		getConfiguredVideoStorageDirs()
			.filter { it.isWriteable() }
	}

	suspend fun getVideoWriteableRoots(): List<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		getConfiguredVideoStorageRoots().filter(LocalStorageRoot::isWriteable)
	}

	suspend fun getDefaultVideoWriteableRoot(): LocalStorageRoot? = runInterruptible(Dispatchers.IO) {
		settings.videoStorageUri
			?.let { LocalStorageRoot.fromUri(context, it) }
			?.takeIf(LocalStorageRoot::isWriteable)
			?: getFallbackVideoStorageDir()?.let(LocalStorageRoot::fromFile)?.takeIf(LocalStorageRoot::isWriteable)
	}

	suspend fun getApplicationStorageDirs(): Set<File> = runInterruptible(Dispatchers.IO) {
		getAvailableStorageDirs()
	}

	suspend fun getApplicationStorageRoots(): Set<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		getAvailableStorageDirs().mapTo(LinkedHashSet(), LocalStorageRoot::fromFile)
	}

	suspend fun getAllReadableRoots(): List<LocalStorageRoot> = runInterruptible(Dispatchers.IO) {
		(
			getConfiguredStorageRoots() +
				getConfiguredNovelStorageRoots() +
				getConfiguredVideoStorageRoots()
			).distinct().filter(LocalStorageRoot::isReadable)
	}

	suspend fun getReadableDirs(kind: StorageContentKind): List<File> = runInterruptible(Dispatchers.IO) {
		when (kind) {
			StorageContentKind.MANGA -> getConfiguredStorageDirs()
			StorageContentKind.NOVEL -> getConfiguredNovelStorageDirs()
			StorageContentKind.VIDEO -> getConfiguredVideoStorageDirs()
		}.filter { it.isReadable() }
	}

	suspend fun resolveUri(uri: Uri): File = runInterruptible(Dispatchers.IO) {
		if (uri.isFileUri()) {
			uri.toFile()
		} else {
			uri.resolveFile(context) ?: throw NonFileUriException(uri)
		}
	}

	suspend fun resolveRoot(uri: Uri): LocalStorageRoot = runInterruptible(Dispatchers.IO) {
		LocalStorageRoot.fromUri(context, uri) ?: throw NonFileUriException(uri)
	}

	suspend fun setDirIsNoMedia(dir: File) = runInterruptible(Dispatchers.IO) {
		File(dir, NOMEDIA).createNewFile()
	}

	suspend fun setDirIsNoMedia(root: LocalStorageRoot) = runInterruptible(Dispatchers.IO) {
		root.file.findFile(NOMEDIA) ?: root.file.createFile(NOMEDIA)?.openOutputStream()?.close()
	}

	fun takePermissions(uri: Uri) {
		val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
		contentResolver.takePersistableUriPermission(uri, flags)
	}

	fun isOnExternalStorage(file: File): Boolean {
		return !file.absolutePath.contains(context.packageName)
	}

	fun hasExternalStoragePermission(isReadOnly: Boolean): Boolean {
		return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
			Environment.isExternalStorageManager()
		} else {
			val permission = if (isReadOnly) {
				Manifest.permission.READ_EXTERNAL_STORAGE
			} else {
				Manifest.permission.WRITE_EXTERNAL_STORAGE
			}
			ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
		}
	}

	suspend fun getDirectoryDisplayName(dir: File, isFullPath: Boolean): String = runInterruptible(Dispatchers.IO) {
		val packageName = context.packageName
		if (dir.absolutePath.contains(packageName)) {
			dir.getStorageName(context)
		} else if (isFullPath) {
			dir.path
		} else {
			dir.name
		}
	}

	suspend fun getDirectoryDisplayName(root: LocalStorageRoot, isFullPath: Boolean): String =
		runInterruptible(Dispatchers.IO) {
			if (isFullPath) root.displayPath else root.name
		}

	@WorkerThread
	private fun getConfiguredStorageDirs(): MutableSet<File> {
		val set = getAvailableStorageDirs()
		set.addAll(settings.userSpecifiedContentDirectories)
		return set
	}

	@WorkerThread
	private fun getConfiguredStorageRoots(): MutableSet<LocalStorageRoot> {
		val roots = getAvailableStorageDirs().mapTo(LinkedHashSet(), LocalStorageRoot::fromFile)
		settings.userSpecifiedContentDirectoryUris.mapNotNullTo(roots) {
			LocalStorageRoot.fromUri(context, it)
		}
		return roots
	}

	@WorkerThread
	private fun getAvailableStorageDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += File(context.filesDir, DIR_NAME)
		context.getExternalFilesDirs(DIR_NAME).filterNotNullTo(result)
		result.retainAll { it.exists() || it.mkdirs() }
		return result
	}

	@WorkerThread
	private fun getAvailableNovelStorageDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += File(context.filesDir, DIR_NAME_NOVEL)
		context.getExternalFilesDirs(DIR_NAME_NOVEL).filterNotNullTo(result)
		result.retainAll { it.exists() || it.mkdirs() }
		return result
	}

	@WorkerThread
	private fun getConfiguredNovelStorageDirs(): MutableSet<File> {
		val result = getAvailableNovelStorageDirs()
		settings.novelStorageDir?.let(result::add)
		return result
	}

	@WorkerThread
	private fun getConfiguredNovelStorageRoots(): MutableSet<LocalStorageRoot> {
		val roots = getAvailableNovelStorageDirs().mapTo(LinkedHashSet(), LocalStorageRoot::fromFile)
		settings.novelStorageUri?.let { uri ->
			LocalStorageRoot.fromUri(context, uri)?.let(roots::add)
		}
		return roots
	}

	@WorkerThread
	private fun getAvailableVideoStorageDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += File(context.filesDir, DIR_NAME_VIDEO)
		context.getExternalFilesDirs(DIR_NAME_VIDEO).filterNotNullTo(result)
		result.retainAll { it.exists() || it.mkdirs() }
		return result
	}

	@WorkerThread
	private fun getConfiguredVideoStorageDirs(): MutableSet<File> {
		val result = getAvailableVideoStorageDirs()
		settings.videoStorageDir?.let(result::add)
		return result
	}

	@WorkerThread
	private fun getConfiguredVideoStorageRoots(): MutableSet<LocalStorageRoot> {
		val roots = getAvailableVideoStorageDirs().mapTo(LinkedHashSet(), LocalStorageRoot::fromFile)
		settings.videoStorageUri?.let { uri ->
			LocalStorageRoot.fromUri(context, uri)?.let(roots::add)
		}
		return roots
	}

	/**
	 * 返回小说下载根目录（files/novel）
	 */
	@WorkerThread
	fun getNovelRoot(): File? {
		return File(context.filesDir, DIR_NAME_NOVEL).takeIf { it.exists() || it.mkdirs() }
	}

	/**
	 * 返回视频下载根目录（files/video）
	 */
	suspend fun getDefaultVideoWriteableDir(): File? = runInterruptible(Dispatchers.IO) {
		val preferredDir = settings.videoStorageDir?.takeIfWriteable()
		preferredDir ?: getFallbackVideoStorageDir()?.takeIfWriteable()
	}

	@WorkerThread
	fun getVideoRoot(): File? {
		val preferredDir = settings.videoStorageDir
		if (preferredDir != null && preferredDir.isWriteable()) return preferredDir
		return context.getExternalFilesDir(DIR_NAME_VIDEO) ?: File(context.filesDir, DIR_NAME_VIDEO).takeIf {
			it.exists() || it.mkdirs()
		}
	}

	@WorkerThread
	private fun getFallbackNovelStorageDir(): File? {
		return context.getExternalFilesDir(DIR_NAME_NOVEL) ?: File(context.filesDir, DIR_NAME_NOVEL).takeIf {
			it.exists() || it.mkdirs()
		}
	}

	@WorkerThread
	private fun getFallbackStorageDir(): File? {
		return context.getExternalFilesDir(DIR_NAME) ?: File(context.filesDir, DIR_NAME).takeIf {
			it.exists() || it.mkdirs()
		}
	}

	@WorkerThread
	private fun getFallbackVideoStorageDir(): File? {
		return context.getExternalFilesDir(DIR_NAME_VIDEO) ?: File(context.filesDir, DIR_NAME_VIDEO).takeIf {
			it.exists() || it.mkdirs()
		}
	}

	@WorkerThread
	private fun getCacheDirsFor(cache: CacheDir): MutableSet<File> {
		val result = LinkedHashSet<File>()
		when (cache) {
			CacheDir.VIDEO -> {
				result += File(context.filesDir, "mpv_cache")
				context.getExternalFilesDirs("mpv_cache").filterNotNullTo(result)
				result += File(context.filesDir, "video_cache")
				context.getExternalFilesDirs("video_cache").filterNotNullTo(result)
			}
			CacheDir.VIDEO_PROXY -> {
				result += File(context.filesDir, cache.dir)
				context.getExternalFilesDirs(cache.dir).filterNotNullTo(result)
			}
			CacheDir.DANMAKU,
			CacheDir.TORRENT,
			CacheDir.HTTP,
			CacheDir.SUPER_RESOLUTION,
			CacheDir.THUMBS,
			CacheDir.FAVICONS,
			CacheDir.PAGES,
			CacheDir.NOVELS,
			CacheDir.TtsAudio -> {
				val subDir = cache.dir
				result += File(context.cacheDir, subDir)
				context.externalCacheDirs.mapNotNullTo(result) {
					File(it ?: return@mapNotNullTo null, subDir)
				}
			}
		}
		return result
	}

	@WorkerThread
	private fun getCacheDirs(): MutableSet<File> {
		val result = LinkedHashSet<File>()
		result += context.cacheDir
		context.externalCacheDirs.filterNotNullTo(result)
		return result
	}

	private fun calculateDiskCacheSize(cacheDirectory: File): Long {
		return try {
			val cacheDir = StatFs(cacheDirectory.absolutePath)
			val size = CACHE_DISK_PERCENTAGE * cacheDir.blockCountLong * cacheDir.blockSizeLong
			return size.toLong().coerceIn(CACHE_SIZE_MIN, CACHE_SIZE_MAX)
		} catch (_: Exception) {
			CACHE_SIZE_MIN
		}
	}
}
