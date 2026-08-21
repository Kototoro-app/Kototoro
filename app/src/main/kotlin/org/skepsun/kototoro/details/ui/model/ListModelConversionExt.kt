package org.skepsun.kototoro.details.ui.model

import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_BOOKMARKED
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_CURRENT
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_DOWNLOADED
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_GRID
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_NEW
import org.skepsun.kototoro.details.ui.model.ChapterListItem.Companion.FLAG_UNREAD
import org.skepsun.kototoro.parsers.model.ContentChapter
import kotlin.experimental.or

fun ContentChapter.toListItem(
    isCurrent: Boolean,
    isUnread: Boolean,
    isNew: Boolean,
    isDownloaded: Boolean,
    isBookmarked: Boolean,
    isGrid: Boolean,
): ChapterListItem {
    var flags: Byte = 0
    if (isCurrent) flags = flags or FLAG_CURRENT
    if (isUnread) flags = flags or FLAG_UNREAD
    if (isNew) flags = flags or FLAG_NEW
    if (isBookmarked) flags = flags or FLAG_BOOKMARKED
    if (isDownloaded) flags = flags or FLAG_DOWNLOADED
    if (isGrid) flags = flags or FLAG_GRID
    return ChapterListItem(
        chapter = this,
        flags = flags,
    )
}
