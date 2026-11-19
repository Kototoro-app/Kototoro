package org.skepsun.kototoro.reader.ui.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import org.skepsun.kototoro.core.ui.chapters.ChapterBreakCard
import org.skepsun.kototoro.reader.ui.pager.ReaderPage

/**
 * What the chapter break page needs from the reader: chapter titles by position relative to a chapter.
 * Provided only while chapter break cards are enabled, so the pagers need no extra parameters.
 */
internal class ChapterBreakHandler(
    val titleAt: (chapterId: Long, offset: Int) -> String?,
)

internal val LocalChapterBreakHandler = staticCompositionLocalOf<ChapterBreakHandler?> { null }

/** The informational card shown where a chapter ends; the reader keeps flowing into the next chapter. */
@Composable
internal fun ReaderChapterBreakPage(page: ReaderPage, modifier: Modifier = Modifier) {
    val handler = LocalChapterBreakHandler.current
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        ChapterBreakCard(
            finishedTitle = handler?.titleAt?.invoke(page.chapterId, 0),
            nextTitle = handler?.titleAt?.invoke(page.chapterId, 1),
        )
    }
}
