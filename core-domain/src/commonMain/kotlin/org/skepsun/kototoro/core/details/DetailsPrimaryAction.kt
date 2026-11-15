package org.skepsun.kototoro.core.details

/** What the details page's one prominent button is going to do. */
enum class DetailsPrimaryActionKind {
    /** Nothing can be opened yet: no chapters, or the source has not answered. */
    NONE,

    /** Never opened: start at the first chapter the reader has not finished. */
    START,

    /** Already opened: pick up exactly where the history left off. */
    CONTINUE,
}

/**
 * The single action a details page offers, plus the chapter it opens.
 *
 * [chapterTitle] is the work's own chapter title, so the button can say which chapter it will open instead of only
 * "continue". [chapterCount] lets the page show the scale of the work without asking for it separately.
 */
data class DetailsPrimaryAction(
    val kind: DetailsPrimaryActionKind,
    val chapterId: Long? = null,
    val chapterTitle: String? = null,
    val chapterCount: Int = 0,
) {
    val isAvailable: Boolean get() = kind != DetailsPrimaryActionKind.NONE
}

/** The part of a chapter the decision needs; callers keep their platform chapter type. */
data class DetailsChapterRef(
    val id: Long,
    val title: String,
)

/**
 * Decides the details page's primary action.
 *
 * Precedence, in the order the reader experiences it:
 * 1. no chapters → [DetailsPrimaryActionKind.NONE];
 * 2. the history's current chapter, when it still exists in this branch → [DetailsPrimaryActionKind.CONTINUE] on it;
 * 3. the furthest chapter ever read, when it still exists → [DetailsPrimaryActionKind.START] on the chapter after it,
 *    or back on the first chapter when that was the last one (a finished work reads as "read it again");
 * 4. otherwise → [DetailsPrimaryActionKind.START] on the first chapter.
 *
 * Only chapter identity is used, never a percent: a percent that survived a branch change would point at the wrong
 * chapter, and the reader itself restores the pixel position from the history.
 */
fun detailsPrimaryAction(
    chapters: List<DetailsChapterRef>,
    currentChapterId: Long?,
    readThroughChapterId: Long? = null,
): DetailsPrimaryAction {
    if (chapters.isEmpty()) return DetailsPrimaryAction(DetailsPrimaryActionKind.NONE)
    val count = chapters.size
    chapters.firstOrNull { it.id == currentChapterId }?.let { chapter ->
        return DetailsPrimaryAction(
            kind = DetailsPrimaryActionKind.CONTINUE,
            chapterId = chapter.id,
            chapterTitle = chapter.title,
            chapterCount = count,
        )
    }
    val readThroughIndex = chapters.indexOfFirst { it.id == readThroughChapterId }
    if (readThroughIndex >= 0) {
        val next = chapters.getOrNull(readThroughIndex + 1) ?: chapters.first()
        return DetailsPrimaryAction(
            kind = DetailsPrimaryActionKind.START,
            chapterId = next.id,
            chapterTitle = next.title,
            chapterCount = count,
        )
    }
    val first = chapters.first()
    return DetailsPrimaryAction(
        kind = DetailsPrimaryActionKind.START,
        chapterId = first.id,
        chapterTitle = first.title,
        chapterCount = count,
    )
}
