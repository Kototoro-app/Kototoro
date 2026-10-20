package org.skepsun.kototoro.download.ui.worker

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

enum class DownloadTaskKind {
    DOWNLOAD,
    PREPARE_TRANSLATION,
    PREPARE_SUPER_RESOLUTION,

    /**
     * Per-chapter "translate" action from the chapter list: downloads the chapter when it is missing,
     * then translates it in the background and records the durable "translated" marker.
     */
    TRANSLATE;

    @get:StringRes
    val actionTitleResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.download
            PREPARE_TRANSLATION -> R.string.prepare_translation
            PREPARE_SUPER_RESOLUTION -> R.string.prepare_super_resolution
            TRANSLATE -> R.string.translate_chapter
        }

    @get:StringRes
    val activeStatusResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.manga_downloading_
            PREPARE_TRANSLATION -> R.string.translating_
            PREPARE_SUPER_RESOLUTION -> R.string.super_resolution_processing_
            TRANSLATE -> R.string.translating_
        }

    @get:StringRes
    val completedStatusResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.download_complete
            PREPARE_TRANSLATION -> R.string.translation_prepared
            PREPARE_SUPER_RESOLUTION -> R.string.super_resolution_prepared
            TRANSLATE -> R.string.translation_prepared
        }
}
