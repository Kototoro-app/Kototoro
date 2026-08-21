package org.skepsun.kototoro.reader.translate.domain

import android.graphics.Rect
import android.net.Uri

data class OcrRequest(
    val sourceUri: Uri,
    val sourceLang: String,
    val roi: Rect? = null,
    val pageId: Long? = null,
    val requestType: OcrRequestType = OcrRequestType.PAGE,
    val debugTag: String? = null,
)

enum class OcrRequestType {
    PAGE,
    ROI,
    FALLBACK,
}
