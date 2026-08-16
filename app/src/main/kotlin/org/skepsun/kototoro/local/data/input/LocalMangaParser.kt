package org.skepsun.kototoro.local.data.input

import android.net.Uri
import androidx.core.net.toFile
import androidx.core.net.toUri
import com.github.tvbox.osc.base.App
import com.hippo.unifile.UniFile
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
import org.skepsun.kototoro.core.util.ext.isContentZipUri
import org.skepsun.kototoro.core.util.ext.isFileUri
import org.skepsun.kototoro.core.util.ext.isImage
import org.skepsun.kototoro.core.util.ext.isRegularFile
import org.skepsun.kototoro.core.util.ext.isZipUri
import org.skepsun.kototoro.core.util.ext.printStackTraceDebug
import org.skepsun.kototoro.core.util.ext.toFileNameSafe
import org.skepsun.kototoro.core.util.ext.toZipUri
import org.skepsun.kototoro.core.util.ext.toListSorted
import org.skepsun.kototoro.core.util.ext.toUnderlyingZipUri
import org.skepsun.kototoro.core.util.ext.withFragmentFrom
import org.skepsun.kototoro.local.data.ContentIndex
import org.skepsun.kototoro.local.data.hasZipExtension
import org.skepsun.kototoro.local.data.isZipArchive
import org.skepsun.kototoro.local.data.output.LocalContentOutput.Companion.ENTRY_NAME_INDEX
import org.skepsun.kototoro.local.epub.LocalEpubParser
import org.skepsun.kototoro.local.domain.model.LocalContent
import org.skepsun.kototoro.parsers.model.Content
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.parsers.util.longHashCode
import org.skepsun.kototoro.parsers.util.runCatchingCancellable
import org.skepsun.kototoro.parsers.util.toTitleCase
import java.io.File
import java.util.LinkedHashSet

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
	private val uniFile: UniFile?
	private val cacheDir: File

	constructor(file: File) {
		this.uri = null
		this.rootFile = file
		this.uniFile = null
		this.cacheDir = file.parentFile ?: App.getInstance().cacheDir
	}

	constructor(uri: Uri) {
		this.uri = uri
		this.uniFile = if (uri.scheme == "content" || uri.isContentZipUri()) {
			UniFile.fromUri(App.getInstance(), uri.toUnderlyingZipUri().buildUpon().fragment(null).build())
		} else {
			null
		}
		this.rootFile = if (uri.isFileUri()) {
			File(requireNotNull(uri.path) { "File uri path is null: $uri" })
		} else {
			File(App.getInstance().cacheDir, "saf_${uri.toString().longHashCode()}")
		}
		this.cacheDir = App.getInstance().cacheDir
	}

	constructor(file: UniFile, cacheDir: File) {
		this.uri = file.uri
		this.rootFile = file.filePath?.let(::File) ?: File(cacheDir, "saf_${file.uri.toString().longHashCode()}")
		this.uniFile = file
		this.cacheDir = cacheDir
	}

	suspend fun getContent(withDetails: Boolean, forceRefresh: Boolean = false): LocalContent {
		uniFile?.let { return getUniFileContent(it, withDetails, forceRefresh) }
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
		uniFile?.let { file ->
			return if (file.isDirectory) {
				file.findFile(ENTRY_NAME_INDEX)?.openInputStream()?.bufferedReader()?.use { ContentIndex(it.readText()) }
					?.getContentInfo() ?: getUniFileContent(file, withDetails = false, forceRefresh = false).manga
			} else {
				getUniFileContent(file, withDetails = false, forceRefresh = false).manga
			}
		}
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

	suspend fun getPages(chapter: ContentChapter): List<ContentPage> {
		uniFile?.let { file ->
			if (file.isDirectory) {
				return file.listFiles().orEmpty()
					.filter { child -> child.isFile && child.name?.isImageName() == true }
					.sortedWith(compareBy(AlphanumComparator()) { child -> child.name.orEmpty() })
					.map { child ->
						ContentPage(
							id = child.uri.toString().longHashCode(),
							url = child.uri.toString(),
							preview = null,
							source = chapter.source ?: LocalMangaSource,
						)
					}
			}
			val localFile = materialize(file)
			val localUrl = localFile.toUri().withFragmentFrom(chapter.url.toUri()).toString()
			val localChapter = chapter.copy(url = localUrl)
			return LocalContentParser(localFile).getPages(localChapter)
		}
		return runInterruptible(Dispatchers.IO) {
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
					entries.filter { x ->
						(x.isImage() || x.name.endsWith(".html", true) || x.name.endsWith(".xhtml", true)) &&
							x.parent == rootPath
					}
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
	}

	private suspend fun getUniFileContent(file: UniFile, withDetails: Boolean, forceRefresh: Boolean): LocalContent {
		if (file.isFile) {
			val directFile = file.filePath?.let(::File)?.takeIf { file.uri.scheme == "file" }
			if (directFile != null) {
				return LocalContentParser(directFile).getContent(withDetails, forceRefresh)
			}
			val localFile = materialize(file, forceRefresh)
			val parsed = LocalContentParser(localFile).getContent(withDetails)
			val stableCoverUrl = parsed.manga.coverUrl
				?.toUri()
				?.takeIf(Uri::isZipUri)
				?.fragment
				?.takeIf(String::isNotBlank)
				?.let { entry -> file.uri.toZipUri(entry).toString() }
			val localManga = parsed.manga.copy(
				url = file.uri.toString(),
				publicUrl = file.uri.toString(),
				coverUrl = stableCoverUrl ?: parsed.manga.coverUrl,
				chapters = parsed.manga.chapters?.map { chapter ->
					val chapterUri = file.uri.withFragmentFrom(chapter.url.toUri()).toString()
					chapter.copy(url = chapterUri)
				},
			)
			return LocalContent(localManga, localFile, file.uri)
		}

		val index = file.findFile(ENTRY_NAME_INDEX)
			?.openInputStream()
			?.bufferedReader()
			?.use { ContentIndex(it.readText()) }
			?: createUniFileIndex(file)
		val info = checkNotNull(index.getContentInfo()) { "Invalid $ENTRY_NAME_INDEX in ${file.uri}" }
		val localSource = when (info.source?.contentType) {
			ContentType.NOVEL, ContentType.HENTAI_NOVEL -> LocalNovelSource
			ContentType.VIDEO, ContentType.HENTAI_VIDEO -> LocalVideoSource
			else -> LocalMangaSource
		}
		val epubFilesByTitle = lazy {
			file.walkRelative()
				.mapNotNull { (_, child) ->
					child.name
						?.takeIf { it.endsWith(".epub", ignoreCase = true) }
						?.substringBeforeLast('.')
						?.let { title -> title to child }
				}
				.toMap()
		}
		val chapters = if (withDetails) {
			info.chapters?.mapNotNull { chapter ->
				if (chapter.id in index.getHiddenChapterIds()) return@mapNotNull null
				val child = index.getChapterFileName(chapter.id)?.let { relativePath ->
					file.resolveRelative(relativePath)
				} ?: chapter.scanlator
					?.takeIf { chapter.url.toUri().fragment.orEmpty().startsWith("chapter/") }
					?.let { epubFilesByTitle.value[it] }
				if (child != null) {
					val chapterUrl = child.uri.withFragmentFrom(chapter.url.toUri()).toString()
					chapter.copy(url = chapterUrl, source = localSource)
				} else {
					chapter
				}
			}
		} else {
			null
		}
		val coverUrl = index.getCoverEntry()?.let(file::findFile)?.uri?.toString()
		val manga = info.copy(
			url = file.uri.toString(),
			publicUrl = file.uri.toString(),
			coverUrl = coverUrl ?: info.coverUrl,
			chapters = chapters,
		)
		return LocalContent(manga, rootFile, file.uri)
	}

	private suspend fun createUniFileIndex(root: UniFile): ContentIndex {
		val entries = root.walkRelative()
		val videoEntries = entries.filter { (_, child) -> child.name?.isVideoName() == true }
		val novelEntries = entries.filter { (_, child) -> child.name?.isNovelName() == true }
		val archiveEntries = entries.filter { (_, child) -> hasZipExtension(child.name.orEmpty()) }
		val imageEntries = entries.filter { (_, child) -> child.name?.isImageName() == true }
		val source = when {
			videoEntries.isNotEmpty() -> LocalVideoSource
			novelEntries.isNotEmpty() -> LocalNovelSource
			else -> LocalMangaSource
		}
		val chapters = ArrayList<Pair<ContentChapter, String?>>()
		val authors = LinkedHashSet<String>()
		var description: String? = null
		var order = 0
		var volume = 0

		if (source == LocalVideoSource) {
			videoEntries.forEach { (relativePath, child) ->
				order++
				chapters += createUniFileChapter(child, relativePath, order, LocalVideoSource) to relativePath
			}
		} else if (source == LocalNovelSource) {
			novelEntries.forEach { (relativePath, child) ->
				if (child.name?.endsWith(".epub", ignoreCase = true) == true) {
					val parsed = runCatchingCancellable { LocalEpubParser(materialize(child)).parseContent() }.getOrNull()
					parsed?.authors?.filterTo(authors) { it.isNotBlank() }
					if (description == null) description = parsed?.description?.takeIf(String::isNotBlank)
					val internalChapters = parsed?.chapters.orEmpty()
					if (internalChapters.isNotEmpty()) {
						volume++
						internalChapters.forEachIndexed { chapterIndex, chapter ->
							order++
							val chapterUri = child.uri.buildUpon().fragment("chapter/$chapterIndex").build().toString()
							chapters += chapter.copy(
								id = chapterUri.longHashCode(),
								number = order.toFloat(),
								volume = volume,
								url = chapterUri,
								scanlator = child.name?.substringBeforeLast('.'),
								source = LocalNovelSource,
							) to relativePath
						}
						return@forEach
					}
				}
				order++
				chapters += createUniFileChapter(child, relativePath, order, LocalNovelSource) to relativePath
			}
		} else {
			val imageParents = imageEntries.mapTo(LinkedHashSet()) { (relativePath, _) ->
				relativePath.substringBeforeLast('/', "")
			}
			imageParents.forEach { relativePath ->
				order++
				val chapterFile = if (relativePath.isEmpty()) root else root.resolveRelative(relativePath) ?: return@forEach
				chapters += createUniFileChapter(chapterFile, relativePath, order, LocalMangaSource) to relativePath
			}
			archiveEntries.forEach { (relativePath, child) ->
				order++
				chapters += createUniFileChapter(child, relativePath, order, LocalMangaSource) to relativePath
			}
		}

		check(chapters.isNotEmpty()) { "No supported local content in ${root.uri}" }
		val coverUrl = imageEntries.firstOrNull()?.second?.uri?.toString().orEmpty()
		val content = Content(
			id = root.uri.toString().longHashCode(),
			title = root.name.orEmpty().fileNameToTitle(),
			altTitles = emptySet(),
			url = root.uri.toString(),
			publicUrl = root.uri.toString(),
			rating = -1f,
			contentRating = null,
			coverUrl = coverUrl,
			tags = emptySet(),
			state = null,
			authors = authors,
			largeCoverUrl = null,
			description = description,
			chapters = null,
			source = source,
		)
		val index = ContentIndex(null)
		index.setContentInfo(content)
		chapters.forEachIndexed { chapterIndex, (chapter, relativePath) ->
			index.addChapter(IndexedValue(chapterIndex, chapter), relativePath, null)
		}
		runCatchingCancellable {
			val indexFile = root.findFile(ENTRY_NAME_INDEX) ?: checkNotNull(root.createFile(ENTRY_NAME_INDEX))
			indexFile.openOutputStream().bufferedWriter().use { it.write(index.toString()) }
		}.onFailure { it.printStackTraceDebug() }
		return index
	}

	private fun createUniFileChapter(
		file: UniFile,
		relativePath: String,
		order: Int,
		source: org.skepsun.kototoro.parsers.model.ContentSource,
	): ContentChapter {
		val url = file.uri.toString()
		val title = file.name?.substringBeforeLast('.')?.replace('_', ' ')?.toTitleCase().orEmpty()
		return ContentChapter(
			id = "$relativePath#$url".longHashCode(),
			title = title.ifBlank { order.toString() },
			number = order.toFloat(),
			volume = 0,
			url = url,
			scanlator = null,
			uploadDate = file.lastModified(),
			branch = null,
			source = source,
		)
	}

	private fun UniFile.walkRelative(): List<Pair<String, UniFile>> {
		val result = ArrayList<Pair<String, UniFile>>()
		fun visit(directory: UniFile, prefix: String) {
			directory.listFiles().orEmpty()
				.sortedWith(compareBy(AlphanumComparator()) { it.name.orEmpty() })
				.forEach { child ->
					val name = child.name ?: return@forEach
					if (name.startsWith('.') || name == ENTRY_NAME_INDEX) return@forEach
					val relativePath = if (prefix.isEmpty()) name else "$prefix/$name"
					if (child.isDirectory) visit(child, relativePath) else result += relativePath to child
				}
		}
		visit(this, "")
		return result
	}

	private fun UniFile.resolveRelative(relativePath: String): UniFile? {
		if (relativePath.isEmpty()) return this
		val segments = relativePath.split('/').filter(String::isNotEmpty)
		if (segments.any { it == "." || it == ".." }) return null
		return segments.fold(this as UniFile?) { parent, name ->
			parent?.findFile(name)
		}
	}

	private fun String.isImageName(): Boolean =
		MimeTypes.getMimeTypeFromExtension(this)?.isImage == true

	private fun String.isVideoName(): Boolean = endsWith(".mp4", true) ||
		endsWith(".mkv", true) ||
		endsWith(".ts", true) ||
		endsWith(".webm", true) ||
		endsWith(".avi", true) ||
		endsWith(".m3u8", true)

	private fun String.isNovelName(): Boolean = endsWith(".epub", true) || endsWith(".txt", true)

	private fun materialize(file: UniFile, forceRefresh: Boolean = false): File {
		val directory = File(cacheDir, "local_saf").apply { mkdirs() }
		val extension = file.name?.substringAfterLast('.', "").orEmpty()
		val suffix = extension.takeIf(String::isNotEmpty)?.let { ".$it" }.orEmpty()
		val target = File(directory, "${file.uri.toString().longHashCode()}$suffix")
		val sourceLength = file.length()
		val sourceModified = file.lastModified()
		if (
			forceRefresh ||
				!target.isFile ||
				sourceLength >= 0L && target.length() != sourceLength ||
				sourceModified > 0L && target.lastModified() != sourceModified
		) {
			file.openInputStream().use { input -> target.outputStream().use(input::copyTo) }
			if (sourceModified > 0L) target.setLastModified(sourceModified)
		}
		return target
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

		@Blocking
		fun getOrNull(file: UniFile, cacheDir: File): LocalContentParser? {
			if (!file.canRead()) return null
			if (file.isFile && file.name?.hasSupportedLocalContentExtension() == true) {
				return LocalContentParser(file, cacheDir)
			}
			if (!file.isDirectory) return null
			return LocalContentParser(file, cacheDir).takeIf { file.hasSupportedLocalContent() }
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
							MimeTypes.getMimeTypeFromExtension(child.name)?.isImage == true
						)
				}
		}

		private fun UniFile.hasSupportedLocalContent(): Boolean {
			val pending = ArrayDeque<UniFile>()
			pending.add(this)
			while (pending.isNotEmpty()) {
				val current = pending.removeFirst()
				for (child in current.listFiles().orEmpty()) {
					val name = child.name ?: continue
					if (name.startsWith('.')) continue
					if (child.isDirectory) {
						pending.addLast(child)
					} else if (
						name == ENTRY_NAME_INDEX ||
						name.hasSupportedLocalContentExtension() ||
						MimeTypes.getMimeTypeFromExtension(name)?.isImage == true
					) {
						return true
					}
				}
			}
			return false
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
