package org.skepsun.kototoro.desktop.app

import androidx.compose.runtime.Composable
import org.skepsun.kototoro.desktop.runtime.DesktopBookmark
import org.skepsun.kototoro.desktop.runtime.isChapterBookmark

/**
 * The position line both Windows bookmark surfaces draw: Android's rows carry the chapter title and the reading ring,
 * and a position that was saved under the chapter id has no page number to show.
 */
@Composable
internal fun desktopBookmarkLabel(bookmark: DesktopBookmark): String = if (bookmark.isChapterBookmark) {
    desktopText("details.chapterBookmarkLabel")
} else {
    desktopText("common.pageOf", bookmark.page + 1, (bookmark.percent * 100).toInt())
}
