package org.skepsun.kototoro.ios

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.skepsun.kototoro.core.source.RoutedSourceRuntime
import org.skepsun.kototoro.core.library.SourceChapterPart
import org.skepsun.kototoro.core.library.withTrackingMetadata
import org.skepsun.kototoro.core.source.SourceChapter
import org.skepsun.kototoro.core.source.SourceChapterContent
import org.skepsun.kototoro.core.source.SourceChapterNavigation
import org.skepsun.kototoro.core.source.SourceContent
import org.skepsun.kototoro.core.source.SourceDescriptor
import org.skepsun.kototoro.core.source.SourceDetailsFetchMode
import org.skepsun.kototoro.core.source.SourceFilter
import org.skepsun.kototoro.core.source.SourcePage
import org.skepsun.kototoro.core.source.SourcePagingMode
import org.skepsun.kototoro.core.source.SourcePreferenceValue
import org.skepsun.kototoro.core.source.SourceProtocolClient
import org.skepsun.kototoro.core.source.SourceTag
import org.skepsun.kototoro.core.source.SourceTransport
import org.skepsun.kototoro.core.source.playbackLine
import org.skepsun.kototoro.tvbox.MacCmsSourceRuntime
import org.skepsun.kototoro.bookmarks.domain.novelBookmarkChapterProgress
import org.skepsun.kototoro.bookmarks.domain.resolveNovelBookmarkPosition
import org.skepsun.kototoro.local.shared.LocalSourceRuntime
import org.skepsun.kototoro.local.shared.LocalFormat
import org.skepsun.kototoro.reader.novel.NovelDocumentBlock
import org.skepsun.kototoro.reader.novel.NovelPageSegment
import org.skepsun.kototoro.reader.novel.NovelParagraphStyle
import org.skepsun.kototoro.reader.novel.NovelStyledText
import org.skepsun.kototoro.reader.novel.NovelReaderLimits
import org.skepsun.kototoro.reader.novel.NovelReaderThemePreset
import org.skepsun.kototoro.reader.novel.buildNovelPageSegments
import org.skepsun.kototoro.reader.novel.buildNovelLineDocument
import org.skepsun.kototoro.reader.novel.novelBlockAtOffset
import org.skepsun.kototoro.reader.novel.novelHtmlToStyledText
import org.skepsun.kototoro.reader.novel.isNovelChapterTitleLine
import org.skepsun.kototoro.reader.novel.novelOffsetScroll
import org.skepsun.kototoro.reader.novel.novelReaderColors
import org.skepsun.kototoro.reader.novel.novelScrollOffset
import org.skepsun.kototoro.reader.novel.positionOf
import org.skepsun.kototoro.reader.novel.splitNovelPageLineRanges
import okio.Path.Companion.toPath
import platform.Foundation.NSUUID

/** Swift owns the synchronous JNI transport; shared protocol work runs off the UI thread. */
interface IosSourceTransport {
    @Throws(Exception::class)
    fun exchange(requestJson: String): String
    fun close()
}

/** [kind] is "video" for native Apple CMS sites and "extension" for embedded-JVM ecosystems. */
data class IosSource(
    val name: String,
    val title: String,
    val locale: String,
    val contentType: String,
    val kind: String,
    /** The extension package or parser plugin providing this source; empty for native sites and older hosts. */
    val group: String = "",
    /** Distinct languages in [group]: above one, only the user's language is enabled by default. */
    val groupLanguages: Int = 1,
) {
    /** CMS, MIHON, TSUNDOKU, ANIYOMI or JAR (kototoro / kotatsu / UMA parser plugins), from the wire identity. */
    val ecosystem: String get() = ecosystemOf(name)
    val isAdult: Boolean get() = contentType.startsWith("HENTAI_")
    /** MANGA, NOVEL or VIDEO without the adult marker. */
    val mediaKind: String get() = mediaKindOf(contentType)
}

/** 12f → "12", 12.5f → "12.5", as Android formats chapter numbers. */
internal fun chapterNumber(number: Float): String =
    if (number == number.toLong().toFloat()) number.toLong().toString() else number.toString()

internal fun ecosystemOf(name: String): String = when {
    MacCmsSourceRuntime.owns(name) -> "CMS"
    LocalSourceRuntime.owns(name) -> "LOCAL"
    name.startsWith("MIHON_") -> "MIHON"
    name.startsWith("TSUNDOKU_") -> "TSUNDOKU"
    name.startsWith("ANIYOMI_") -> "ANIYOMI"
    else -> "JAR"
}

internal fun mediaKindOf(contentType: String): String = contentType.removePrefix("HENTAI_").let { kind ->
    if (kind == "VIDEO" || kind == "NOVEL") kind else "MANGA"
}

/** A novel chapter as HTML; [images] are the chapter's picture URLs (with the headers the source requires). */
class IosChapterText internal constructor(internal val value: SourceChapterContent) {
    val html: String get() = value.html
    val images: List<String> get() = value.images.map { it.url }

    /** Request headers the source requires for one of the chapter's pictures (Referer, cookies, ...). */
    fun headers(imageUrl: String): Map<String, String> = value.images.firstOrNull { it.url == imageUrl }?.headers.orEmpty()

    /** The chapter as the reader shows it, one block per line; the shared HTML conversion Android's EPUB reader uses. */
    fun document(): IosNovelDocument = IosNovelDocument(novelHtmlToStyledText(value.html))

