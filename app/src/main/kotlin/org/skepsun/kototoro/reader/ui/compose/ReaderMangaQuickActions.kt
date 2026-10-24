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

/**
 * The quick actions this device can actually carry out for the open chapter.
 *
 * Two of the nine cells have nothing behind them in some situations, and a cell that closes the panel
 * without doing anything is worse than an absent one: on a television `Rotate screen` only flips an
 * orientation lock the set cannot honour (it is always landscape), and `Download` has no remote pages
 * to fetch once the chapter already comes from a local file.
 *
 * @param canRotate false on a television, which has no orientation to rotate.
 * @param canDownload false when the chapter's source is local, so there is nothing to fetch.
 */
internal fun mangaQuickActions(
    translationAvailable: Boolean,
    translationActive: Boolean,
    canRotate: Boolean = true,
    canDownload: Boolean = true,
): List<ReaderQuickAction> =
    MangaQuickActionId.entries
        .filter { it != MangaQuickActionId.TRANSLATE || translationAvailable }
        .filter { it != MangaQuickActionId.ROTATE || canRotate }
        .filter { it != MangaQuickActionId.DOWNLOAD || canDownload }
        .map { id ->
            ReaderQuickAction(
                id = id.name,
                toggled = if (id == MangaQuickActionId.TRANSLATE) translationActive else null,
            )
        }
