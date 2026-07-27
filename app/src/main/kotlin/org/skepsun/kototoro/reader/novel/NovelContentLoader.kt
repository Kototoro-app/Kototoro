package org.skepsun.kototoro.reader.novel

import android.content.Context
import android.util.Base64
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.withContext
import okio.buffer
import okio.source
import org.skepsun.kototoro.core.db.MangaDatabase
import org.skepsun.kototoro.core.parser.ContentRepository
import org.skepsun.kototoro.core.replace.ReplaceRule
import org.skepsun.kototoro.core.replace.ReplaceRuleRepository
import org.skepsun.kototoro.local.data.LocalStorageCache
import org.skepsun.kototoro.local.data.NovelCache
import org.skepsun.kototoro.core.util.ext.toMimeType
import org.skepsun.kototoro.parsers.model.ContentChapter
import org.skepsun.kototoro.parsers.model.ContentPage
import org.skepsun.kototoro.core.util.ext.URI_SCHEME_ZIP
import org.skepsun.kototoro.core.util.ext.toMimeType
import java.io.File
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 小说内容加载器
 * 负责加载和缓存小说章节内容
 * 复用漫画阅读器的缓存机制
 * 
 * 支持：
 * - 在线章节（通过repository加载）
 * - EPUB章节（通过EpubReader加载，使用epub://协议）
 */
