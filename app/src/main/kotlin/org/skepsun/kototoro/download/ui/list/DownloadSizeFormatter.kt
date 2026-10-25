package org.skepsun.kototoro.download.ui.list

import org.skepsun.kototoro.download.domain.DownloadByteProgress
import java.text.NumberFormat
import java.util.Locale

internal fun formatDownloadSize(
    progress: DownloadByteProgress?,
    chapterTitle: String? = null,
    locale: Locale = Locale.getDefault(),
): String? {
    progress ?: return null
    val numberFormat = NumberFormat.getNumberInstance(locale).apply {
        minimumFractionDigits = 1
        maximumFractionDigits = 1
    }
    fun megabytes(bytes: Long): String = "${numberFormat.format(bytes / (1024.0 * 1024.0))} MB"
    val size = buildString {
        append(megabytes(progress.downloadedBytes))
        progress.totalBytes?.takeIf { it > 0 }?.let { total ->
            append(" / ")
            if (progress.isTotalEstimated) append('~')
            append(megabytes(total))
        }
    }
    return if (chapterTitle.isNullOrBlank()) size else "$size · $chapterTitle"
}
