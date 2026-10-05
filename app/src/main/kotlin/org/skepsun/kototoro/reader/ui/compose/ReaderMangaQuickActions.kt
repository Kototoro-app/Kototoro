package org.skepsun.kototoro.reader.ui.compose

import org.skepsun.kototoro.R
import org.skepsun.kototoro.reader.ui.compose.design.ReaderQuickAction

internal enum class MangaQuickActionId(val iconResId: Int, val labelResId: Int) {
    CHAPTERS(R.drawable.ic_grid, R.string.chapters_and_pages),
    BOOKMARK(R.drawable.ic_bookmark, R.string.bookmark_add),
    SAVE_PAGE(R.drawable.ic_save, R.string.save_page),
    CROP_NOTE(R.drawable.ic_crop, R.string.crop_and_annotate),
    AUTO_SCROLL(R.drawable.ic_timer, R.string.automatic_scroll),
    ROTATE(R.drawable.ic_screen_rotation, R.string.rotate_screen),
    DOWNLOAD(R.drawable.ic_download, R.string.download),
    BROWSER(R.drawable.ic_web, R.string.open_in_browser),
    TRANSLATE(R.drawable.ic_translate, R.string.reader_translation_action),
}

internal fun mangaQuickActions(translationAvailable: Boolean, translationActive: Boolean): List<ReaderQuickAction> =
    MangaQuickActionId.entries
        .filter { it != MangaQuickActionId.TRANSLATE || translationAvailable }
        .map { id ->
            ReaderQuickAction(
                id = id.name,
                toggled = if (id == MangaQuickActionId.TRANSLATE) translationActive else null,
            )
        }
