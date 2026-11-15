package org.skepsun.kototoro.desktop.app

import org.skepsun.kototoro.core.details.DetailsChapterRef
import org.skepsun.kototoro.core.details.DetailsPrimaryAction
import org.skepsun.kototoro.core.details.DetailsPrimaryActionKind
import org.skepsun.kototoro.core.details.detailsPrimaryAction

/**
 * The details page's one prominent button, built from the shared rule so Android can say the same thing later.
 *
 * The label names the chapter it will open: "继续阅读 · 第 7 话" answers both "what happens if I press this" and "where
 * was I", which the old combined "开始 / 继续阅读" label did not.
 */
internal fun desktopDetailsPrimaryLabel(action: DetailsPrimaryAction, isVideo: Boolean): String {
    val chapter = action.chapterTitle.orEmpty()
    return when (action.kind) {
        DetailsPrimaryActionKind.CONTINUE -> desktopText(
            if (isVideo) "details.continueWatching" else "details.continueReading",
            chapter,
        )
        else -> desktopText(
            if (isVideo) "details.startWatchingAt" else "details.startReadingAt",
            chapter,
        )
    }
}

/** Projects the branch's chapters into the shared decision's shape. */
internal fun desktopDetailsPrimaryAction(
    chapters: List<DetailsChapterRef>,
    currentChapterId: Long?,
    readThroughChapterId: Long?,
): DetailsPrimaryAction = detailsPrimaryAction(chapters, currentChapterId, readThroughChapterId)
