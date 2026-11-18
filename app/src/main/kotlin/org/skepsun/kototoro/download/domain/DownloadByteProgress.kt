package org.skepsun.kototoro.download.domain

data class DownloadByteProgress(
    val downloadedBytes: Long,
    val totalBytes: Long? = null,
    val isTotalEstimated: Boolean = false,
) {

    companion object {

        /**
         * Byte progress of a segmented download. The total is an estimate: the average segment size seen so
         * far scaled to all segments, blended with [priorTotalBytes] (a sampled guess made before the
         * download) while few segments are done, so early segments of unusual size cannot swing it wildly.
         */
        fun forSegments(
            downloadedBytes: Long,
            completed: Int,
            total: Int,
            priorTotalBytes: Long? = null,
        ): DownloadByteProgress {
            val finished = completed >= total && total > 0
            val prior = priorTotalBytes?.takeIf { it > 0 }
            val observed = if (completed > 0 && downloadedBytes > 0) {
                downloadedBytes.toDouble() * total / completed
            } else {
                null
            }
            val estimatedTotal = when {
                finished -> downloadedBytes
                observed != null && prior != null -> {
                    val weight = (completed.toDouble() / total).coerceIn(0.0, 1.0)
                    (observed * weight + prior * (1 - weight)).toLong().coerceAtLeast(downloadedBytes)
                }
                observed != null -> observed.toLong().coerceAtLeast(downloadedBytes)
                prior != null -> prior.coerceAtLeast(downloadedBytes)
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
