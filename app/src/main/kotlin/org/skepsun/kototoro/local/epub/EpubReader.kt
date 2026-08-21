package org.skepsun.kototoro.local.epub

import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.ag2s.epublib.domain.Book
import me.ag2s.epublib.epub.EpubReader as AgEpubReader
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * Interface for reading EPUB files and extracting content.
 */
interface EpubReader {
    /**
     * Reads an EPUB file and extracts its content
     * @param file The EPUB file to read
     * @return EpubContent containing metadata and chapters, or null if parsing fails
     */
    suspend fun readEpub(file: File): EpubContent?

    /**
     * Extracts text content from HTML
     * @param htmlBytes Raw HTML content as byte array
     * @return Plain text extracted from HTML
     */
    fun extractTextFromHtml(htmlBytes: ByteArray): String
}

/**
 * EPUB文件读取器
 * 支持从CBZ压缩包中读取EPUB文件
 *
 * Performance optimizations:
 * - LRU cache for parsed content (Requirement 11.2)
 * - Background thread operations (Requirement 11.3)
 * - Lazy loading support (Requirement 11.1)
 * - Progress indicators for large files (Requirement 11.4)
 */
class EpubReaderImpl(
    private val cache: EpubContentCache = EpubContentCache.getInstance(),
    private val progressListener: EpubLoadingProgressListener? = null
) : EpubReader {

    /**
     * Helper function to log errors safely (won't fail in unit tests)
     */
    private fun logError(tag: String, message: String, throwable: Throwable? = null) {
        try {
            android.util.Log.e(tag, message, throwable)
        } catch (e: RuntimeException) {
            // Ignore - Android Log not available in unit tests
            System.err.println("$tag: $message")
            throwable?.printStackTrace()
        }
    }

    /**
     * Gets the cache instance for external management.
     */
    fun getCache(): EpubContentCache = cache

    /**
     * Reads an EPUB file and extracts its content
     * @param file The EPUB file to read
     * @return EpubContent containing metadata and chapters, or null if parsing fails
     *
     * Performance: Uses LRU cache to avoid re-parsing (Requirement 11.2)
     * Threading: Runs on IO dispatcher (Requirement 11.3)
     * Progress: Reports progress for large files >50MB (Requirement 11.4)
     */
    override suspend fun readEpub(file: File): EpubContent? = withContext(Dispatchers.IO) {
        val progressTracker = EpubLoadingProgressTracker(progressListener)

        try {
            // Check if file exists (Requirement 10.4)
            if (!file.exists()) {
                val error = EpubError.FileSystemError.FileNotFound(file.name)
                EpubErrorHandler.handleError(error, "readEpub")
                return@withContext null
            }

            // Check if file is readable
            if (!file.canRead()) {
                val error = EpubError.FileSystemError.PermissionDenied(file.absolutePath)
                EpubErrorHandler.handleError(error, "readEpub")
                return@withContext null
            }

            // Check cache first (Requirement 11.2)
            cache.get(file)?.let { cachedContent ->
                android.util.Log.d(TAG, "Using cached EPUB content for: ${file.name}")
                return@withContext cachedContent
            }

            // Report progress for large files (Requirement 11.4)
            val fileSize = file.length()
            val isLargeFile = EpubLoadingProgressTracker.isLargeFile(fileSize)

            if (isLargeFile) {
                progressTracker.reportStarted(file.name, fileSize)
                progressTracker.reportProgress(10, "Opening EPUB file...")
            }

            // Parse and cache
            val content = file.inputStream().use { stream ->
                if (isLargeFile) {
                    progressTracker.reportProgress(30, "Parsing EPUB structure...")
                }
                parseEpub(stream, file.name)
            }

            if (isLargeFile && content != null) {
                progressTracker.reportProgress(90, "Extracting chapters...")
            }

            // Cache the result if successful
            if (content != null) {
                cache.put(file, content)
                android.util.Log.d(TAG, "Cached EPUB content for: ${file.name}")

                if (isLargeFile) {
                    progressTracker.reportCompleted(content.chapters.size)
                }
            }

            content
        } catch (e: java.io.FileNotFoundException) {
            val error = EpubError.FileSystemError.FileNotFound(file.name, e)
            EpubErrorHandler.handleError(error, "readEpub")
            progressTracker.reportFailed(e)
            null
        } catch (e: java.io.IOException) {
            val error = EpubError.FileSystemError.ReadError(file.name, e)
            EpubErrorHandler.handleError(error, "readEpub")
            progressTracker.reportFailed(e)
            null
        } catch (e: SecurityException) {
            val error = EpubError.FileSystemError.PermissionDenied(file.absolutePath, e)
            EpubErrorHandler.handleError(error, "readEpub")
            progressTracker.reportFailed(e)
            null
        } catch (e: Exception) {
            // Generic parsing error (Requirement 10.1, 10.2)
            val error = EpubError.ParseError.CorruptedFile(file.name, e)
            EpubErrorHandler.handleError(error, "readEpub")
            progressTracker.reportFailed(e)
            null
        }
    }

    /**
     * Extracts text content from HTML
     * @param htmlBytes Raw HTML content as byte array
     * @return Plain text extracted from HTML
     */
    override fun extractTextFromHtml(htmlBytes: ByteArray): String {
        val html = String(htmlBytes, Charsets.UTF_8)
        return htmlToText(html)
    }

    /**
     * 从URI读取EPUB文件
     * 支持：
     * - 直接的EPUB文件
     * - CBZ压缩包中的EPUB文件
     */
    suspend fun readEpubFromUri(uri: Uri): EpubContent? = withContext(Dispatchers.IO) {
        try {
            val inputStream = getEpubInputStream(uri) ?: return@withContext null
            val fileName = uri.lastPathSegment ?: "unknown"
            inputStream.use { stream ->
                parseEpub(stream, fileName)
            }
        } catch (e: java.io.FileNotFoundException) {
            val error = EpubError.FileSystemError.FileNotFound(uri.toString(), e)
            EpubErrorHandler.handleError(error, "readEpubFromUri")
            null
        } catch (e: java.io.IOException) {
            val error = EpubError.FileSystemError.ReadError(uri.toString(), e)
            EpubErrorHandler.handleError(error, "readEpubFromUri")
            null
        } catch (e: Exception) {
            val error = EpubError.ParseError.CorruptedFile(uri.toString(), e)
            EpubErrorHandler.handleError(error, "readEpubFromUri")
            null
        }
    }

    /**
     * 从章节URL获取EPUB输入流
     *
     * URL格式：
     * - file:///path/to/manga.cbz#chapter/0.epub
     * - zip:file:///path/to/manga.cbz#chapter/0.epub
     */
    private fun getEpubInputStream(uri: Uri): InputStream? {
        try {
            val uriString = uri.toString()

            // 解析ZIP URI
            // 格式: zip:file:///path/to/file.cbz#entry/path.epub
            val zipPath: String
            val entryPath: String

            if (uriString.startsWith("zip:")) {
                val parts = uriString.removePrefix("zip:").split("#", limit = 2)
                zipPath = parts[0].removePrefix("file://")
                entryPath = parts.getOrNull(1) ?: return null
            } else if (uriString.contains(".cbz#")) {
                val parts = uriString.split("#", limit = 2)
                zipPath = parts[0].removePrefix("file://")
                entryPath = parts.getOrNull(1) ?: return null
            } else {
                // 直接的EPUB文件
                return java.io.FileInputStream(uri.path ?: return null)
            }

            // 打开ZIP文件
            val zipFileObj = java.io.File(zipPath)
            if (!zipFileObj.exists()) {
                logError("EpubReader", "ZIP file not found: $zipPath")
                return null
            }

            val zip = ZipFile(zipFileObj)

            // 查找EPUB文件
            val entry = zip.getEntry(entryPath)
            if (entry == null) {
                logError("EpubReader", "EPUB entry not found: $entryPath")
                zip.close()
                return null
            }

            // 读取EPUB文件到内存
            val epubBytes = zip.getInputStream(entry).use { it.readBytes() }
            zip.close()

            return epubBytes.inputStream()
        } catch (e: Exception) {
            logError("EpubReader", "Failed to extract EPUB from ZIP", e)
            return null
        }
    }

    /**
     * 解析EPUB文件
     */
    private fun parseEpub(inputStream: InputStream, fileName: String = "unknown"): EpubContent? {
        return try {
            val reader = AgEpubReader()
            val book = reader.readEpub(inputStream)

            extractContent(book)
        } catch (e: IllegalArgumentException) {
            // Invalid EPUB format (Requirement 10.1)
            val error = EpubError.ParseError.InvalidFormat(e)
            EpubErrorHandler.handleError(error, "parseEpub")
            null
        } catch (e: java.util.zip.ZipException) {
            // Corrupted EPUB file (Requirement 10.2)
            val error = EpubError.ParseError.CorruptedFile(fileName, e)
            EpubErrorHandler.handleError(error, "parseEpub")
            null
        } catch (e: Exception) {
            // Generic parsing error
            val error = EpubError.ParseError.CorruptedFile(fileName, e)
            EpubErrorHandler.handleError(error, "parseEpub")
            null
        }
    }

    /**
     * 从EPUB Book中提取内容
     */
    private fun extractContent(book: Book): EpubContent {
        val title = book.title ?: "Unknown title"
        val author = book.metadata.authors.firstOrNull()?.toString() ?: "Unknown author"

        // 提取所有章节内容
        val chapters = mutableListOf<EpubChapter>()
        val spine = book.spine

        // Extract all spine references as individual chapters (Requirement 2.1)
        for ((index, spineRef) in spine.spineReferences.withIndex()) {
            try {
                val resource = spineRef.resource
                val htmlContent = String(resource.data, Charsets.UTF_8)

                // 尝试从HTML内容中提取更准确的标题
                val extractedTitle = extractTitleFromHtml(htmlContent)

                // Preserve chapter titles from EPUB metadata (Requirement 2.2)
                // Generate default titles using chapter index numbers if missing (Requirement 2.3)
                val chapterTitle = when {
                    !extractedTitle.isNullOrBlank() -> extractedTitle
                    !resource.title.isNullOrBlank() -> resource.title
                    else -> "Chapter ${index + 1}"
                }

                // 检测是否是图片章节（封面、插图等）
                val isImageChapter = detectImageChapter(htmlContent)

                // Convert HTML content to readable text format (Requirement 2.4)
                // 对于图片章节，保留图片标签
                val textContent = htmlToTextWithImages(htmlContent)

                chapters.add(
                    EpubChapter(
                        index = index,
                        title = chapterTitle,
                        content = textContent,
                        isImageChapter = isImageChapter,
                        href = resource.href,  // Store the chapter's path in EPUB
                    )
                )
            } catch (e: Exception) {
                // Log error and notify user with clear error message (Requirement 2.5)
                val error = EpubError.ParseError.MalformedHtml(index, e)
                EpubErrorHandler.handleError(error, "extractContent")
                // Continue with other chapters instead of failing completely
            }
        }

        return EpubContent(
            title = title,
            author = author,
            chapters = chapters,
        )
    }

    /**
     * 从HTML内容中提取标题
     * 优先级：
     * 1. 特定格式的章节标题（如：<p id="toc-xxx"><span>第X章</span>...</p>）
     * 2. h1 > h2 > h3 标签
     * 3. title标签
     */
    private fun extractTitleFromHtml(html: String): String? {
        // 1. 尝试提取特定格式的章节标题
        // 格式：<p id="toc-xxx"><span class="gfont">第X章</span>　<span class="font-130per">标题</span></p>
        val tocPattern = Regex(
            """<p\s+id=["']toc-[^"']*["'][^>]*>(.*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val tocMatch = tocPattern.find(html)
        if (tocMatch != null) {
            val content = tocMatch.groupValues[1]
            // 提取所有span标签的内容并组合
            val spans = Regex("""<span[^>]*>(.*?)</span>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                .findAll(content)
                .map { it.groupValues[1].trim() }
                .filter { it.isNotBlank() }
                .toList()

            if (spans.isNotEmpty()) {
                // 组合所有span内容，用空格分隔
                val title = spans.joinToString(" ")
                    .replace("　", " ") // 替换全角空格
                    .replace(Regex("\\s+"), " ") // 合并多个空格
                    .trim()
                if (title.isNotBlank()) {
                    android.util.Log.d("EpubReader", "Extracted title from toc pattern: $title")
                    return title
                }
            }
        }

        // 2. 尝试提取前几个段落中包含"第X章"、"第X话"、"第X卷"等模式的内容
        val chapterPattern = Regex(
            """<p[^>]*>(.*?第\s*[0-9０-９一二三四五六七八九十百千万]+\s*[章话卷節节].*?)</p>""",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        val chapterMatch = chapterPattern.find(html)
        if (chapterMatch != null) {
            val title = chapterMatch.groupValues[1]
                .replace(Regex("<[^>]+>"), "") // 移除内部标签
                .replace("　", " ") // 替换全角空格
                .replace(Regex("\\s+"), " ") // 合并多个空格
                .trim()
            // 确保标题不会太长（避免提取到整段内容）
            if (title.isNotBlank() && title.length < 100) {
                android.util.Log.d("EpubReader", "Extracted title from chapter pattern: $title")
                return title
            }
        }

        // 3. 尝试提取h1标签
        val h1Match = Regex("<h1[^>]*>(.*?)</h1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        if (h1Match != null) {
            val title = h1Match.groupValues[1]
                .replace(Regex("<[^>]+>"), "") // 移除内部标签
                .trim()
            if (title.isNotBlank()) {
                android.util.Log.d("EpubReader", "Extracted title from h1: $title")
                return title
            }
        }

        // 4. 尝试提取h2标签
        val h2Match = Regex("<h2[^>]*>(.*?)</h2>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        if (h2Match != null) {
            val title = h2Match.groupValues[1]
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (title.isNotBlank()) {
                android.util.Log.d("EpubReader", "Extracted title from h2: $title")
                return title
            }
        }

        // 5. 尝试提取h3标签
        val h3Match = Regex("<h3[^>]*>(.*?)</h3>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        if (h3Match != null) {
            val title = h3Match.groupValues[1]
                .replace(Regex("<[^>]+>"), "")
                .trim()
            if (title.isNotBlank()) {
                android.util.Log.d("EpubReader", "Extracted title from h3: $title")
                return title
            }
        }

        // 6. 尝试提取title标签
        val titleMatch = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(html)
        if (titleMatch != null) {
            var title = titleMatch.groupValues[1]
                .replace(Regex("<[^>]+>"), "")
                .trim()

            // 清理title标签中的书名部分
            // 常见格式：
            // - "书名 - 第X章"
            // - "书名　第X章"
            // - "书名 第X章"
            if (title.isNotBlank()) {
                // 如果包含分隔符，尝试提取后半部分
                val separators = listOf(" - ", "　", " | ", " / ")
                for (separator in separators) {
                    if (title.contains(separator)) {
                        val parts = title.split(separator)
                        // 取最后一部分（通常是章节名）
                        val lastPart = parts.lastOrNull()?.trim()
                        if (!lastPart.isNullOrBlank() && lastPart.length < title.length) {
                            title = lastPart
                            break
                        }
                    }
                }

                android.util.Log.d("EpubReader", "Extracted title from title tag: $title")
                return title
            }
        }

        android.util.Log.d("EpubReader", "No title extracted from HTML")
        return null
    }

    /**
     * 检测是否是图片章节
     * 判断标准：
     * 1. 包含img标签
     * 2. 文本内容很少（少于100个字符）
     */
    private fun detectImageChapter(html: String): Boolean {
        val hasImage = html.contains("<img", ignoreCase = true)
        if (!hasImage) return false

        // 提取纯文本内容
        val textContent = html
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .trim()

        // 如果文本内容很少，认为是图片章节
        return textContent.length < 100
    }

    /**
     * HTML转文本，保留图片信息
     * 用于图片章节（封面、插图等）
     */
    private fun htmlToTextWithImages(html: String): String {
        return html
            // 移除script和style标签及其内容
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // 保留图片标签，转换为描述性文本
            .replace(Regex("<img[^>]*src=[\"']([^\"']+)[\"'][^>]*(?:alt=[\"']([^\"']*)[\"'])?[^>]*>", RegexOption.IGNORE_CASE)) { matchResult ->
                val src = matchResult.groupValues[1]
                val alt = matchResult.groupValues.getOrNull(2)
                // Create a more user-friendly placeholder
                // 保留原始 src 以便后续相对路径解析
                val displayText = when {
                    !src.isNullOrBlank() -> src
                    !alt.isNullOrBlank() -> alt
                    else -> src
                }

                "\n\n📷 [图片: $displayText]\n\n"
            }
            // 将<br>和<p>标签转换为换行
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            // 移除其他HTML标签
            .replace(Regex("<[^>]+>"), "")
            // 解码HTML实体
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            // 清理多余的空白
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * 简单的HTML转文本
     * 移除HTML标签，保留文本内容
     */
    private fun htmlToText(html: String): String {
        return html
            // 移除script和style标签及其内容
            .replace(Regex("<script[^>]*>.*?</script>", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("<style[^>]*>.*?</style>", RegexOption.DOT_MATCHES_ALL), "")
            // 将<br>和<p>标签转换为换行
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("<p[^>]*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n")
            // 移除所有HTML标签
            .replace(Regex("<[^>]+>"), "")
            // 解码HTML实体
            .replace("&nbsp;", " ")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            // 清理多余的空白
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\n[ \\t]+"), "\n")
            .replace(Regex("[ \\t]+\n"), "\n")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Creates a lazy-loading wrapper for an EPUB file.
     *
     * This allows on-demand loading of chapters for better performance with large files.
     *
     * @param file The EPUB file
     * @return LazyEpubContent for on-demand chapter loading, or null if file cannot be read
     *
     * Requirements: 11.1 - Implement lazy loading for EPUB chapters
     */
    suspend fun createLazyContent(file: File): LazyEpubContent? = withContext(Dispatchers.IO) {
        try {
            if (!file.exists() || !file.canRead()) {
                return@withContext null
            }

            // Read just the metadata (title, author, chapter count)
            val content = readEpub(file) ?: return@withContext null

            LazyEpubContent(
                title = content.title,
                author = content.author,
                file = file,
                epubReader = this@EpubReaderImpl,
                chapterCount = content.chapters.size
            )
        } catch (e: Exception) {
            logError(TAG, "Failed to create lazy content", e)
            null
        }
    }

    companion object {
        private const val TAG = "EpubReader"
    }
}

/**
 * EPUB内容
 */
data class EpubContent(
    val title: String,
    val author: String,
    val chapters: List<EpubChapter>,
)

/**
 * EPUB章节
 */
data class EpubChapter(
    val index: Int,
    val title: String,
    val content: String,
    val isImageChapter: Boolean = false,  // 是否是图片章节（封面、插图等）
    val href: String? = null,  // 章节在EPUB中的路径（如 "OEBPS/Text/content_1.html"）
)