    /** The titles of a whole book's own chapters (an EPUB download); empty for an ordinary chapter. */
    val sections: List<String> get() = value.sections.map { it.title }

    /** The book's chapter [index] alone, with the book's pictures; null when there is no such chapter. */
    fun section(index: Int): IosChapterText? {
        val section = value.sections.getOrNull(index) ?: return null
        val end = value.sections.getOrNull(index + 1)?.start ?: value.html.length
        if (section.start !in 0..end || end > value.html.length) return null
        return IosChapterText(SourceChapterContent(value.html.substring(section.start, end), value.images))
    }
}

/**
 * A converted novel chapter: its line blocks plus the position rules shared with Android. History stores the chapter
 * progress of the first visible line (see `novelOffsetScroll`), so either reader restores the other's position.
 */
class IosNovelDocument internal constructor(internal val styled: NovelStyledText) {
    internal val text: String get() = styled.text
    internal val lines = buildNovelLineDocument(styled.text)

    val blocks: List<IosNovelBlock> = lines.map { block ->
        when (block) {
            is NovelDocumentBlock.Text -> IosNovelBlock(block.original, null)
            is NovelDocumentBlock.Image -> IosNovelBlock(null, block.path)
        }
    }

    /** The history `scroll` value for a reader whose first visible block is [index]. */
    fun scroll(index: Int): Float =
        lines.getOrNull(index)?.sourceRange?.let { novelOffsetScroll(it.first, text.length).toFloat() } ?: 0f

    /** The block a history record points at: its chapter progress when it has one, else the stored line [index]. */
    fun index(scroll: Float, index: Int): Int {
        val located = novelScrollOffset(scroll, text.length)?.let { novelBlockAtOffset(lines, it) } ?: index
        return located.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    }

    /**
     * The block a bookmark of this chapter points at, found by its text excerpt as Android does (the branch progress
     * separates repeats); bookmarks without an excerpt, or whose excerpt is gone, keep their stored line.
     */
    fun bookmarkIndex(bookmark: IosBookmark, content: IosSourceContent): Int {
        val progress = content.branchPlace(bookmark.value.chapterId)?.let { (chapterIndex, count) ->
            novelBookmarkChapterProgress(bookmark.value.percent, chapterIndex, count)
        } ?: 0f
        val segments = lines.map { (it as? NovelDocumentBlock.Text)?.original.orEmpty() }
        val located = resolveNovelBookmarkPosition(segments, bookmark.textPreview, bookmark.index, progress) ?: bookmark.index
        return located.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
    }

    /**
     * The chapter prepared for the paged reader with the given paragraph settings. With [bookStyles], lines the book
     * styles as headings, centred or right-aligned text, or without a first-line indent are not indented.
     */
    fun pages(indent: Boolean, spacingLines: Int, bookStyles: Boolean): IosNovelPages =
        IosNovelPages(this, bookStyles, buildNovelPageSegments(lines, indent, spacingLines) { index, line ->
            !isNovelChapterTitleLine(line) && (!bookStyles || paragraphOf(index)?.takesIndent != false)
        })

    /** The book's styles for line [index] alone, in its own character offsets (the scrolling reader). */
    fun lineStyles(index: Int): List<IosNovelStyleRun> {
        val range = lines.getOrNull(index)?.sourceRange ?: return emptyList()
        return styleRuns(range.first, range.last + 1, -range.first)
    }

    fun lineParagraph(index: Int): IosNovelParagraphRun? {
        val range = lines.getOrNull(index)?.sourceRange ?: return null
        return paragraphRun(index, 0, range.last - range.first + 1)
    }

    internal fun paragraphOf(index: Int): NovelParagraphStyle? =
        lines.getOrNull(index)?.sourceRange?.let { styled.paragraphAt(it.first) }

    /** Inline styles and ruby readings of `start until end`, moved by [shift]. */
    internal fun styleRuns(start: Int, end: Int, shift: Int): List<IosNovelStyleRun> =
        styled.spansIn(start, end).map { span ->
            val style = span.style
            IosNovelStyleRun(span.start + shift, span.end + shift, style.bold, style.italic, style.underline,
                style.strike, style.verticalPosition, style.fontScale, null)
        } + styled.rubiesIn(start, end).map { ruby ->
            IosNovelStyleRun(ruby.start + shift, ruby.end + shift, false, false, false, false, 0, 1f, ruby.text)
        }

    /** Paragraph layout of line [index] laid out at `start until end`. */
    internal fun paragraphRun(index: Int, start: Int, end: Int): IosNovelParagraphRun? {
        val range = lines.getOrNull(index)?.sourceRange ?: return null
        val paragraph = styled.paragraphAt(range.first) ?: return null
        val runs = styled.spansIn(range.first, range.last + 1)
        return IosNovelParagraphRun(start, end, paragraph.align.ordinal, paragraph.heading,
            maxOf(1f, runs.maxOfOrNull { it.style.fontScale } ?: 1f),
            styled.rubiesIn(range.first, range.last + 1).isNotEmpty(), paragraph.takesIndent)
    }
}

/** An inline style or, with [ruby], a reading over its characters. [vertical] is 1 for superscript, -1 for subscript. */
data class IosNovelStyleRun(val start: Int, val end: Int, val bold: Boolean, val italic: Boolean,
    val underline: Boolean, val strike: Boolean, val vertical: Int, val scale: Float, val ruby: String?)

