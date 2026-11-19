package org.skepsun.kototoro.reader.novel.compose

/**
 * Puts a [NovelComposePage.Break] wherever one chapter hands over to the next when transition pages are on: between
 * two chapters of the window, before the window's first page when the book has an earlier chapter, and after its
 * last page. The window's edges get one even while the neighbouring chapter is not loaded yet, so the page is there
 * from the first frame; the page has the same identity ([NovelComposePage.Break.finishedChapterId] and spread) as
 * the one between the two chapters once the neighbour is stitched in, so it neither flickers nor doubles.
 *
 * Two-page spreads pair pages (0,1), (2,3)…: a card has to start on an even position to own its spread, so a
 * [BreakSpread.PAD] page is added when the page count before it is odd. The leading card starts at 0 and needs none.
 */
internal fun insertNovelChapterBreaks(
    pages: List<NovelComposePage>,
    config: NovelChapterBreakConfig?,
    dualPage: Boolean,
    loadedTitleOf: (chapterIndex: Int) -> String? = { null },
): List<NovelComposePage> {
    if (config == null || pages.isEmpty()) return pages
    val result = ArrayList<NovelComposePage>(pages.size + 6)

    fun titleOf(chapterIndex: Int): String? = loadedTitleOf(chapterIndex) ?: config.titleOf(chapterIndex)

    fun addBreak(finishedChapterIndex: Int, finishedChapterId: Long) {
        val hasNext = finishedChapterIndex < config.chapterCount - 1
        fun breakPage(spread: BreakSpread) = NovelComposePage.Break(
            finishedChapterId = finishedChapterId,
            finishedChapterIndex = finishedChapterIndex,
            finishedTitle = titleOf(finishedChapterIndex),
            nextTitle = if (hasNext) titleOf(finishedChapterIndex + 1) else null,
            spread = spread,
        )
        if (!dualPage) {
            result += breakPage(BreakSpread.NONE)
        } else {
            if (result.size % 2 == 1) result += breakPage(BreakSpread.PAD)
            result += breakPage(BreakSpread.LEFT)
            result += breakPage(BreakSpread.RIGHT)
        }
    }

    val first = pages.first()
    if (first.chapterIndex > 0) {
        config.idOf(first.chapterIndex - 1)?.let { addBreak(first.chapterIndex - 1, it) }
    }
    pages.forEachIndexed { position, page ->
        result += page
        val next = pages.getOrNull(position + 1)
        if (next == null || next.chapterIndex != page.chapterIndex) addBreak(page.chapterIndex, page.chapterId)
    }
    return result
}
