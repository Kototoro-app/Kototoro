package org.skepsun.kototoro.reader.novel

const val NOVEL_PARAGRAPH_INDENT = "　　"

fun formatNovelParagraphText(
    text: String,
    indentEnabled: Boolean,
    spacingLines: Int,
): String {
    val separator = "\n".repeat(spacingLines.coerceIn(0, 3) + 1)
    return text
        .replace("\r\n", "\n")
        .split(Regex("\\n+"))
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(separator) { paragraph ->
            if (indentEnabled && !isNovelChapterTitleLine(paragraph)) {
                NOVEL_PARAGRAPH_INDENT + paragraph.trimStart()
            } else {
                paragraph
            }
        }
}

/**
 * Immutable, renderer-independent chapter projection consumed by the novel readers.
 * Image loading and chapter navigation remain separate concerns.
 */
sealed interface NovelDocumentBlock {
    /** Characters of the converted chapter text the block shows, when known. */
    val sourceRange: IntRange?

    data class Text(
        val key: String,
        val original: String,
        val translation: String?,
        val displayMode: NovelTranslationDisplayMode,
        val inlineImages: Map<String, String>,
        override val sourceRange: IntRange?,
    ) : NovelDocumentBlock

    data class Image(
        val key: String,
        val path: String,
        override val sourceRange: IntRange? = null,
    ) : NovelDocumentBlock
}

fun buildNovelDocument(
    content: String,
    translation: NovelChapterTranslation? = null,
): List<NovelDocumentBlock> {
    val parsed = parseNovelImages(content)
    val translationsByOriginal = translation
        ?.paragraphs
        ?.associate { paragraph -> paragraph.originalText to translation.translations[paragraph.index] }
        .orEmpty()
    val displayMode = translation?.displayMode ?: NovelTranslationDisplayMode.TRANSLATION_ONLY

    var searchOffset = 0
    return NovelParagraphSplitter.split(parsed.text).flatMap { paragraph ->
        val sourceStart = parsed.text.indexOf(paragraph.originalText, searchOffset)
        val sourceRange = sourceStart.takeIf { it >= 0 }?.let { start ->
            searchOffset = start + paragraph.originalText.length
            start until searchOffset
        }
        val imageIndex = IMAGE_PLACEHOLDER.matchEntire(paragraph.originalText)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
        if (imageIndex != null) {
            parsed.blockImagePaths.getOrNull(imageIndex)?.let { path ->
                listOf(NovelDocumentBlock.Image(key = "image-$imageIndex", path = path, sourceRange = sourceRange))
            }.orEmpty()
        } else {
            listOf(
                NovelDocumentBlock.Text(
                    key = "text-${paragraph.index}",
                    original = paragraph.originalText,
                    translation = translationsByOriginal[paragraph.originalText],
                    displayMode = displayMode,
                    inlineImages = parsed.inlineImagePaths.mapIndexedNotNull { index, path ->
                        val token = "[INLINE_IMAGE_$index]"
                        if (paragraph.originalText.contains(token)) token to path else null
                    }.toMap(),
                    sourceRange = sourceRange,
                ),
            )
        }
    }
}

private val IMAGE_PLACEHOLDER = Regex("\\[IMAGE_PLACEHOLDER_(\\d+)]")

/**
 * One block per non-empty line of [novelHtmlToText] output, for readers that position and bookmark by line
 * (the iOS reader) rather than by blank-line paragraph. A `📷 [图片: src]` line becomes an image block.
 */
fun buildNovelLineDocument(text: String): List<NovelDocumentBlock> {
    val blocks = mutableListOf<NovelDocumentBlock>()
    var start = 0
    while (start <= text.length) {
        val end = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val line = text.substring(start, end).trim()
        if (line.isNotEmpty()) {
            val image = LINE_IMAGE.matchEntire(line)?.groupValues?.get(1)?.trim()
            val range = text.indexOf(line, start).let { it until it + line.length }
            blocks += if (image != null) {
                NovelDocumentBlock.Image(key = "image-${blocks.size}", path = image, sourceRange = range)
            } else {
                NovelDocumentBlock.Text(
                    key = "text-${blocks.size}",
                    original = line,
                    translation = null,
                    displayMode = NovelTranslationDisplayMode.TRANSLATION_ONLY,
                    inlineImages = emptyMap(),
                    sourceRange = range,
                )
            }
        }
        start = end + 1
    }
    return blocks
}

/**
 * [this] document with [translations] (by block index) on its text blocks, shown as [mode]. Blocks keep their
 * source ranges, so positions, history and bookmarks stay those of the original chapter; untranslated blocks and
 * blank translations show the original.
 */
fun List<NovelDocumentBlock>.withNovelTranslations(
    translations: Map<Int, String>,
    mode: NovelTranslationDisplayMode,
): List<NovelDocumentBlock> = mapIndexed { index, block ->
    val translation = translations[index]?.trim()?.takeIf { it.isNotEmpty() }
    if (block is NovelDocumentBlock.Text && translation != null && translation != block.original) {
        block.copy(translation = translation, displayMode = mode)
    } else {
        block
    }
}

/**
 * The lines a reader shows for a text block, as Android's novel reader does: the original, its translation alone,
 * or the original followed by the translation (bilingual).
 */
val NovelDocumentBlock.Text.displayLines: List<String>
    get() = when {
        translation == null -> listOf(original)
        displayMode == NovelTranslationDisplayMode.TRANSLATION_ONLY -> listOf(translation)
        else -> listOf(original, translation)
    }

private val LINE_IMAGE = Regex("""📷\s*\[图片:\s*([^\]]+)]""")