/**
 * Layout of one line: [align] is start, centre, end or justify (0–3), [scale] the largest font scale on it, so its
 * line height grows with headings, [ruby] whether readings need room above it, and [indent] whether the book leaves
 * the reader's paragraph indent to it.
 */
data class IosNovelParagraphRun(val start: Int, val end: Int, val align: Int, val heading: Int, val scale: Float,
    val ruby: Boolean, val indent: Boolean)

/**
 * Text segments and pictures of the paged reader, with positions in the same terms as the scrolling reader: the
 * block shown at a segment offset, and the history value for it (the character offset in the converted chapter).
 */
class IosNovelPages internal constructor(
    private val document: IosNovelDocument,
    private val bookStyles: Boolean,
    private val segments: List<NovelPageSegment>,
) {
    private val lines get() = document.lines
    private val text get() = document.text

    val count: Int get() = segments.size

    /** The text to lay out for [segment], or null for a picture. */
    fun text(segment: Int): String? = (segments.getOrNull(segment) as? NovelPageSegment.Text)?.text

    fun imageUrl(segment: Int): String? = (segments.getOrNull(segment) as? NovelPageSegment.Image)?.path

    /** The book's inline styles and ruby readings in [segment]'s text; none without book styles. */
    fun styles(segment: Int): List<IosNovelStyleRun> {
        val value = segments.getOrNull(segment) as? NovelPageSegment.Text ?: return emptyList()
        if (!bookStyles) return emptyList()
        return (value.firstBlock until value.firstBlock + value.blockCount).flatMap { block ->
            val range = lines.getOrNull(block)?.sourceRange ?: return@flatMap emptyList()
            document.styleRuns(range.first, range.last + 1, value.contentStart(block) - range.first)
        }
    }

    /** Layout of each line of [segment]'s text; none without book styles. */
    fun paragraphs(segment: Int): List<IosNovelParagraphRun> {
        val value = segments.getOrNull(segment) as? NovelPageSegment.Text ?: return emptyList()
        if (!bookStyles) return emptyList()
        return (value.firstBlock until value.firstBlock + value.blockCount).mapNotNull { block ->
            val range = lines.getOrNull(block)?.sourceRange ?: return@mapNotNull null
            document.paragraphRun(block, value.lineStart(block), value.contentStart(block) + range.last - range.first + 1)
        }
    }

    /** The document block shown at [offset] of [segment]. */
    fun block(segment: Int, offset: Int): Int = when (val value = segments.getOrNull(segment)) {
        is NovelPageSegment.Text -> value.blockAt(offset)
        is NovelPageSegment.Image -> value.block
        null -> 0
    }

    /** The history `scroll` value for a page starting at [offset] of [segment]. */
    fun scroll(segment: Int, offset: Int): Float {
        val value = segments.getOrNull(segment) ?: return 0f
        val block = block(segment, offset)
        val start = lines.getOrNull(block)?.sourceRange ?: return 0f
        val inBlock = (value as? NovelPageSegment.Text)?.offsetInBlock(offset) ?: 0
        return novelOffsetScroll(start.first + minOf(inBlock, start.last - start.first + 1), text.length).toFloat()
    }

    /** Where a history record points: its chapter progress when it has one, else the stored line [index]. */
    fun position(scroll: Float, index: Int): IosNovelSegmentPosition {
        val offset = novelScrollOffset(scroll, text.length)
        val block = offset?.let { novelBlockAtOffset(lines, it) } ?: index.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val inBlock = offset?.let { it - (lines.getOrNull(block)?.sourceRange?.first ?: it) } ?: 0
        return position(block, inBlock)
    }

    /** Where [block] (and character [inBlock] of it) is laid out. */
    fun position(block: Int, inBlock: Int): IosNovelSegmentPosition =
        segments.positionOf(block, inBlock)?.let { IosNovelSegmentPosition(it.segment, it.offset) }
            ?: IosNovelSegmentPosition(0, 0)
}

data class IosNovelSegmentPosition(val segment: Int, val offset: Int)

/** Reader settings and colors shared with Android, and the page-splitting rule its paged reader uses. */
object IosNovelStyle {
    val defaultFontSize: Float = NovelReaderLimits.DEFAULT_FONT_SIZE
    val minFontSize: Float = NovelReaderLimits.FONT_SIZE_RANGE.start
    val maxFontSize: Float = NovelReaderLimits.FONT_SIZE_RANGE.endInclusive
    val fontSizeStep: Float = NovelReaderLimits.FONT_SIZE_STEP
    val defaultLineSpacing: Float = NovelReaderLimits.DEFAULT_LINE_SPACING
    val minLineSpacing: Float = NovelReaderLimits.LINE_SPACING_RANGE.start
    val maxLineSpacing: Float = NovelReaderLimits.LINE_SPACING_RANGE.endInclusive
    val lineSpacingStep: Float = NovelReaderLimits.LINE_SPACING_STEP
    val maxParagraphSpacing: Int = NovelReaderLimits.PARAGRAPH_SPACING_RANGE.endInclusive.toInt()
    val defaultMargin: Int = NovelReaderLimits.DEFAULT_MARGIN
    val minMargin: Int = NovelReaderLimits.MARGIN_RANGE.first
    val maxMargin: Int = NovelReaderLimits.MARGIN_RANGE.last
    val marginStep: Int = NovelReaderLimits.MARGIN_STEP
    val themes: List<String> = NovelReaderThemePreset.entries.map { it.name }

