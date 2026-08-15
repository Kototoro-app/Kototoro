package org.skepsun.kototoro.local.data.output

import androidx.annotation.WorkerThread
import com.hippo.unifile.UniFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.internal.closeQuietly
import org.skepsun.kototoro.core.model.isLocal
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.MimeType
import org.skepsun.kototoro.core.util.ext.deleteAwait
import org.skepsun.kototoro.core.util.ext.readText
import org.skepsun.kototoro.core.zip.ZipOutput
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import java.io.File
import java.util.zip.ZipFile

class LocalContentZipOutput(
	rootFile: UniFile,
	manga: Content,
	cacheDir: File,
) : LocalContentOutput(rootFile, cacheDir) {

	val output = ZipOutput(File.createTempFile("content_", SUFFIX_TMP, cacheDir))
	val index = ContentIndex(null)
	private val mutex = Mutex()

	init {
		if (!manga.isLocal) {
			index.setContentInfo(manga)
		}
	}

	override suspend fun mergeWithExisting() = mutex.withLock {
		if (rootFile.exists() && rootFile.length() > 0L) {
			runInterruptible(Dispatchers.IO) {
				val existing = File.createTempFile("existing_", ".cbz", cacheDir)
				try {
					rootFile.openInputStream().use { input -> existing.outputStream().use(input::copyTo) }
					mergeWith(existing)
				} finally {
					existing.delete()
				}
			}
		}
	}

	override suspend fun addCover(file: File, type: MimeType?) = mutex.withLock {
		val name = buildString {
			append(FILENAME_PATTERN.format(0, 0, 0))
			MimeTypes.getExtension(type)?.let { ext ->
				append('.')
				append(ext)
			}
		}
		runInterruptible(Dispatchers.IO) {
			output.put(name, file)
		}
		index.setCoverEntry(name)
	}

	override suspend fun addPage(chapter: IndexedValue<ContentChapter>, file: File, pageNumber: Int, type: MimeType?) =
		mutex.withLock {
			val name = buildString {
				append(FILENAME_PATTERN.format(chapter.value.branch.hashCode(), chapter.index + 1, pageNumber))
				MimeTypes.getExtension(type)?.let { ext ->
					append('.')
					append(ext)
				}
			}
			runInterruptible(Dispatchers.IO) {
				output.put(name, file)
			}
			index.addChapter(chapter, null, null)
		}

	override suspend fun putChapterImages(chapterId: Long, remoteImages: Map<String, String>) =
		mutex.withLock {
			index.putChapterImages(chapterId, remoteImages)
		}

	override suspend fun flushChapter(chapter: ContentChapter): Boolean = false

	override suspend fun finish() = mutex.withLock {
		runInterruptible(Dispatchers.IO) {
			output.use { output ->
				output.put(ENTRY_NAME_INDEX, index.toString())
				output.finish()
			}
		}
		output.file.inputStream().use { input -> rootFile.openOutputStream().use(input::copyTo) }
		output.file.delete()
		Unit
	}

	override suspend fun cleanup() = mutex.withLock {
		output.file.deleteAwait()
		Unit
	}

	override fun close() {
		output.close()
	}

	@WorkerThread
	private fun mergeWith(other: File) {
		var otherIndex: ContentIndex? = null
		ZipFile(other).use { zip ->
			for (entry in zip.entries()) {
				if (entry.name == ENTRY_NAME_INDEX) {
					otherIndex = ContentIndex(
						zip.getInputStream(entry).use {
							it.reader().readText()
						},
					)
				} else {
					output.copyEntryFrom(zip, entry)
				}
			}
		}
		otherIndex?.getContentInfo()?.chapters?.withIndex()?.let { chapters ->
			for (chapter in chapters) {
				index.addChapter(chapter, null)
			}
		}
	}

	companion object {

		private const val FILENAME_PATTERN = "%08d_%04d%04d"

		suspend fun filterChapters(file: File, manga: Content, idsToRemove: Set<Long>) =
			runInterruptible(Dispatchers.IO) {
				val subject = LocalContentZipOutput(checkNotNull(UniFile.fromFile(file)), manga, file.parentFile ?: file)
				try {
					ZipFile(file).use { zip ->
						val index = ContentIndex(zip.readText(zip.getEntry(ENTRY_NAME_INDEX)))
						idsToRemove.forEach { id -> index.removeChapter(id) }
						val patterns = requireNotNull(index.getContentInfo()?.chapters)
							.filter { it.id !in idsToRemove }
							.map {
								index.getChapterNamesPattern(it)
							}
						val coverEntryName = index.getCoverEntry()
						for (entry in zip.entries()) {
							when {
								entry.name == ENTRY_NAME_INDEX -> {
									subject.output.put(ENTRY_NAME_INDEX, index.toString())
								}

								entry.isDirectory -> {
									subject.output.addDirectory(entry.name)
								}

								entry.name == coverEntryName -> {
									subject.output.copyEntryFrom(zip, entry)
								}

								else -> {
									val name = entry.name.substringBefore('.')
									if (patterns.any { it.matches(name) }) {
										subject.output.copyEntryFrom(zip, entry)
									}
								}
							}
						}
						subject.output.finish()
						subject.output.close()
						file.delete()
						subject.output.file.renameTo(file)
					}
				} catch (e: Throwable) {
					subject.closeQuietly()
					try {
						subject.output.file.delete()
					} catch (e2: Throwable) {
						e.addSuppressed(e2)
					}
					throw e
				}
			}
	}
}
