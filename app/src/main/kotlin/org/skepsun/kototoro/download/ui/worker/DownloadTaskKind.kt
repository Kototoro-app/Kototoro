package org.skepsun.kototoro.download.ui.worker

import androidx.annotation.StringRes
import org.skepsun.kototoro.R

enum class DownloadTaskKind {
    DOWNLOAD,
    PREPARE_TRANSLATION,
    PREPARE_SUPER_RESOLUTION;

    @get:StringRes
    val actionTitleResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.download
            PREPARE_TRANSLATION -> R.string.prepare_translation
            PREPARE_SUPER_RESOLUTION -> R.string.prepare_super_resolution
        }

    @get:StringRes
    val activeStatusResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.manga_downloading_
            PREPARE_TRANSLATION -> R.string.translating_
            PREPARE_SUPER_RESOLUTION -> R.string.super_resolution_processing_
        }

    @get:StringRes
    val completedStatusResId: Int
        get() = when (this) {
            DOWNLOAD -> R.string.download_complete
            PREPARE_TRANSLATION -> R.string.translation_prepared
            PREPARE_SUPER_RESOLUTION -> R.string.super_resolution_prepared
        }
}
