package org.skepsun.kototoro.core.prefs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * Visibility of the per-chapter row actions shared by the details page and both readers.
 * Both default to on; the chapter panel more menu flips them.
 */
@Stable
class ChapterRowActionPrefs(
    val showDownload: Boolean,
    val showTranslate: Boolean,
    private val updateDownload: (Boolean) -> Unit,
    private val updateTranslate: (Boolean) -> Unit,
) {
    fun setShowDownload(value: Boolean) = updateDownload(value)

    fun setShowTranslate(value: Boolean) = updateTranslate(value)
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
    return remember(settings, showDownload, showTranslate) {
        ChapterRowActionPrefs(
            showDownload = showDownload,
            showTranslate = showTranslate,
            updateDownload = { settings.isChapterDownloadButtonVisible = it },
            updateTranslate = { settings.isChapterTranslateButtonVisible = it },
        )
    }
}
