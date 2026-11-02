package org.skepsun.kototoro.local.shared

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceContentImage
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.openZip

/** One imported work as read from its file: metadata plus where each chapter's pages or text are. */
data class LocalBook(
    val title: String,
    val authors: List<String>,
    val description: String?,
    val tags: List<String>,
    /** Archive entry of the cover picture, when there is one. */
    val cover: String?,
    val chapters: List<LocalChapter>,
)

data class LocalChapter(
    val title: String,
    val number: Float,
    /** Image entries in reading order (archives). */
    val pages: List<String> = emptyList(),
    /** The chapter's XHTML entry (EPUB). */
    val document: String? = null,
    /** Anchors (element ids) where the chapter starts and where the next one starts within [document]; null for
     *  the document start or end. Set when a document holds several table-of-contents sections. */
    val section: String? = null,
    val sectionEnd: String? = null,
    /** Character range of the chapter in the decoded text (TXT). */
    val textStart: Int = 0,
    val textEnd: Int = 0,
    val branch: String? = null,
)

/** Reads CBZ/ZIP, EPUB, TXT and video metadata; [decodeLegacyText] handles TXT files that are not UTF-8. */
class LocalBookReader(
    private val fileSystem: FileSystem = localFileSystem,
    private val decodeLegacyText: (ByteArray) -> String? = { null },
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * [splitSections] makes every table-of-contents section of an EPUB document a chapter of its own; without it each
     * spine document is one chapter (the layout of works imported before sections were split).
     */
    fun read(file: Path, format: LocalFormat, fallbackTitle: String, splitSections: Boolean = true): LocalBook =
        when (format) {
            LocalFormat.ARCHIVE -> readArchive(file, fallbackTitle)
            LocalFormat.EPUB -> readEpub(file, fallbackTitle, splitSections)
            LocalFormat.TXT -> readText(file, fallbackTitle)
            LocalFormat.VIDEO -> {
                require((fileSystem.metadata(file).size ?: 0) > 0) { "视频文件是空的" }
                LocalBook(fallbackTitle, emptyList(), null, emptyList(), null,
                    listOf(LocalChapter(fallbackTitle, 1f)))
            }
        }

    /** Bytes of one entry inside an archive or EPUB. */
    fun entry(file: Path, entry: String): ByteArray {
        val zip = fileSystem.openZip(file)
        return zip.read(("/" + entry).toPath()) { readByteArray() }
    }

    /** The chapter's XHTML body, with its pictures extracted next to the work and referenced as file URLs. */
    fun chapterContent(file: Path, part: LocalChapter, publishImage: (String, ByteArray) -> String): SourceChapterContent {
        val document = part.document ?: throw IllegalArgumentException("章节不存在")
        val xhtml = entry(file, document).decodeToString()
        val whole = Regex("<body[^>]*>([\\s\\S]*)</body>", RegexOption.IGNORE_CASE)
            .find(xhtml)?.groupValues?.get(1) ?: xhtml
        // A section runs from its anchor element to the next section's; markup cut open there is tolerated by readers.
        val start = part.section?.let { LocalBookReader.anchorPosition(whole, it) } ?: 0
        val end = part.sectionEnd?.let { LocalBookReader.anchorPosition(whole, it) }?.takeIf { it >= start } ?: whole.length
        val body = whole.substring(start, end)
        val images = mutableListOf<SourceContentImage>()
        val html = Regex("<(img|image)\\b([^>]*?)(src|xlink:href|href)\\s*=\\s*([\"'])([^\"']+)\\4([^>]*)>",
            RegexOption.IGNORE_CASE).replace(body) { match ->
            val target = resolveHref(document, match.groupValues[5])
            val bytes = runCatching { entry(file, target) }.getOrNull() ?: return@replace ""
            val url = publishImage(target, bytes)
            images += SourceContentImage(url)
            "<img src=\"$url\">"
        }
        return SourceChapterContent(stylesheets(file, document, xhtml) + html, images)
    }

    /**
     * The document's style sheets (linked files and `<style>` elements of its head) as one leading `<style>` element,
     * so readers can apply the book's layout to the body.
     */
    private fun stylesheets(file: Path, document: String, xhtml: String): String {
        val head = Regex("<head\\b[^>]*>([\\s\\S]*?)</head\\s*>", RegexOption.IGNORE_CASE)
            .find(xhtml)?.groupValues?.get(1) ?: return ""
        // Linked files and <style> elements, in the order the head lists them.
        val sheets = mutableListOf<Pair<Int, String>>()
        LocalXml.tags(head, "link").forEach { link ->
            if (link["rel"]?.contains("stylesheet", ignoreCase = true) != true) return@forEach
            val href = link["href"] ?: return@forEach
            runCatching { entry(file, resolveHref(document, href)).decodeToString() }
                .getOrNull()?.let { sheets += link.start to it }
        }
        Regex("<style\\b[^>]*>([\\s\\S]*?)</style\\s*>", RegexOption.IGNORE_CASE)
            .findAll(head).forEach { sheets += it.range.first to it.groupValues[1] }
        val css = sheets.sortedBy { it.first }.joinToString("\n") { it.second }
        // A style sheet never contains "</style>"; drop it in case one does, so it cannot end the element early.
        return if (css.isBlank()) "" else "<style>" + css.replace("</style", "") + "</style>"
    }

    /** The whole TXT as text, UTF-8 first (with or without BOM), the platform's legacy decoder otherwise. */
    fun text(file: Path): String {
        val bytes = fileSystem.read(file) { readByteArray() }
        val utf8 = try { bytes.decodeToString(throwOnInvalidSequence = true) } catch (_: CharacterCodingException) { null }
        val text = utf8 ?: decodeLegacyText(bytes) ?: bytes.decodeToString()
        return text.removePrefix("\uFEFF").replace("\r\n", "\n").replace('\r', '\n')
    }

    // MARK: archives

    private fun readArchive(file: Path, fallbackTitle: String): LocalBook {
        val zip = fileSystem.openZip(file)
        val files = zip.listRecursively("/".toPath())
            .filter { zip.metadata(it).isRegularFile }
            .map { it.toString().removePrefix("/") }
            .filterNot(LocalFiles::isJunk)
            .toList()
        val images = files.filter(LocalFiles::isImage).sortedWith(NaturalOrder)
        val index = files.firstOrNull { it == INDEX }?.let { name ->
            runCatching { json.parseToJsonElement(zip.read(("/$name").toPath()) { readUtf8() }).jsonObject }.getOrNull()
        }
        // Mihon and other readers describe an archive in ComicInfo.xml; Kototoro's own index.json takes precedence.
        val comicInfo = if (index != null) null else files
            .firstOrNull { it.equals(ComicInfo.FILE_NAME, ignoreCase = true) }
            ?.let { name -> runCatching { zip.read(("/$name").toPath()) { readUtf8() } }.getOrNull() }
            ?.let(ComicInfo::parse)
        val title = index?.string("title") ?: comicInfo?.series ?: fallbackTitle
        val indexed = index?.let { indexedChapters(it, images) }.orEmpty()
        val chapters = indexed.ifEmpty {
            folderChapters(images, title).let { folders ->
                // A single-chapter archive takes the chapter's own name and number from ComicInfo.
                val single = folders.singleOrNull() ?: return@let folders
                listOf(single.copy(
                    title = comicInfo?.title ?: single.title,
                    number = comicInfo?.chapterNumber ?: single.number,
                ))
            }
        }
        require(chapters.isNotEmpty()) { "压缩包里没有图片" }
        val coverEntry = index?.string("cover_entry")?.takeIf { it in files }
        return LocalBook(
            title = title,
            authors = index?.let { it.strings("authors").ifEmpty { listOfNotNull(it.string("author")) } }
                ?: comicInfo?.authors.orEmpty(),
            description = index?.string("description") ?: comicInfo?.summary,
            tags = index?.let { (it["tags"] as? JsonArray).orEmpty().mapNotNull { tag -> (tag as? JsonObject)?.string("title") } }
                ?: comicInfo?.allTags.orEmpty(),
            cover = coverEntry ?: chapters.first().pages.first(),
            chapters = chapters,
        )
    }

    /**
     * Archives Kototoro writes: index.json lists the chapters, and each chapter's pages are the entries whose names
     * (without extension) match its `entries` pattern (`%08d_%04d\d{4}` from branch hash and order).
     */
    private fun indexedChapters(index: JsonObject, images: List<String>): List<LocalChapter> {
        val chapters = index["chapters"] as? JsonObject ?: return emptyList()
        return chapters.entries.mapNotNull { (_, value) ->
            val chapter = value as? JsonObject ?: return@mapNotNull null
            val order = chapter.int("order") ?: 1
            val branch = chapter.string("branch")
            val pattern = chapter.string("entries")
                ?: "${javaPad(branch?.hashCode() ?: 0, 8)}_${javaPad(order, 4)}\\d{4}"
            val regex = runCatching { Regex(pattern) }.getOrNull() ?: return@mapNotNull null
            val folder = chapter.string("file")?.trim('/')
            val pages = images.filter { entry ->
                if (folder != null && folder.isNotEmpty()) entry.startsWith("$folder/")
                else LocalFiles.baseName(entry).matches(regex)
            }
            if (pages.isEmpty()) return@mapNotNull null
            order to LocalChapter(
                title = chapter.string("name")?.takeIf(String::isNotBlank) ?: "第 $order 章",
                number = chapter.float("number") ?: order.toFloat(),
                pages = pages,
                branch = branch,
            )
        }.sortedBy { it.first }.map { it.second }
    }

    /** Plain archives: every folder of pictures is a chapter, pictures at the top level form one chapter. */
    private fun folderChapters(images: List<String>, title: String): List<LocalChapter> {
        val groups = images.groupBy { it.substringBeforeLast('/', "") }
        if (groups.size <= 1) {
            return listOfNotNull(images.takeIf { it.isNotEmpty() }?.let { LocalChapter(title, 1f, pages = it) })
        }
        return groups.keys.sortedWith(NaturalOrder).mapIndexed { index, folder ->
            LocalChapter(
                title = if (folder.isEmpty()) title else folder.substringAfterLast('/'),
                number = (index + 1).toFloat(),
                pages = groups.getValue(folder),
            )
        }
    }

    // MARK: EPUB

    private fun readEpub(file: Path, fallbackTitle: String, splitSections: Boolean): LocalBook {
        val zip = fileSystem.openZip(file)
        try {
            return readEpub(zip, fallbackTitle, splitSections, withChapters = true)
        } finally {
            zip.close()
        }
    }

    /** Reads packaging metadata from an already opened archive without opening the TOC or chapter documents. */
    fun readEpubMetadata(archive: FileSystem, fallbackTitle: String): LocalBook =
        readEpub(archive, fallbackTitle, splitSections = false, withChapters = false)

    private fun readEpub(
        zip: FileSystem,
        fallbackTitle: String,
        splitSections: Boolean,
        withChapters: Boolean,
    ): LocalBook {
        fun read(entry: String): String? = runCatching { zip.read(("/$entry").toPath()) { readUtf8() } }.getOrNull()
        val container = read("META-INF/container.xml") ?: throw IllegalArgumentException("EPUB 缺少 container.xml")
        val opfPath = LocalXml.tags(container, "rootfile").firstNotNullOfOrNull { it["full-path"] }
            ?: throw IllegalArgumentException("EPUB 没有内容清单")
        val opf = read(opfPath) ?: throw IllegalArgumentException("EPUB 内容清单无法读取")
        val manifest = LocalXml.tags(opf, "item").mapNotNull { item ->
            val id = item["id"] ?: return@mapNotNull null
            val href = item["href"] ?: return@mapNotNull null
            id to EpubItem(resolveHref(opfPath, href), item["media-type"].orEmpty(), item["properties"].orEmpty())
        }.toMap()
        val coverId = LocalXml.tags(opf, "meta").firstOrNull { it["name"] == "cover" }?.get("content")
        val cover = manifest.values.firstOrNull { "cover-image" in it.properties }?.href
            ?: coverId?.let { manifest[it]?.href }?.takeIf(LocalFiles::isImage)
            ?: manifest.values.firstOrNull { LocalFiles.isImage(it.href) && "cover" in it.href.lowercase() }?.href
        val metadata = LocalBook(
            title = LocalXml.texts(opf, "title").firstOrNull() ?: fallbackTitle,
            authors = LocalXml.texts(opf, "creator").distinct(),
            description = LocalXml.texts(opf, "description").firstOrNull(),
            tags = LocalXml.texts(opf, "subject").distinct(),
            cover = cover,
            chapters = emptyList(),
        )
        if (!withChapters) return metadata
        val spine = LocalXml.tags(opf, "itemref").mapNotNull { manifest[it["idref"]] }
            .filter { it.mediaType.contains("html") || it.href.endsWith("html", ignoreCase = true) }
        require(spine.isNotEmpty()) { "EPUB 没有正文" }
        val toc = tableOfContents(manifest.values, ::read)
        val chapters = mutableListOf<LocalChapter>()
        spine.forEach { item ->
            val entries = toc.filter { it.document == item.href }
            val sections = if (splitSections && entries.size > 1) sections(entries, read(item.href)) else emptyList()
            if (sections.size < 2) {
                chapters += LocalChapter(entries.firstOrNull()?.title ?: "第 ${chapters.size + 1} 节",
                    (chapters.size + 1).toFloat(), document = item.href)
                return@forEach
            }
            sections.forEachIndexed { index, (title, anchor) ->
                chapters += LocalChapter(title, (chapters.size + 1).toFloat(), document = item.href,
                    section = anchor, sectionEnd = sections.getOrNull(index + 1)?.second)
            }
        }
        return metadata.copy(chapters = chapters)
    }

    /** One table-of-contents entry: its title and where it points (a document, optionally an anchor in it). */
    private class TocEntry(val title: String, val document: String, val fragment: String?)

    /** Table-of-contents entries in reading order: the EPUB 3 navigation document first, the EPUB 2 NCX otherwise. */
    private fun tableOfContents(items: Collection<EpubItem>, read: (String) -> String?): List<TocEntry> {
        fun entry(base: String, href: String, title: String) = TocEntry(title, resolveHref(base, href),
            href.substringAfter('#', "").takeIf(String::isNotEmpty)?.let(::decodePercent))
        items.firstOrNull { "nav" in it.properties.split(' ') }?.let { nav ->
            val xml = read(nav.href) ?: return@let
            // Only the table of contents: other navigation lists (landmarks, page lists) follow it.
            val toc = Regex("<nav\\b[^>]*toc[^>]*>([\\s\\S]*?)</nav>", RegexOption.IGNORE_CASE)
                .find(xml)?.groupValues?.get(1) ?: xml
            val entries = Regex("<a\\s[^>]*href\\s*=\\s*[\"']([^\"']+)[\"'][^>]*>([\\s\\S]*?)</a>",
                RegexOption.IGNORE_CASE).findAll(toc).mapNotNull { match ->
                LocalXml.text(match.groupValues[2]).takeIf(String::isNotEmpty)?.let { entry(nav.href, match.groupValues[1], it) }
            }.toList()
            if (entries.isNotEmpty()) return entries
        }
        val ncx = items.firstOrNull { it.mediaType == "application/x-dtbncx+xml" || it.href.endsWith(".ncx", true) }
            ?: return emptyList()
        val xml = read(ncx.href) ?: return emptyList()
        return Regex("<navLabel[^>]*>\\s*<text[^>]*>([\\s\\S]*?)</text>[\\s\\S]*?<content\\s[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']",
            RegexOption.IGNORE_CASE).findAll(xml).mapNotNull { match ->
            LocalXml.text(match.groupValues[1]).takeIf(String::isNotEmpty)?.let { entry(ncx.href, match.groupValues[2], it) }
        }.toList()
    }

    /**
     * A document's sections as (title, start anchor): the first starts at the document start under the first entry's
     * title, each later entry whose anchor the document has starts the next one. Repeated anchors count once.
     */
    private fun sections(entries: List<TocEntry>, xhtml: String?): List<Pair<String, String?>> {
        if (xhtml == null) return emptyList()
        val sections = mutableListOf<Pair<String, String?>>(entries.first().title to null)
        val seen = mutableSetOf<String>()
        entries.drop(1).forEach { entry ->
            val anchor = entry.fragment ?: return@forEach
            if (anchor in seen || anchorPosition(xhtml, anchor) == null) return@forEach
            seen += anchor
            sections += entry.title to anchor
        }
        return sections
    }

    private class EpubItem(val href: String, val mediaType: String, val properties: String)

    // MARK: TXT

    private fun readText(file: Path, fallbackTitle: String): LocalBook {
        val text = text(file)
        require(text.isNotBlank()) { "文本文件是空的" }
        return LocalBook(fallbackTitle, emptyList(), null, emptyList(), null, splitText(text))
    }

    companion object {
        const val INDEX = "index.json"
        private const val PART_LENGTH = 8000
        private val heading = Regex(
            "^[ \\t\\u3000]*(第[ \\t\\u3000]*[0-9０-９零〇一二三四五六七八九十百千万两]+[ \\t\\u3000]*[章回节卷集部篇话]" +
                "|(?:Chapter|CHAPTER)[ \\t]+[0-9IVXLC]+|序章|序言|楔子|引子|前言|尾声|后记|番外)[^\\n]{0,40}$",
            RegexOption.MULTILINE,
        )

        /** Chapters at "第N章"-style headings; text without headings is cut into parts at line ends. */
        fun splitText(text: String): List<LocalChapter> {
            val headings = heading.findAll(text).toList()
            if (headings.size >= 2) {
                val chapters = mutableListOf<LocalChapter>()
                val preface = text.substring(0, headings.first().range.first)
                if (preface.isNotBlank() && preface.trim().length > 20) {
                    chapters += LocalChapter("前言", 0f, textStart = 0, textEnd = headings.first().range.first)
                }
                headings.forEachIndexed { index, match ->
                    val end = headings.getOrNull(index + 1)?.range?.first ?: text.length
                    chapters += LocalChapter(match.value.trim(), (index + 1).toFloat(),
                        textStart = match.range.last + 1, textEnd = end)
                }
                return chapters
            }
            val chapters = mutableListOf<LocalChapter>()
            var start = 0
            while (start < text.length) {
                var end = minOf(text.length, start + PART_LENGTH)
                if (end < text.length) {
                    val lineEnd = text.indexOf('\n', end)
                    end = if (lineEnd == -1 || lineEnd - end > PART_LENGTH / 2) end else lineEnd + 1
                }
                chapters += LocalChapter("第 ${chapters.size + 1} 部分", (chapters.size + 1).toFloat(),
                    textStart = start, textEnd = end)
                start = end
            }
            return chapters
        }

        /** Where the element with id (or anchor name) [anchor] starts in [xhtml]. */
        fun anchorPosition(xhtml: String, anchor: String): Int? =
            Regex("<[^<>]*\\s(?:id|name)\\s*=\\s*[\"']${Regex.escape(anchor)}[\"']").find(xhtml)?.range?.first

        /** Java's `%0Nd`: zero padding after the sign, as Android formats the entry pattern. */
        private fun javaPad(value: Int, width: Int): String {
            val digits = if (value < 0) (-value.toLong()).toString() else value.toString()
            val sign = if (value < 0) "-" else ""
            return sign + digits.padStart(width - sign.length, '0')
        }

        private fun JsonObject.string(key: String): String? =
            (get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

        private fun JsonObject.strings(key: String): List<String> =
            (get(key) as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }

        private fun JsonObject.int(key: String): Int? = (get(key) as? JsonPrimitive)?.intOrNull

        private fun JsonObject.float(key: String): Float? = (get(key) as? JsonPrimitive)?.floatOrNull
    }
}
