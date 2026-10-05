package org.skepsun.kototoro.reader.ui.tapgrid

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

/** Android's string resource naming [TapAction] (the enum itself is shared in core-ui). */
@get:StringRes
val TapAction.nameStringResId: Int
    get() = when (this) {
        TapAction.PAGE_NEXT -> R.string.next_page
        TapAction.PAGE_PREV -> R.string.prev_page
        TapAction.CHAPTER_NEXT -> R.string.next_chapter
        TapAction.CHAPTER_PREV -> R.string.prev_chapter
        TapAction.TOGGLE_UI -> R.string.toggle_ui
        TapAction.SHOW_MENU -> R.string.show_menu
    }
