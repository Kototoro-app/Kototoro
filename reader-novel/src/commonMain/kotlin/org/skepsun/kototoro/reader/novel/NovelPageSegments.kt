package org.skepsun.kototoro.reader.novel

/**
 * What a paginating reader lays out for one chapter: runs of text lines formatted as the Android reader shows them
 * (two full-width spaces of indent except on title lines, blank lines between paragraphs), and each picture on a page of
 * its own. Built from a [buildNovelLineDocument] document; offsets map back to its blocks so pages and history use the
 * same positions as the scrolling reader.
 */
sealed interface NovelPageSegment {

    class Text internal constructor(
        val text: String,
        /** Index of the segment's first block in the document. */
        val firstBlock: Int,
        private val starts: IntArray,
        private val contentStarts: IntArray,
    ) : NovelPageSegment {
        val blockCount: Int get() = starts.size

        /** The document block shown at [offset] of [text]. */
        fun blockAt(offset: Int): Int = firstBlock + localBlockAt(offset)

        /** How far into its block's own text [offset] is (the indent counts as the block's start). */
        fun offsetInBlock(offset: Int): Int {
            val local = localBlockAt(offset)
            return (offset - contentStarts[local]).coerceAtLeast(0)
        }

        /** Where document [block]'s line starts (its indent included) and where its own text starts. */
        fun lineStart(block: Int): Int = starts[(block - firstBlock).coerceIn(0, blockCount - 1)]

        fun contentStart(block: Int): Int = contentStarts[(block - firstBlock).coerceIn(0, blockCount - 1)]

        /** The offset of [text] showing character [inBlock] of document block [block]. */
        fun offsetOf(block: Int, inBlock: Int): Int {
            val local = (block - firstBlock).coerceIn(0, blockCount - 1)
            val end = if (local + 1 < blockCount) starts[local + 1] else text.length
            return (contentStarts[local] + inBlock.coerceAtLeast(0)).coerceAtMost(end)
        }

        private fun localBlockAt(offset: Int): Int {
            var low = 0
            var high = starts.size - 1
            while (low < high) {
                val middle = (low + high + 1) ushr 1
                if (starts[middle] <= offset) low = middle else high = middle - 1
            }
            return low
        }
    }

    class Image internal constructor(val path: String, val block: Int) : NovelPageSegment
}

/**
 * [indentLine] decides per block whether it takes the paragraph indent (by default every line that is not a title);
 * it is consulted only when [indentEnabled].
 */
fun buildNovelPageSegments(
    blocks: List<NovelDocumentBlock>,
    indentEnabled: Boolean,
    spacingLines: Int,
    indentLine: (index: Int, line: String) -> Boolean = { _, line -> !isNovelChapterTitleLine(line) },
): List<NovelPageSegment> {
    val separator = "\n".repeat(spacingLines.coerceIn(0, 3) + 1)
    val segments = mutableListOf<NovelPageSegment>()
    val text = StringBuilder()
    val starts = mutableListOf<Int>()
    val contentStarts = mutableListOf<Int>()
    var firstBlock = 0
    fun flush() {
        if (starts.isNotEmpty()) {
            segments += NovelPageSegment.Text(text.toString(), firstBlock, starts.toIntArray(), contentStarts.toIntArray())
        }
        text.clear()
        starts.clear()
        contentStarts.clear()
    }
    blocks.forEachIndexed { index, block ->
        when (block) {
            is NovelDocumentBlock.Image -> {
                flush()
                segments += NovelPageSegment.Image(block.path, index)
            }
            is NovelDocumentBlock.Text -> {
                if (starts.isEmpty()) firstBlock = index else text.append(separator)
                starts += text.length
                val indent = indentEnabled && indentLine(index, block.original)
                // A translated block shows its translation, or both lines when bilingual, as one block.
                block.displayLines.forEachIndexed { line, value ->
                    if (line > 0) text.append(separator)
                    if (indent) text.append(NOVEL_PARAGRAPH_INDENT)
                    if (line == 0) contentStarts += text.length
                    text.append(value)
                }
            }
        }
    }
    flush()
    return segments
}

/** Segment and offset in it of a document position. */
data class NovelSegmentPosition(val segment: Int, val offset: Int)

/** Where [block] (and character [inBlock] of it) is laid out, or null when no segment shows that block. */
fun List<NovelPageSegment>.positionOf(block: Int, inBlock: Int = 0): NovelSegmentPosition? {
    forEachIndexed { index, segment ->
        when (segment) {
            is NovelPageSegment.Image -> if (segment.block == block) return NovelSegmentPosition(index, 0)
            is NovelPageSegment.Text -> if (block in segment.firstBlock until segment.firstBlock + segment.blockCount) {
                return NovelSegmentPosition(index, segment.offsetOf(block, inBlock))
            }
        }
    }
    return null
}