    fun colors(theme: String, dark: Boolean): IosNovelColors {
        val preset = NovelReaderThemePreset.entries.firstOrNull { it.name == theme } ?: NovelReaderThemePreset.PAPER
        val colors = novelReaderColors(preset, dark)
        return IosNovelColors(colors.background, colors.text, colors.secondaryText, colors.chromeBackground,
            colors.chromeText, colors.highlight)
    }

    /** A "第N章"-style or bracketed title line, which is not indented. */
    fun isTitleLine(line: String): Boolean = isNovelChapterTitleLine(line)

    /** First line of each page, given each line's top and bottom in points; Android splits its pages the same way. */
    fun pageStarts(tops: FloatArray, bottoms: FloatArray, pageHeight: Int): IntArray =
        splitNovelPageLineRanges(minOf(tops.size, bottoms.size), pageHeight, tops::get, bottoms::get)
            .map { it.first }.toIntArray()
}

/** ARGB colors of a reader theme. */
data class IosNovelColors(val background: Long, val text: Long, val secondaryText: Long, val chromeBackground: Long,
    val chromeText: Long, val highlight: Long)

/** A paragraph line ([text]) or an illustration ([imageUrl]) of a novel chapter. */
class IosNovelBlock internal constructor(val text: String?, val imageUrl: String?)

/** Native sources are always listed; an extension runtime failure is reported without hiding them. */
data class IosSourceCatalog(val sources: List<IosSource>, val extensionError: String?)

class IosSourceTag internal constructor(internal val value: SourceTag) {
    val title: String get() = value.title
    val key: String get() = value.key
}

/** Keep opaque extension fields inside Kotlin, including when Swift pushes a detail/reader screen. */
class IosSourceContent internal constructor(
    internal val value: SourceContent,
    internal val metadata: org.skepsun.kototoro.core.db.entity.TrackingSiteItemEntity? = null,
    /** Downloaded books listed by their own chapters: the download's chapter id → those chapters' titles. */
    private val books: Map<Long, List<String>> = emptyMap(),
) {
    private val display = value.withTrackingMetadata(metadata)
    val usesTrackingCover: Boolean get() = !metadata?.coverUrl.isNullOrBlank()
    val metadataSiteTitle: String? get() = metadata?.service?.let(::trackingSiteTitle)
    val id: String get() = value.id.toString()
    val sourceName: String get() = value.source.name
    val contentType: String get() = value.source.contentType
    val mediaKind: String get() = mediaKindOf(value.source.contentType)
    val isLocal: Boolean get() = LocalSourceRuntime.owns(sourceName)
    val title: String get() = display.title
    val url: String get() = value.url
    val publicUrl: String get() = value.publicUrl
    val authors: List<String> get() = display.authors.toList()
    val altTitles: List<String> get() = display.altTitles.filter { it.isNotBlank() && it != display.title }
    /** 0…1 as on Android; negative when the source has no rating. */
    val rating: Float get() = display.rating
    /** ONGOING / FINISHED / ABANDONED / PAUSED / UPCOMING / RESTRICTED, when known. */
    val state: String? get() = value.state?.takeIf(String::isNotBlank)
    val contentRating: String? get() = value.contentRating?.takeIf(String::isNotBlank)
    val tags: List<String> get() = display.tags.map { it.title }
    val summary: String? get() = display.description?.takeIf(String::isNotBlank)
    val coverUrl: String? get() = (display.largeCoverUrl ?: display.coverUrl)?.takeIf(String::isNotBlank)
    val hasCover: Boolean get() = coverUrl != null
    val chapters: List<IosSourceChapter> get() = listed.map(::IosSourceChapter)
    /** Without building the chapter list (each [chapters] read maps and bridges every chapter). */
    val chapterCount: Int get() = listed.size

    fun adjacentChapter(chapter: IosSourceChapter, forward: Boolean): IosSourceChapter? =
        SourceChapterNavigation.adjacent(listed, chapter.value.id, forward)?.let(::IosSourceChapter)

    /**
     * The source's chapters, with each downloaded book replaced by its own chapters as Android lists an EPUB download
     * (the download names their group). Sources, history and downloads keep using [value]'s chapters.
     */
    internal val listed: List<SourceChapter> by lazy {
        if (books.isEmpty()) value.chapters.orEmpty() else value.chapters.orEmpty().flatMap { chapter ->
            books[chapter.id]?.takeIf { it.isNotEmpty() }?.let { titles ->
                titles.mapIndexed { index, title -> bookChapter(chapter, index, titles.size, title) }
            } ?: listOf(chapter)
        }
    }
    private val parts: Map<Long, SourceChapterPart> by lazy {
        val chapters = value.chapters.orEmpty().associateBy { it.id }
        buildMap {
            for ((id, titles) in books) {
                val parent = chapters[id] ?: continue
                titles.indices.forEach { put(bookChapterId(parent.id, it), SourceChapterPart(parent, it, titles.size)) }
            }
        }
    }

    /** This work's fields with [other]'s chapters: a source that answered without its chapters keeps the shown list. */
    fun withChaptersOf(other: IosSourceContent): IosSourceContent =
        IosSourceContent(value.copy(chapters = other.value.chapters), metadata)

    /** This work without a chapter list, so its details screen lists the saved or fetched chapters. */
    val withoutChapters: IosSourceContent get() = IosSourceContent(value.copy(chapters = null), metadata)

    /** The source's own chapters, downloads of books included (what [chapters] lists by their chapters). */
    val sourceChapters: List<IosSourceChapter> get() = value.chapters.orEmpty().map(::IosSourceChapter)

    /** This work with the given downloaded books listed by their chapters: download chapter id → chapter titles. */
    fun withBooks(books: Map<String, List<String>>): IosSourceContent {
        val parsed = books.mapNotNull { (id, titles) -> id.toLongOrNull()?.let { it to titles } }.toMap()
        return if (parsed == this.books) this else IosSourceContent(value, metadata, parsed)
    }

    /** Some downloaded book is listed by its chapters. */
    val hasBooks: Boolean get() = parts.isNotEmpty()

    /** The download a book's chapter is listed from; null for the source's own chapters. */
    fun bookOf(chapter: IosSourceChapter): IosSourceChapter? = parts[chapter.value.id]?.parent?.let(::IosSourceChapter)

    /** The chapter's place inside its book; -1 for the source's own chapters. */
    fun bookIndex(chapter: IosSourceChapter): Int = parts[chapter.value.id]?.index ?: -1

    /** The first chapter listed for a download shown by its chapters, or the chapter itself. */
    fun listedChapter(chapter: IosSourceChapter): IosSourceChapter =
        if (books[chapter.value.id].isNullOrEmpty()) chapter
        else listed.firstOrNull { parts[it.id]?.parent?.id == chapter.value.id }?.let(::IosSourceChapter) ?: chapter

    internal fun part(chapter: SourceChapter): SourceChapterPart? = parts[chapter.id]

    /** The id the stored chapter list knows a chapter by: a book's chapter is stored as its download. */
    internal fun storedChapterId(id: Long): Long = parts[id]?.parent?.id ?: id

    /** The chapter's index and the chapter count of its branch, a book's chapters sharing the download's place. */
    internal fun branchPlace(chapterId: Long): Pair<Int, Int>? {
        val part = parts[chapterId]
        val all = value.chapters.orEmpty()
        val stored = all.firstOrNull { it.id == (part?.parent?.id ?: chapterId) } ?: return null
        val branch = all.filter { it.branch == stored.branch }
        val count = part?.count ?: 1
        return branch.indexOfFirst { it.id == stored.id } * count + (part?.index ?: 0) to branch.size * count
    }
}

