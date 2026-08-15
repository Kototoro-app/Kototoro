package org.skepsun.kototoro.local.data.importer

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.hippo.unifile.UniFile
import dagger.Reusable
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import okio.buffer
import okio.sink
import org.skepsun.kototoro.core.exceptions.UnsupportedFileException
import org.skepsun.kototoro.core.util.ext.openSource
import org.skepsun.kototoro.core.util.ext.resolveName
import org.skepsun.kototoro.core.util.ext.writeAllCancellable
import org.skepsun.kototoro.local.data.LocalStorageChanges
import org.skepsun.kototoro.local.data.LocalStorageManager
import org.skepsun.kototoro.local.data.LocalStorageRoot
import org.skepsun.kototoro.local.data.hasZipExtension
import org.skepsun.kototoro.local.data.input.LocalContentParser
import org.skepsun.kototoro.local.domain.model.LocalContent
import java.io.File
import java.io.IOException
import java.util.UUID
import javax.inject.Inject

/**
 * Import mode for directory import
 */
enum class ImportMode {
	/** Import a single work - the folder itself is one work */
	SINGLE_MANGA,
	/** Import multiple works - subdirectories and supported top-level files are separate works */
	MULTIPLE_MANGA
}

@Reusable
class SingleContentImporter @Inject constructor(
	@ApplicationContext private val context: Context,
	private val storageManager: LocalStorageManager,
	@LocalStorageChanges private val localStorageChanges: MutableSharedFlow<LocalContent?>,
) {

	private val contentResolver = context.contentResolver

	/**
	 * Import files (CBZ/ZIP archives)
	 */
	suspend fun import(uri: Uri, importKind: LocalImportKind? = null): List<LocalContent> {
		val results = if (isDirectory(uri)) {
			// For file import, auto-detect (for backward compatibility)
			importDirectoryAuto(uri, importKind)
		} else {
			listOf(importFile(uri, importKind))
		}
		results.forEach { localStorageChanges.emit(it) }
		return results
	}

	/**
	 * Import directory with specified mode
	 */
	suspend fun import(uri: Uri, mode: ImportMode, importKind: LocalImportKind? = null): List<LocalContent> {
		val results = if (isDirectory(uri)) {
			when (mode) {
				ImportMode.SINGLE_MANGA -> importDirectorySingle(uri, importKind)
				ImportMode.MULTIPLE_MANGA -> importDirectoryMultiple(uri, importKind)
			}
		} else {
			listOf(importFile(uri, importKind))
		}
		results.forEach { localStorageChanges.emit(it) }
		return results
	}

	private suspend fun importFile(uri: Uri, overrideKind: LocalImportKind? = null): LocalContent = withContext(Dispatchers.IO) {
		val contentResolver = storageManager.contentResolver
		val name = contentResolver.resolveName(uri) ?: throw IOException("Cannot fetch name from uri: $uri")
		if (!LocalImportSupport.supportsFileName(name)) {
			throw UnsupportedFileException("Unsupported file $name on $uri")
		}
		val kind = LocalImportSupport.classifyFileName(name)
		if (overrideKind != null && overrideKind != kind && !hasZipExtension(name)) {
			throw UnsupportedFileException("File $name does not match selected content type: $overrideKind")
		}
		val outputRoot = getOutputRoot(overrideKind ?: kind)
		if (hasZipExtension(name)) {
			publishFile(outputRoot, name) { target ->
				copyUriTo(uri, target)
			}
		} else {
			publishDirectory(outputRoot, LocalImportSupport.contentFolderName(name)) { target ->
				copyUriTo(uri, checkNotNull(target.createFile(name)))
			}
		}
	}

	/**
	 * Auto-detect import mode (for backward compatibility with file import)
	 */
	private suspend fun importDirectoryAuto(uri: Uri, overrideKind: LocalImportKind? = null): List<LocalContent> {
		// Default to single manga mode for auto-detect
		return importDirectorySingle(uri, overrideKind)
	}

	/**
	 * Import as single work - the selected folder is one work
	 */
	private suspend fun importDirectorySingle(uri: Uri, overrideKind: LocalImportKind? = null): List<LocalContent> {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		val childFiles = root.listFiles()
		val kind = overrideKind ?: classifyImportKind(childFiles.mapNotNull { it.name })
		val content = publishDirectory(getOutputRoot(kind), root.requireName()) { target ->
			for (docFile in childFiles) {
				docFile.copyInto(target)
			}
		}
		return listOf(content)
	}

	/**
	 * Import as multiple works - subdirectories and supported top-level files are separate works
	 */
	private suspend fun importDirectoryMultiple(uri: Uri, overrideKind: LocalImportKind? = null): List<LocalContent> {
		val root = requireNotNull(DocumentFile.fromTreeUri(context, uri)) {
			"Provided uri $uri is not a tree"
		}
		val childFiles = root.listFiles()
		val subDirs = childFiles.filter { it.isDirectory }
		val importableFiles = childFiles.filter { 
			it.isFile && LocalImportSupport.supportsFileName(it.name ?: "") &&
			(overrideKind == null || hasZipExtension(it.name ?: "") || LocalImportSupport.classifyFileName(it.name ?: "") == overrideKind)
		}
		
		val results = mutableListOf<LocalContent>()
		try {
			for (folder in subDirs) {
				val folderChildren = folder.listFiles()
				val kind = overrideKind ?: classifyImportKind(folderChildren.mapNotNull { it.name })
				results += publishDirectory(getOutputRoot(kind), folder.requireName()) { target ->
					for (docFile in folderChildren) {
						docFile.copyInto(target)
					}
				}
			}

			for (file in importableFiles) {
				val name = file.name ?: continue
				val kind = overrideKind ?: LocalImportSupport.classifyFileName(name)
				val outputRoot = getOutputRoot(kind)
				results += if (hasZipExtension(name)) {
					publishFile(outputRoot, name) { target -> file.copyFileTo(target) }
				} else {
					publishDirectory(outputRoot, LocalImportSupport.contentFolderName(name)) { target ->
						file.copyFileTo(checkNotNull(target.createFile(name)))
					}
				}
			}
			return results
		} catch (e: Throwable) {
			results.forEach { content -> UniFile.fromUri(context, content.toUri())?.delete() }
			throw e
		}
	}

	private suspend fun DocumentFile.copyInto(destDir: UniFile) {
		if (isDirectory) {
			val subDir = checkNotNull(destDir.createDirectory(requireName()))
			for (docFile in listFiles()) {
				docFile.copyInto(subDir)
			}
		} else {
			copyFileTo(checkNotNull(destDir.createFile(requireName())))
		}
	}

	private suspend fun DocumentFile.copyFileTo(target: UniFile) {
		source().use { input ->
			target.openOutputStream().sink().buffer().use { output ->
				output.writeAllCancellable(input)
			}
		}
	}

	private suspend fun copyUriTo(uri: Uri, target: UniFile) {
		runInterruptible { contentResolver.openSource(uri) }.use { input ->
			target.openOutputStream().sink().buffer().use { output ->
				output.writeAllCancellable(input)
			}
		}
	}

	private fun classifyImportKind(fileNames: Collection<String>): LocalImportKind {
		if (fileNames.any { LocalImportSupport.classifyFileName(it) == LocalImportKind.VIDEO }) {
			return LocalImportKind.VIDEO
		}
		if (fileNames.any { LocalImportSupport.classifyFileName(it) == LocalImportKind.NOVEL }) {
			return LocalImportKind.NOVEL
		}
		return LocalImportKind.MANGA
	}

	private suspend fun getOutputRoot(kind: LocalImportKind): LocalStorageRoot {
		val root = when (kind) {
			LocalImportKind.MANGA -> storageManager.getDefaultWriteableRoot()
			LocalImportKind.NOVEL -> storageManager.getDefaultNovelWriteableRoot()
			LocalImportKind.VIDEO -> storageManager.getDefaultVideoWriteableRoot()
		}
		return root ?: throw IOException("External files dir unavailable")
	}

	private suspend fun publishFile(
		root: LocalStorageRoot,
		requestedName: String,
		copy: suspend (UniFile) -> Unit,
	): LocalContent = publish(root, requestedName, isDirectory = false) { staging ->
		copy(staging)
	}

	private suspend fun publishDirectory(
		root: LocalStorageRoot,
		requestedName: String,
		copy: suspend (UniFile) -> Unit,
	): LocalContent = publish(root, requestedName, isDirectory = true, copy = copy)

	private suspend fun publish(
		root: LocalStorageRoot,
		requestedName: String,
		isDirectory: Boolean,
		copy: suspend (UniFile) -> Unit,
	): LocalContent {
		root.file.listFiles().orEmpty()
			.filter { it.name?.startsWith(LocalImportSupport.IMPORT_STAGING_PREFIX) == true }
			.forEach(UniFile::delete)
		val finalName = findAvailableName(root.file, requestedName)
		val stagingName = buildStagingName(finalName)
		val staging = checkNotNull(
			if (isDirectory) root.file.createDirectory(stagingName) else root.file.createFile(stagingName),
		) { "Cannot create import staging node in ${root.uri}" }
		var published: UniFile? = null
		try {
			copy(staging)
			val publishedFile = if (staging.renameTo(finalName)) {
				checkNotNull(root.file.findFile(finalName)) { "Published content is missing: $finalName" }
			} else {
				val destination = checkNotNull(
					if (isDirectory) root.file.createDirectory(finalName) else root.file.createFile(finalName),
				) { "Cannot create imported content as $finalName" }
				try {
					staging.copyTo(destination)
					check(staging.delete()) { "Cannot remove import staging node: $stagingName" }
					destination
				} catch (e: Throwable) {
					destination.delete()
					throw e
				}
			}
			published = publishedFile
			val rawFile = publishedFile.filePath?.let(::File)?.takeIf { publishedFile.uri.scheme == "file" }
			return if (rawFile != null && publishedFile.isFile) {
				LocalContentParser(rawFile).getContent(withDetails = false)
			} else {
				LocalContentParser(publishedFile, context.cacheDir).getContent(withDetails = false)
			}
		} catch (e: Throwable) {
			(published ?: staging).delete()
			throw e
		}
	}

	private fun findAvailableName(root: UniFile, requestedName: String): String {
		if (root.findFile(requestedName) == null) return requestedName
		val extension = requestedName.substringAfterLast('.', "").takeIf { requestedName.contains('.') }
		val baseName = extension?.let { requestedName.removeSuffix(".$it") } ?: requestedName
		var suffix = 1
		while (true) {
			val candidate = buildString {
				append(baseName)
				append('_')
				append(suffix++)
				if (extension != null) append('.').append(extension)
			}
			if (root.findFile(candidate) == null) return candidate
		}
	}

	private fun buildStagingName(finalName: String): String {
		val extension = finalName.substringAfterLast('.', "").takeIf { finalName.contains('.') }
		return buildString {
			append(LocalImportSupport.IMPORT_STAGING_PREFIX)
			append(UUID.randomUUID())
			if (extension != null) append('.').append(extension)
		}
	}

	private fun UniFile.copyTo(destination: UniFile) {
		if (isDirectory) {
			for (child in listFiles().orEmpty()) {
				val name = checkNotNull(child.name) { "Imported document has no display name: ${child.uri}" }
				val target = checkNotNull(
					if (child.isDirectory) destination.createDirectory(name) else destination.createFile(name),
				)
				child.copyTo(target)
			}
		} else {
			openInputStream().use { input -> destination.openOutputStream().use(input::copyTo) }
		}
	}

	private suspend fun DocumentFile.source() = runInterruptible(Dispatchers.IO) {
		contentResolver.openSource(uri)
	}

	private fun DocumentFile.requireName(): String {
		return name ?: throw IOException("Cannot fetch name from uri: $uri")
	}

	private fun isDirectory(uri: Uri): Boolean {
		return runCatching {
			DocumentFile.fromTreeUri(context, uri)
		}.isSuccess
	}

}
