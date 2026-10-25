package org.skepsun.kototoro.local.shared

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.HashingSource
import okio.Path
import okio.buffer
import okio.use
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceContentImage
import org.skepsun.kototoro.core.source.SourceCoverArtifact
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourceFilterCapabilities
import org.skepsun.kototoro.core.source.SourceFilterOptions
import org.skepsun.kototoro.core.source.SourceImageArtifact
import org.skepsun.kototoro.core.source.SourceOperationUnsupportedException
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourceRef
import org.skepsun.kototoro.core.source.SourceRuntime
import org.skepsun.kototoro.core.source.SourceTag
import kotlin.random.Random
import kotlin.time.Clock

/** What the store keeps next to each imported file. */
@Serializable
data class LocalItem(
    val id: Long,
    val title: String,
    val fileName: String,
    val format: LocalFormat,
    val size: Long,
    val importedAt: Long,
    /**
     * How EPUB chapters are cut: 1 is one chapter per spine document (works imported before 2026-10), 2 splits a
     * document at its table-of-contents sections. Chapter ids follow the chapter order, so an existing import keeps
     * its scheme and its history and bookmarks stay on the right chapters.
     */
    val chapterScheme: Int = 1,
    /** A video folder: the original names of its episode files, stored as `episodes/<index>.<ext>`; empty otherwise. */
    val episodes: List<String> = emptyList(),
    /** A video folder's identity: SHA-256 over its episodes' names, sizes and hashes. */
    val contentHash: String? = null,
)

/**
 * Imported works as a [SourceRuntime] under Android's local source names, so browsing, details, the readers,
 * favourites and history treat them like any other source. Files live in `[root]/items/<id>/`; pictures handed to
 * the readers are written content-addressed to [imageDirectory] (`<sha256>.img`), as the other runtimes do.
 */
