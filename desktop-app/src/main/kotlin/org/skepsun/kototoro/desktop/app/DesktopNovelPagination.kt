package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.reader.novel.splitNovelPageLineRanges

/**
 * Pages of whole blocks for the Windows novel reader.
 *
 * Android's paged novel reader measures text lines and cuts pages between them. The desktop blocks (paragraphs,
 * headings, rules and illustrations) are the same units the scroll reader reports progress with, so the shared line
 * splitter is fed one "line" per block: a paragraph is never cut in half, and the reading position stays a block index
 * both modes can restore. A block taller than the viewport still gets a page of its own, so flipping always advances.
 */
internal fun desktopNovelPages(blockHeights: List<Int>, pageHeightPx: Int): List<IntRange> {
    if (blockHeights.isEmpty() || pageHeightPx <= 0) return emptyList()
    val tops = IntArray(blockHeights.size + 1)
    blockHeights.forEachIndexed { index, height -> tops[index + 1] = tops[index] + height }
    return splitNovelPageLineRanges(
        lineCount = blockHeights.size,
        pageHeightPx = pageHeightPx,
        lineTop = { tops[it].toFloat() },
        lineBottom = { tops[it + 1].toFloat() },
    )
}

/** Index of the page that holds [block], or -1 when the page list has no such block. */
internal fun desktopNovelPageOf(pages: List<IntRange>, block: Int): Int = pages.indexOfFirst { block in it }