/** Android's id for chapter [index] of an EPUB download (`EpubInternalChapterLoader`), so history matches. */
private fun bookChapterId(parentId: Long, index: Int): Long = parentId + index * 1_000_000L + 1

/** Chapter [index] of a downloaded book in place of its download [parent], numbered within the download's number. */
private fun bookChapter(parent: SourceChapter, index: Int, count: Int, title: String) = parent.copy(
    id = bookChapterId(parent.id, index),
    title = title.takeIf(String::isNotBlank) ?: localized("第 %@ 章", index + 1),
    number = parent.number + index.toFloat() / count,
    url = "${parent.url}#chapter/$index",
    sourceData = null,
    ebookFormats = emptyList(),
)

class IosSourceChapter internal constructor(internal val value: SourceChapter) {
    val id: String get() = value.id.toString()
    val title: String get() = value.title?.takeIf(String::isNotBlank) ?: localized("第 %@ 话", chapterNumber(value.number))
    val branch: String? get() = value.branch
    val number: Float get() = value.number
    val volume: Int get() = value.volume
    val scanlator: String? get() = value.scanlator?.takeIf(String::isNotBlank)
    /** Epoch milliseconds; 0 when unknown. */
    val uploadDate: Long get() = value.uploadDate
    /** The named episode group supplied by the source. */
    val line: String? get() = value.playbackLine
}

class IosSourcePage internal constructor(internal val value: SourcePage) {
    val id: String get() = value.id.toString()
    val url: String get() = value.url
    val headers: Map<String, String> get() = value.headers.orEmpty()
    val label: String? get() = value.playbackLabel
    val quality: Int get() = value.playbackQuality ?: 0
    val subtitles: List<IosExternalTrack> get() = value.externalSubtitleTracks.map(::IosExternalTrack)
}

data class IosSourceImage(val relativePath: String, val sha256: String, val byteSize: Long)

/**
 * Apple CMS sites run natively through the shared [MacCmsSourceRuntime]; every other source goes through the optional
 * embedded-JVM transport. Both sit behind one routed runtime, so screens do not care where a source executes.
 */
/** A cancellation Swift hands to a Kotlin call and triggers from its task's cancellation handler. */
class IosCancellation {
    internal val signal = CompletableDeferred<Unit>()

    fun cancel() {
        signal.complete(Unit)
    }
}

/** Longer than any single image download a source completes, short enough that a stalled one frees its slot. */
private const val IMAGE_TIMEOUT_MILLIS = 90_000L

