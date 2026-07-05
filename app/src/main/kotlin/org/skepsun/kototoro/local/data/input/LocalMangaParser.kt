package org.skepsun.kototoro.local.data.input

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toOkioPath
import okio.Path.Companion.toPath
import okio.openZip
import org.jetbrains.annotations.Blocking
import org.skepsun.kototoro.core.model.LocalMangaSource
import org.skepsun.kototoro.core.model.LocalNovelSource
import org.skepsun.kototoro.core.model.LocalVideoSource
import org.skepsun.kototoro.parsers.model.ContentType
import org.skepsun.kototoro.core.util.AlphanumComparator
import org.skepsun.kototoro.core.util.MimeTypes
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_ZIP
import org.skepsun.kototoro.core.util.ext.isDirectory
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isImage
import org.skepsun.kototoro.core.util.ext.isRegularFile
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.core.util.ext.toListSorted
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.local.data.hasZipExtension
import org.skepsun.kototoro.local.data.isZipArchive
import org.skepsun.kototoro.local.data.output.LocalContentOutput.Companion.ENTRY_NAME_INDEX
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.toTitleCase
import java.io.File

/**
 * Content root {dir or zip file}
 * |--- index.json (optional)
 * |--- Page 1.png
 * |--- Page 2.png
 * |---Chapter 1/(dir or zip, optional)
 * |------Page 1.1.png
 * :
 * L--- Page x.png
 */
class LocalContentParser {

	private val uri: Uri?
	private val rootFile: File

	constructor(file: File) {
		this.uri = null
		this.rootFile = file
	}

	constructor(uri: Uri) {
		this.uri = uri
		this.rootFile = if (uri.isFileUri()) {
			File(requireNotNull(uri.path) { "File uri path is null: $uri" })
		} else {
			File(uri.schemeSpecificPart)
		}
	}

	suspend fun getContent(withDetails: Boolean): LocalContent {
		val hasIndexFile = rootFile.isDirectory && File(rootFile, ENTRY_NAME_INDEX).isFile
		if (rootFile.isFile && rootFile.name.endsWith(".epub", ignoreCase = true)) {
			val parser = org.skepsun.kototoro.local.epub.LocalEpubParser(rootFile)
			val content = parser.parseContent()
			if (content != null) {
				val updatedChapters = content.chapters?.map {
					val index = it.url.substringAfterLast("chapter/").toIntOrNull() ?: 0
					it.copy(url = "localepub://${rootFile.absolutePath}#chapter/$index")
				}
				
					var extractedCoverUrl: String? = null
					runCatching {
						okio.FileSystem.SYSTEM.openZip(rootFile.absolutePath.toPath()).use { zipFs ->
							extractedCoverUrl = zipFs.findFirstImageUrl(okio.Path.Companion.DIRECTORY_SEPARATOR.toPath())
						}
					}
				val updatedContent = content.copy(
					chapters = if (withDetails) updatedChapters else null,
					coverUrl = extractedCoverUrl ?: ""
				)
				return LocalContent(updatedContent, rootFile)
			}
		}

		// If the folder contains EPUB files, delegate to LocalEpubParser for proper parsing
		if (rootFile.isDirectory && !hasIndexFile) {
			val epubFiles = rootFile.listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }
			if (!epubFiles.isNullOrEmpty()) {
				val epubFile = epubFiles.first()
				val parser = org.skepsun.kototoro.local.epub.LocalEpubParser(epubFile)
				val epubContent = parser.parseContent()
				if (epubContent != null) {
					val updatedChapters = epubContent.chapters?.map {
						val idx = it.url.substringAfterLast("chapter/").toIntOrNull() ?: 0
						it.copy(url = "localepub://${epubFile.absolutePath}#chapter/$idx")
					}
						var extractedCoverUrl: String? = null
						runCatching {
							val tempParser = LocalContentParser(epubFile)
							okio.FileSystem.SYSTEM.openZip(epubFile.absolutePath.toPath()).use { zipFs ->
								extractedCoverUrl = with(tempParser) {
									zipFs.findFirstImageUrl(okio.Path.Companion.DIRECTORY_SEPARATOR.toPath())
								}
							}
						}
					val updatedContent = epubContent.copy(
						id = rootFile.absolutePath.longHashCode(),
						chapters = if (withDetails) updatedChapters else null,
						coverUrl = extractedCoverUrl ?: ""
					)
					return LocalContent(updatedContent, rootFile)
				}
			}
		}

