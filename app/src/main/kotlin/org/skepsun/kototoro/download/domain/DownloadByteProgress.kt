package org.skepsun.kototoro.download.domain

data class DownloadByteProgress(
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val isTotalEstimated: Boolean = false,
) {

    companion object {

        fun forSegments(downloadedBytes: Long, completed: Int, total: Int): DownloadByteProgress {
            val finished = completed >= total && total > 0
            val estimatedTotal = when {
                finished -> downloadedBytes
                completed > 0 && downloadedBytes > 0 ->
                    (downloadedBytes.toDouble() * total / completed).toLong().coerceAtLeast(downloadedBytes)
                else -> null
            }
            return DownloadByteProgress(
                downloadedBytes = downloadedBytes,
                totalBytes = estimatedTotal,
                isTotalEstimated = estimatedTotal != null && !finished,
            )
        }
    }
}
