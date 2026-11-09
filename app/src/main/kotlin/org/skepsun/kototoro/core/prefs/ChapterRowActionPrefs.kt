package org.skepsun.kototoro.core.prefs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Visibility of the per-chapter row actions shared by the details page and both readers.
 * The two buttons default to on and first-page thumbnails default to off; the chapter panel more menu flips them.
 */
@Stable
class ChapterRowActionPrefs(
    val showDownload: Boolean,
    val showTranslate: Boolean,
    val showThumbnails: Boolean,
    private val updateDownload: (Boolean) -> Unit,
    private val updateTranslate: (Boolean) -> Unit,
    private val updateThumbnails: (Boolean) -> Unit,
) {
    fun setShowDownload(value: Boolean) = updateDownload(value)

    fun setShowTranslate(value: Boolean) = updateTranslate(value)

    fun setShowThumbnails(value: Boolean) = updateThumbnails(value)
}

@Composable
fun rememberChapterRowActionPrefs(): ChapterRowActionPrefs {
    val context = LocalContext.current
    val settings = remember(context.applicationContext) { AppSettings(context.applicationContext) }
    val showDownload by settings.observeAsState(AppSettings.KEY_CHAPTER_DOWNLOAD_BUTTON) {
        isChapterDownloadButtonVisible
    }
    val showTranslate by settings.observeAsState(AppSettings.KEY_CHAPTER_TRANSLATE_BUTTON) {
        isChapterTranslateButtonVisible
    }
    val showThumbnails by settings.observeAsState(AppSettings.KEY_CHAPTER_THUMBNAILS) {
        isChapterThumbnailsEnabled
    }
    return remember(settings, showDownload, showTranslate, showThumbnails) {
        ChapterRowActionPrefs(
            showDownload = showDownload,
            showTranslate = showTranslate,
            showThumbnails = showThumbnails,
            updateDownload = { settings.isChapterDownloadButtonVisible = it },
            updateTranslate = { settings.isChapterTranslateButtonVisible = it },
            updateThumbnails = { settings.isChapterThumbnailsEnabled = it },
        )
    }
}