		return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
			resolveFsAndPath().use { (fileSystem, rootPath) ->
				val index = org.skepsun.kototoro.local.data.ContentIndex.read(fileSystem, rootPath / org.skepsun.kototoro.local.data.output.LocalContentOutput.ENTRY_NAME_INDEX)
				val mangaInfo = index?.getContentInfo()
				if (mangaInfo != null) {
					val coverEntry: okio.Path? = index.getCoverEntry()?.let { rootPath / it }?.takeIf {
						fileSystem.exists(it)
					}
					// 获取隐藏的章节ID列表
					val hiddenChapterIds = index.getHiddenChapterIds()
					
					val resolvedLocalSource = when (mangaInfo.source?.contentType) {
						org.skepsun.kototoro.parsers.model.ContentType.NOVEL, org.skepsun.kototoro.parsers.model.ContentType.HENTAI_NOVEL -> org.skepsun.kototoro.core.model.LocalNovelSource
						org.skepsun.kototoro.parsers.model.ContentType.VIDEO, org.skepsun.kototoro.parsers.model.ContentType.HENTAI_VIDEO -> org.skepsun.kototoro.core.model.LocalVideoSource
						else -> org.skepsun.kototoro.core.model.LocalMangaSource
					}

					val zipEntriesCache = lazy {
						fileSystem.listRecursively(rootPath)
							.filter { fileSystem.isRegularFile(it) }
							.map { it.name.substringBefore('.') }
							.toList()
					}

					mangaInfo.copy(
					chapters = if (withDetails) {
						mangaInfo.chapters?.mapNotNull { c ->
							if (c.url.contains("#chapter/")) {
								return@mapNotNull c
							}
							// 过滤掉隐藏的章节
							if (c.id in hiddenChapterIds) {
								return@mapNotNull null
							}
							
							val fileName = index.getChapterFileName(c.id)
							val path = fileName?.toPath()
							if (path != null && fileSystem.exists(rootPath / path)) {
								// 已加载的本地章节
									c.copy(url = buildChildUriString(path, resolve = false), source = resolvedLocalSource)
								} else if (fileName == null) {
								// 单个CBZ漫画场景，章节没有独立文件夹，但通过 entries 记录了页面
								val pattern = index.getChapterNamesPattern(c)
								if (zipEntriesCache.value.any { it.matches(pattern) }) {
										c.copy(url = buildChildUriString("".toPath(), resolve = false), source = resolvedLocalSource)
									} else {
									c
								}
							} else {
								// 未下载的在线章节（保留原始 URL 和 Source）
								c
							}
						}
					} else {
						// 如果不需要详情，也按索引过滤出实际存在的章节，并统一来源和URL
						mangaInfo.chapters?.mapNotNull { c ->
							if (c.url.contains("#chapter/")) {
								return@mapNotNull c
							}
							val fileName = index.getChapterFileName(c.id)
							val path = fileName?.toPath()
							if (path != null && fileSystem.exists(rootPath / path)) {
									c.copy(url = buildChildUriString(path, resolve = false), source = resolvedLocalSource)
								} else if (fileName == null) {
									val pattern = index.getChapterNamesPattern(c)
									if (zipEntriesCache.value.any { it.matches(pattern) }) {
										c.copy(url = buildChildUriString("".toPath(), resolve = false), source = resolvedLocalSource)
									} else {
									c
								}
							} else {
								// 如果不需要详情，通常是列表页，保留原始章节以显示进度条等
								c
							}
						}
					},
				)
			} else {
				val title = rootFile.name.fileNameToTitle()
				var inferedSource: org.skepsun.kototoro.parsers.model.ContentSource = org.skepsun.kototoro.core.model.LocalMangaSource
				val flatFiles = fileSystem.listRecursively(rootPath).toList()
				if (flatFiles.any {
						it.name.endsWith(".mp4", true) ||
							it.name.endsWith(".mkv", true) ||
							it.name.endsWith(".ts", true) ||
							it.name.endsWith(".webm", true) ||
							it.name.endsWith(".avi", true) ||
							it.name.endsWith(".m3u8", true)
					}) inferedSource = org.skepsun.kototoro.core.model.LocalVideoSource
				else if (flatFiles.any { it.name.endsWith(".epub", true) || it.name.endsWith(".txt", true) }) inferedSource = org.skepsun.kototoro.core.model.LocalNovelSource

				var detectedChapters: List<Pair<ContentChapter, String>>? = null
				val shouldGenerateIndex = rootFile.isDirectory && rootFile.canWrite() && fileSystem == okio.FileSystem.SYSTEM
				if (withDetails || shouldGenerateIndex) {
					var detectedSource: org.skepsun.kototoro.parsers.model.ContentSource = org.skepsun.kototoro.core.model.LocalMangaSource
					val chapters = fileSystem.listRecursively(rootPath)
						.mapNotNullTo(HashSet()) { path ->
							when {
								!fileSystem.isRegularFile(path) -> null
								path.isImage() -> path.parent
								org.skepsun.kototoro.local.data.hasZipExtension(path.name) -> path
								path.name.endsWith(".mp4", true) ||
									path.name.endsWith(".mkv", true) ||
									path.name.endsWith(".ts", true) ||
									path.name.endsWith(".webm", true) ||
									path.name.endsWith(".avi", true) ||
									path.name.endsWith(".m3u8", true) -> {
									detectedSource = org.skepsun.kototoro.core.model.LocalVideoSource
									path
								}
								path.name.endsWith(".epub", true) || path.name.endsWith(".txt", true) -> {
									detectedSource = org.skepsun.kototoro.core.model.LocalNovelSource
									path
								}
								else -> null
							}
						}.sortedWith(compareBy(org.skepsun.kototoro.core.util.AlphanumComparator()) { x -> x.toString() })
					detectedChapters = chapters.mapIndexed { i, p ->
						val s = if (p.root == rootPath.root) {
							p.relativeTo(rootPath).toString()
						} else {
							p
						}.toString().removePrefix(okio.Path.DIRECTORY_SEPARATOR)
						val chapter = ContentChapter(
							id = "$i$s".longHashCode(),
							title = p.userFriendlyName().takeIf { it.isNotBlank() } ?: "1",
							number = 0f,
							volume = 0,
							source = detectedSource,
							uploadDate = 0L,
							url = buildChildUriString(p.relativeTo(rootPath), resolve = false),
							scanlator = null,
							branch = null,
						)
						Pair(chapter, s)
					}
				}

				val content = Content(
					id = rootFile.absolutePath.longHashCode(),
					title = title,
					url = rootFile.toUri().toString(),
					publicUrl = rootFile.toUri().toString(),
					source = inferedSource,
						coverUrl = fileSystem.findFirstImageUrl(rootPath),
					chapters = if (withDetails) detectedChapters?.map { it.first } else null,
					altTitles = emptySet(),
					rating = -1f,
					contentRating = null,
					tags = emptySet(),
					state = null,
					authors = emptySet(),
					largeCoverUrl = null,
					description = null,
				)

				if (shouldGenerateIndex && detectedChapters != null) {
					runCatchingCancellable {
						val newIndex = org.skepsun.kototoro.local.data.ContentIndex(null)
						newIndex.setContentInfo(content.copy(chapters = null))
						detectedChapters.forEachIndexed { idx, pair ->
							newIndex.addChapter(IndexedValue(idx, pair.first), pair.second)
						}
						java.io.File(rootFile, org.skepsun.kototoro.local.data.output.LocalContentOutput.ENTRY_NAME_INDEX).writeText(newIndex.toString())
					}.onFailure {
						it.printStackTraceDebug()
					}
				}

				content
			}.let { org.skepsun.kototoro.local.domain.model.LocalContent(it, rootFile) }
			}
		}
	}

	suspend fun getContentInfo(): Content? {
		val hasIndexFile = rootFile.isDirectory && File(rootFile, ENTRY_NAME_INDEX).isFile
		if (rootFile.isFile && rootFile.name.endsWith(".epub", ignoreCase = true)) {
			val parser = org.skepsun.kototoro.local.epub.LocalEpubParser(rootFile)
			val content = parser.parseContent()
			if (content != null) {
					var extractedCoverUrl: String? = null
					runCatching {
						okio.FileSystem.SYSTEM.openZip(rootFile.absolutePath.toPath()).use { zipFs ->
							extractedCoverUrl = zipFs.findFirstImageUrl(okio.Path.Companion.DIRECTORY_SEPARATOR.toPath())
						}
					}
				return content.copy(
					chapters = null,
					coverUrl = extractedCoverUrl ?: ""
				)
			}
		}

		if (rootFile.isDirectory && !hasIndexFile) {
			val epubFiles = rootFile.listFiles { f -> f.isFile && f.name.endsWith(".epub", ignoreCase = true) }
			if (!epubFiles.isNullOrEmpty()) {
				val epubFile = epubFiles.first()
				val parser = org.skepsun.kototoro.local.epub.LocalEpubParser(epubFile)
				val content = parser.parseContent()
				if (content != null) {
						var extractedCoverUrl: String? = null
						runCatching {
							val tempParser = LocalContentParser(epubFile)
							okio.FileSystem.SYSTEM.openZip(epubFile.absolutePath.toPath()).use { zipFs ->
								extractedCoverUrl = with(tempParser) {
									zipFs.findFirstImageUrl(okio.Path.Companion.DIRECTORY_SEPARATOR.toPath())
								}
							}
						}
					return content.copy(
						id = rootFile.absolutePath.longHashCode(),
						chapters = null,
						coverUrl = extractedCoverUrl ?: ""
					)
				}
			}
		}

		return kotlinx.coroutines.runInterruptible(kotlinx.coroutines.Dispatchers.IO) {
			resolveFsAndPath().use { (fileSystem, rootPath) ->
				val index = org.skepsun.kototoro.local.data.ContentIndex.read(fileSystem, rootPath / org.skepsun.kototoro.local.data.output.LocalContentOutput.ENTRY_NAME_INDEX)
				index?.getContentInfo()
			}
		}
	}

	suspend fun getPages(chapter: ContentChapter): List<ContentPage> = runInterruptible(Dispatchers.IO) {
		val chapterUri = chapter.url.toUri().resolve()
		chapterUri.resolveFsAndPath().use { (fileSystem, rootPath) ->
			if (fileSystem.metadataOrNull(rootPath)?.isDirectory != true) {
				return@runInterruptible listOf(
					ContentPage(
						id = chapterUri.toString().longHashCode(),
						url = chapterUri.toString(),
						preview = null,
						source = chapter.source ?: LocalMangaSource,
					)
				)
			}
			val index = ContentIndex.read(fileSystem, rootPath / ENTRY_NAME_INDEX)
			val entries = fileSystem.listRecursively(rootPath)
				.filter { fileSystem.isRegularFile(it) }
			if (index != null) {
				val pattern = index.getChapterNamesPattern(chapter)
				entries.filter { x -> x.name.substringBefore('.').matches(pattern) }
			} else {
				entries.filter { x -> (x.isImage() || x.name.endsWith(".html", ignoreCase = true) || x.name.endsWith(".xhtml", ignoreCase = true)) && x.parent == rootPath }
			}.toListSorted(compareBy(AlphanumComparator()) { x -> x.toString() })
				.map { x ->
					val entryUri = chapterUri.child(x, resolve = true).toString()
					ContentPage(
						id = entryUri.longHashCode(),
						url = entryUri,
						preview = null,
						source = chapter.source ?: LocalMangaSource,
					)
				}
		}
	}

	private fun buildChildUriString(path: Path, resolve: Boolean): String {
		return uri?.child(path, resolve).toString()
			.takeIf { uri != null }
			?: rootFile.buildChildUriString(path, resolve)
	}

	private fun resolveFsAndPath(): FsAndPath {
		return uri?.resolveFsAndPath() ?: rootFile.resolveFsAndPath()
	}

	private fun Uri.child(path: Path, resolve: Boolean): Uri {
		val file = fileFromPath()
		val builder = buildUpon()
		val isZip = isZipUri() || file.isZipArchive
		if (isZip) {
			builder.scheme(URI_SCHEME_ZIP)
		}
		if (isZip || !resolve) {
			builder.fragment(path.toString().removePrefix(Path.DIRECTORY_SEPARATOR))
		} else {
			builder.appendEncodedPath(path.relativeTo(file.toOkioPath()).toString())
		}
		return builder.build()
	}

	private fun FileSystem.findFirstImageUrl(
		rootPath: Path,
		recursive: Boolean = false
	): String? = runCatchingCancellable {
		val list = list(rootPath)
		for (file in list.sortedWith(compareBy(AlphanumComparator()) { x -> x.name })) {
			if (isRegularFile(file)) {
				if (file.isImage()) {
					return@runCatchingCancellable buildChildUriString(file, resolve = true)
				}
				if (recursive && file.isZip()) {
					openZip(file).use { zipFs ->
						zipFs.findFirstImageUrl(Path.DIRECTORY_SEPARATOR.toPath())?.let { subUrl ->
							val fragment = java.net.URI(subUrl).fragment.orEmpty()
							val baseUrl = buildChildUriString(file, resolve = true)
							return@runCatchingCancellable if (fragment.isBlank()) {
								baseUrl
							} else {
								"$baseUrl#$fragment"
							}
						}
					}
				}
			} else if (recursive && isDirectory(file)) {
				findFirstImageUrl(file, true)?.let {
					return@runCatchingCancellable it
				}
			}
		}
		if (recursive) {
			null
		} else {
				findFirstImageUrl(rootPath, recursive = true)
			}
		}.onFailure { e ->
			e.printStackTraceDebug()
	}.getOrNull()

	private fun Path.userFriendlyName(): String = name.substringBeforeLast('.')
		.replace('_', ' ')
		.toTitleCase()

	private class FsAndPath(
		val fileSystem: FileSystem,
		val path: Path,
		private val isCloseable: Boolean,
	) : AutoCloseable {

		override fun close() {
			if (isCloseable) {
				fileSystem.close()
			}
		}

		operator fun component1() = fileSystem

		operator fun component2() = path
	}

	companion object {

		private val REGEX_PARENT_PATH_PREFIX = Regex("^(/\\.\\.)+")

		@Blocking
		fun getOrNull(file: File): LocalContentParser? {
			if (!file.canRead()) {
				return null
			}
			if (file.isZipArchive) {
				return LocalContentParser(file)
			}
			if (!file.isDirectory) {
				return null
			}
			return LocalContentParser(file).takeIf { file.hasSupportedLocalContent() }
		}

		suspend fun find(roots: Iterable<File>, manga: Content): LocalContentParser? = channelFlow {
			val fileName = manga.title.toFileNameSafe()
			val idFileName = "${manga.id}_$fileName"
			for (root in roots) {
				launch {
					val parser = getOrNull(File(root, fileName)) 
						?: getOrNull(File(root, "$fileName.cbz"))
						?: getOrNull(File(root, idFileName))
						?: getOrNull(File(root, "$idFileName.cbz"))
					val info = runCatchingCancellable { parser?.getContentInfo() }.getOrNull()
					if (info?.id == manga.id) {
						send(parser)
					} else if (parser != null && root.name == manga.title.toFileNameSafe()) {
						send(parser)
					}
				}
			}
		}.flowOn(Dispatchers.Default).firstOrNull()

		private fun Path.isImage(): Boolean = MimeTypes.getMimeTypeFromExtension(name)?.isImage == true

		private fun Path.isZip(): Boolean = hasZipExtension(name)

		private fun File.hasSupportedLocalContent(): Boolean {
			if (File(this, ENTRY_NAME_INDEX).isFile) {
				return true
			}
			return walkTopDown()
				.onEnter { dir -> dir == this || !dir.isHidden }
				.any { child ->
					child.isFile && (
						child.name.hasSupportedLocalContentExtension() ||
							MimeTypes.getMimeTypeFromExtension(child.extension)?.isImage == true
						)
				}
		}

		private fun String.hasSupportedLocalContentExtension(): Boolean {
			return endsWith(".epub", ignoreCase = true) ||
				endsWith(".txt", ignoreCase = true) ||
				endsWith(".cbz", ignoreCase = true) ||
				endsWith(".zip", ignoreCase = true) ||
				endsWith(".mp4", ignoreCase = true) ||
				endsWith(".mkv", ignoreCase = true) ||
				endsWith(".ts", ignoreCase = true) ||
				endsWith(".webm", ignoreCase = true) ||
				endsWith(".avi", ignoreCase = true) ||
				endsWith(".m3u8", ignoreCase = true)
		}

		private fun Uri.resolve(): Uri = if (isFileUri()) {
			val file = toFile()
			if (file.isZipArchive) {
				this
			} else if (file.isDirectory) {
				file.resolve(fragment.orEmpty()).toUri()
			} else {
				this
			}
		} else {
			this
		}

		private fun Uri.fileFromPath(): File = File(requireNotNull(path) { "Uri path is null: $this" })

		private fun File.buildChildUriString(path: Path, resolve: Boolean): String {
			val relative = path.toString().removePrefix(Path.DIRECTORY_SEPARATOR)
			return if (isZipArchive || !resolve) {
				if (relative.isBlank()) {
					toURI().toString()
				} else {
					"${toURI()}#$relative"
				}
			} else {
				resolve(relative).toURI().toString()
			}
		}

		@Blocking
		private fun File.resolveFsAndPath(): FsAndPath = if (isZipArchive) {
			FsAndPath(
				FileSystem.SYSTEM.openZip(absolutePath.toPath()),
				"".toRootedPath(),
				isCloseable = true,
			)
		} else {
			FsAndPath(FileSystem.SYSTEM, toOkioPath(), isCloseable = false)
		}

		@Blocking
		private fun Uri.resolveFsAndPath(): FsAndPath {
			val resolved = resolve()
			return when {
				resolved.isZipUri() -> FsAndPath(
					FileSystem.SYSTEM.openZip(resolved.schemeSpecificPart.toPath()),
					resolved.fragment.orEmpty().toRootedPath(),
					isCloseable = true,
				)

				isFileUri() -> {
					val file = toFile()
					if (file.isZipArchive) {
						FsAndPath(
							FileSystem.SYSTEM.openZip(schemeSpecificPart.toPath()),
							fragment.orEmpty().toRootedPath(),
							isCloseable = true,
						)
					} else {
						FsAndPath(FileSystem.SYSTEM, file.toOkioPath(), isCloseable = false)
					}
				}

				else -> throw IllegalArgumentException("Unsupported uri $resolved")
			}
		}

		private fun String.toRootedPath(): Path = if (startsWith(Path.DIRECTORY_SEPARATOR)) {
			this
		} else {
			Path.DIRECTORY_SEPARATOR + this
		}.toPath()

		private fun String.fileNameToTitle() = substringBeforeLast('.')
			.replace('_', ' ')
			.replaceFirstChar { it.uppercase() }
	}
}