class IosSources(
    transport: IosSourceTransport?,
    private val videoSources: IosVideoSources?,
    /** Where imported local works are stored and where reader pictures go (`<sha256>.img`); null disables them. */
    localDirectory: String?,
    imageDirectory: String?,
) {
    constructor(transport: IosSourceTransport?, videoSources: IosVideoSources?) : this(transport, videoSources, null, null)
    constructor(transport: IosSourceTransport) : this(transport, null)

    private val transport = transport
    private val extensions = transport?.let { owned ->
        SourceProtocolClient(
            SourceTransport { request -> withContext(Dispatchers.IO) { owned.exchange(request) } },
            { NSUUID().UUIDString },
        )
    }
    private val cms = videoSources?.let { store -> MacCmsSourceRuntime(store.http) { store.stored() } }
    private val local = if (localDirectory == null || imageDirectory == null) null else
        LocalSourceRuntime(localDirectory.toPath(), imageDirectory.toPath(), decodeLegacyText = ::decodeLegacyText)
    internal val runtime = RoutedSourceRuntime(
        listOfNotNull(
            local?.let { RoutedSourceRuntime.Route(LocalSourceRuntime::owns, it) },
            cms?.let { RoutedSourceRuntime.Route(MacCmsSourceRuntime::owns, it) },
            extensions?.let { RoutedSourceRuntime.Route({ true }, it) },
        ),
    )
    private val descriptors = HashMap<String, SourceDescriptor>()
    private val descriptorsMutex = Mutex()
    private val panels = mutableMapOf<String, IosFilterPanel>()
    private var sourceCacheRevision = 0L

    val hasExtensionRuntime: Boolean get() = extensions != null

    /** Versioned host management is separate from the source invocation protocol. */
    @Throws(Exception::class)
    suspend fun manage(requestJson: String): String = withContext(Dispatchers.IO) {
        checkNotNull(transport) { localized("此构建未包含扩展运行时") }.exchange(requestJson)
    }

    @Throws(Exception::class)
    suspend fun catalog(): IosSourceCatalog {
        val video = videoSources?.list().orEmpty().map { IosSource(it.id, it.name, "", MacCmsSourceRuntime.CONTENT_TYPE, "video") }
        var extensionError: String? = null
        val external = extensions?.let { client ->
            try {
                val info = managementInfo()
                client.getSources().map {
                    val group = info.groups[it.name]
                    IosSource(it.name, info.titles[it.name] ?: it.name, it.locale, it.contentType, "extension",
                        group?.first.orEmpty(), group?.second ?: 1)
                }
            } catch (error: kotlin.coroutines.cancellation.CancellationException) {
                throw error
            } catch (error: Exception) {
                extensionError = error.message ?: localized("扩展运行时调用失败")
                emptyList()
            }
        }.orEmpty()
        val imported = if (local == null) emptyList() else listOf(
            IosSource(LocalSourceRuntime.MANGA.name, localized("本地漫画"), "", LocalSourceRuntime.MANGA.contentType, "local"),
            IosSource(LocalSourceRuntime.NOVEL.name, localized("本地小说"), "", LocalSourceRuntime.NOVEL.contentType, "local"),
            IosSource(LocalSourceRuntime.VIDEO.name, localized("本地视频"), "", LocalSourceRuntime.VIDEO.contentType, "local"),
        )
        return IosSourceCatalog(imported + video + external, extensionError)
    }

    /** True when local import is available in this build. */
    val supportsLocalImport: Boolean get() = local != null

    /** The shared file classification, used by the platform for media validation before copying. */
    fun localFileContentType(fileName: String): String? = LocalFormat.of(fileName)?.contentType

    /** Imports a local file (copied into the app); [fileName] decides the format and default title. */
    @Throws(Exception::class)
    suspend fun importLocal(path: String, fileName: String): IosSourceContent =
        IosSourceContent(checkNotNull(local) { localized("本地导入不可用") }.import(path.toPath(), fileName))

    fun isLocal(content: IosSourceContent): Boolean = LocalSourceRuntime.owns(content.value.source.name)

    /** Deletes the imported file; favourites and history of the work stay like those of any removed source. */
    @Throws(Exception::class)
    suspend fun removeLocal(content: IosSourceContent) {
        checkNotNull(local) { localized("本地导入不可用") }.remove(content.value.id)
    }

    private class ManagementInfo(val titles: Map<String, String>, val groups: Map<String, Pair<String, Int>>)

    /** Presentation metadata is optional, allowing older hosts to keep serving the source protocol. */
    private suspend fun managementInfo(): ManagementInfo = try {
        val reply = Json.parseToJsonElement(manage("""{"managementVersion":1,"operation":"list"}""")).jsonObject
        val titles = reply["sourceTitles"]?.jsonObject?.mapValues { it.value.jsonPrimitive.content }.orEmpty()
        val groups = HashMap<String, Pair<String, Int>>()
        reply["installed"]?.jsonArray.orEmpty().forEach { element ->
            val item = element.jsonObject
            val packageName = item["packageName"]?.jsonPrimitive?.content ?: return@forEach
            val languages = item["languages"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }.distinct().size
            item["sourceIds"]?.jsonArray.orEmpty().forEach { id ->
                groups.getOrPut(id.jsonPrimitive.content) { packageName to maxOf(languages, 1) }
            }
        }
        ManagementInfo(titles, groups)
    } catch (error: kotlin.coroutines.cancellation.CancellationException) {
        throw error
    } catch (_: Exception) { ManagementInfo(emptyMap(), emptyMap()) }

    /** Extension sources only (the embedded-JVM preview); kept for callers that predate native sources. */
    @Throws(Exception::class)
    suspend fun sources(): List<IosSource> = checkNotNull(extensions) { localized("此构建未包含扩展运行时") }
        .getSources().map { IosSource(it.name, it.name, it.locale, it.contentType, "extension") }

    /** Call after sites were imported or removed so cached detection is not reused. */
    @Throws(Exception::class)
    suspend fun sourcesChanged() {
        cms?.invalidate()
        descriptorsMutex.withLock {
            sourceCacheRevision++
            descriptors.clear()
            panels.clear()
        }
    }

    @Throws(Exception::class)
    suspend fun categories(sourceName: String): List<IosSourceTag> =
        runtime.getFilterOptions(sourceName).effectiveTagGroups.flatMap { it.tags }.distinctBy { it.key }.map(::IosSourceTag)

    @Throws(Exception::class)
    suspend fun list(sourceName: String, offset: Int): List<IosSourceContent> =
        runtime.getList(sourceName, offset, null, null).map(::IosSourceContent)

    @Throws(Exception::class)
    suspend fun browserSession(sourceName: String): IosBrowserSession =
        runtime.browserSession(sourceName).let { IosBrowserSession(it.url, it.userAgent) }

    /** Cookies from the in-app browser (`Set-Cookie` lines) for the source's own requests; returns how many were kept. */
    @Throws(Exception::class)
    suspend fun importCookies(sourceName: String, url: String, cookies: List<String>): Int {
        val imported = runtime.importCookies(sourceName, url, cookies)
        if (imported > 0) invalidateSourceSettings(sourceName)
        return imported
    }

    /** Drops cached CMS site state after its settings change, so the next request uses the new address. */
    @Throws(Exception::class)
    suspend fun reloadVideoSites() { sourcesChanged() }

    /** The source's settings; sources without any (or whose runtime has none) throw an unsupported error. */
    @Throws(Exception::class)
    suspend fun preferences(sourceName: String): IosPreferenceScreen = IosPreferenceScreen(runtime.getPreferences(sourceName))

    @Throws(Exception::class)
    suspend fun updateText(screen: IosPreferenceScreen, node: IosPreferenceNode, text: String): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.Text(text))

    @Throws(Exception::class)
    suspend fun updateToggle(screen: IosPreferenceScreen, node: IosPreferenceNode, on: Boolean): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.Toggle(on))

    @Throws(Exception::class)
    suspend fun updateChoice(screen: IosPreferenceScreen, node: IosPreferenceNode, choice: String): IosPreferenceResult =
        update(screen, node, node.choiceValue(choice))

    @Throws(Exception::class)
    suspend fun updateChoices(screen: IosPreferenceScreen, node: IosPreferenceNode, choices: Set<String>): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.TextSet(choices))

    @Throws(Exception::class)
    suspend fun triggerAction(screen: IosPreferenceScreen, node: IosPreferenceNode): IosPreferenceResult =
        update(screen, node, SourcePreferenceValue.Action())

    private suspend fun update(screen: IosPreferenceScreen, node: IosPreferenceNode, value: SourcePreferenceValue): IosPreferenceResult {
        val result = try {
            runtime.updatePreference(screen.sourceName, screen.revision, node.id, value)
        } finally {
            // Native preference listeners may change derived settings even when they reject an edit or throw.
            invalidateSourceSettings(screen.sourceName)
        }
        return IosPreferenceResult(result.status.label(), IosPreferenceScreen(result.screen))
    }

    private suspend fun invalidateSourceSettings(sourceName: String) {
        descriptorsMutex.withLock {
            sourceCacheRevision++
            descriptors.remove(sourceName)
            panels.remove(sourceName)
        }
    }

    @Throws(Exception::class)
    suspend fun filterPanel(sourceName: String): IosFilterPanel {
        while (true) {
            val revision = descriptorsMutex.withLock { sourceCacheRevision }
            val descriptor = descriptor(sourceName)
            val options = runtime.getFilterOptions(sourceName)
            // An advertised native FilterList must load successfully; a failure is retryable, not an empty panel.
            val dynamic = if (!descriptor.isDynamicFilteringSupported) null else
                runtime.getDynamicFilters(sourceName).also { check(it.source.name == sourceName) { "Filter source mismatch" } }
            // Apple CMS listings keep the site's newest-first order; relevance only applies to search.
            val panel = if (MacCmsSourceRuntime.owns(sourceName)) IosFilterPanel(descriptor, options,
                MacCmsSourceRuntime.ORDER_NEWEST, setOf(MacCmsSourceRuntime.ORDER_RELEVANCE))
                else IosFilterPanel(descriptor, options, dynamic = dynamic)
            val published = descriptorsMutex.withLock {
                if (revision != sourceCacheRevision) false else {
                    panels[sourceName] = panel
                    true
                }
            }
            if (published) return panel
        }
    }

    /** Listing with the browse screen's filter panel selection; search text wins when the source cannot combine. */
    @Throws(Exception::class)
    suspend fun filteredPage(
        sourceName: String,
        page: Int,
        loadedCount: Int,
        query: String?,
        selection: IosFilterSelection?,
    ): List<IosSourceContent> {
        val descriptor = descriptor(sourceName)
        val offset = if (descriptor.pagingMode == SourcePagingMode.PAGE_INDEX) page else loadedCount
        val search = query?.trim()?.takeIf(String::isNotEmpty)
        val combined = search == null || descriptor.filterCapabilities.isSearchWithFiltersSupported
        val panel = if (combined && selection?.needsPanel == true) {
            descriptorsMutex.withLock { panels[sourceName] } ?: filterPanel(sourceName)
        } else null
        val filter = if (combined) selection?.toFilter(panel, search) ?: search?.let { SourceFilter(query = it) }
            else SourceFilter(query = search)
        val order = when {
            MacCmsSourceRuntime.owns(sourceName) -> if (search != null) MacCmsSourceRuntime.ORDER_RELEVANCE
                else selection?.order ?: MacCmsSourceRuntime.ORDER_NEWEST
            else -> selection?.order?.takeIf { it in descriptor.sortOrders }
        }
        return runtime.getList(sourceName, offset, order, filter).map(::IosSourceContent)
    }

    /**
     * One page of a listing or search. [loadedCount] counts raw results from previous pages, before UI deduplication;
     * the source's paging mode decides whether the request offset is the page index or that count.
     */
    @Throws(Exception::class)
    suspend fun page(
        sourceName: String,
        page: Int,
        loadedCount: Int,
        query: String?,
        tag: IosSourceTag?,
    ): List<IosSourceContent> {
        val descriptor = descriptor(sourceName)
        val offset = if (descriptor.pagingMode == SourcePagingMode.PAGE_INDEX) page else loadedCount
        val search = query?.trim()?.takeIf(String::isNotEmpty)
        val filter = if (search == null && tag == null) null else SourceFilter(query = search, tags = setOfNotNull(tag?.value))
        // Apple CMS sites keep their own order (latest first) unless searching; other sources use their default.
        val order = when {
            !MacCmsSourceRuntime.owns(sourceName) -> null
            search != null -> MacCmsSourceRuntime.ORDER_RELEVANCE
            else -> MacCmsSourceRuntime.ORDER_NEWEST
        }
        return runtime.getList(sourceName, offset, order, filter).map(::IosSourceContent)
    }

    @Throws(Exception::class)
    suspend fun details(content: IosSourceContent): IosSourceContent =
        IosSourceContent(runtime.getDetails(content.value, SourceDetailsFetchMode.ALLOW_CACHE))

    @Throws(Exception::class)
    suspend fun refreshDetails(content: IosSourceContent): IosSourceContent =
        IosSourceContent(runtime.getDetails(content.value, SourceDetailsFetchMode.FORCE_REFRESH))

    @Throws(Exception::class)
    suspend fun pages(chapter: IosSourceChapter): List<IosSourcePage> =
        runtime.getPages(chapter.value, null).map(::IosSourcePage)

    @Throws(Exception::class)
    suspend fun chapterText(chapter: IosSourceChapter): IosChapterText? =
        runtime.getChapterContent(chapter.value, null)?.let(::IosChapterText)

    /** Extension covers are downloaded by the source's own client; native sources expose plain cover URLs. */
    fun fetchesCovers(content: IosSourceContent): Boolean =
        !content.usesTrackingCover && !MacCmsSourceRuntime.owns(content.value.source.name)

    @Throws(Exception::class)
    suspend fun image(page: IosSourcePage): IosSourceImage {
        val artifact = runtime.fetchImage(page.value)
        check(artifact.source.name == page.value.source.name && artifact.pageId == page.value.id) {
            "Image identity mismatch"
        }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    /**
     * [image] that Swift can cancel and that cannot hang: Kotlin/Native's async export does not forward Swift task
     * cancellation, so a reader page scrolled away would otherwise keep its download (and its reader-thread slot)
     * until the source answered. A source that never answers fails after [IMAGE_TIMEOUT_MILLIS] like any error.
     */
    @Throws(Exception::class)
    suspend fun image(page: IosSourcePage, cancellation: IosCancellation): IosSourceImage = coroutineScope {
        val work = async {
            withTimeoutOrNull(IMAGE_TIMEOUT_MILLIS) { image(page) } ?: throw IllegalStateException(localized("页面加载超时"))
        }
        val watcher = launch {
            cancellation.signal.await()
            work.cancel()
        }
        try {
            work.await()
        } finally {
            watcher.cancel()
        }
    }

    @Throws(Exception::class)
    suspend fun cover(content: IosSourceContent): IosSourceImage {
        val artifact = runtime.fetchCover(content.value, true)
        // Same source by name: a work restored from the library carries the stored locale/content type, which can
        // differ from the runtime's descriptor (the continue-reading cover failed for exactly those works).
        check(artifact.source.name == content.value.source.name && artifact.contentId == content.value.id) {
            "Cover identity mismatch"
        }
        return IosSourceImage(artifact.relativePath, artifact.sha256, artifact.byteSize)
    }

    /** The source site's base address for its icon; null when the runtime has none or describe fails. */
    @Throws(Exception::class)
    suspend fun homepage(sourceName: String): String? = try { descriptor(sourceName).homepage } catch (
        error: kotlin.coroutines.cancellation.CancellationException) { throw error } catch (_: Exception) { null }

    /** Related or recommended works for the content, as provided by the underlying source. */
    @Throws(Exception::class)
    suspend fun related(content: IosSourceContent): List<IosSourceContent> = try {
        runtime.getRelated(content.value).map(::IosSourceContent)
    } catch (e: kotlin.coroutines.cancellation.CancellationException) {
        throw e
    } catch (_: Exception) {
        emptyList()
    }

    private suspend fun descriptor(sourceName: String): SourceDescriptor {
        while (true) {
            val (cached, revision) = descriptorsMutex.withLock { descriptors[sourceName] to sourceCacheRevision }
            if (cached != null) return cached
            val described = runtime.describe(sourceName)
            check(described.source.name == sourceName) { "Descriptor source mismatch" }
            val published = descriptorsMutex.withLock {
                if (revision != sourceCacheRevision) false else {
                    descriptors[sourceName] = described
                    true
                }
            }
            if (published) return described
        }
    }

    fun close() {
        transport?.close()
    }
}