@Singleton
class NovelContentLoader @Inject constructor(
    @NovelCache private val cache: LocalStorageCache,
    private val epubStorageManager: org.skepsun.kototoro.local.epub.EpubStorageManager,
    private val mangaDatabase: MangaDatabase,
    private val epubContentCache: org.skepsun.kototoro.local.epub.EpubContentCache,
    @ApplicationContext private val appContext: Context,
) {

    private val replaceRuleRepo: ReplaceRuleRepository by lazy {
        ReplaceRuleRepository(appContext)
    }

    /**
     * 加载章节内容（带缓存）
     * @param repository 漫画仓库
     * @param chapter 章节信息
     * @return 章节的纯文本内容
     */
    suspend fun loadChapterContent(
        repository: ContentRepository,
        chapter: ContentChapter,
        pages: List<ContentPage>? = null,
    ): String = withContext(Dispatchers.IO) {
        val flow = loadChapterContentFlow(repository, chapter, pages, forceRefresh = false)
        flow.toList().lastOrNull() ?: ""
    }

    /**
     * 以流的形式加载章节内容，支持增量渲染
     */
    fun loadChapterContentFlow(
        repository: ContentRepository,
        chapter: ContentChapter,
        prefetchedPages: List<ContentPage>? = null,
        forceRefresh: Boolean = false,
        priority: Int = org.skepsun.kototoro.core.parser.legado.RequestPriority.FOREGROUND,
        nextChapterUrl: String? = null
    ): Flow<String> = kotlinx.coroutines.flow.channelFlow {
        android.util.Log.d("NovelContentLoader", ">>> loadChapterContentFlow START: id=${chapter.id}, priority=$priority, nextChapterUrl=$nextChapterUrl")

        // 注入优先级到当前协程上下文，确保流的 upstream (repository.getPagesFlow) 能看到
        withContext(org.skepsun.kototoro.core.parser.legado.RequestPriority(priority)) {
            // 0. 本地/EPUB 逻辑（目前不支持增量，直接一次性返回）
            if (org.skepsun.kototoro.local.epub.LocalEpubSource.isEpubUrl(chapter.url)) {
                send(loadEpubChapterContent(chapter))
                return@withContext
            }

            if (chapter.url.startsWith("localepub://")) {
                val filePath = chapter.url.substringAfter("localepub://").substringBefore("#")
                val chapterIndex = chapter.url.substringAfter("#chapter/").toIntOrNull() ?: 0
                val epubParser = org.skepsun.kototoro.local.epub.LocalEpubParser(java.io.File(filePath), epubContentCache)
                val rawContent = epubParser.getChapterContent(chapterIndex) ?: "加载本地小说失败: 内容为空"
                send(htmlToPlainText(rawContent))
                return@withContext
            }

            if (chapter.url.startsWith(URI_SCHEME_ZIP, ignoreCase = true) || 
                chapter.url.startsWith("zip", ignoreCase = true) || 
                chapter.url.startsWith("file", ignoreCase = true) ||
                chapter.url.startsWith("cbz", ignoreCase = true)) {
                
                android.util.Log.d("NovelContentLoader", "Detected local novel chapter, reading directly: ${chapter.url}")
                val localContent = runCatching {
                    val uri = android.net.Uri.parse(chapter.url)
                    val pages = org.skepsun.kototoro.local.data.input.LocalContentParser(uri).getPages(chapter)
                    if (pages.isNotEmpty()) {
                        htmlToPlainText(concatPagesHtml(pages))
                    } else {
                        val javaUri = java.net.URI(chapter.url)
                        readLocalHtmlFromUri(javaUri)?.let { html ->
                            val rewritten = rewriteLocalImageSrc(html, javaUri)
                            htmlToPlainText(rewritten)
                        }
                    }
                }.getOrNull()

                if (localContent != null && localContent.isNotBlank()) {
                    send(localContent)
                    return@withContext
                }
            }

            val cacheKey = generateCacheKey(chapter)
            android.util.Log.d("NovelContentLoader", "Checking cache for $cacheKey (chapterId=${chapter.id}, url=${chapter.url})")
            if (!forceRefresh) {
                val cachedFile = cache.get(cacheKey)
                if (cachedFile != null) {
                    val content = readTextFromFile(cachedFile)
                    if (!isErrorContent(content)) {
                        android.util.Log.d("NovelContentLoader", "Cache HIT for $cacheKey")
                        send(content)
                        return@withContext
                    } else {
                        android.util.Log.d("NovelContentLoader", "Cache EXPIRED/INVALID for $cacheKey")
                    }
                } else {
                    android.util.Log.d("NovelContentLoader", "Cache MISS for $cacheKey")
                }
            }

            // 1. 网络抓取（支持 Flow）
            if (prefetchedPages != null) {
                val html = concatPagesHtml(prefetchedPages)
                val plainText = htmlToPlainText(html)
                send(plainText)
                if (plainText.isNotBlank() && !isErrorContent(plainText)) {
                    saveToCache(cacheKey, plainText)
                }
            } else {
                repository.getChapterContent(chapter, nextChapterUrl)?.let { chapterContent ->
                    val plainText = htmlToPlainText(chapterContent.html)
                    send(plainText)
                    if (plainText.isNotBlank() && !isErrorContent(plainText)) {
                        saveToCache(cacheKey, plainText)
                    }
                    return@withContext
                }
                var fullHtml = ""
                repository.getPagesFlow(chapter, nextChapterUrl).collect { currentPages ->
                    val html = concatPagesHtml(currentPages)
                    val plainText = htmlToPlainText(html)
                    send(plainText)
                    fullHtml = html
                }
                
                // 保存最终结果到缓存
                val finalText = htmlToPlainText(fullHtml)
                if (finalText.isNotBlank() && !isErrorContent(finalText)) {
                    saveToCache(cacheKey, finalText)
                }
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun concatPagesHtml(pages: List<ContentPage>): String {
        val sb = StringBuilder()
        pages.forEach { page ->
            val html = if (page.url.startsWith("data:", ignoreCase = true)) {
                decodeChapterHtml(page.url)
            } else {
                runCatching {
                    val uri = java.net.URI(page.url)
                    readLocalHtmlFromUri(uri)?.let { h ->
                        rewriteLocalImageSrc(h, uri)
                    }
                }.getOrNull()
            }
            sb.append(html ?: "")
            sb.append("\n")
        }
        return sb.toString()
    }

    /**
     * 检查章节是否已缓存
     */
    suspend fun isCached(chapter: ContentChapter): Boolean = withContext(Dispatchers.IO) {
        val cacheKey = generateCacheKey(chapter)
        cache.get(cacheKey)?.let { !isErrorContent(readTextFromFile(it)) } ?: false
    }

    /**
     * 强制重新拉取（忽略缓存），用于之前缓存了错误提示时的手动刷新
     */
    suspend fun refreshChapterContent(
        repository: ContentRepository,
        chapter: ContentChapter,
    ): String = withContext(Dispatchers.IO) {
        loadChapterContentInternal(repository, chapter, null, forceRefresh = true)
    }

    private suspend fun loadChapterContentInternal(
        repository: ContentRepository,
        chapter: ContentChapter,
        prefetchedPages: List<ContentPage>?,
        forceRefresh: Boolean,
    ): String {
        android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal START: id=${chapter.id}, prefetched=${prefetchedPages != null}, force=$forceRefresh")
        
        // 0. 本地 CBZ/ZIP 小说章节：通过 pages 读取 HTML
        val localHtml = loadLocalHtmlViaPages(repository, chapter, prefetchedPages)
        if (localHtml != null) {
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Loaded via local pages logic. Length=${localHtml.length}")
            return localHtml
        }

        // Check if this is an EPUB chapter (NEW ARCHITECTURE)
        if (org.skepsun.kototoro.local.epub.LocalEpubSource.isEpubUrl(chapter.url)) {
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Loading EPUB chapter: ${chapter.url}")
            return loadEpubChapterContent(chapter)
        }

        if (chapter.url.startsWith("localepub://")) {
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Loading Local EPUB chapter: ${chapter.url}")
            val filePath = chapter.url.substringAfter("localepub://").substringBefore("#")
            val chapterIndex = chapter.url.substringAfter("#chapter/").toIntOrNull() ?: 0
            val epubParser = org.skepsun.kototoro.local.epub.LocalEpubParser(java.io.File(filePath), epubContentCache)
            val rawContent = epubParser.getChapterContent(chapterIndex) ?: "加载本地小说失败: 内容为空"
            return htmlToPlainText(rawContent)
        }
        
        val cacheKey = generateCacheKey(chapter)
        if (!forceRefresh) {
            // 1. 尝试从缓存读取
            cache.get(cacheKey)?.let { cachedFile ->
                val content = readTextFromFile(cachedFile)
                if (!isErrorContent(content)) {
                    android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: CACHE HIT for $cacheKey, length=${content.length}")
                    return content
                } else {
                    android.util.Log.w("NovelContentLoader", ">>> loadChapterContentInternal: Cached content looks like error/placeholder, skipping cache")
                }
            }
        }

        android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: CACHE MISS or FORCE, loading from network")

        // 2. 从网络加载
        val pages = if (prefetchedPages != null) {
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Using prefetched pages (${prefetchedPages.size})")
            prefetchedPages
        } else {
            repository.getChapterContent(chapter)?.let { chapterContent ->
                val plainText = htmlToPlainText(chapterContent.html)
                android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Loaded via getChapterContent. Length=${plainText.length}")
                if (plainText.isNotBlank() && !isErrorContent(plainText)) {
                    saveToCache(cacheKey, plainText)
                    android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Saved to cache: $cacheKey")
                }
                return plainText
            }
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Fetching pages from repository...")
            repository.getPages(chapter)
        }
        
        val firstUrl = pages.firstOrNull()?.url
        android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: First page URL=${firstUrl?.take(100)}")
        
        val html = if (firstUrl != null) decodeChapterHtml(firstUrl) else ""
        val plainText = htmlToPlainText(html)
        
        android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Content parsed from network. Length=${plainText.length}")
        
        // 3. 保存到缓存
        if (plainText.isNotBlank() && !isErrorContent(plainText)) {
            saveToCache(cacheKey, plainText)
            android.util.Log.d("NovelContentLoader", ">>> loadChapterContentInternal: Saved to cache: $cacheKey")
        }
        
        return plainText
    }

    private fun loadLocalHtmlChapter(chapter: ContentChapter): String {
        return try {
            val uri = java.net.URI(chapter.url)
            val file = java.io.File(uri)
            if (!file.exists()) {
                android.util.Log.w("NovelContentLoader", "Local chapter file not found: ${chapter.url}")
                "本地章节文件不存在：${chapter.url}"
            } else {
                val html = readTextFromFile(file)
                val baseDir = file.parentFile
                val rewritten = if (baseDir != null) {
                    rewriteLocalImageSrc(html, baseDir)
                } else html
                htmlToPlainText(rewritten)
            }
        } catch (e: Exception) {
            android.util.Log.e("NovelContentLoader", "Failed to load local chapter ${chapter.url}", e)
            "加载本地章节失败：${e.message}"
        }
    }

	private suspend fun loadLocalHtmlViaPages(
		repository: ContentRepository,
		chapter: ContentChapter,
		prefetchedPages: List<ContentPage>?,
	): String? {
		return runCatching {
			val pages = prefetchedPages ?: repository.getPages(chapter)
			android.util.Log.d(
				"NovelContentLoader",
				"loadLocalHtmlViaPages: id=${chapter.id}, prefetched=${prefetchedPages != null}, pages=${pages.size}",
			)
			if (pages.isEmpty()) return null
			
			// 如果第一页是图片，说明这是插图章节，生成合成HTML显示所有图片
			val firstUrl = pages.first().url
			if (firstUrl.isImageName()) {
				android.util.Log.d("NovelContentLoader", "Detected image-only chapter, generating synthetic HTML")
				val syntheticHtml = pages.joinToString("\n") { p -> 
					// 使用 fragment (entry name) 或者完整 URL
					val entryName = java.net.URI(p.url).fragment?.removePrefix("/") ?: p.url
					"<img src=\"$entryName\">" 
				}
				// 即使是合成HTML也经过转换，确保渲染器能正确处理占位符
				return htmlToPlainText(syntheticHtml)
			}

			htmlToPlainText(concatPagesHtml(pages))
        }.getOrNull()
    }

    private fun readLocalHtmlFromUri(uri: java.net.URI): String? {
		return runCatching {
			when {
				uri.scheme.equals("file", ignoreCase = true) -> {
					val file = File(uri.path) // Use path to avoid fragment issues
					if (file.exists()) file.readText() else null
				}

				uri.scheme.equals("data", ignoreCase = true) -> {
					val data = uri.schemeSpecificPart
					val comma = data.indexOf(',')
					if (comma <= 0) return null
					val meta = data.substring(0, comma)
					val contentPart = data.substring(comma + 1)
					val isBase64 = meta.contains(";base64", ignoreCase = true)
					if (isBase64) {
						String(android.util.Base64.decode(contentPart, android.util.Base64.DEFAULT))
					} else {
						java.net.URLDecoder.decode(contentPart, "UTF-8")
					}
				}

				uri.scheme.equals(URI_SCHEME_ZIP, ignoreCase = true) ||
					uri.scheme.equals("zip", ignoreCase = true) ||
					uri.scheme.equals("cbz", ignoreCase = true) -> {
					val zipPath = uri.schemeSpecificPart.substringBefore('#').removePrefix("///").let { 
						if (it.startsWith("/")) it else "/$it" 
					}
					val entryPath = uri.fragment?.removePrefix("/") ?: return null
					ZipFile(zipPath).use { zip ->
						val entry = zip.getEntry(entryPath) ?: return null
						zip.getInputStream(entry).bufferedReader().use { it.readText() }
					}
				}

				else -> null
			}
		}.getOrNull()
    }

    private fun rewriteLocalImageSrc(html: String, uri: java.net.URI): String {
        return when {
            uri.scheme.equals("file", ignoreCase = true) -> {
                val file = File(uri)
                val base = file.parentFile
                if (base != null) rewriteLocalImageSrc(html, base) else html
            }

			uri.scheme.equals(URI_SCHEME_ZIP, ignoreCase = true) ||
				uri.scheme.equals("zip", ignoreCase = true) ||
				uri.scheme.equals("cbz", ignoreCase = true) -> {
				val zipPath = uri.schemeSpecificPart
				val base = uri.fragment.orEmpty().substringBeforeLast("/", "")
				val nameToEntry = HashMap<String, String>()
				val chapterImages = HashMap<String, String>()
				val imageEntries = runCatching {
					ZipFile(zipPath).use { zip ->
						// 1) 读取 index.json 映射。针对 MULTIPLE_CBZ，索引文件在 ZIP 同级目录下
						val zipFile = File(zipPath)
						val indexContent = zip.getEntry("index.json")?.let { entry ->
							zip.getInputStream(entry).bufferedReader().use { it.readText() }
						} ?: File(zipFile.parentFile, "index.json").takeIf { it.exists() }?.readText()

						indexContent?.let { json ->
							val rootObj = org.json.JSONObject(json)
							val chaptersObj = rootObj.optJSONObject("chapters")
							val zipName = zipFile.name
							val htmlInside = uri.fragment?.removePrefix("/")

							val matched = chaptersObj?.keys()?.asSequence()?.any { id ->
								val chapterObj = chaptersObj.optJSONObject(id) ?: return@any false
								val fileName = chapterObj.optString("file")
								// 匹配逻辑：
								// 1. 如果 filename 匹配 ZIP 文件名（MULTIPLE_CBZ）
								// 2. 或者 filename 匹配内部 HTML（旧逻辑或 SINGLE_CBZ 兜底）
								if (fileName != zipName && fileName != htmlInside) return@any false

								chapterObj.optJSONArray("images")?.let { arr ->
									for (i in 0 until arr.length()) {
										val o = arr.optJSONObject(i) ?: continue
										val remote = o.optString("remote")
										val local = o.optString("local")
										if (remote.isNotBlank() && local.isNotBlank()) {
											chapterImages.putIfAbsent(remote, local)
										}
									}
								}
								true
							} ?: false

							android.util.Log.d(
								"NovelContentLoader",
								"index.json hit: zip=$zipName, html=$htmlInside, matched=$matched, images=${chapterImages.size}",
							)

							if (!matched) {
								chaptersObj?.keys()?.forEach { id ->
									val chapterObj = chaptersObj.optJSONObject(id) ?: return@forEach
									chapterObj.optJSONArray("images")?.let { arr ->
										for (i in 0 until arr.length()) {
											val o = arr.optJSONObject(i) ?: continue
											val remote = o.optString("remote")
											val local = o.optString("local")
											if (remote.isNotBlank() && local.isNotBlank()) {
												chapterImages.putIfAbsent(remote, local)
											}
										}
									}
								}
							}
						}

						// 2) 枚举图片 entries，按文件名建立映射并记录顺序
						val acc = mutableListOf<String>()
						val iter = zip.entries().asIterator()
						while (iter.hasNext()) {
							val entry = iter.next()
							if (!entry.isDirectory && entry.name.isImageName()) {
								acc.add(entry.name)
								val name = entry.name.substringAfterLast('/')
								nameToEntry.putIfAbsent(name, entry.name)
							}
						}
						android.util.Log.d(
							"NovelContentLoader",
							"zip entries collected: images=${acc.size}, nameToEntry=${nameToEntry.size}",
						)
						acc.sorted()
					}
				}.getOrDefault(emptyList())
				var fallbackIndex = 0
				runCatching {
					val doc = org.jsoup.Jsoup.parse(html)
					doc.select("img").forEach { img ->
                        val src = (img.attr("data-src").ifBlank { img.attr("src") }).trim()
                        if (src.isBlank()) return@forEach
                        // 先用 index.json 中记录的映射
                        var entryPath: String? = chapterImages[src]
						if (entryPath != null) {
							android.util.Log.d("NovelContentLoader", "image map hit: $src -> $entryPath")
						}
                        if (src.startsWith("http", true) || src.startsWith("file", true)) {
                            val key = src.substringAfterLast('/')
                            entryPath = entryPath ?: nameToEntry[key]
							if (entryPath != null) {
								android.util.Log.d("NovelContentLoader", "image name hit: $src -> $entryPath")
							}
                        } else {
                            entryPath = entryPath ?: normalizeZipPath(base, src)?.takeIf { it in imageEntries }
							if (entryPath != null) {
								android.util.Log.d("NovelContentLoader", "image relative hit: $src -> $entryPath")
							}
                        }
                        if (entryPath == null && fallbackIndex < imageEntries.size) {
                            entryPath = imageEntries[fallbackIndex++]
							android.util.Log.d("NovelContentLoader", "image fallback: $src -> $entryPath")
                        }
						if (entryPath == null) {
							android.util.Log.w("NovelContentLoader", "image unresolved, keep remote: $src")
						}
                        if (entryPath != null) {
							val absolute = "$URI_SCHEME_ZIP://$zipPath#$entryPath"
							img.attr("src", absolute)
							img.removeAttr("data-src")
						}
					}
					doc.outerHtml()
				}.getOrDefault(html)
			}

			else -> html
		}
	}

	private fun String.isImageName(): Boolean {
		val ext = substringAfterLast('.', missingDelimiterValue = "").lowercase()
		return ext in setOf("jpg", "jpeg", "png", "webp", "gif", "avif", "bmp")
	}

    private fun normalizeZipPath(base: String, src: String): String {
        val clean = src.removePrefix("./").removePrefix("/")
        return if (base.isBlank()) clean else "$base/$clean"
    }

    private fun rewriteLocalImageSrc(html: String, baseDir: java.io.File): String {
        val imagesMap = try {
            val indexContent = File(baseDir, "index.json").takeIf { it.exists() }?.readText()
                ?: File(baseDir.parentFile, "index.json").takeIf { it.exists() }?.readText()
            
            if (indexContent != null) {
                val json = org.json.JSONObject(indexContent)
                val chaptersObj = json.optJSONObject("chapters")
                val map = HashMap<String, String>()
                chaptersObj?.keys()?.forEach { id ->
                    val chapterObj = chaptersObj.optJSONObject(id)
                    chapterObj?.optJSONArray("images")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val remote = o.optString("remote")
                            val local = o.optString("local")
                            if (remote.isNotBlank() && local.isNotBlank()) {
                                map[remote] = local
                            }
                        }
                    }
                }
                map
            } else emptyMap()
        } catch (e: Exception) {
            emptyMap()
        }

        return runCatching {
            val doc = org.jsoup.Jsoup.parse(html)
            doc.select("img").forEach { img ->
                val src = (img.attr("data-src").ifBlank { img.attr("src") }).trim()
                if (src.isBlank()) return@forEach
                
                // 优先使用索引映射
                val localPath = imagesMap[src]
                val file = if (localPath != null) {
                    java.io.File(baseDir, localPath)
                } else {
                    java.io.File(baseDir, src)
                }
                
                if (file.exists()) {
                    val abs = file.toURI().toString()
                    img.attr("src", abs)
                    img.removeAttr("data-src")
                } else if (!src.startsWith("http", ignoreCase = true)) {
                    // 如果不是远程，也不是已知文件，尝试补全为本地 URI
                    val abs = file.toURI().toString()
                    img.attr("src", abs)
                }
            }
            doc.outerHtml()
        }.getOrDefault(html)
    }
    
    /**
     * Load EPUB chapter content (NEW ARCHITECTURE)
     * 
     * Parses epub:// URL and loads content from EPUB file
     * Format: epub://{manga_id}/chapter/{index}
     * 
     * Uses EpubChapterMappingDao to find the correct EPUB file path
     * (supports multiple EPUB files per manga, e.g., Z-Library)
     */
    private suspend fun loadEpubChapterContent(chapter: ContentChapter): String = withContext(Dispatchers.IO) {
        try {
            // Parse epub:// URL
            val regex = Regex("epub://(-?\\d+)/chapter/(\\d+)")
            val match = regex.matchEntire(chapter.url)
                ?: throw IllegalStateException("Invalid EPUB URL: ${chapter.url}")
            
            val mangaId = match.groupValues[1].toLong()
            val chapterIndex = match.groupValues[2].toInt()
            
            android.util.Log.d("NovelContentLoader", "Loading EPUB: mangaId=$mangaId, chapterIndex=$chapterIndex")
            
            // Query database for EPUB file path using chapter index
            val epubChapterMappingDao = mangaDatabase.getEpubChapterMappingDao()
            val allMappings = epubChapterMappingDao.findByContentId(mangaId)
            
            // Sort mappings by parentChapterId and chapterIndex to match LocalEpubSource ordering
            val sortedMappings = allMappings.sortedWith(compareBy({ it.parentChapterId }, { it.chapterIndex }))
            
            // Find mapping by global index (the index in the URL corresponds to the position in sorted list)
            val mapping = sortedMappings.getOrNull(chapterIndex)
                ?: throw IllegalStateException("EPUB chapter mapping not found for manga $mangaId, index $chapterIndex (total mappings: ${sortedMappings.size})")
            
            android.util.Log.d("NovelContentLoader", "Found EPUB file: ${mapping.epubFilePath}")
            
            // Get EPUB file from mapping
            val epubFile = java.io.File(mapping.epubFilePath)
            if (!epubFile.exists()) {
                throw IllegalStateException("EPUB file not found: ${mapping.epubFilePath}")
            }
            
            // Load chapter content using EpubReaderImpl
            val reader = org.skepsun.kototoro.local.epub.EpubReaderImpl(epubContentCache)
            val epubContent = reader.readEpub(epubFile)
                ?: throw IllegalStateException("Failed to parse EPUB file")
            
            // Get chapter by index (use mapping.chapterIndex which is the index within the EPUB file)
            val epubChapter = epubContent.chapters.getOrNull(mapping.chapterIndex)
                ?: throw IllegalStateException("Chapter index ${mapping.chapterIndex} not found in EPUB")
            
            val content = epubChapter.content

            android.util.Log.d("NovelContentLoader", "Loaded EPUB chapter, content length: ${content.length}")

            applyReplaceRules(content)
        } catch (e: Exception) {
            android.util.Log.e("NovelContentLoader", "Failed to load EPUB chapter", e)
            throw e
        }
    }

    /**
     * 清除所有小说缓存
     */
    suspend fun clearCache() {
        cache.clear()
    }

    /**
     * 生成缓存key
     * 只使用章节ID，因为URL可能包含动态参数（时间戳、token等）
     */
    private fun generateCacheKey(chapter: ContentChapter): String {
        // 只使用章节ID作为key，确保稳定性
        return "novel_chapter_${chapter.id}"
    }

    /**
     * 从文件读取文本
     */
    private fun readTextFromFile(file: File): String {
        return runCatching {
            file.source().buffer().use { source ->
                source.readUtf8()
            }
        }.getOrElse { e ->
            android.util.Log.e("NovelContentLoader", "Failed to read from cache file: ${file.absolutePath}", e)
            "" // 返回空字符串，触发重新加载
        }
    }

    /**
     * 保存文本到缓存
     */
    private suspend fun saveToCache(key: String, content: String) {
        withContext(Dispatchers.IO) {
            runCatching {
                val bytes = content.toByteArray(Charsets.UTF_8)
                okio.Buffer().write(bytes).use { buffer ->
                    cache.set(key, buffer, null)
                }
                android.util.Log.d("NovelContentLoader", "Successfully saved to cache: $key, size: ${bytes.size} bytes")
            }.onFailure { e ->
                android.util.Log.e("NovelContentLoader", "Failed to save to cache: $key", e)
            }
        }
    }

    /**
     * 解码章节HTML（从data URL）
     */
    private fun decodeChapterHtml(url: String): String {
        if (url.startsWith("data:", ignoreCase = true)) {
            val commaIndex = url.indexOf(',')
            if (commaIndex != -1) {
                val meta = url.substring(5, commaIndex)
                val data = url.substring(commaIndex + 1)
                return if (meta.contains("base64", ignoreCase = true)) {
                    val decoded = Base64.decode(data, Base64.DEFAULT)
                    String(decoded, Charsets.UTF_8)
                } else {
                    data
                }
            }
        }
        return ""
    }

    /**
     * 将HTML转换为纯文本
     */
    private fun htmlToPlainText(html: String): String {
        val imgRe = Regex("(?i)<img[^>]+(?:data-src|src)=['\"]([^'\"]+)['\"][^>]*>")
        return html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // <img> => 插图占位，保证前后有换行
            .replace(imgRe) { m ->
                val src = m.groupValues.getOrNull(1).orEmpty()
                if (src.isNotBlank()) "\n📷 [图片: $src]\n" else ""
            }
            // 段落/换行处理
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n")
            .replace(Regex("(?i)<p[^>]*>"), "")
            // 其他标签移除
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            // Decode numeric HTML entities (&#NNNN; and &#xHHHH;)
            .replace(Regex("&#x([0-9a-fA-F]+);")) { m ->
                runCatching {
                    String(Character.toChars(m.groupValues[1].toInt(16)))
                }.getOrDefault(m.value)
            }
            .replace(Regex("&#(\\d+);")) { m ->
                runCatching {
                    String(Character.toChars(m.groupValues[1].toInt()))
                }.getOrDefault(m.value)
            }
            .lines()
            .map { it.trimEnd() }
            .joinToString("\n")
            .let { applyReplaceRules(it) }
    }

    private fun applyReplaceRules(text: String): String {
        val rules = kotlinx.coroutines.runBlocking { replaceRuleRepo.getContentRules() }
        if (rules.isEmpty()) return text
        var result = text
        for (rule in rules) {
            result = rule.apply(result)
        }
        return result
    }

    /**
     * 判断缓存内容是否为错误/占位信息（需要重载）
     */
    private fun isErrorContent(content: String): Boolean {
        if (content.isBlank()) return true
        val lower = content.lowercase()
        // 降低文字长度限制，避免误判插图章节（可能只有 10 几个字）
        return (content.length < 5 && !content.contains("📷")) ||
            lower.contains("加载失败") ||
            lower.contains("需要先登录") ||
            lower.contains("authorization required") ||
            (lower.contains("error") && content.length < 200)
    }
}