class LocalSourceRuntime(
    private val root: Path,
    private val imageDirectory: Path,
    private val fileSystem: FileSystem = localFileSystem,
    decodeLegacyText: (ByteArray) -> String? = { null },
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) : SourceRuntime {
    private val reader = LocalBookReader(fileSystem, decodeLegacyText)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val importMutex = Mutex()
    private val books = HashMap<Long, LocalBook>()
    private var cachedText: Pair<Long, String>? = null

    override suspend fun getSources(): List<SourceRef> = listOf(MANGA, NOVEL, VIDEO)

    override suspend fun describe(sourceName: String): SourceDescriptor = SourceDescriptor(
        source = ref(sourceName),
        sortOrders = setOf(ORDER_NEWEST, ORDER_TITLE),
        defaultSortOrder = ORDER_NEWEST,
        filterCapabilities = SourceFilterCapabilities(false, false, true, false, false, false, false, false),
        pagingMode = SourcePagingMode.OFFSET,
        isImageFetchingSupported = sourceName != VIDEO.name,
        isCoverFetchingSupported = sourceName != VIDEO.name,
        isChapterContentSupported = sourceName == NOVEL.name,
    )

    override suspend fun getFilterOptions(sourceName: String): SourceFilterOptions {
        ref(sourceName)
        return SourceFilterOptions(emptySet(), emptyList(), emptySet(), emptySet(), emptySet(), emptySet(), emptySet())
    }

    override suspend fun getList(sourceName: String, offset: Int, order: String?, filter: SourceFilter?): List<SourceContent> {
        val source = ref(sourceName)
        val query = filter?.query?.trim().orEmpty()
        val matching = items().filter { it.format.contentType == source.contentType }
            .filter { query.isEmpty() || it.title.contains(query, ignoreCase = true) }
        val sorted = if (order == ORDER_TITLE) matching.sortedWith { a, b -> NaturalOrder.compare(a.title, b.title) }
            else matching.sortedByDescending { it.importedAt }
        return sorted.drop(offset).take(PAGE_SIZE).map { content(it, null) }
    }

    override suspend fun getDetails(content: SourceContent, fetchMode: SourceDetailsFetchMode): SourceContent {
        val item = item(content.id)
        return content(item, book(item))
    }

    override suspend fun getPages(chapter: SourceChapter, nextChapterUrl: String?): List<SourcePage> {
        val (item, index) = chapterOf(chapter.url)
        if (item.format == LocalFormat.VIDEO && item.episodes.isNotEmpty()) {
            val name = item.episodes.getOrNull(index)
            require(name != null && chapter.source == VIDEO) { "视频章节无效" }
            val path = episodeFile(item, index)
            require(fileSystem.exists(path)) { "本地视频已被删除" }
            return listOf(SourcePage(stableId(chapter.url), fileUrl(path), null, VIDEO))
        }
        if (item.format == LocalFormat.VIDEO) {
            require(index == 0 && chapter.source == VIDEO) { "视频章节无效" }
            val path = file(item)
            require(fileSystem.exists(path)) { "本地视频已被删除" }
            return listOf(SourcePage(stableId(chapter.url), fileUrl(path), null, VIDEO))
        }
        val pages = book(item).chapters.getOrNull(index)?.pages ?: throw IllegalArgumentException("章节不存在")
        return pages.map { entry ->
            val url = "$SCHEME${item.id}/$index/$entry"
            SourcePage(stableId(url), url, null, chapter.source)
        }
    }

    override suspend fun getPageUrl(page: SourcePage): String = page.url

    override suspend fun getChapterContent(chapter: SourceChapter, nextChapterUrl: String?): SourceChapterContent =
        withContext(Dispatchers.IO) {
            val (item, index) = chapterOf(chapter.url)
            val part = book(item).chapters.getOrNull(index) ?: throw IllegalArgumentException("章节不存在")
            when (item.format) {
                LocalFormat.TXT -> SourceChapterContent(textHtml(text(item).substring(part.textStart, part.textEnd)))
                LocalFormat.EPUB -> epubChapter(item, part)
                LocalFormat.ARCHIVE, LocalFormat.VIDEO -> throw SourceOperationUnsupportedException()
            }
        }

    override suspend fun fetchImage(page: SourcePage): SourceImageArtifact = withContext(Dispatchers.IO) {
        val rest = page.url.removePrefix(SCHEME)
        val id = rest.substringBefore('/').toLongOrNull() ?: throw IllegalArgumentException("页面地址无效")
        val entry = rest.substringAfter('/').substringAfter('/')
        val stored = store(reader.entry(file(item(id)), entry))
        SourceImageArtifact(page.source, page.id, stored.first, stored.second, stored.third, LocalFiles.mimeType(entry))
    }

    override suspend fun fetchCover(content: SourceContent, large: Boolean): SourceCoverArtifact = withContext(Dispatchers.IO) {
        val item = item(content.id)
        val cover = book(item).cover ?: throw SourceOperationUnsupportedException()
        val stored = store(reader.entry(file(item), cover))
        SourceCoverArtifact(content.source, content.id, stored.first, stored.second, stored.third, LocalFiles.mimeType(cover))
    }

    override suspend fun getRelated(content: SourceContent): List<SourceContent> = emptyList()

    /** Copies [source] into the store; unreadable files leave nothing behind and identical imports keep their id. */
    suspend fun import(source: Path, fileName: String): SourceContent = withContext(Dispatchers.IO) {
        importMutex.withLock { importFile(source, fileName) }
    }

    private suspend fun importFile(source: Path, fileName: String): SourceContent {
        require(LocalFiles.extension(fileName) != "m3u8") {
            "暂不支持导入本地 M3U8 播放列表，请选择完整视频文件"
        }
        val format = LocalFormat.of(fileName) ?: throw IllegalArgumentException(
            "不支持的文件类型：$fileName（支持 CBZ/ZIP、EPUB、TXT 和视频文件）",
        )
        require(fileSystem.metadata(source).isRegularFile) { "请选择文件" }
        val id = Random.nextLong(1, Long.MAX_VALUE)
        val directory = root / ITEMS / id.toString()
        fileSystem.createDirectories(directory)
        try {
            val target = directory / "book.${fileName.substringAfterLast('.').lowercase()}"
            val input = HashingSource.sha256(fileSystem.source(source))
            val size = input.use { fileSystem.sink(target).buffer().use { output -> output.writeAll(it) } }
            val hash = input.hash.hex()
            val book = reader.read(target, format, fileName.substringBeforeLast('.'))
            // Preserve ids, chapters, favourites and playback position when the same bytes are imported again.
            // Same name and size alone cannot prove identity, and a failed import must never delete the old copy.
            val existing = items().firstOrNull { candidate ->
                candidate.fileName == fileName && candidate.size == size &&
                    runCatching { fileHash(file(candidate)) == hash }.getOrDefault(false)
            }
            if (existing != null) {
                fileSystem.deleteRecursively(directory)
                return content(existing, book(existing))
            }
            val item = LocalItem(id, book.title, fileName, format, size, now(), CHAPTER_SCHEME)
            fileSystem.write(directory / META) { writeUtf8(json.encodeToString(LocalItem.serializer(), item)) }
            mutex.withLock { books[id] = book }
            return content(item, book)
        } catch (error: Throwable) {
            runCatching { fileSystem.deleteRecursively(directory) }
            throw error
        }
    }

    /**
     * Imports a folder of video files as one work with an episode per file, in natural name order (Android imports a
     * video directory the same way). Files that are not videos are skipped; importing the same files again keeps the
     * work, its episodes, favourites and playback positions.
     */
    suspend fun importVideoFolder(files: List<Pair<Path, String>>, title: String): SourceContent = withContext(Dispatchers.IO) {
        importMutex.withLock {
            val videos = files.filter { (path, name) ->
                LocalFormat.of(name) == LocalFormat.VIDEO && fileSystem.metadata(path).isRegularFile
            }.sortedWith { a, b -> NaturalOrder.compare(a.second, b.second) }
            require(videos.isNotEmpty()) { "文件夹里没有可导入的视频文件" }
            val id = Random.nextLong(1, Long.MAX_VALUE)
            val directory = root / ITEMS / id.toString()
            val episodes = directory / EPISODES
            fileSystem.createDirectories(episodes)
            try {
                var size = 0L
                val identity = StringBuilder()
                videos.forEachIndexed { index, (path, name) ->
                    val target = episodes / "$index.${LocalFiles.extension(name)}"
                    val input = HashingSource.sha256(fileSystem.source(path))
                    val written = input.use { fileSystem.sink(target).buffer().use { output -> output.writeAll(it) } }
                    require(written > 0) { "视频文件是空的：$name" }
                    size += written
                    identity.append(name).append(':').append(written).append(':').append(input.hash.hex()).append('\n')
                }
                val hash = identity.toString().encodeToByteArray().toByteString().sha256().hex()
                val fileName = "$title/"
                items().firstOrNull { it.fileName == fileName && it.contentHash == hash }?.let { existing ->
                    fileSystem.deleteRecursively(directory)
                    return@withLock content(existing, book(existing))
                }
                val item = LocalItem(id, title, fileName, LocalFormat.VIDEO, size, now(), CHAPTER_SCHEME,
                    episodes = videos.map { it.second }, contentHash = hash)
                fileSystem.write(directory / META) { writeUtf8(json.encodeToString(LocalItem.serializer(), item)) }
                val book = book(item)
                content(item, book)
            } catch (error: Throwable) {
                runCatching { fileSystem.deleteRecursively(directory) }
                throw error
            }
        }
    }

    private fun episodeFile(item: LocalItem, index: Int): Path =
        root / ITEMS / item.id.toString() / EPISODES / "$index.${LocalFiles.extension(item.episodes[index])}"

    private fun fileHash(path: Path): String = HashingSource.sha256(fileSystem.source(path)).use { input ->
        val buffered = input.buffer()
        while (!buffered.exhausted()) buffered.skip(minOf(8192L, buffered.buffer.size))
        input.hash.hex()
    }

    suspend fun remove(contentId: Long) = withContext(Dispatchers.IO) {
        mutex.withLock {
            books.remove(contentId)
            if (cachedText?.first == contentId) cachedText = null
        }
        fileSystem.deleteRecursively(root / ITEMS / contentId.toString())
    }

    private suspend fun items(): List<LocalItem> = withContext(Dispatchers.IO) {
        val directory = root / ITEMS
        if (!fileSystem.exists(directory)) return@withContext emptyList()
        fileSystem.list(directory).mapNotNull { folder ->
            runCatching { json.decodeFromString(LocalItem.serializer(), fileSystem.read(folder / META) { readUtf8() }) }.getOrNull()
        }
    }

    private suspend fun item(id: Long): LocalItem = withContext(Dispatchers.IO) {
        val meta = root / ITEMS / id.toString() / META
        if (!fileSystem.exists(meta)) throw IllegalArgumentException("本地作品已被删除")
        json.decodeFromString(LocalItem.serializer(), fileSystem.read(meta) { readUtf8() })
    }

    private fun file(item: LocalItem): Path =
        root / ITEMS / item.id.toString() / "book.${item.fileName.substringAfterLast('.').lowercase()}"

    private suspend fun book(item: LocalItem): LocalBook {
        mutex.withLock { books[item.id] }?.let { return it }
        if (item.episodes.isNotEmpty()) {
            val folder = LocalBook(item.title, emptyList(), null, emptyList(), null, item.episodes.mapIndexed { index, name ->
                LocalChapter(name.substringBeforeLast('.'), index + 1f)
            })
            mutex.withLock { books[item.id] = folder }
            return folder
        }
        val book = withContext(Dispatchers.IO) {
            reader.read(file(item), item.format, item.title, splitSections = item.chapterScheme >= 2)
        }
        mutex.withLock { books[item.id] = book }
        return book
    }

    private suspend fun text(item: LocalItem): String {
        mutex.withLock { cachedText?.takeIf { it.first == item.id }?.second }?.let { return it }
        val text = reader.text(file(item))
        mutex.withLock { cachedText = item.id to text }
        return text
    }

    private suspend fun chapterOf(url: String): Pair<LocalItem, Int> {
        val rest = url.removePrefix(SCHEME)
        val id = rest.substringBefore('/').toLongOrNull() ?: throw IllegalArgumentException("章节地址无效")
        val index = rest.substringAfter('/').substringBefore('/').toIntOrNull() ?: throw IllegalArgumentException("章节地址无效")
        return item(id) to index
    }

    private fun content(item: LocalItem, book: LocalBook?): SourceContent {
        val source = when (item.format.contentType) {
            NOVEL.contentType -> NOVEL
            VIDEO.contentType -> VIDEO
            else -> MANGA
        }
        val url = "$SCHEME${item.id}"
        return SourceContent(
            id = item.id,
            title = book?.title ?: item.title,
            altTitles = emptySet(),
            url = url,
            publicUrl = "",
            rating = -1f,
            contentRating = null,
            coverUrl = if (item.format == LocalFormat.TXT || item.format == LocalFormat.VIDEO) null else url,
            tags = book?.tags.orEmpty().map { SourceTag(it, it.lowercase(), source) }.toSet(),
            state = null,
            authors = book?.authors.orEmpty().toSet(),
            source = source,
            description = book?.description,
            chapters = book?.chapters?.mapIndexed { index, chapter ->
                val chapterUrl = "$url/$index"
                SourceChapter(stableId(chapterUrl), chapter.title, chapter.number, 0, chapterUrl, null, item.importedAt,
                    chapter.branch, source)
            },
        )
    }

    /** Writes the bytes as `<sha256>.img` once; returns path, hash and size. */
    private fun store(bytes: ByteArray): Triple<String, String, Long> {
        require(bytes.isNotEmpty()) { "图片是空的" }
        val hash = bytes.toByteString().sha256().hex()
        val name = "$hash.img"
        fileSystem.createDirectories(imageDirectory)
        val target = imageDirectory / name
        if (!fileSystem.exists(target)) {
            val partial = imageDirectory / "$name.partial"
            fileSystem.write(partial) { write(bytes) }
            fileSystem.atomicMove(partial, target)
        }
        return Triple(name, hash, bytes.size.toLong())
    }

    /** The chapter's XHTML body, using the same EPUB parser as remote volumes. */
    private fun epubChapter(item: LocalItem, part: LocalChapter): SourceChapterContent =
        reader.chapterContent(file(item), part) { target, bytes ->
            val assets = root / ITEMS / item.id.toString() / ASSETS
            val name = bytes.toByteString().sha256().hex() + "." + LocalFiles.extension(target).ifEmpty { "img" }
            fileSystem.createDirectories(assets)
            if (!fileSystem.exists(assets / name)) fileSystem.write(assets / name) { write(bytes) }
            fileUrl(assets / name)
        }

    private fun textHtml(text: String): String = text.split('\n').map { it.trim().trim('\u3000') }
        .filter { it.isNotEmpty() }
        .joinToString("") { "<p>${LocalXml.escape(it)}</p>" }

    private fun ref(sourceName: String): SourceRef = when (sourceName) {
        MANGA.name -> MANGA
        NOVEL.name -> NOVEL
        VIDEO.name -> VIDEO
        else -> throw IllegalArgumentException("未知的本地来源：$sourceName")
    }

    companion object {
        /** The [LocalItem.chapterScheme] of new imports. */
        const val CHAPTER_SCHEME = 2

        /** Android's local source names (`LocalMangaSource`, `LocalNovelSource`, `LocalVideoSource`). */
        val MANGA = SourceRef("LOCAL", "", "MANGA")
        val NOVEL = SourceRef("LOCAL_NOVEL", "", "NOVEL")
        val VIDEO = SourceRef("LOCAL_VIDEO", "", "VIDEO")
        const val ORDER_NEWEST = "NEWEST"
        const val ORDER_TITLE = "ALPHABETICAL"
        private const val SCHEME = "local://"
        private const val ITEMS = "items"
        private const val META = "meta.json"
        private const val ASSETS = "assets"
        private const val EPISODES = "episodes"
        private const val PAGE_SIZE = 60

        fun owns(sourceName: String): Boolean =
            sourceName == MANGA.name || sourceName == NOVEL.name || sourceName == VIDEO.name

        private fun stableId(key: String): Long {
            val bytes = key.encodeUtf8().sha256()
            var value = 0L
            for (index in 0 until 8) value = (value shl 8) or (bytes[index].toLong() and 0xFF)
            return value and Long.MAX_VALUE
        }

        private fun fileUrl(path: Path): String = "file://" + path.toString().encodeToByteArray().joinToString("") { byte ->
            val c = byte.toInt() and 0xFF
            if (c.toChar().isLetterOrDigit() && c < 0x80 || c.toChar() in "/._-~") c.toChar().toString()
            else "%" + c.toString(16).uppercase().padStart(2, '0')
        }
    }
}
